package com.maaxgr.telekram.lib.results

import com.maaxgr.telekram.lib.entities.ChatUser

sealed class GetChatUserResult {

    data class Ok(val user: ChatUser) : GetChatUserResult()
    data class Error(val errorMessage: String): GetChatUserResult()

}