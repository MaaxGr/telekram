package com.maaxgr.telekram.lib

import it.tdlight.common.ExceptionHandler
import it.tdlight.common.Init
import it.tdlight.common.ResultHandler
import it.tdlight.common.TelegramClient
import it.tdlight.jni.TdApi
import it.tdlight.tdlight.ClientManager
import java.io.BufferedReader
import java.io.IOError
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

/**
 * Based on TDLight-Example with some modifications:
 * https://github.com/tdlight-team/tdlight-java/blob/master/example/src/main/java/it.tdlight.example/Example.java
 */
class InternalTelekramApi(
    private val apiId: Int,
    private val apiHash: String,
    private val phoneNumberProvider: () -> String,
    private val submitCodeProvider: () -> String
) {

    var client: TelegramClient? = null

    private var authorizationState: TdApi.AuthorizationState? = null

    @Volatile
    private var haveAuthorization = false

    @Volatile
    private var needQuit = false

    @Volatile
    private var canQuit = false

    private val defaultHandler: ResultHandler = DefaultHandler()

    private val authorizationLock: Lock = ReentrantLock()
    private val gotAuthorization: Condition = authorizationLock.newCondition()

    private val users: ConcurrentMap<Int, TdApi.User> = ConcurrentHashMap()
    private val basicGroups: ConcurrentMap<Int, TdApi.BasicGroup> = ConcurrentHashMap()
    private val supergroups: ConcurrentMap<Int, TdApi.Supergroup> = ConcurrentHashMap()
    private val secretChats: ConcurrentMap<Int, TdApi.SecretChat> = ConcurrentHashMap()

    private val chats: ConcurrentMap<Long, TdApi.Chat> = ConcurrentHashMap()
    private val mainChatList: NavigableSet<OrderedChat> = TreeSet()
    private var haveFullMainChatList = false

    private val usersFullInfo: ConcurrentMap<Int, TdApi.UserFullInfo> = ConcurrentHashMap()
    private val basicGroupsFullInfo: ConcurrentMap<Int, TdApi.BasicGroupFullInfo> = ConcurrentHashMap()
    private val supergroupsFullInfo: ConcurrentMap<Int, TdApi.SupergroupFullInfo> = ConcurrentHashMap()

    private val newLine = System.getProperty("line.separator")
    private val commandsLine =
        "Enter command (gcs - GetChats, gc <chatId> - GetChat, me - GetMe, sm <chatId> <message> - SendMessage, lo - LogOut, q - Quit): "

    @Volatile
    private var currentPrompt: String? = null

    private fun print(str: String) {
        if (currentPrompt != null) {
            println("")
        }
        println(str)
        if (currentPrompt != null) {
            print(currentPrompt)
        }
    }

    private fun setChatPositions(chat: TdApi.Chat, positions: Array<TdApi.ChatPosition>) {
        synchronized(mainChatList) {
            synchronized(chat) {
                for (position in chat.positions) {
                    if (position.list.constructor == TdApi.ChatListMain.CONSTRUCTOR) {
                        val isRemoved = mainChatList.remove(OrderedChat(chat.id, position))
                        assert(isRemoved)
                    }
                }
                chat.positions = positions
                for (position in chat.positions) {
                    if (position.list.constructor == TdApi.ChatListMain.CONSTRUCTOR) {
                        val isAdded = mainChatList.add(OrderedChat(chat.id, position))
                        assert(isAdded)
                    }
                }
            }
        }
    }

    private fun onAuthorizationStateUpdated(authorizationState: TdApi.AuthorizationState?) {
        if (authorizationState != null) {
            this.authorizationState = authorizationState
        }


        when (this.authorizationState!!.getConstructor()) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                val parameters = TdApi.TdlibParameters()
                parameters.databaseDirectory = "tdlib"
                parameters.useMessageDatabase = false
                parameters.useSecretChats = true
                parameters.apiId = apiId
                parameters.apiHash = apiHash
                parameters.systemLanguageCode = "en"
                parameters.deviceModel = "Desktop"
                parameters.applicationVersion = "1.0"
                parameters.enableStorageOptimizer = false
                client!!.send(TdApi.SetTdlibParameters(parameters), AuthorizationRequestHandler())
            }
            TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR -> client!!.send(
                TdApi.CheckDatabaseEncryptionKey(),
                AuthorizationRequestHandler()
            )
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                val phoneNumber = phoneNumberProvider()
                client!!.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null), AuthorizationRequestHandler())
            }
            TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR -> {
                val link = (this.authorizationState as TdApi.AuthorizationStateWaitOtherDeviceConfirmation).link
                println("Please confirm this login link on another device: $link")
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                val code = submitCodeProvider()
                client!!.send(TdApi.CheckAuthenticationCode(code), AuthorizationRequestHandler())
            }
            TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR -> {
                val firstName = promptString("Please enter your first name: ")
                val lastName = promptString("Please enter your last name: ")
                client!!.send(TdApi.RegisterUser(firstName, lastName), AuthorizationRequestHandler())
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                val password = promptString("Please enter password: ")
                client!!.send(TdApi.CheckAuthenticationPassword(password), AuthorizationRequestHandler())
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                haveAuthorization = true
                authorizationLock.lock()
                try {
                    gotAuthorization.signal()
                } finally {
                    authorizationLock.unlock()
                }
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                haveAuthorization = false
                print("Logging out")
            }
            TdApi.AuthorizationStateClosing.CONSTRUCTOR -> {
                haveAuthorization = false
                print("Closing")
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                print("Closed")
                if (!needQuit) {
                    client = ClientManager.create() // recreate client after previous has closed
                    client!!.initialize(UpdateHandler(), ErrorHandler(), ErrorHandler())
                } else {
                    canQuit = true
                }
            }
            else -> System.err.println("Unsupported authorization state:" + newLine + this.authorizationState)
        }
    }

    private fun toInt(arg: String): Int {
        var result = 0
        try {
            result = arg.toInt()
        } catch (ignored: NumberFormatException) {
        }
        return result
    }

    private fun getChatId(arg: String): Long {
        var chatId: Long = 0
        try {
            chatId = arg.toLong()
        } catch (ignored: NumberFormatException) {
        }
        return chatId
    }

    private fun promptString(prompt: String): String {
        print(prompt)
        currentPrompt = prompt
        val reader = BufferedReader(InputStreamReader(System.`in`))
        var str = ""
        try {
            str = reader.readLine()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        currentPrompt = null
        return str
    }

    private fun getCommand() {
        val command = promptString(commandsLine)
        val commands = command.split(Regex(" "), 2).toTypedArray()
        try {
            when (commands[0]) {
                "gcs" -> {
                    var limit = 20
                    if (commands.size > 1) {
                        limit = toInt(commands[1])
                    }
                    getMainChatList(limit)
                }
                "gc" -> {
                    client!!.send(TdApi.GetChatHistory(getChatId(commands[1]), 18003001344, 0, 10, false), defaultHandler)
                    client!!.send(TdApi.GetChat(), defaultHandler)
                }
                "me" -> client!!.send(TdApi.GetMe(), defaultHandler)
                "sm" -> {
                    val args = commands[1].split(Regex(" "), 2).toTypedArray()
                    sendMessage(getChatId(args[0]), args[1])
                }
                "lo" -> {
                    haveAuthorization = false
                    client!!.send(TdApi.LogOut(), defaultHandler)
                }
                "q" -> {
                    needQuit = true
                    haveAuthorization = false
                    client!!.send(TdApi.Close(), defaultHandler)
                }
                else -> System.err.println("Unsupported command: $command")
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            print("Not enough arguments")
        }
    }

    private fun getMainChatList(limit: Int) {
        synchronized(mainChatList) {
            if (!haveFullMainChatList && limit > mainChatList.size) {
                // have enough chats in the chat list or chat list is too small
                var offsetOrder = Long.MAX_VALUE
                var offsetChatId: Long = 0
                if (!mainChatList.isEmpty()) {
                    val last = mainChatList.last()
                    offsetOrder = last.position.order
                    offsetChatId = last.chatId
                }
                client!!.send(
                    TdApi.GetChats(TdApi.ChatListMain(), offsetOrder, offsetChatId, limit - mainChatList.size)
                ) { `object` ->
                    when (`object`.constructor) {
                        TdApi.Error.CONSTRUCTOR -> System.err.println("Receive an error for GetChats:$newLine$`object`")
                        TdApi.Chats.CONSTRUCTOR -> {
                            val chatIds = (`object` as TdApi.Chats).chatIds
                            if (chatIds.size == 0) {
                                synchronized(mainChatList) { haveFullMainChatList = true }
                            }
                            // chats had already been received through updates, let's retry request
                            getMainChatList(limit)
                        }
                        else -> System.err.println("Receive wrong response from TDLib:$newLine$`object`")
                    }
                }
                return
            }

            // have enough chats in the chat list to answer request
            val iter: Iterator<OrderedChat> = mainChatList.iterator()
            println()
            println("First " + limit + " chat(s) out of " + mainChatList.size + " known chat(s):")
            var i = 0
            while (i < limit && iter.hasNext()) {
                val chatId = iter.next().chatId
                val chat = chats[chatId]
                synchronized(chat!!) { println(chatId.toString() + ": " + chat.title) }
                i++
            }
            print("")
        }
    }

    private fun sendMessage(chatId: Long, message: String) {
        // initialize reply markup just for testing
        val row = arrayOf(
            TdApi.InlineKeyboardButton("https://telegram.org?1", TdApi.InlineKeyboardButtonTypeUrl()),
            TdApi.InlineKeyboardButton("https://telegram.org?2", TdApi.InlineKeyboardButtonTypeUrl()),
            TdApi.InlineKeyboardButton("https://telegram.org?3", TdApi.InlineKeyboardButtonTypeUrl())
        )
        val replyMarkup: TdApi.ReplyMarkup = TdApi.ReplyMarkupInlineKeyboard(arrayOf(row, row, row))
        val content: TdApi.InputMessageContent = TdApi.InputMessageText(TdApi.FormattedText(message, null), false, true)
        client!!.send(TdApi.SendMessage(chatId, 0, 0, null, replyMarkup, content), defaultHandler)
    }

    fun main() {
        // create client
        Init.start()
        client = ClientManager.create()
        client!!.initialize(UpdateHandler(), ErrorHandler(), ErrorHandler())
        client!!.execute(TdApi.SetLogVerbosityLevel(0))
        // disable TDLib log
        if (client!!.execute(TdApi.SetLogStream(TdApi.LogStreamFile("tdlib.log", 1 shl 27, false))) is TdApi.Error) {
            throw IOError(IOException("Write access to the current directory is required"))
        }

        // test Client.execute
        defaultHandler.onResult(client!!.execute(TdApi.GetTextEntities("@telegram /test_command https://telegram.org telegram.me @gif @test")))

        // await authorization
        authorizationLock.lock()
        try {
            while (!haveAuthorization) {
                gotAuthorization.await()
            }
        } finally {
            authorizationLock.unlock()
        }
    }

    class OrderedChat internal constructor(val chatId: Long, val position: TdApi.ChatPosition) : Comparable<OrderedChat> {
        override operator fun compareTo(o: OrderedChat): Int {
            if (position.order != o.position.order) {
                return if (o.position.order < position.order) -1 else 1
            }
            return if (chatId != o.chatId) {
                if (o.chatId < chatId) -1 else 1
            } else 0
        }

        override fun equals(obj: Any?): Boolean {
            val o = obj as OrderedChat?
            return chatId == o!!.chatId && position.order == o.position.order
        }
    }

    private class DefaultHandler : ResultHandler {
        override fun onResult(obj: TdApi.Object) {
        }
    }

    private inner class UpdateHandler : ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            when (`object`.constructor) {
                TdApi.UpdateAuthorizationState.CONSTRUCTOR -> onAuthorizationStateUpdated((`object` as TdApi.UpdateAuthorizationState).authorizationState)
                TdApi.UpdateUser.CONSTRUCTOR -> {
                    val updateUser = `object` as TdApi.UpdateUser
                    users.put(updateUser.user.id, updateUser.user)
                }
                TdApi.UpdateUserStatus.CONSTRUCTOR -> {
                    val updateUserStatus = `object` as TdApi.UpdateUserStatus
                    val user: TdApi.User = users.get(updateUserStatus.userId)!!
                    synchronized(user) { user.status = updateUserStatus.status }
                }
                TdApi.UpdateBasicGroup.CONSTRUCTOR -> {
                    val updateBasicGroup = `object` as TdApi.UpdateBasicGroup
                    basicGroups.put(updateBasicGroup.basicGroup.id, updateBasicGroup.basicGroup)
                }
                TdApi.UpdateSupergroup.CONSTRUCTOR -> {
                    val updateSupergroup = `object` as TdApi.UpdateSupergroup
                    supergroups.put(updateSupergroup.supergroup.id, updateSupergroup.supergroup)
                }
                TdApi.UpdateSecretChat.CONSTRUCTOR -> {
                    val updateSecretChat = `object` as TdApi.UpdateSecretChat
                    secretChats.put(updateSecretChat.secretChat.id, updateSecretChat.secretChat)
                }
                TdApi.UpdateNewChat.CONSTRUCTOR -> {
                    val updateNewChat = `object` as TdApi.UpdateNewChat
                    val chat = updateNewChat.chat
                    synchronized(chat) {
                        chats.put(chat.id, chat)
                        val positions = chat.positions
                        chat.positions = arrayOfNulls(0)
                        setChatPositions(chat, positions)
                    }
                }
                TdApi.UpdateChatTitle.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatTitle
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) { chat.title = updateChat.title }
                }
                TdApi.UpdateChatPhoto.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatPhoto
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) { chat.photo = updateChat.photo }
                }
                TdApi.UpdateChatLastMessage.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatLastMessage
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) {
                        chat.lastMessage = updateChat.lastMessage
                        setChatPositions(chat, updateChat.positions)
                    }
                }
                TdApi.UpdateChatPosition.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatPosition
                    if (updateChat.position.list.constructor != TdApi.ChatListMain.CONSTRUCTOR) {
                        return
                    }
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) {
                        var i: Int
                        i = 0
                        while (i < chat.positions.size) {
                            if (chat.positions[i].list.constructor == TdApi.ChatListMain.CONSTRUCTOR) {
                                break
                            }
                            i++
                        }
                        val new_positions =
                            arrayOfNulls<TdApi.ChatPosition>(chat.positions.size + (if (updateChat.position.order == 0L) 0 else 1) - if (i < chat.positions.size) 1 else 0)
                        var pos = 0
                        if (updateChat.position.order != 0L) {
                            new_positions[pos++] = updateChat.position
                        }
                        var j = 0
                        while (j < chat.positions.size) {
                            if (j != i) {
                                new_positions[pos++] = chat.positions[j]
                            }
                            j++
                        }
                        assert(pos == new_positions.size)
                        setChatPositions(chat, new_positions.filterNotNull().toTypedArray())
                    }
                }
                TdApi.UpdateChatReadInbox.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatReadInbox
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) {
                        chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId
                        chat.unreadCount = updateChat.unreadCount
                    }
                }
                TdApi.UpdateChatReadOutbox.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatReadOutbox
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) { chat.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId }
                }
                TdApi.UpdateChatUnreadMentionCount.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatUnreadMentionCount
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) { chat.unreadMentionCount = updateChat.unreadMentionCount }
                }
                TdApi.UpdateMessageMentionRead.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateMessageMentionRead
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) { chat.unreadMentionCount = updateChat.unreadMentionCount }
                }
                TdApi.UpdateChatReplyMarkup.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatReplyMarkup
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) { chat.replyMarkupMessageId = updateChat.replyMarkupMessageId }
                }
                TdApi.UpdateChatDraftMessage.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatDraftMessage
                    val chat: TdApi.Chat = chats.get(updateChat.chatId)!!
                    synchronized(chat) {
                        chat.draftMessage = updateChat.draftMessage
                        setChatPositions(chat, updateChat.positions)
                    }
                }
                TdApi.UpdateChatPermissions.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatPermissions
                    val chat: TdApi.Chat = chats.get(update.chatId)!!
                    synchronized(chat) { chat.permissions = update.permissions }
                }
                TdApi.UpdateChatNotificationSettings.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatNotificationSettings
                    val chat: TdApi.Chat = chats.get(update.chatId)!!
                    synchronized(chat) { chat.notificationSettings = update.notificationSettings }
                }
                TdApi.UpdateChatDefaultDisableNotification.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatDefaultDisableNotification
                    val chat: TdApi.Chat = chats.get(update.chatId)!!
                    synchronized(chat) { chat.defaultDisableNotification = update.defaultDisableNotification }
                }
                TdApi.UpdateChatIsMarkedAsUnread.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatIsMarkedAsUnread
                    val chat: TdApi.Chat = chats.get(update.chatId)!!
                    synchronized(chat) { chat.isMarkedAsUnread = update.isMarkedAsUnread }
                }
                TdApi.UpdateChatIsBlocked.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatIsBlocked
                    val chat: TdApi.Chat = chats.get(update.chatId)!!
                    synchronized(chat) { chat.isBlocked = update.isBlocked }
                }
                TdApi.UpdateChatHasScheduledMessages.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatHasScheduledMessages
                    val chat: TdApi.Chat = chats.get(update.chatId)!!
                    synchronized(chat) { chat.hasScheduledMessages = update.hasScheduledMessages }
                }
                TdApi.UpdateUserFullInfo.CONSTRUCTOR -> {
                    val updateUserFullInfo = `object` as TdApi.UpdateUserFullInfo
                    usersFullInfo.put(updateUserFullInfo.userId, updateUserFullInfo.userFullInfo)
                }
                TdApi.UpdateBasicGroupFullInfo.CONSTRUCTOR -> {
                    val updateBasicGroupFullInfo = `object` as TdApi.UpdateBasicGroupFullInfo
                    basicGroupsFullInfo.put(
                        updateBasicGroupFullInfo.basicGroupId,
                        updateBasicGroupFullInfo.basicGroupFullInfo
                    )
                }
                TdApi.UpdateSupergroupFullInfo.CONSTRUCTOR -> {
                    val updateSupergroupFullInfo = `object` as TdApi.UpdateSupergroupFullInfo
                    supergroupsFullInfo.put(
                        updateSupergroupFullInfo.supergroupId,
                        updateSupergroupFullInfo.supergroupFullInfo
                    )
                }
                else -> {
                }
            }
        }
    }

    private inner class ErrorHandler : ExceptionHandler {
        override fun onException(e: Throwable) {
            e.printStackTrace()
        }
    }

    private inner class AuthorizationRequestHandler : ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            when (`object`.constructor) {
                TdApi.Error.CONSTRUCTOR -> {
                    System.err.println("Receive an error:$newLine$`object`")
                    onAuthorizationStateUpdated(null) // repeat last action
                }
                TdApi.Ok.CONSTRUCTOR -> {
                }
                else -> System.err.println("Receive wrong response from TDLib:$newLine$`object`")
            }
        }
    }


}