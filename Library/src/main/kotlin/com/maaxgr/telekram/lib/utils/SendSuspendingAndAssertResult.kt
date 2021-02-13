package com.maaxgr.telekram.lib.utils

import it.tdlight.jni.TdApi


sealed class SendSuspendingAndAssertResult<T> {
    data class CorrectType<T>(val result: T): SendSuspendingAndAssertResult<T>()
    data class WrongType<T>(val result: TdApi.Object): SendSuspendingAndAssertResult<T>()
}