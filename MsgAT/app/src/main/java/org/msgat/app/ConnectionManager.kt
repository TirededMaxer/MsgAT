package org.msgat.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.msgat.core.network.NettyClient

class ConnectionManager(private val nettyClient: NettyClient) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (nettyClient.isConfigured()) {
            nettyClient.connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        nettyClient.disconnect()
    }
}