package dev.mtproxypilot.data

import android.content.Context
import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.MtProtoProxy
import dev.mtproxypilot.domain.ProxyAvailability
import dev.mtproxypilot.domain.ProxyHistory
import dev.mtproxypilot.domain.ProxyHistoryPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small local, account-free pool. It deliberately keeps failed routes so a
 * temporary carrier/Wi-Fi problem does not erase a proxy forever.
 */
class ProxyPoolStore(context: Context) {
    private val preferences = context.getSharedPreferences("proxy_pool_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun readAll(): List<ProxyHistory> = runCatching {
        val rows = JSONArray(preferences.getString(KEY_ROWS, "[]"))
        buildList {
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val proxy = MtProtoProxy(
                    server = row.getString("server"),
                    port = row.getInt("port"),
                    secret = row.getString("secret"),
                )
                add(
                    ProxyHistory(
                        proxy = proxy,
                        successes = row.optInt("successes"),
                        failures = row.optInt("failures"),
                        consecutiveFailures = row.optInt("consecutive_failures"),
                        lastCheckedAt = row.optLong("last_checked_at"),
                        lastSuccessfulAt = row.optLong("last_successful_at"),
                        lastLatencyMs = row.optLong("last_latency_ms").takeIf { row.has("last_latency_ms") },
                    )
                )
            }
        }.distinctBy { it.proxy.key }
    }.getOrDefault(emptyList())

    @Synchronized
    fun mergeCandidates(proxies: Collection<MtProtoProxy>) {
        if (proxies.isEmpty()) return
        val records = readAll().associateByTo(linkedMapOf()) { it.proxy.key }
        proxies.forEach { proxy ->
            if (!records.containsKey(proxy.key)) records[proxy.key] = ProxyHistory(proxy)
        }
        write(records.values)
    }

    @Synchronized
    fun record(result: ProxyAvailability, checkedAt: Long = System.currentTimeMillis()): ProxyHistory {
        return recordAll(listOf(result), checkedAt).getValue(result.proxy.key)
    }

    @Synchronized
    fun recordAll(
        results: Collection<ProxyAvailability>,
        checkedAt: Long = System.currentTimeMillis(),
    ): Map<String, ProxyHistory> {
        val records = readAll().associateByTo(linkedMapOf()) { it.proxy.key }
        val updated = linkedMapOf<String, ProxyHistory>()
        results.forEach { result ->
            val old = records[result.proxy.key] ?: ProxyHistory(result.proxy)
            ProxyHistoryPolicy.record(old, result, checkedAt).also { history ->
                records[result.proxy.key] = history
                updated[result.proxy.key] = history
            }
        }
        write(records.values)
        return updated
    }

    fun visibleResult(
        current: ProxyAvailability,
        history: ProxyHistory,
        now: Long = System.currentTimeMillis(),
    ): ProxyAvailability? {
        return ProxyHistoryPolicy.visibleResult(current, history, now)
    }

    @Synchronized
    fun cachedCandidates(): List<MtProtoProxy> = readAll()
        .sortedWith(compareBy<ProxyHistory> { it.consecutiveFailures }.thenByDescending { it.lastSuccessfulAt })
        .map { it.proxy }

    private fun write(records: Collection<ProxyHistory>) {
        val rows = JSONArray()
        records.take(MAX_POOL_SIZE).forEach { history ->
            rows.put(
                JSONObject()
                    .put("server", history.proxy.server)
                    .put("port", history.proxy.port)
                    .put("secret", history.proxy.secret)
                    .put("successes", history.successes)
                    .put("failures", history.failures)
                    .put("consecutive_failures", history.consecutiveFailures)
                    .put("last_checked_at", history.lastCheckedAt)
                    .put("last_successful_at", history.lastSuccessfulAt)
                    .apply { history.lastLatencyMs?.let { put("last_latency_ms", it) } }
            )
        }
        preferences.edit().putString(KEY_ROWS, rows.toString()).apply()
    }

    private companion object {
        const val KEY_ROWS = "rows"
        const val MAX_POOL_SIZE = 500
    }
}
