package org.msgat.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress

internal enum class RelayNetworkStatus(val canConnect: Boolean) {
    UNKNOWN(true), CHECKING(false), AVAILABLE(true), UNAVAILABLE(false), BLOCKED(false)
}

internal class NetworkMonitor(
    private val context: Context,
    private val nettyClient: NettyClient
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var monitoring = false
    private var defaultNetwork: Network? = null
    private var validated: Boolean? = null
    private var link: NetworkLink? = null

    private data class NetworkLink(
        val interfaceName: String?,
        val addresses: Set<InetAddress>,
        val defaultRoutes: Set<RouteInfo>,
        val dnsServers: List<InetAddress>
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!monitoring || defaultNetwork == network) return
            defaultNetwork = network
            validated = null
            link = null
            nettyClient.defaultNetworkChanged(RelayNetworkStatus.CHECKING)
        }

        override fun onLost(network: Network) {
            if (!monitoring || defaultNetwork != network) return
            defaultNetwork = null
            validated = null
            link = null
            nettyClient.defaultNetworkChanged(RelayNetworkStatus.UNAVAILABLE)
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (!monitoring || defaultNetwork != network) return
            nettyClient.defaultNetworkChanged(if (blocked) RelayNetworkStatus.BLOCKED else RelayNetworkStatus.AVAILABLE)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!monitoring || defaultNetwork != network) return
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val recovered = validated == false && hasInternet
            validated = hasInternet
            if (recovered) nettyClient.retryDefaultNetwork()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (!monitoring || defaultNetwork != network) return
            val current = NetworkLink(
                properties.interfaceName,
                properties.linkAddresses.mapTo(mutableSetOf()) { it.address },
                properties.routes.filter { it.isDefaultRoute }.toSet(),
                properties.dnsServers.toList()
            )
            val previous = link
            link = current
            if (previous == null || previous == current) return

            val interfaceChanged = previous.interfaceName != current.interfaceName
            val existingRouteRemoved = !current.defaultRoutes.containsAll(previous.defaultRoutes)
            val removedAddresses = previous.addresses - current.addresses
            if (interfaceChanged || existingRouteRemoved || removedAddresses.isNotEmpty()) {
                nettyClient.defaultNetworkRouteChanged(interfaceChanged || existingRouteRemoved, removedAddresses)
            } else if (previous.dnsServers != current.dnsServers || previous.defaultRoutes != current.defaultRoutes) {
                nettyClient.retryDefaultNetwork()
            }
        }
    }

    fun startMonitoring() {
        if (monitoring) return
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback, Handler(Looper.getMainLooper()))
            monitoring = true
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "Failed to register network callback", e)
            return
        }
        val initial = try {
            if (connectivityManager.activeNetwork != null) RelayNetworkStatus.CHECKING else RelayNetworkStatus.UNAVAILABLE
        } catch (e: SecurityException) {
            Log.w("NetworkMonitor", "Unable to read the current default network", e)
            RelayNetworkStatus.UNKNOWN
        }
        nettyClient.defaultNetworkChanged(initial)
    }

    fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        defaultNetwork = null
        validated = null
        link = null
        nettyClient.defaultNetworkChanged(RelayNetworkStatus.UNKNOWN)
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "Failed to unregister network callback", e)
        }
    }
}
