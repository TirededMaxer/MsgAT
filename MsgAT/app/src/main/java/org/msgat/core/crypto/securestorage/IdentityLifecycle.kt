// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.crypto.securestorage

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.network.AttachmentTransport
import org.msgat.core.network.RelayEndpoint
import java.io.File

/** Startup resumes a confirmed reset before opening any identity. */
internal object IdentityLifecycle {
    private const val MARKER="identity.reset.pending"
    private val localOnly = "msgat-local-only-reset-v2".toByteArray(Charsets.US_ASCII)
    private val pendingCount = MutableStateFlow(0)
    val pendingRevocations = pendingCount.asStateFlow()
    private var lastAttempted: String? = null
    private fun marker(context: Context) = File(context.noBackupFilesDir, MARKER)
    private fun queue(context: Context)=File(context.noBackupFilesDir,"revocations")
    fun hasPendingReset(context: Context) = marker(context).exists()
    fun canDiscardUnreadableIdentity(context: Context): Boolean = !hasPendingReset(context) &&
        (File(context.noBackupFilesDir, "identity.initialized").exists() ||
            context.getDatabasePath("msgat_vault.db").exists() ||
            context.getDatabasePath(org.msgat.data.db.AppDatabase.DATABASE_NAME).exists())

    fun prepareLocalOnlyReset(context: Context) {
        check(canDiscardUnreadableIdentity(context)) { "There is no unreadable identity eligible for reset" }
        write(marker(context), localOnly)
    }
    private fun write(file: File,bytes: ByteArray) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create revocation storage" }
        val atomic=AtomicFile(file); val output=atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        check(atomic.readFully().contentEquals(bytes)) { "Cannot commit identity reset storage" }
    }
    fun prepare(context: Context,prefs: SecurePrefs) {
        check(!hasPendingReset(context)) { "A reset is already pending" }
        check((queue(context).listFiles { f -> f.extension=="json" }?.size ?: 0)<256) { "Deliver pending revocations before another reset" }
        val proof=prefs.withRootSeed { seed -> SecurityCore.call(JSONObject().put("operation","create_revocation").put("seed",Base64.encodeToString(seed,Base64.NO_WRAP))).getString("proof") }
        val intent=JSONObject().put("proof",proof)
        if(!prefs.serverIp.isNullOrBlank()) {
            val endpoint=RelayEndpoint.from(prefs)
            intent.put("endpoint",JSONObject().put("host",endpoint.host).put("port",endpoint.port).put("pin",endpoint.certificatePin).put("development",endpoint.allowDevelopmentCertificate))
        }
        val result=validate(intent)
        check(result.length==64)
        write(marker(context),intent.toString().toByteArray(Charsets.UTF_8))
    }
    @Suppress("DEPRECATION")
    fun destroyProtection(context: Context) {
        org.msgat.core.network.AndroidPush.destroySystemRegistration(context)
        val store=java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        check(context.getSharedPreferences("MSGAT_SECURE_PREFS",Context.MODE_PRIVATE).edit().clear().commit()) { "Cannot clear the old identity vault" }
    }
    fun resume(context: Context) {
        val marker=marker(context)
        if(!marker.exists()) return
        check(marker.length()<=8192) { "Invalid identity reset intent" }
        val bytes=AtomicFile(marker).readFully()
        if (!bytes.contentEquals(localOnly) && !bytes.contentEquals(byteArrayOf(1))) {
            val intent=JSONObject(String(bytes,Charsets.UTF_8)); val hash=validate(intent)
            if(intent.has("endpoint")) write(File(queue(context),"$hash.json"),bytes)
        }
        org.msgat.data.db.AppDatabase.closeCurrent()
        destroyProtection(context)
        for(name in listOf("msgat_vault.db",org.msgat.data.db.AppDatabase.DATABASE_NAME)) {
            context.deleteDatabase(name)
            for(suffix in listOf("","-wal","-shm","-journal")) check(!File(context.getDatabasePath(name).path+suffix).exists()) { "Cannot erase old identity database" }
        }
        for(path in listOf(File(context.noBackupFilesDir,"objects"),File(context.filesDir,"media"),context.cacheDir))
            check(!path.exists() || path.deleteRecursively()) { "Cannot erase old identity content" }
        val initialized=File(context.noBackupFilesDir,"identity.initialized")
        check(!initialized.exists() || initialized.delete()) { "Cannot erase old identity marker" }
        check(marker.delete()) { "Cannot commit identity erasure" }
    }
    private fun validate(intent: JSONObject): String {
        check(intent.length() in 1..2 && intent.keys().asSequence().all { it=="proof" || it=="endpoint" })
        val result=SecurityCore.call(JSONObject().put("operation","verify_revocation").put("proof",intent.getString("proof")))
        if(intent.has("endpoint")) endpoint(intent.getJSONObject("endpoint"))
        return result.getString("proof_hash")
    }
    private fun endpoint(value: JSONObject): RelayEndpoint {
        check(value.length()==4)
        val host=value.getString("host"); val port=value.getInt("port"); val pin=value.getString("pin")
        check(host.isNotBlank() && host.length<=253 && port in 1..65535 && (pin.isEmpty() || pin.matches(Regex("[a-f0-9]{64}"))))
        return RelayEndpoint(host,port,pin,value.getBoolean("development"))
    }
    suspend fun deliver(context: Context) {
        val files = queue(context).listFiles { file -> file.extension == "json" }?.sortedBy { it.name } ?: emptyList()
        pendingCount.value = files.size
        if (files.isEmpty()) {
            lastAttempted = null
            return
        }
        val after = lastAttempted
        val start = if (after == null) 0 else files.indexOfFirst { it.name > after }.coerceAtLeast(0)
        for (offset in 0 until minOf(files.size, 8)) {
            val file = files[(start + offset) % files.size]
            lastAttempted = file.name
            try {
                check(file.length()<=8192)
                val intent=JSONObject(String(AtomicFile(file).readFully(),Charsets.UTF_8)); val hash=validate(intent)
                if(file.name!="$hash.json" || !intent.has("endpoint")) continue
                AttachmentTransport.revoke(endpoint(intent.getJSONObject("endpoint")),Base64.decode(intent.getString("proof"),Base64.NO_WRAP))
                check(file.delete()) { "Cannot clear delivered revocation" }
                pendingCount.value -= 1
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { /* The public signed intent remains for the next connection. */ }
        }
    }
}
