package dev.mtproxypilot.tdlib

import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Loads Telegram's official JsonClient at runtime. The class and native library are produced from
 * pinned official TDLib sources in CI; no Telegram session is sent to a third-party backend.
 */
class ReflectiveTdJsonClient(
    private val timeoutMs: Long = 20_000,
) : TdRawTransport, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val jsonClient = Class.forName("org.drinkless.tdlib.JsonClient")
    private val clientId = invokeInt(jsonClient.getMethod("createClientId"))
    private val send: Method = jsonClient.getMethod(
        "send",
        Int::class.javaPrimitiveType,
        String::class.java,
    )
    private val receive: Method = jsonClient.getMethod(
        "receive",
        Double::class.javaPrimitiveType,
    )
    private val receiver: Job = scope.launch { receiveLoop() }

    override suspend fun request(json: String): String {
        val extra = UUID.randomUUID().toString()
        val payload = JSONObject(json).put("@extra", extra).toString()
        val result = CompletableDeferred<String>()
        pending[extra] = result
        return try {
            send.invoke(null, clientId, payload)
            withTimeout(timeoutMs) { result.await() }
        } finally {
            pending.remove(extra)
        }
    }

    private suspend fun receiveLoop() {
        while (scope.isActive) {
            val raw = runCatching { receive.invoke(null, 1.0) as? String }.getOrNull() ?: continue
            val extra = runCatching { JSONObject(raw).optString("@extra") }.getOrNull()
            if (!extra.isNullOrBlank()) pending.remove(extra)?.complete(raw)
        }
    }

    override fun close() {
        receiver.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private fun invokeInt(method: Method): Int = (method.invoke(null) as Number).toInt()
}
