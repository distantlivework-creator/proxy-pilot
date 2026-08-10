package dev.mtproxypilot.tdlib

import dev.mtproxypilot.domain.TelegramChannelMessage
import org.json.JSONArray
import org.json.JSONObject

data class TelegramChannelSubscription(
    val chatId: Long,
    val title: String,
    val latestMessageId: Long,
)

class TdLibSubscribedChannelSource(
    private val transport: TdRawTransport,
) {
    suspend fun list(limit: Int = 200): List<TelegramChannelSubscription> {
        runCatching {
            transport.request(
                JSONObject()
                    .put("@type", "loadChats")
                    .put("chat_list", JSONObject().put("@type", "chatListMain"))
                    .put("limit", limit)
                    .toString(),
            )
        }
        val chats = requestObject(
            JSONObject()
                .put("@type", "getChats")
                .put("chat_list", JSONObject().put("@type", "chatListMain"))
                .put("limit", limit),
        ).optJSONArray("chat_ids") ?: JSONArray()

        return buildList {
            for (index in 0 until chats.length()) {
                val chat = requestObject(
                    JSONObject().put("@type", "getChat").put("chat_id", chats.getLong(index))
                )
                val type = chat.optJSONObject("type") ?: continue
                if (type.optString("@type") != "chatTypeSupergroup") continue
                val supergroup = requestObject(
                    JSONObject()
                        .put("@type", "getSupergroup")
                        .put("supergroup_id", type.optLong("supergroup_id")),
                )
                if (!supergroup.optBoolean("is_channel")) continue
                add(
                    TelegramChannelSubscription(
                        chatId = chat.optLong("id"),
                        title = chat.optString("title", "Telegram channel"),
                        latestMessageId = chat.optJSONObject("last_message")?.optLong("id") ?: 0,
                    )
                )
            }
        }
    }

    private suspend fun requestObject(request: JSONObject): JSONObject {
        val response = JSONObject(transport.request(request.toString()))
        check(response.optString("@type") != "error") {
            response.optString("message", "TDLib error")
        }
        return response
    }
}

object TdLibNewMessageDecoder {
    fun decode(rawUpdate: String): TelegramChannelMessage? {
        val update = runCatching { JSONObject(rawUpdate) }.getOrNull() ?: return null
        if (update.optString("@type") != "updateNewMessage") return null
        val message = update.optJSONObject("message") ?: return null
        val content = message.optJSONObject("content") ?: return null
        val text = extractText(content).trim()
        if (text.isEmpty()) return null
        return TelegramChannelMessage(
            channelId = message.optLong("chat_id"),
            messageId = message.optLong("id"),
            dateEpochSeconds = message.optLong("date"),
            text = text,
        )
    }

    private fun extractText(content: JSONObject): String = when (content.optString("@type")) {
        "messageText" -> content.optJSONObject("text")?.optString("text").orEmpty()
        "messagePhoto", "messageVideo", "messageAnimation", "messageDocument" -> {
            content.optJSONObject("caption")?.optString("text").orEmpty()
        }
        else -> ""
    }
}
