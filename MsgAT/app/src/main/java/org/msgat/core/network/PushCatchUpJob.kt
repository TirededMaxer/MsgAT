// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.msgat.MsgATApp

/** One coalesced network job per subscription, without a separate notification or transport. */
class PushCatchUpJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        running = scope.launch {
            val app = application as MsgATApp
            try {
                // A pending job from an older install may still have a wake in JobInfo extras.
                params.extras.getString(GENERATION)?.let { catchUp(app, it, params.extras.getLong(DEADLINE)) }
                while (isActive) {
                    val first = params.dequeueWork() ?: break
                    val work = mutableListOf(first)
                    while (work.size < 8) work.add(params.dequeueWork() ?: break)
                    val deadlines = mutableMapOf<String, Long>()
                    for (item in work) {
                        val intent = item.intent ?: continue
                        val generation = intent.getStringExtra(GENERATION) ?: continue
                        val deadline = intent.getLongExtra(DEADLINE, 0)
                        deadlines[generation] = maxOf(deadlines[generation] ?: 0, deadline)
                    }
                    for ((generation, deadline) in deadlines) catchUp(app, generation, deadline)
                    ensureActive()
                    work.forEach(params::completeWork)
                    // dequeueWork closes an empty job without racing a new enqueue.
                }
            } finally {
                if (running === coroutineContext[Job]) running = null
            }
        }
        return true
    }

    private suspend fun catchUp(app: MsgATApp, generation: String, deadline: Long) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) return
        withTimeoutOrNull(remaining) {
            if (app.awaitStartup() != MsgATApp.StartupState.READY || !app.isReady) return@withTimeoutOrNull
            while (isActive && app.isReady) {
                val complete = try {
                    app.androidPush.catchUp(generation)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("MsgAT_Push", "Catch-up failed (${error.javaClass.simpleName})")
                    false
                }
                if (complete) break
                delay(2_000)
            }
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        return true // The scheduler redelivers unfinished items. Their original deadlines still apply.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 0x4D534701
        private const val GENERATION = "generation"
        private const val DEADLINE = "deadline"

        fun schedule(context: Context, generation: String, timeoutMillis: Long): Boolean {
            if (timeoutMillis <= 0) return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            return try {
                val deadline = SystemClock.elapsedRealtime() + timeoutMillis.coerceAtMost(120_000L)
                fun item() = JobWorkItem(Intent().putExtra(GENERATION, generation).putExtra(DEADLINE, deadline))
                fun builder() = JobInfo.Builder(JOB_ID, ComponentName(context, PushCatchUpJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                val existing = scheduler.getPendingJob(JOB_ID)
                if (existing != null) return scheduler.enqueue(existing, item()) == JobScheduler.RESULT_SUCCESS
                if (Build.VERSION.SDK_INT >= 31) {
                    val expedited = try {
                        scheduler.enqueue(builder().setExpedited(true).build(), item()) == JobScheduler.RESULT_SUCCESS
                    } catch (_: IllegalArgumentException) { false }
                    if (expedited) return true
                }
                scheduler.enqueue(builder().build(), item()) == JobScheduler.RESULT_SUCCESS
            } catch (_: RuntimeException) { false }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
