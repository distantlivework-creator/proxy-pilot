package dev.mtproxypilot.tdlib

import dev.mtproxypilot.domain.MtProtoProxy
import kotlin.math.roundToLong
import org.json.JSONObject

class TdLibProxyChecker(
    private val transport: TdRawTransport,
) {
    suspend fun ping(proxy: MtProtoProxy): Long? {
        val pong = transport.request(
            JSONObject()
                .put("@type", "pingProxy")
                .put(
                    "proxy",
                    JSONObject()
                        .put("@type", "proxy")
                        .put("server", proxy.server)
                        .put("port", proxy.port)
                        .put(
                            "type",
                            JSONObject()
                                .put("@type", "proxyTypeMtproto")
                                .put("secret", proxy.secret),
                        ),
                )
                .toString(),
        ).asTdObject()
        return if (pong.optString("@type") != "seconds") null
        else (pong.optDouble("seconds") * 1_000).roundToLong()
    }

    private fun String.asTdObject(): JSONObject = JSONObject(this)
}
