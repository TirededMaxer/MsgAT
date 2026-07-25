package org.msgat.model

import androidx.annotation.Keep
import com.google.gson.Gson

@Keep
data class MessagePayload(
    val senderUuid: String,
    val fileId: String,
    val total: Int,
    val index: Int,
    val ext: String,
    val data: String, 
    val timestamp: Long,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToContent: String? = null,
    val action: String? = null,
    val targetMessageId: String? = null
) {
    // Serializes the message payload object into a JSON string.
    fun toJson(): String = Gson().toJson(this)

    companion object {
        // Deserializes a JSON string back into a message payload object.
        fun fromJson(json: String): MessagePayload = Gson().fromJson(json, MessagePayload::class.java)
    }
}