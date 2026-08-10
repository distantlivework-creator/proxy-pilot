package dev.mtproxypilot.domain

data class MtProtoProxy(
    val server: String,
    val port: Int,
    val secret: String,
) {
    val key: String = "${server.lowercase()}:$port:${secret.lowercase()}"

    fun telegramUrl(): String = buildString {
        append("tg://proxy?server=")
        append(server)
        append("&port=")
        append(port)
        append("&secret=")
        append(secret)
    }
}

data class TelegramChannelMessage(
    val channelId: Long,
    val messageId: Long,
    val dateEpochSeconds: Long,
    val text: String,
)

