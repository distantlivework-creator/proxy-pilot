package dev.mtproxypilot

import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.ChannelCursor
import dev.mtproxypilot.domain.MtProtoLinkParser
import dev.mtproxypilot.domain.MtProtoProxy
import dev.mtproxypilot.domain.NewProxyUpdateScanner
import dev.mtproxypilot.domain.ProxyAvailabilityPolicy
import dev.mtproxypilot.domain.ProxyHistory
import dev.mtproxypilot.domain.ProxyHistoryPolicy
import dev.mtproxypilot.domain.TelegramChannelMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoDiscoveryTest {
    private val secret = "9c1d9ff498b2baa7cba0b0336239f509"

    @Test
    fun extractsTelegramLinksAndRemovesDuplicates() {
        val text = """
            Новый адрес: https://t.me/proxy?server=198.199.120.58&port=443&secret=$secret
            Дубль: tg://proxy?server=198.199.120.58&port=443&secret=$secret
        """.trimIndent()

        val result = MtProtoLinkParser.parseAll(text)

        assertEquals(1, result.size)
        assertEquals("198.199.120.58", result.single().server)
        assertEquals(443, result.single().port)
    }

    @Test
    fun rejectsMalformedProxyLinks() {
        assertTrue(MtProtoLinkParser.parseAll("https://t.me/proxy?server=x&port=70000&secret=short").isEmpty())
        assertTrue(MtProtoLinkParser.parseAll("https://example.com/proxy?server=x&port=443&secret=$secret").isEmpty())
    }

    @Test
    fun cursorIgnoresHistoryAndAcceptsOnlyNewMessages() {
        val cursor = ChannelCursor(startedAtEpochSeconds = 1_000, lastMessageId = 50)
        val historical = TelegramChannelMessage(10, 49, 999, "old")
        val delayedOld = TelegramChannelMessage(10, 51, 999, "old date")
        val fresh = TelegramChannelMessage(10, 51, 1_001, "new")

        assertFalse(cursor.accepts(historical))
        assertFalse(cursor.accepts(delayedOld))
        assertTrue(cursor.accepts(fresh))
        assertEquals(51, cursor.advance(fresh).lastMessageId)
    }

    @Test
    fun keepsEveryProxyThatAnswersInsteadOfPickingOnlyTheFastest() {
        val proxy = MtProtoProxy("proxy.example", 443, secret)

        val stable = ProxyAvailabilityPolicy.evaluate(proxy, listOf(800, 1_100, null))
        val unstable = ProxyAvailabilityPolicy.evaluate(proxy, listOf(null, 2_500, null))
        val dead = ProxyAvailabilityPolicy.evaluate(proxy, listOf(null, null, null))

        assertEquals(Availability.AVAILABLE, stable.availability)
        assertEquals(1_100L, stable.medianLatencyMs)
        assertEquals(Availability.UNSTABLE, unstable.availability)
        assertEquals(Availability.UNAVAILABLE, dead.availability)
    }

    @Test
    fun scannerIgnoresOldAndUnsubscribedMessagesButAcceptsNewChannelPost() {
        val scanner = NewProxyUpdateScanner(
            mapOf(10L to ChannelCursor(startedAtEpochSeconds = 1_000, lastMessageId = 50))
        )
        val link = "https://t.me/proxy?server=proxy.example&port=443&secret=$secret"

        assertTrue(scanner.accept(TelegramChannelMessage(99, 51, 1_001, link)).isEmpty())
        assertTrue(scanner.accept(TelegramChannelMessage(10, 49, 1_001, link)).isEmpty())
        assertEquals(1, scanner.accept(TelegramChannelMessage(10, 51, 1_001, link)).size)
        assertEquals(51L, scanner.cursor(10)?.lastMessageId)
    }

    @Test
    fun temporaryFailureKeepsRecentlyWorkingProxyForLaterNetworkCheck() {
        val proxy = MtProtoProxy("proxy.example", 443, secret)
        val now = 2_000_000L
        val successful = ProxyAvailabilityPolicy.evaluate(proxy, listOf(90, 110))
        val firstHistory = ProxyHistoryPolicy.record(ProxyHistory(proxy), successful, now - 1_000)
        val failed = ProxyAvailabilityPolicy.evaluate(proxy, listOf(null, null))
        val afterFailure = ProxyHistoryPolicy.record(firstHistory, failed, now)

        val retained = ProxyHistoryPolicy.visibleResult(failed, afterFailure, now)

        assertTrue(retained?.retainedFromHistory == true)
        assertEquals(Availability.UNSTABLE, retained?.availability)
        assertEquals(110L, retained?.medianLatencyMs)
    }

    @Test
    fun repeatedFailedRoundsHideButDoNotEraseHistory() {
        val proxy = MtProtoProxy("proxy.example", 443, secret)
        val now = 2_000_000L
        val failed = ProxyAvailabilityPolicy.evaluate(proxy, listOf(null, null))
        var history = ProxyHistory(
            proxy = proxy,
            successes = 2,
            lastSuccessfulAt = now - 1_000,
            lastLatencyMs = 110,
        )
        repeat(ProxyHistoryPolicy.HIDE_AFTER_FAILED_ROUNDS) { round ->
            history = ProxyHistoryPolicy.record(history, failed, now + round)
        }

        assertEquals(null, ProxyHistoryPolicy.visibleResult(failed, history, now + 10))
        assertEquals(2, history.successes)
        assertEquals(ProxyHistoryPolicy.HIDE_AFTER_FAILED_ROUNDS, history.consecutiveFailures)
    }
}
