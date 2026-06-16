package org.msgat.model

import com.google.gson.Gson

data class MessagePayload(
    val senderUuid: String,
    val fileId: String,
    val total: Int,
    val index: Int,
    val ext: String,
    val data: String, 
    val timestamp: Long
) {
    // Serializes the message payload object into a JSON string.
    fun toJson(): String = Gson().toJson(this)

    companion object {
        // Deserializes a JSON string back into a message payload object.
        fun fromJson(json: String): MessagePayload = Gson().fromJson(json, MessagePayload::class.java)
    }
}