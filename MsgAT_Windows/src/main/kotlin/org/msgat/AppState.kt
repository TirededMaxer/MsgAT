package org.msgat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.media.MediaStorageManager
import org.msgat.core.network.MessageResender
import org.msgat.core.network.NettyClient
import org.msgat.core.network.NetworkMonitor
import org.msgat.data.db.DatabaseManager
import org.msgat.data.db.MsgDao
import org.msgat.model.SyncState
import java.io.File

object AppState {

    lateinit var securePrefs: SecurePrefs
        private set
    lateinit var msgDao: MsgDao
        private set
    lateinit var nettyClient: NettyClient
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(customDataDir: File? = null) {
        val baseDir = customDataDir ?: SecurePrefs.getDefaultDataDirectory()
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        securePrefs = SecurePrefs(baseDir)
        MediaStorageManager.init(baseDir)

        val dbFile = File(baseDir, "msgat_vault.db")
        msgDao = DatabaseManager(dbFile)

        nettyClient = NettyClient(securePrefs, msgDao)
        networkMonitor = NetworkMonitor(nettyClient)

        networkMonitor.startMonitoring()

        if (securePrefs.isConfigured()) {
            nettyClient.connect()
        }

        scope.launch {
            nettyClient.connectionState.collect { state ->
                if (state == SyncState.SYNCED) {
                    MessageResender.resendPendingMessages()
                }
            }
        }
    }
}
