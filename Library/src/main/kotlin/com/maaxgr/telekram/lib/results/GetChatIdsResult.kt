package com.maaxgr.telekram.lib.results


sealed class GetChatIdsResult {

    data class Ok(val chatIds: List<Long>): GetChatIdsResult()
    data class Error(val errorMessage: String): GetChatIdsResult()

}