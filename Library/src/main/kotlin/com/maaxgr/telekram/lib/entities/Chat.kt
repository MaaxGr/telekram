package com.maaxgr.telekram.lib.entities

data class Chat(
    val chatId: Long,
    val chatTitle: String,
    val lastMessage: ChatMessage
)