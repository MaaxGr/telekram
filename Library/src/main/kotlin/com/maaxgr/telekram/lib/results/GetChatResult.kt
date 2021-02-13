package com.maaxgr.telekram.lib.results

import com.maaxgr.telekram.lib.entities.Chat

sealed class GetChatResult {

    data class Ok(val chat: Chat) : GetChatResult()
    data class Error(val errorMessage: String): GetChatResult()

}