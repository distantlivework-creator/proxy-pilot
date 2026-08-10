package dev.mtproxypilot.domain

/**
 * The cursor is created when monitoring is enabled for a channel. The baseline
 * message is never processed, so pre-existing channel history stays ignored.
 */
data class ChannelCursor(
    val startedAtEpochSeconds: Long,
    val lastMessageId: Long,
) {
    fun accepts(message: TelegramChannelMessage): Boolean =
        message.channelId != 0L &&
            message.messageId > lastMessageId &&
            message.dateEpochSeconds >= startedAtEpochSeconds

    fun advance(message: TelegramChannelMessage): ChannelCursor =
        if (message.messageId > lastMessageId) copy(lastMessageId = message.messageId) else this
}

