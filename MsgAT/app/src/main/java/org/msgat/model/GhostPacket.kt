package org.msgat.model

class GhostPacket(
    val type: Byte,
    val targetMailboxId: String?,
    val nonceOrIv: ByteArray?,    // 16 bytes
    val encryptedData: ByteArray?,
    val authTag: ByteArray?       // 16 bytes
) {
    val payloadLen: Int = encryptedData?.size ?: 0

    companion object {
        const val PACKET_SIZE = 512000 // 500KB

        const val TYPE_AUTH_REQ: Byte = 0x00
        const val TYPE_AUTH_CHAL: Byte = 0x01
        const val TYPE_AUTH_RESP: Byte = 0x02
        const val TYPE_MESSAGE: Byte = 0x03
        const val TYPE_HEARTBEAT: Byte = 0x04
        const val TYPE_ACK: Byte = 0x05
        const val TYPE_OFFLINE_END: Byte = 0x06
    }
}