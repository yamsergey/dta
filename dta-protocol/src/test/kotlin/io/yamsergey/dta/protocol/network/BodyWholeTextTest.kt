package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Live events carry only the preview: it stands for the body only when it is all of it. */
class BodyWholeTextTest {
    private fun info(text: String?, stored: Long, encoding: String? = null, binary: Boolean = false, complete: Boolean = true) = BodyInfo(
        BodyPart.MESSAGE, storedBytes = stored, encoding = encoding, binary = binary,
        completeness = if (complete) Completeness.COMPLETE else Completeness.TRUNCATED, completenessReason = if (complete) null else "cut",
        preview = text,
    )

    @Test
    fun `a short text body is its preview`() {
        assertEquals("echo 12", info("echo 12", 7).wholeText())
        assertEquals("ключ", info("ключ", "ключ".toByteArray().size.toLong()).wholeText())
    }

    @Test
    fun `a cut, encoded, binary or longer body is not`() {
        assertNull(info("echo", 400).wholeText())
        assertNull(info("echo", 4, encoding = "gzip").wholeText())
        assertNull(info(null, 4, binary = true).wholeText())
        assertNull(info("echo", 4, complete = false).wholeText())
    }
}
