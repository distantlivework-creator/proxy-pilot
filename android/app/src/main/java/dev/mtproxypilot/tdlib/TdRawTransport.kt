package dev.mtproxypilot.tdlib

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface TdRawTransport {
    val updates: Flow<String>
        get() = emptyFlow()

    suspend fun request(json: String): String
}
