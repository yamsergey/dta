package io.yamsergey.dta.sidekick.network.script

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ThrottleInputStreamTest {

    private var now = 0L
    private val slept = mutableListOf<Long>()
    private val clock = { now }
    private val sleeper: (Long) -> Unit = { ms -> slept += ms; now += ms * 1_000_000 }

    @Test
    fun `S bytes at R bytes per second take at least S over R seconds`() {
        val data = ByteArray(10_000) { it.toByte() }
        val read = ThrottleInputStream(ByteArrayInputStream(data), bytesPerSecond = 1_000, nanoClock = clock, sleep = sleeper).use { it.readBytes() }
        assertContentEquals(data, read)
        assertTrue(now >= 9_000_000_000L, "elapsed ${now / 1_000_000} ms")
    }

    @Test
    fun `single byte reads are throttled too`() {
        val stream = ThrottleInputStream(ByteArrayInputStream(ByteArray(3000)), 1_000, clock, sleeper)
        while (stream.read() != -1) Unit
        assertTrue(now >= 2_000_000_000L, "elapsed ${now / 1_000_000} ms")
    }

    @Test
    fun `closing stops further waiting`() {
        val stream = ThrottleInputStream(ByteArrayInputStream(ByteArray(10_000)), 1_000, clock, sleeper)
        stream.read(ByteArray(100))
        stream.close()
        val before = slept.size
        runCatching { stream.read(ByteArray(100)) }
        assertTrue(slept.size == before)
    }
}
