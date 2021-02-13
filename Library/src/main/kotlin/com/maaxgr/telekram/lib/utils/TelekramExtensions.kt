package com.maaxgr.telekram.lib.utils

import com.maaxgr.telekram.lib.entities.ChatUser
import com.maaxgr.telekram.lib.results.GetChatUserResult

fun GetChatUserResult.getOrDefault(): ChatUser {
    return when (this) {
        is GetChatUserResult.Ok -> this.user
        is GetChatUserResult.Error -> ChatUser(-1, "", "")
    }
}