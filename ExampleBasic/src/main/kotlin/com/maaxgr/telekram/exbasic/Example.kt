package com.maaxgr.telekram.exbasic

import com.maaxgr.telekram.lib.Telekram
import com.maaxgr.telekram.lib.results.GetChatMessagesResult
import com.maaxgr.telekram.lib.results.GetChatResult
import com.maaxgr.telekram.lib.results.GetChatsResult
import kotlinx.coroutines.runBlocking
import java.util.*

fun main() = runBlocking {
    val telekram = Telekram(
        apiId = 94575,
        apiHash = "a3406de8d171bb422bb6ddf3bbd800e2",
        phoneNumberProvider = {
            println("Enter telephone number: ")
            Scanner(System.`in`).nextLine()
        },
        submitCodeProvider = {
            println("Enter submit code number: ")
            Scanner(System.`in`).nextLine()
        }
    )

    // get all chats
    when (val result = telekram.getChats()) {
        is GetChatsResult.Ok -> {
            val chats = result.chats
            println("Fetched ${chats.size} chats")

            chats.forEach { chat ->
                println("The last message in chat '${chat.chatTitle}' is '${chat.lastMessage.text}'")
            }
        }
        is GetChatsResult.Error -> {
            println("Error while getting chats: ${result.errorMessage}")
        }
    }

    // get messages for specific chat
    val chatInfoResult = telekram.getChatInfo(-1001257721998)
    if (chatInfoResult is GetChatResult.Ok) {

        val chat = chatInfoResult.chat
        when (val result = telekram.getNewestChatMessages(chat)) {
            is GetChatMessagesResult.Ok -> {
                println("Fetched: ${result.chat}")
            }
            is GetChatMessagesResult.Error -> {
                System.err.println("Error while getting chat: ${result.errorMessage}")
            }
        }
    }
}