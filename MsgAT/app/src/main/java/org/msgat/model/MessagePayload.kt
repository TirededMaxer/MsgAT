package org.msgat.model

import com.google.gson.Gson

data class MessagePayload(
    val senderUuid: String,
    val fileId: String,
    val total: Int,
    val index: Int,
    val ext: String,
    val data: String, // Base64 编码数据
    val timestamp: Long
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): MessagePayload = Gson().fromJson(json, MessagePayload::class.java)
    }
}