package dev.mtproxypilot.tdlib

import dev.mtproxypilot.domain.MtProtoProxy
import kotlin.math.roundToLong
import org.json.JSONObject

class TdLibProxyChecker(
    private val transport: TdRawTransport,
) {
    suspend fun ping(proxy: MtProtoProxy): Long? {
        val added = transport.request(
            JSONObject()
                .put("@type", "addProxy")
                .put("server", proxy.server)
                .put("port", proxy.port)
                .put("enable", false)
                .put(
                    "type",
                    JSONObject()
                        .put("@type", "proxyTypeMtproto")
                        .put("secret", proxy.secret),
                )
                .toString(),
        ).asTdObject()
        if (added.optString("@type") != "proxy") return null
        val proxyId = added.optInt("id", -1)
        if (proxyId < 0) return null

        return try {
            val pong = transport.request(
                JSONObject()
                    .put("@type", "pingProxy")
                    .put("proxy_id", proxyId)
                    .toString(),
            ).asTdObject()
            if (pong.optString("@type") != "seconds") null
            else (pong.optDouble("seconds") * 1_000).roundToLong()
        } finally {
            runCatching {
                transport.request(
                    JSONObject()
                        .put("@type", "removeProxy")
                        .put("proxy_id", proxyId)
                        .toString(),
                )
            }
        }
    }

    private fun String.asTdObject(): JSONObject = JSONObject(this)
}
