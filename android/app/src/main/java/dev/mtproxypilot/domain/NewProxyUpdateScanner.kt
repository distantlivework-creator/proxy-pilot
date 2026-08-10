package dev.mtproxypilot.domain

class NewProxyUpdateScanner(
    subscriptions: Map<Long, ChannelCursor>,
) {
    private val cursors = subscriptions.toMutableMap()

    fun accept(message: TelegramChannelMessage): List<MtProtoProxy> {
        val cursor = cursors[message.channelId] ?: return emptyList()
        if (!cursor.accepts(message)) return emptyList()
        cursors[message.channelId] = cursor.advance(message)
        return MtProtoLinkParser.parseAll(message.text)
    }

    fun cursor(chatId: Long): ChannelCursor? = cursors[chatId]
}
