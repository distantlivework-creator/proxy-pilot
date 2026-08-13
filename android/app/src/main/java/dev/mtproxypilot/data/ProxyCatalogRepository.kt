package dev.mtproxypilot.data

import dev.mtproxypilot.domain.MtProtoProxy
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import org.json.JSONObject

data class ProxyCatalog(val updatedAt: String?, val proxies: List<MtProtoProxy>)

object ProxyCatalogParser {
    fun parse(raw: String): ProxyCatalog {
        val root = JSONObject(raw)
        val rows = root.getJSONArray("proxies")
        val proxies = buildList {
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val proxy = MtProtoProxy(
                    server = row.getString("host"),
                    port = row.getInt("port"),
                    secret = row.getString("secret"),
                )
                if (proxy.server.isNotBlank() && proxy.port in 1..65535 && proxy.secret.length >= 32) add(proxy)
            }
        }.distinctBy(MtProtoProxy::key)
        require(proxies.isNotEmpty()) { "Каталог пока не содержит доступных адресов" }
        return ProxyCatalog(root.optString("updated_at").takeIf(String::isNotBlank), proxies)
    }
}

class ProxyCatalogRepository(
    private val catalogUrl: String = "https://distantlivework-creator.github.io/proxy-pilot/data/proxies.json",
) {
    fun load(): ProxyCatalog {
        val connection = URL(catalogUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ProxyPilot-Android/0.3")
            check(connection.responseCode in 200..299) { "Каталог ответил ${connection.responseCode}" }
            ProxyCatalogParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

class TcpProxyChecker(private val timeoutMs: Int = 3_500) {
    fun ping(proxy: MtProtoProxy): Long? {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(proxy.server, proxy.port), timeoutMs)
            }
            (System.nanoTime() - started) / 1_000_000
        }.getOrNull()
    }
}
