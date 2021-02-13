package com.maaxgr.telekram.lib.entities

data class ChatMessage(
    val id: Long,
    val text: String,
    val sender: ChatUser
)