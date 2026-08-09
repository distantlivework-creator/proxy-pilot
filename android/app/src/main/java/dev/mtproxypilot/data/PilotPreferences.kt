package dev.mtproxypilot.data

import android.content.Context

class PilotPreferences(context: Context) {
    private val values = context.getSharedPreferences("pilot", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = values.getString("server_url", "http://10.0.2.2:8000")!!
        set(value) = values.edit().putString("server_url", value).apply()

    fun cachedStatus(): PilotStatus? {
        val host = values.getString("best_host", null) ?: return null
        return PilotStatus(
            aliveCount = values.getInt("alive_count", 0),
            proxyCount = values.getInt("proxy_count", 0),
            lastChecked = values.getString("last_checked", null),
            bestProxyUrl = values.getString("best_proxy_url", null),
            bestHost = host,
            latencyMs = if (values.contains("latency_ms")) values.getInt("latency_ms", 0) else null,
        )
    }

    fun cache(status: PilotStatus) {
        values.edit()
            .putInt("alive_count", status.aliveCount)
            .putInt("proxy_count", status.proxyCount)
            .putString("last_checked", status.lastChecked)
            .putString("best_proxy_url", status.bestProxyUrl)
            .putString("best_host", status.bestHost)
            .apply {
                if (status.latencyMs == null) remove("latency_ms") else putInt("latency_ms", status.latencyMs)
            }
            .apply()
    }
}

