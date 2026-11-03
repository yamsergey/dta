package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.Completeness
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeeStreamsTest {

    private class MemorySink(private val failAfter: Int = Int.MAX_VALUE) : BodySink {
        val bytes = ByteArrayOutputStream()
        var closed = false
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (bytes.size() + length > failAfter) throw IOException("disk full")
            bytes.write(buffer, offset, length)
        }
        override fun close() { closed = true }
    }

    private val data = ByteArray(10_000) { (it * 31 + 7).toByte() }

    private fun recorder(sink: BodySink?, bound: Long? = 1_000_000, declared: Long? = null): Pair<BodyRecorder, () -> BodyCapture?> {
        var captured: BodyCapture? = null
        return BodyRecorder(sink, bound, declared) { captured = it } to { captured }
    }

    @Test
    fun `without a bound every byte the app reads is stored`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink, bound = null)
        TeeInputStream(ByteArrayInputStream(data), rec).use { it.readBytes() }
        assertContentEquals(data, sink.bytes.toByteArray())
        assertEquals(BodyCapture(10_000, 10_000, Completeness.COMPLETE, null), result())
    }

    @Test
    fun `teed input returns exactly the app's bytes and stores them complete`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink)
        val read = TeeInputStream(ByteArrayInputStream(data), rec).use { it.readBytes() }
        assertContentEquals(data, read)
        assertContentEquals(data, sink.bytes.toByteArray())
        assertEquals(BodyCapture(10_000, 10_000, Completeness.COMPLETE, null), result())
        assertTrue(sink.closed)
    }

    @Test
    fun `single-byte reads are teed too`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink)
        TeeInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), rec).use { s -> while (s.read() != -1) Unit }
        assertContentEquals(byteArrayOf(1, 2, 3), sink.bytes.toByteArray())
        assertEquals(Completeness.COMPLETE, result()!!.completeness)
    }

    @Test
    fun `bound hit truncates storage but the app still gets every byte`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink, bound = 4096)
        val read = TeeInputStream(ByteArrayInputStream(data), rec).use { it.readBytes() }
        assertContentEquals(data, read)
        assertEquals(4096, sink.bytes.size())
        val capture = result()!!
        assertEquals(Completeness.TRUNCATED, capture.completeness)
        assertEquals(4096, capture.storedBytes)
        assertEquals(10_000, capture.observedBytes)
        assertTrue("4096" in capture.reason!!, capture.reason)
    }

    @Test
    fun `closing before end of stream is partially observed`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink)
        TeeInputStream(Arrived(data, 100), rec).use { s -> s.read(ByteArray(100)) }
        val capture = result()!!
        assertEquals(Completeness.PARTIALLY_OBSERVED, capture.completeness)
        assertEquals(100, capture.observedBytes)
        assertNotNull(capture.reason)
    }

    /** A network stream: [arrived] bytes are in its buffer; reading past them would wait for the network. */
    private class Arrived(private val bytes: ByteArray, private var arrived: Int) : java.io.InputStream() {
        var pos = 0
        var closed = false
        var waited = false
        override fun available() = arrived - pos
        override fun read(): Int {
            if (pos >= arrived) { waited = true; throw IOException("would wait for the network") }
            return bytes[pos++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= arrived) { waited = true; throw IOException("would wait for the network") }
            val n = minOf(len, arrived - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
        override fun close() { closed = true }
    }

    @Test
    fun `a body the app closes unread is captured from what already arrived`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink, declared = 41)
        val body = data.copyOf(41)
        val stream = Arrived(body, 41)
        TeeInputStream(stream, rec).close()
        assertEquals(BodyCapture(41, 41, Completeness.COMPLETE, null), result())
        assertContentEquals(body, sink.bytes.toByteArray())
        assertTrue(stream.closed)
        assertTrue(!stream.waited)
    }

    @Test
    fun `a body the app read in part is completed from what already arrived`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink, declared = 1000)
        TeeInputStream(Arrived(data.copyOf(1000), 1000), rec).use { it.read(ByteArray(100)) }
        assertEquals(BodyCapture(1000, 1000, Completeness.COMPLETE, null), result())
        assertContentEquals(data.copyOf(1000), sink.bytes.toByteArray())
    }

    @Test
    fun `what arrived is still bounded`() {
        val (rec, result) = recorder(MemorySink(), bound = 4096, declared = 10_000)
        TeeInputStream(Arrived(data, 10_000), rec).close()
        val capture = result()!!
        assertEquals(Completeness.TRUNCATED, capture.completeness)
        assertEquals(4096, capture.storedBytes)
    }

    @Test
    fun `closing before any of the body arrived never waits and says so`() {
        val (rec, result) = recorder(MemorySink(), declared = 41)
        val stream = Arrived(data.copyOf(41), 0)
        TeeInputStream(stream, rec).close()
        val capture = result()!!
        assertEquals(Completeness.PARTIALLY_OBSERVED, capture.completeness)
        assertEquals("the app closed the body without reading it; 0 of 41 bytes had arrived", capture.reason)
        assertTrue(!stream.waited)
        assertTrue(stream.closed)
    }

    @Test
    fun `closing with part of the body arrived counts it`() {
        val (rec, result) = recorder(MemorySink(), declared = 1000)
        TeeInputStream(Arrived(data.copyOf(1000), 300), rec).use { it.read(ByteArray(100)) }
        val capture = result()!!
        assertEquals(Completeness.PARTIALLY_OBSERVED, capture.completeness)
        assertEquals(300, capture.observedBytes)
        assertEquals("the app stopped reading after 100 bytes; 300 of 1000 bytes had arrived", capture.reason)
    }

    @Test
    fun `a body of unknown length closed early keeps what arrived and says the end was not seen`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink)
        TeeInputStream(Arrived(data, 500), rec).close()
        val capture = result()!!
        assertEquals(Completeness.PARTIALLY_OBSERVED, capture.completeness)
        assertEquals(500, capture.storedBytes)
        assertEquals("the app closed the body without reading it; 500 bytes had arrived, the end was not seen", capture.reason)
    }

    @Test
    fun `a decompressing stream is not read at close`() {
        val zipped = ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(data) } }.toByteArray()
        val (rec, result) = recorder(MemorySink(), declared = 10_000)
        TeeInputStream(java.util.zip.GZIPInputStream(ByteArrayInputStream(zipped)), rec).close()
        val capture = result()!!
        assertEquals(Completeness.PARTIALLY_OBSERVED, capture.completeness)
        assertEquals(0, capture.observedBytes)
    }

    @Test
    fun `a failure while taking what arrived is contained`() {
        val failing = object : java.io.InputStream() {
            var closed = false
            override fun available() = 10
            override fun read(): Int = throw IOException("reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("reset")
            override fun close() { closed = true }
        }
        val (rec, result) = recorder(MemorySink(), declared = 10)
        TeeInputStream(failing, rec).close()
        assertTrue(failing.closed)
        assertEquals(Completeness.PARTIALLY_OBSERVED, result()!!.completeness)
    }

    @Test
    fun `closing after every declared byte was read is complete`() {
        val (empty, emptyResult) = recorder(MemorySink(), declared = 0)
        TeeInputStream(ByteArrayInputStream(ByteArray(0)), empty).close()
        assertEquals(Completeness.COMPLETE, emptyResult()!!.completeness, "an empty body the app never read")
        val (rec, result) = recorder(MemorySink(), declared = 100)
        TeeInputStream(ByteArrayInputStream(data.copyOf(100)), rec).use { s -> s.readNBytes(100) }
        assertEquals(Completeness.COMPLETE, result()!!.completeness, "all 100 declared bytes seen, EOF never read")
    }

    @Test
    fun `sink failure drops the body without disturbing the app`() {
        val sink = MemorySink(failAfter = 50)
        val (rec, result) = recorder(sink)
        val read = TeeInputStream(ByteArrayInputStream(data), rec).use { it.readBytes() }
        assertContentEquals(data, read)
        val capture = result()!!
        assertEquals(Completeness.DROPPED, capture.completeness)
        assertTrue("disk full" in capture.reason!!, capture.reason)
    }

    @Test
    fun `no sink (storage unavailable) still counts bytes and says why`() {
        val (rec, result) = recorder(null)
        TeeInputStream(ByteArrayInputStream(data), rec).use { it.readBytes() }
        val capture = result()!!
        assertEquals(Completeness.DROPPED, capture.completeness)
        assertEquals(0, capture.storedBytes)
        assertEquals(10_000, capture.observedBytes)
    }

    @Test
    fun `app read error is rethrown unchanged and the body is partially observed`() {
        val failing = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 10) 1 else throw IOException("connection reset")
        }
        val (rec, result) = recorder(MemorySink())
        val e = runCatching { TeeInputStream(failing, rec).use { it.readBytes() } }.exceptionOrNull()
        assertEquals("connection reset", e?.message)
        assertEquals(Completeness.PARTIALLY_OBSERVED, result()!!.completeness)
    }

    @Test
    fun `teed output forwards writes and records them complete on close`() {
        val target = ByteArrayOutputStream()
        val sink = MemorySink()
        val (rec, result) = recorder(sink)
        TeeOutputStream(target, rec).use { out ->
            out.write(data, 0, 6000)
            out.write(data[6000].toInt())
            out.write(data, 6001, 3999)
        }
        assertContentEquals(data, target.toByteArray())
        assertContentEquals(data, sink.bytes.toByteArray())
        assertEquals(Completeness.COMPLETE, result()!!.completeness)
    }

    @Test
    fun `an omitted body states why and stores nothing`() {
        val sink = MemorySink()
        val (rec, result) = recorder(sink)
        rec.omit("duplex body")
        assertEquals(BodyCapture(0, 0, Completeness.OMITTED, "duplex body"), result())
        assertTrue(sink.closed)
    }

    @Test
    fun `recorder finishes once`() {
        var calls = 0
        val rec = BodyRecorder(MemorySink(), 100) { calls++ }
        rec.finish(endOfStream = true)
        rec.finish(endOfStream = false)
        assertEquals(1, calls)
    }

    @Test
    fun `a throwing completion callback is contained`() {
        val rec = BodyRecorder(MemorySink(), 100) { error("boom") }
        TeeInputStream(ByteArrayInputStream(byteArrayOf(1)), rec).use { it.readBytes() }
    }
}
