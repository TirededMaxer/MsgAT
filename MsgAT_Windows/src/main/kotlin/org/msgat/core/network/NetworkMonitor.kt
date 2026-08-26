package org.msgat.core.network

import kotlinx.coroutines.*
import org.msgat.model.SyncState
import java.net.InetSocketAddress
import java.net.Socket

class NetworkMonitor(
    private val nettyClient: NettyClient
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startMonitoring() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(10000) // Poll network health every 10 seconds
                if (nettyClient.isConfigured() && nettyClient.connectionState.value == SyncState.DISCONNECTED) {
                    val hasInternet = checkInternetReachability()
                    if (hasInternet && nettyClient.connectionState.value == SyncState.DISCONNECTED) {
                        println("[NetworkMonitor] Network available, reconnecting...")
                        nettyClient.resetBackoff()
                        nettyClient.connect()
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        job?.cancel()
        job = null
    }

    private fun checkInternetReachability(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 53), 2000)
                true
            }
        } catch (_: Exception) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("8.8.8.8", 53), 2000)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
