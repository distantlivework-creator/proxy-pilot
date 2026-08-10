package dev.mtproxypilot

import dev.mtproxypilot.domain.MtProtoProxy
import dev.mtproxypilot.tdlib.TdLibProxyChecker
import dev.mtproxypilot.tdlib.TdRawTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TdLibProxyCheckerTest {
    @Test
    fun pingsProxyDirectlyWithoutAddingOrEnablingIt() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val transport = object : TdRawTransport {
            override suspend fun request(json: String): String {
                val request = JSONObject(json)
                requests += request
                return """{"@type":"seconds","seconds":0.245}"""
            }
        }

        val latency = TdLibProxyChecker(transport).ping(
            MtProtoProxy("proxy.example", 443, "9c1d9ff498b2baa7cba0b0336239f509")
        )

        assertEquals(245L, latency)
        assertEquals(listOf("pingProxy"), requests.map { it.getString("@type") })
        val requestProxy = requests.single().getJSONObject("proxy")
        assertEquals("proxy.example", requestProxy.getString("server"))
        assertFalse(requests.single().has("enable"))
    }
}
