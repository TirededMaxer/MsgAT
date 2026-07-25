package org.msgat.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

class NetworkMonitor(
    private val context: Context,
    private val nettyClient: NettyClient
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkMonitor", "Network available: $network")
            if (nettyClient.isConfigured() && nettyClient.connectionState.value != org.msgat.ui.chat.SyncState.SYNCED) {
                nettyClient.resetBackoff()
                nettyClient.connect()
            }
        }

        override fun onLost(network: Network) {
            Log.w("NetworkMonitor", "Network lost: $network")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Log.d("NetworkMonitor", "Network capabilities changed. Has Internet: $hasInternet")
            if (hasInternet && nettyClient.isConfigured() && nettyClient.connectionState.value != org.msgat.ui.chat.SyncState.SYNCED) {
                nettyClient.resetBackoff()
                nettyClient.connect()
            }
        }
    }

    fun startMonitoring() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            Log.i("NetworkMonitor", "Started network monitoring")
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "Failed to register network callback", e)
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "Failed to unregister network callback", e)
        }
    }
}
