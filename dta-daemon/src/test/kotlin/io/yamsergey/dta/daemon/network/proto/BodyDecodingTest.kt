package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.Timestamp
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingOutcome
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BodyDecodingTest {

    private val bytes = Timestamp.newBuilder().setSeconds(1_700_000_000).build().toByteArray()
    private val rule = DecodingRule { t -> if (t.url.endsWith("/ts")) Selection(Timestamp.getDescriptor(), "protobuf", "s", "m1") else null }
    private val decoding = BodyDecoding(listOf(rule))
    private fun info(encoding: String? = null, size: Int = bytes.size) =
        BodyInfo(BodyPart.REQUEST, size.toLong(), contentType = "application/x-protobuf", encoding = encoding, binary = true, completeness = Completeness.COMPLETE)
    private fun target(url: String) = BodyTarget(url, "POST", MappingPart.REQUEST, null, "application/x-protobuf")

    @Test
    fun `no matching rule is not applicable`() {
        val result = decoding.decode(target("http://h/other"), bytes, info())
        assertEquals(DecodingOutcome.NOT_APPLICABLE, result.info.outcome)
        assertNull(result.json)
        assertEquals(DecodingOutcome.NOT_APPLICABLE, BodyDecoding.NONE.decode(target("http://h/ts"), bytes, info()).info.outcome)
    }

    @Test
    fun `content encoding is undone before decoding`() {
        val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
        val plain = decoding.decode(target("http://h/ts"), bytes, info())
        val gzip = decoding.decode(target("http://h/ts"), zipped, info("gzip", zipped.size))
        assertEquals(DecodingOutcome.DECODED, gzip.info.outcome)
        assertEquals(plain, gzip)
    }

    @Test
    fun `an encoding that cannot be undone is not decoded`() {
        val result = decoding.decode(target("http://h/ts"), bytes, info("br"))
        assertEquals(DecodingOutcome.NOT_DECODED, result.info.outcome)
        assertEquals("br encoding not decoded", result.info.reason)
    }

    @Test
    fun `the first matching rule wins`() {
        val second = DecodingRule { Selection(Timestamp.getDescriptor(), "protobuf", "s", "m2") }
        assertEquals("m1", BodyDecoding(listOf(rule, second)).decode(target("http://h/ts"), bytes, info()).info.rule)
        assertEquals("m2", BodyDecoding(listOf(rule, second)).decode(target("http://h/x"), bytes, info()).info.rule)
    }
}
