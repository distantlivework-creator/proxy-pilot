package dev.mtproxypilot.tdlib

interface TdRawTransport {
    suspend fun request(json: String): String
}
