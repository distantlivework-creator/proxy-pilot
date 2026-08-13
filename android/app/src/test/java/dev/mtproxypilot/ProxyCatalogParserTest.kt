package dev.mtproxypilot

import dev.mtproxypilot.data.ProxyCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyCatalogParserTest {
    @Test
    fun parsesAndDeduplicatesPublishedCatalog() {
        val raw = """
            {
              "updated_at": "2026-08-13T10:00:00Z",
              "proxies": [
                {"host":"proxy.example","port":443,"secret":"9c1d9ff498b2baa7cba0b0336239f509"},
                {"host":"proxy.example","port":443,"secret":"9c1d9ff498b2baa7cba0b0336239f509"}
              ]
            }
        """.trimIndent()

        val catalog = ProxyCatalogParser.parse(raw)

        assertEquals("2026-08-13T10:00:00Z", catalog.updatedAt)
        assertEquals(1, catalog.proxies.size)
        assertEquals("proxy.example", catalog.proxies.single().server)
    }

    @Test
    fun rejectsEmptyCatalog() {
        val failure = runCatching { ProxyCatalogParser.parse("""{"proxies":[]}""") }
        assertTrue(failure.isFailure)
    }
}
