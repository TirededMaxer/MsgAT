// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import org.msgat.MsgATApp
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class MsgATPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) =
        deliver { it.endpointChanged(instance, endpoint) }

    override fun onMessage(message: PushMessage, instance: String) {
        if (message.decrypted && message.content.size == 58) deliver { it.received(instance, message) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) =
        deliver { it.distributorError(instance) }

    override fun onUnregistered(instance: String) = deliver { it.distributorError(instance) }

    private fun deliver(operation: (AndroidPush) -> Unit) {
        val app = application as MsgATApp
        // The connector unbinds after delivering the callback, possibly before the vault opens.
        app.onPushStartup { startup ->
            if (startup == MsgATApp.StartupState.READY) operation(app.androidPush)
        }
    }
}
