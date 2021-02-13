package com.maaxgr.telekram.lib.results

import com.maaxgr.telekram.lib.entities.Chat


sealed class GetChatsResult {

    data class Ok(val chats: List<Chat>): GetChatsResult()
    data class Error(val errorMessage: String): GetChatsResult()

}