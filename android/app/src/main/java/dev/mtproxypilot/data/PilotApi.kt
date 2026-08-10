package dev.mtproxypilot.data

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PilotStatus(
    val aliveCount: Int,
    val proxyCount: Int,
    val lastChecked: String?,
    val bestProxyUrl: String?,
    val bestHost: String?,
    val latencyMs: Int?,
)

class PilotApi {
    fun loadStatus(serverUrl: String): PilotStatus {
        val root = normalizeServerUrl(serverUrl)
        val status = getJson("$root/api/status")
        val proxies = getJsonArray("$root/api/proxies")
        val best = (0 until proxies.length())
            .map { proxies.getJSONObject(it) }
            .filter { it.optString("status") == "alive" }
            .minByOrNull { if (it.isNull("latency_ms")) Int.MAX_VALUE else it.optInt("latency_ms") }
        return PilotStatus(
            aliveCount = status.optInt("alive_count"),
            proxyCount = status.optInt("proxy_count"),
            lastChecked = status.optJSONObject("last_run")?.optString("finished_at")?.takeIf { it.isNotBlank() },
            bestProxyUrl = best?.let(::proxyUrl),
            bestHost = best?.let { "${it.optString("host")}:${it.optInt("port")}" },
            latencyMs = best?.takeUnless { it.isNull("latency_ms") }?.optInt("latency_ms"),
        )
    }

    fun requestSync(serverUrl: String) {
        readText("${normalizeServerUrl(serverUrl)}/api/sync", "POST")
    }

    private fun proxyUrl(proxy: JSONObject): String = Uri.Builder()
        .scheme("tg")
        .authority("proxy")
        .appendQueryParameter("server", proxy.getString("host"))
        .appendQueryParameter("port", proxy.getInt("port").toString())
        .appendQueryParameter("secret", proxy.getString("secret"))
        .build().toString()

    private fun getJson(url: String) = JSONObject(readText(url))

    private fun getJsonArray(url: String) = org.json.JSONArray(readText(url))

    private fun readText(url: String, method: String = "GET"): String {
        val connection = request(url, method)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(url: String, method: String = "GET"): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-Platform", "android")
        connection.setRequestProperty("X-App-Version", "0.1.0")
        if (method == "POST") connection.doOutput = true
        if (connection.responseCode !in 200..299) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
            connection.disconnect()
            throw IllegalStateException(message ?: "HTTP error")
        }
        return connection
    }

    companion object {
        fun normalizeServerUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "Адрес должен начинаться с http:// или https://"
            }
            return trimmed
        }
    }
}
