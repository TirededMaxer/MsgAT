// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.msgat.core.call.CallManager
import org.msgat.core.network.SecureMessaging
import org.msgat.ui.theme.AppLanguage

/** Owns the authenticated UI session for one activity. The vault still receives encrypted traffic. */
internal class AppLockController(private val activity: ComponentActivity) {
    private enum class Purpose { UNLOCK, ENABLE, DISABLE }
    private class Request(val purpose: Purpose, val signal: CancellationSignal = CancellationSignal())

    var enabled by mutableStateOf(false)
        private set
    var locked by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<Int?>(null)
        private set

    private var messaging: SecureMessaging? = null
    private var backgroundedAt: Long? = null
    private var current: Request? = null
    private var deferredVerification: Pair<Request, Long>? = null
    private var lockRevision = 0L
    private var legacyRequest: Request? = null
    private val keyguard get() = activity.getSystemService(KeyguardManager::class.java)
    private val legacyPrompt = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = legacyRequest
        legacyRequest = null
        if (request != null) complete(request, result.resultCode == Activity.RESULT_OK)
    }
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lockNow()
        }
    }
    private val lifecycleEvents = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            finishDeferredVerification()
            updateVisibleSession()
        }

        override fun onPause(owner: LifecycleOwner) { updateVisibleSession(visible = false) }
    }

    fun start() {
        ContextCompat.registerReceiver(activity, screenEvents, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        activity.lifecycle.addObserver(lifecycleEvents)
    }

    suspend fun load(service: SecureMessaging) {
        enabled = service.appLockEnabled()
        locked = enabled
        messaging = service
    }

    fun onStart() {
        val stoppedAt = backgroundedAt
        backgroundedAt = null
        if (enabled && (keyguard?.isDeviceLocked == true ||
                (stoppedAt != null && SystemClock.elapsedRealtime() - stoppedAt >= 60_000L))) lockNow()
        updateVisibleSession()
    }

    fun onStop() {
        backgroundedAt = SystemClock.elapsedRealtime()
        updateVisibleSession(visible = false)
    }

    private fun finishDeferredVerification() {
        val (request, verifiedAt) = deferredVerification ?: return
        deferredVerification = null
        complete(request, SystemClock.elapsedRealtime() - verifiedAt < 60_000L)
    }

    fun close() {
        cancelPrompt()
        updateVisibleSession(visible = false)
        activity.lifecycle.removeObserver(lifecycleEvents)
        activity.unregisterReceiver(screenEvents)
    }

    fun lockNow() {
        lockRevision++
        cancelPrompt()
        if (!enabled) return
        error = null
        locked = true
        updateVisibleSession()
    }

    fun unlock() {
        if (locked && messaging != null) authenticate(Purpose.UNLOCK)
    }

    fun toggle() {
        if (!locked && messaging != null) authenticate(if (enabled) Purpose.DISABLE else Purpose.ENABLE)
    }

    private fun updateVisibleSession(visible: Boolean = !locked &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        messaging?.setForeground(visible)
        CallManager.setVideoWindowVisible(visible)
    }

    private fun cancelPrompt() {
        deferredVerification = null
        val request = current ?: return
        current = null
        busy = false
        request.signal.cancel()
    }

    @Suppress("DEPRECATION")
    private fun authenticate(purpose: Purpose) {
        if (busy || legacyRequest != null || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val keyguard = keyguard
        if (keyguard == null || !keyguard.isDeviceSecure) {
            showError(R.string.ui_android_screen_lock_required)
            return
        }
        val request = Request(purpose)
        current = request
        busy = true
        error = null
        try {
            val promptTitle = AppLanguage.restore(activity).resources(activity).getString(R.string.ui_verify_app)
            if (Build.VERSION.SDK_INT >= 30) {
                val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                BiometricPrompt.Builder(activity).setTitle(promptTitle)
                    .setAllowedAuthenticators(authenticators).build()
                    .authenticate(request.signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                            complete(request, true)

                        override fun onAuthenticationError(code: Int, description: CharSequence) =
                            complete(request, false)
                    })
            } else {
                val intent = keyguard.createConfirmDeviceCredentialIntent(promptTitle, null)
                    ?: throw IllegalStateException("Device credentials are unavailable")
                legacyRequest = request
                legacyPrompt.launch(intent)
            }
        } catch (_: Exception) {
            legacyRequest = null
            complete(request, false)
        }
    }

    private fun complete(request: Request, verified: Boolean) {
        if (current !== request) return
        if (verified && keyguard?.isDeviceLocked == false &&
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            deferredVerification = request to SystemClock.elapsedRealtime()
            return
        }
        current = null
        deferredVerification = null
        if (!verified || keyguard?.isDeviceLocked != false) {
            busy = false
            showError(R.string.ui_verify_incomplete)
            return
        }
        if (request.purpose == Purpose.UNLOCK) {
            busy = false
            locked = false
            updateVisibleSession()
            return
        }

        val revision = lockRevision
        activity.lifecycleScope.launch {
            try {
                val shouldEnable = request.purpose == Purpose.ENABLE
                if (messaging?.setAppLockEnabled(shouldEnable) != true) {
                    showError(R.string.ui_app_lock_save_error)
                    return@launch
                }
                enabled = shouldEnable
                locked = (shouldEnable || locked) && (revision != lockRevision ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || keyguard?.isDeviceLocked != false)
                updateVisibleSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError(R.string.ui_app_lock_save_error)
            } finally {
                busy = false
            }
        }
    }

    private fun showError(@StringRes message: Int) {
        if (locked) error = message
        else Toast.makeText(activity, AppLanguage.restore(activity).resources(activity).getString(message), Toast.LENGTH_SHORT).show()
    }
}
