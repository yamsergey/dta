package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 068: the feed's messages, and small bodies carried with their records. */
class FeedModelTest {
    private val message = WsMessageEntry("r1-1:2", "r1-1", 10, "okhttp3-ws", connectionId = "r1-1:1", direction = Direction.IN,
        opcode = MessageOpcode.TEXT, payload = BodyInfo(BodyPart.MESSAGE, 600, completeness = Completeness.COMPLETE, preview = "x".repeat(256)))

    @Test
    fun `a batch goes and comes back whole`() {
        val batch = FeedBatch("f1", "j1", from = "3:10", to = "3:12", records = listOf(message),
            bodies = listOf(FeedBody.of("r1-1:2", BodyPart.MESSAGE, "x".repeat(600).toByteArray(), binary = false)))
        val back = DtaJson.decodeFromString(FeedBatch.serializer(), DtaJson.encodeToString(FeedBatch.serializer(), batch))
        assertEquals(batch, back)
        assertEquals("network.feed", FeedBatch.EVENT)
    }

    @Test
    fun `a body is text when it is text, base64 when it is not, and gives its bytes back`() {
        val text = FeedBody.of("e", BodyPart.RESPONSE, "{\"a\":\"é\"}".toByteArray(), binary = false)
        assertEquals("{\"a\":\"é\"}", text.text)
        assertNull(text.base64)
        assertContentEquals("{\"a\":\"é\"}".toByteArray(), text.bytes())
        val raw = byteArrayOf(0, 1, -1, 120)
        val binary = FeedBody.of("e", BodyPart.RESPONSE, raw, binary = false)
        assertNull(binary.text, "not valid UTF-8 text: base64")
        assertContentEquals(raw, binary.bytes())
        val marked = FeedBody.of("e", BodyPart.RESPONSE, "abc".toByteArray(), binary = true)
        assertNull(marked.text, "a body marked binary stays bytes")
        assertContentEquals("abc".toByteArray(), marked.bytes())
    }

    @Test
    fun `the end of a subscription says why and where`() {
        val ended = FeedEnded("f1", FeedEnded.OVERFLOW, at = "4:7")
        assertEquals(ended, DtaJson.decodeFromString(FeedEnded.serializer(), DtaJson.encodeToString(FeedEnded.serializer(), ended)))
        assertEquals("network.feed.ended", FeedEnded.EVENT)
    }

    @Test
    fun `a journal page carries small bodies too`() {
        val page = JournalPageResult("j1", listOf(message), "3:12", end = true, bodies = listOf(FeedBody.of("r1-1:2", BodyPart.MESSAGE, "hi".toByteArray(), false)))
        assertEquals(page, DtaJson.decodeFromString(JournalPageResult.serializer(), DtaJson.encodeToString(JournalPageResult.serializer(), page)))
        assertTrue(FeedBody.MAX_BYTES == 64L * 1024)
    }

    @Test
    fun `the protocol version says the feed`() {
        assertTrue(PROTOCOL_VERSION >= 3)
    }
}
