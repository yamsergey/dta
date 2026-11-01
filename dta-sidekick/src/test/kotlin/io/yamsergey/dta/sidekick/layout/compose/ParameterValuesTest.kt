package io.yamsergey.dta.sidekick.layout.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 018 (T005): parameter values a reader can trust. */
class ParameterValuesTest {
    private fun render(value: Any?, inline: String? = null, default: Boolean = false) =
        ParameterValues.render("p", value, inline, default)

    @Test
    fun `colors are written as ARGB, and unspecified as such`() {
        val purple = (0xFF6750A4L shl 32)
        assertEquals("#FF6750A4", render(purple, ParameterValues.COLOR)!!.value)
        assertEquals("Color", render(purple, ParameterValues.COLOR)!!.type)
        assertEquals("Unspecified", render(16L, ParameterValues.COLOR)!!.value)
    }

    @Test
    fun `a color in another color space is named, not decoded`() {
        val p = render((0x3C00L shl 48) or 5L, ParameterValues.COLOR)!!
        assertTrue(p.type.endsWith("(packed, not decoded)"), p.type)
    }

    @Test
    fun `dp and text units carry their units`() {
        assertEquals("16.0.dp", render(16f, ParameterValues.DP)!!.value)
        assertEquals("Dp", render(16f, ParameterValues.DP)!!.type)
        assertEquals("Unspecified", render(Float.NaN, ParameterValues.DP)!!.value)
        val sp = 0x1_0000_0000L or (22f.toRawBits().toLong() and 0xFFFFFFFFL)
        val em = 0x2_0000_0000L or (1.5f.toRawBits().toLong() and 0xFFFFFFFFL)
        assertEquals("22.0.sp", render(sp, ParameterValues.TEXT_UNIT)!!.value)
        assertEquals("1.5.em", render(em, ParameterValues.TEXT_UNIT)!!.value)
        assertEquals("Unspecified", render(2143289344L, ParameterValues.TEXT_UNIT)!!.value)
        assertEquals("TextUnit", render(sp, ParameterValues.TEXT_UNIT)!!.type)
    }

    @Test
    fun `an inline class not known is raw, named and said not decoded`() {
        val p = render(7L, "com.example.Weight")!!
        assertEquals("7", p.value)
        assertEquals("Weight (packed, not decoded)", p.type)
    }

    @Test
    fun `lambdas and identity-only objects show their kind only`() {
        val lambda = render({ x: Int -> x + 1 })!!
        assertEquals("<lambda>", lambda.value)
        assertEquals("lambda", lambda.type)
        val opaque = render(Opaque())!!
        assertEquals("<Opaque>", opaque.value)
        assertEquals("Opaque", opaque.type)
        assertEquals("<lambda>", render(ComposableLambdaImpl())!!.value)
    }

    @Test
    fun `readable objects keep their text`() {
        assertEquals("Point(x=1, y=2)", render(Point(1, 2))!!.value)
        assertEquals("Now in Android", render("Now in Android")!!.value)
        assertEquals("String", render("x")!!.type)
    }

    @Test
    fun `null is left out`() {
        assertNull(render(null))
    }

    @Test
    fun `long values are cut, and say so`() {
        val p = render("x".repeat(2_000))!!
        assertEquals(ParameterValues.MAX_VALUE, p.value.length)
        assertTrue(p.value.endsWith("…"))
        assertTrue(p.cut)
    }

    @Test
    fun `a default is marked`() {
        assertTrue(render("x", default = true)!!.default)
    }

    private class Opaque
    private data class Point(val x: Int, val y: Int)
    private class ComposableLambdaImpl
}
