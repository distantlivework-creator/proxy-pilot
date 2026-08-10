package dev.mtproxypilot.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object MtProtoLinkParser {
    private val linkPattern = Regex(
        "(?i)(?:https?://t\\.me/proxy|tg://proxy)\\?[^\\s<>]+",
    )
    private val trailingPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '”', '»')

    fun parseAll(text: String): List<MtProtoProxy> = linkPattern.findAll(text)
        .mapNotNull { parse(it.value.trimEnd(*trailingPunctuation)) }
        .distinctBy(MtProtoProxy::key)
        .toList()

    fun parse(value: String): MtProtoProxy? = runCatching {
        val uri = URI(value)
        val isTelegramLink = when (uri.scheme?.lowercase()) {
            "tg" -> uri.host.equals("proxy", ignoreCase = true)
            "http", "https" -> uri.host.equals("t.me", ignoreCase = true) &&
                uri.path.equals("/proxy", ignoreCase = true)
            else -> false
        }
        if (!isTelegramLink) return null
        val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2) null else decode(parts[0]).lowercase() to decode(parts[1])
        }.toMap()
        val server = parameters["server"]?.trim().orEmpty()
        val port = parameters["port"]?.toIntOrNull()
        val secret = parameters["secret"]?.trim().orEmpty()
        if (server.isBlank() || port == null || port !in 1..65535 || secret.length < 32) return null
        MtProtoProxy(server = server, port = requireNotNull(port), secret = secret)
    }.getOrNull()

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
