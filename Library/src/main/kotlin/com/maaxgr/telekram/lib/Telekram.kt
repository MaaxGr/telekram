package com.maaxgr.telekram.lib

import com.maaxgr.telekram.lib.entities.Chat
import com.maaxgr.telekram.lib.entities.ChatMessage
import com.maaxgr.telekram.lib.entities.ChatUser
import com.maaxgr.telekram.lib.results.*
import com.maaxgr.telekram.lib.utils.SendSuspendingAndAssertResult.CorrectType
import com.maaxgr.telekram.lib.utils.SendSuspendingAndAssertResult.WrongType
import com.maaxgr.telekram.lib.utils.getOrDefault
import com.maaxgr.telekram.lib.utils.getSenderUserId
import com.maaxgr.telekram.lib.utils.getTextContent
import com.maaxgr.telekram.lib.utils.sendSuspendingAndAssert
import it.tdlight.jni.TdApi

class Telekram(
    apiId: Int,
    apiHash: String,
    phoneNumberProvider: () -> String,
    submitCodeProvider: () -> String
) {

    private val internalTelekramApi = InternalTelekramApi(apiId, apiHash, phoneNumberProvider, submitCodeProvider)
    private val chatUserCache = mutableMapOf<Long, ChatUser>()

    init {
        internalTelekramApi.main()
    }

    suspend fun getChatIds(): GetChatIdsResult {
        val tdApiQuery = TdApi.GetChats(TdApi.ChatListMain(), Long.MAX_VALUE, 0, 100)

        return when(val tdApiResult = internalTelekramApi.client!!.sendSuspendingAndAssert<TdApi.Chats>(tdApiQuery)) {
            is CorrectType<TdApi.Chats> -> {
                val chats = tdApiResult.result
                GetChatIdsResult.Ok(chats.chatIds.asList())
            }
            is WrongType -> {
                GetChatIdsResult.Error("Receive an error for GetChats: ${tdApiResult.result}")
            }
        }
    }

    suspend fun getChats(): GetChatsResult {
        return when (val chatIdsResult = getChatIds()) {
            is GetChatIdsResult.Ok -> {
                val chatIds = chatIdsResult.chatIds

                val chats = chatIds.map { chatId ->
                    val getChatResult = getChatInfo(chatId)
                    if (getChatResult is GetChatResult.Ok) {
                        getChatResult.chat
                    } else {
                        null
                    }
                }.filterNotNull()
                GetChatsResult.Ok(chats)
            }
            is GetChatIdsResult.Error -> {
                GetChatsResult.Error(chatIdsResult.errorMessage)
            }
        }
    }

    suspend fun getChatInfo(chatId: Long): GetChatResult {
        val tdApiQuery = TdApi.GetChat(chatId)
        return when(val tdApiResult = internalTelekramApi.client!!.sendSuspendingAndAssert<TdApi.Chat>(tdApiQuery)) {
            is CorrectType -> {
                val chat = tdApiResult.result
                val lastMessage = chat.lastMessage
                val lastMessageText = lastMessage.content.getTextContent()

                val lastMessageSenderUserId = lastMessage.sender.getSenderUserId()

                val lastMessageUser = getUser(lastMessageSenderUserId).getOrDefault()

                val apiChat = Chat(
                    chatId = chat.id,
                    chatTitle = chat.title,
                    lastMessage = ChatMessage(lastMessage.id, lastMessageText, lastMessageUser)
                )
                GetChatResult.Ok(apiChat)
            }
            is WrongType -> GetChatResult.Error("Error: ${tdApiResult.result}")

        }
    }

    suspend fun getNewestChatMessages(chat: Chat, limit: Int = 20): GetChatMessagesResult {
        val tdApiQuery = TdApi.GetChatHistory(
            chat.chatId, chat.lastMessage.id, 0, limit - 1, false
        )

        return when (val tdApiResult = internalTelekramApi.client!!.sendSuspendingAndAssert<TdApi.Messages>(tdApiQuery)) {
            is CorrectType -> {
                val apiMessages = tdApiResult.result.messages.map {
                    ChatMessage(
                        id = it.id,
                        text = it.content.getTextContent(),
                        sender = getUser(it.sender.getSenderUserId()).getOrDefault()
                    )
                }.toTypedArray()
                GetChatMessagesResult.Ok(chat, listOf(chat.lastMessage, *apiMessages))
            }
            is WrongType -> GetChatMessagesResult.Error("Illegal state")
        }
    }

    suspend fun getUser(userId: Long): GetChatUserResult {
        if (chatUserCache.containsKey(userId)) {
            return GetChatUserResult.Ok(chatUserCache[userId]!!)
        }

        val tdApiQuery = TdApi.GetUser(userId.toInt())

        return when (val tdApiResult = internalTelekramApi.client!!.sendSuspendingAndAssert<TdApi.User>(tdApiQuery)) {
            is CorrectType -> {
                val tdApiUser = tdApiResult.result

                val chatUser = ChatUser(
                    userId = tdApiUser.id,
                    userName = tdApiUser.username,
                    realName = tdApiUser.firstName + " " + tdApiUser.lastName
                )

                chatUserCache.put(userId, chatUser)
                GetChatUserResult.Ok(chatUser)
            }
            is WrongType -> {
                GetChatUserResult.Error("Error: ${tdApiResult.result}")
            }
        }
    }
}