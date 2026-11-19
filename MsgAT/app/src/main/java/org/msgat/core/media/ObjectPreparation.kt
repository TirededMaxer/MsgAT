// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore

enum class PreparationSendResult { QUEUED,CANCELLED,FAILED }
enum class PreparationStep { IMPORTING, ENCODING_PHOTO, ENCODING_VIDEO }

data class PreparationProgress(val phase:String,val completed:Long,val total:Long,val canCancel:Boolean,val version:Long=0,val attachment:Int=1,val attachmentCount:Int=1,val step:PreparationStep=PreparationStep.IMPORTING)
data class PendingPreparation(
    val id: String,
    val progress: PreparationProgress,
    val fileCount: Int = 1,
    val isSticker: Boolean = false,
    val savingMessages: Boolean = false,
    val isLinkPreview: Boolean = false
)

internal class PreparationCancelledException:IllegalStateException("Preparation cancelled")

/** One send preparation, owned until all staged cleanup finishes. */
class ObjectPreparation internal constructor():AutoCloseable {
    private var work:String?=null
    private var phase="preparing"
    private var cancelled=false
    private var version=0L
    private var completed=0L
    private var total=0L
    private var attachment = 1
    private var attachmentCount = 1
    private var step = PreparationStep.IMPORTING
    val wasCancelled:Boolean get()=synchronized(this){cancelled}
    @Synchronized fun check(){
        if(cancelled)throw PreparationCancelledException()
        kotlin.check(phase!="finished"){"Preparation has ended"}
    }
    @Synchronized fun workID():String {
        check()
        return work?:SecurityCore.call(JSONObject().put("operation","create_object_work")).getString("work_id").also{work=it}
    }
    @Synchronized fun report(size:Long,done:Long){
        check();SecurityCore.call(JSONObject().put("operation","report_object_work").put("work_id",workID()).put("total",size).put("completed",done))
    }
    @Synchronized fun startAttachment(index: Int, count: Int) {
        check()
        attachment = index
        attachmentCount = count
        setStep(PreparationStep.IMPORTING)
    }
    @Synchronized fun setStep(value: PreparationStep) {
        report(0, 0)
        step = value
        version++
    }
    @Synchronized fun beginCommit(){check();kotlin.check(phase=="preparing"){"Preparation has already entered commit"};phase="committing";version++}
    @Synchronized fun cancel():Boolean {
        if(phase!="preparing")return false
        cancelled=true;phase="canceling";version++
        work?.let{try{SecurityCore.call(JSONObject().put("operation","cancel_object_work").put("work_id",it))}catch(_:Exception){/* Local flag still prevents commit. */}}
        return true
    }
    @Synchronized fun snapshot():PreparationProgress {
        work?.let{try{
            val value=SecurityCore.call(JSONObject().put("operation","object_work_status").put("work_id",it))
            total=value.getLong("total");completed=value.getLong("completed").coerceIn(0,total)
        }catch(_:Exception){/* The worker reports actual errors; progress never reads the vault. */}}
        return PreparationProgress(phase,completed,total,phase=="preparing",version,attachment,attachmentCount,step)
    }
    @Synchronized override fun close(){
        if(phase=="finished")return
        phase="finished";version++
        work?.let{try{SecurityCore.call(JSONObject().put("operation","close_object_work").put("work_id",it))}catch(_:Exception){android.util.Log.w("MsgAT_Preparation","Preparation state could not be released")}}
        work=null
    }
}
