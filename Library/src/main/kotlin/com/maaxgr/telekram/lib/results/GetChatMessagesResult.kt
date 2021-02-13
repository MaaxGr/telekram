package com.maaxgr.telekram.lib.results

import com.maaxgr.telekram.lib.entities.Chat
import com.maaxgr.telekram.lib.entities.ChatMessage

sealed class GetChatMessagesResult {

    data class Ok(val chat: Chat, val messages: List<ChatMessage>) : GetChatMessagesResult()
    data class Error(val errorMessage: String): GetChatMessagesResult()

}