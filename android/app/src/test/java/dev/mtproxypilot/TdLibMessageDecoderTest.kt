package dev.mtproxypilot

import dev.mtproxypilot.tdlib.TdLibNewMessageDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TdLibMessageDecoderTest {
    @Test
    fun decodesNewTextMessage() {
        val update = """
            {"@type":"updateNewMessage","message":{"id":77,"chat_id":-1001,"date":1234,
            "content":{"@type":"messageText","text":{"@type":"formattedText","text":"new proxy"}}}}
        """.trimIndent()

        val message = TdLibNewMessageDecoder.decode(update)

        assertEquals(77L, message?.messageId)
        assertEquals(-1001L, message?.channelId)
        assertEquals("new proxy", message?.text)
    }

    @Test
    fun ignoresNonMessageUpdate() {
        assertNull(TdLibNewMessageDecoder.decode("""{"@type":"updateConnectionState"}"""))
    }
}
