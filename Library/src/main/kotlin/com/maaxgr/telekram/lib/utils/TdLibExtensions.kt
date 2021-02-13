package com.maaxgr.telekram.lib.utils

import it.tdlight.common.TelegramClient
import it.tdlight.jni.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

fun TdApi.MessageContent.getTextContent(): String {
    if (this is TdApi.MessageText) {
        return this.text.text ?: ""
    }

    return "Unsupported message type"
}

fun TdApi.MessageSender.getSenderUserId(): Long {
    if (this is TdApi.MessageSenderUser) {
        return this.userId.toLong()
    }
    return -1L
}

suspend inline fun <reified T> TelegramClient.sendSuspendingAndAssert(query: TdApi.Function): SendSuspendingAndAssertResult<T> {
    return suspendCoroutine { continuation ->
        this.send(query) {
            if (it is T) {
                continuation.resume(SendSuspendingAndAssertResult.CorrectType(it))
            } else {
                continuation.resume(SendSuspendingAndAssertResult.WrongType(it))
            }
        }
    }
}