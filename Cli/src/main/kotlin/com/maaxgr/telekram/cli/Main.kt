package com.maaxgr.telekram.cli

import com.maaxgr.telekram.lib.Telekram
import com.maaxgr.telekram.lib.entities.ChatUser
import com.maaxgr.telekram.lib.results.GetChatMessagesResult
import com.maaxgr.telekram.lib.results.GetChatsResult
import com.yg.kotlin.inquirer.components.promptInput
import com.yg.kotlin.inquirer.components.promptList
import com.yg.kotlin.inquirer.core.KInquirer
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {

    val telekram = Telekram(
        apiId = 94575,
        apiHash = "a3406de8d171bb422bb6ddf3bbd800e2",
        phoneNumberProvider = {
            KInquirer.promptInput("Enter telephone number: ")
        },
        submitCodeProvider = {
            KInquirer.promptInput("Enter submit code: ")
        }
    )

    val chatsResult = telekram.getChats()
    if (chatsResult is GetChatsResult.Ok) {

        val chats = chatsResult.chats
        val chatNames = chats.map { chat -> chat.chatTitle }
        val selectedChatName: String = KInquirer.promptList(
            "Which chat do you want to view?", chatNames)
        val selectedChat = chats.first { it.chatTitle == selectedChatName }

        println()
        println()
        println("Chat Info for " + selectedChat.chatTitle)

        val selectedChatInfo = telekram.getNewestChatMessages(selectedChat)

        if (selectedChatInfo is GetChatMessagesResult.Ok) {
            println("Messages:")
            selectedChatInfo.messages.forEach { message ->
                println("${message.sender.getName()}: ${message.text}")
            }
        }
    }
}

fun ChatUser.getName(): String {
    if (this.userName.isNotBlank()) {
        return this.userName.trim()
    }
    return this.realName.trim()
}