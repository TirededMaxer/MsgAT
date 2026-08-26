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
        const val MAX_PACKET_SIZE = 262144 // 256KB

        const val TYPE_AUTH_REQ: Byte = 0x00
        const val TYPE_AUTH_CHAL: Byte = 0x01
        const val TYPE_AUTH_RESP: Byte = 0x02
        const val TYPE_MESSAGE: Byte = 0x03
        const val TYPE_HEARTBEAT: Byte = 0x04
        const val TYPE_ACK: Byte = 0x05
        const val TYPE_OFFLINE_END: Byte = 0x06
        const val TYPE_SEARCH_USER: Byte = 0x07
        const val TYPE_UPDATE_PROFILE: Byte = 0x08
        const val TYPE_GET_PROFILE: Byte = 0x0C
        const val TYPE_PROFILE_RESP: Byte = 0x0D
        const val TYPE_FRIEND_ACCEPTED: Byte = 0x0E
        const val TYPE_FRIEND_DELETED: Byte = 0x0F
        const val TYPE_REQUEST_REVOKED: Byte = 0x10
        const val TYPE_ACCOUNT_RESET: Byte = 0x11
        const val TYPE_DELETE_ACCOUNT: Byte = 0x12

        // Voice Call Signaling
        const val TYPE_CALL_OFFER: Byte = 0x13
        const val TYPE_CALL_ANSWER: Byte = 0x14
        const val TYPE_CALL_ICE: Byte = 0x15
        const val TYPE_CALL_REJECT: Byte = 0x16
        const val TYPE_CALL_END: Byte = 0x17
        const val TYPE_CALL_BUSY: Byte = 0x18
        const val TYPE_CALL_SYNC: Byte = 0x19
    }
}
