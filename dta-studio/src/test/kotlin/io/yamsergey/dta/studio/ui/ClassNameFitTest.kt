package io.yamsergey.dta.studio.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The truncation rule (mockup "Truncation rules"): the identifying part of a class name is never cut. One char = 1 px here. */
class ClassNameFitTest {
    private val m: (String) -> Int = { it.length }
    private fun fit(name: String, width: Int) = ClassNameFit.fit(name, m, width)

    private val activity = "io.yamsergey.dta.channel.HeapLeakActivity"

    @Test fun `a name that fits is whole, package apart from class`() {
        val f = fit(activity, 100)
        assertEquals("io.yamsergey.dta.channel.", f.prefix)
        assertEquals("HeapLeakActivity", f.name)
    }

    @Test fun `the package is shortened in the middle keeping the first and the last segment`() {
        // 41 chars whole; "io.…channel.HeapLeakActivity" is 28.
        val f = fit(activity, 30)
        assertEquals("io.…channel.", f.prefix)
        assertEquals("HeapLeakActivity", f.name)
    }

    @Test fun `middle segments are dropped one by one, the last ones kept longest`() {
        val f = fit("io.yamsergey.dta.channel.HeapLeakActivity", 36)
        assertEquals("io.…dta.channel.", f.prefix)
    }

    @Test fun `when even that does not fit the package is an ellipsis alone`() {
        val f = fit(activity, 20)
        assertEquals("…", f.prefix)
        assertEquals("HeapLeakActivity", f.name)
    }

    @Test fun `the simple name is never cut however narrow`() {
        val f = fit(activity, 5)
        assertEquals("HeapLeakActivity", f.name)
        assertEquals("…", f.prefix)
    }

    @Test fun `a class without a package is whole`() {
        assertEquals(ClassNameFit.Fit("", "Main"), fit("Main", 2))
    }

    @Test fun `a synthetic class keeps its outer name and the tail of its last part when it must shrink`() {
        val f = fit("io.x.HeapLeakActivity\$\$ExternalSyntheticLambda0", 30)
        assertEquals("…", f.prefix)
        assertTrue(f.name.startsWith("HeapLeakActivity\$\$…"), f.name)
        assertTrue(f.name.endsWith("Lambda0"), f.name)
        assertTrue(f.text.length <= 30, f.text)
    }

    @Test fun `a synthetic class keeps the outer name even when no tail fits`() {
        val f = fit("io.x.HeapLeakActivity\$\$ExternalSyntheticLambda0", 10)
        assertEquals("HeapLeakActivity\$\$…", f.name)
    }

    @Test fun `a synthetic class that fits after the package is shortened is not touched`() {
        val f = fit("io.yamsergey.dta.Outer\$Inner", 20)
        assertEquals("Outer\$Inner", f.name)
        assertEquals("io.…dta.", f.prefix)
    }

    @Test fun `two classes of one package differ in what is drawn`() {
        val a = fit("io.yamsergey.dta.channel.HeapLeakActivity", 30)
        val b = fit("io.yamsergey.dta.channel.HeapLeakService", 30)
        assertTrue(a.text != b.text)
    }

    @Test fun `end ellipsis cuts only the end and only when needed`() {
        assertEquals("hello world", ClassNameFit.end("hello world", m, 11))
        assertEquals("hello…", ClassNameFit.end("hello world", m, 6))
        assertEquals("…", ClassNameFit.end("hello world", m, 1))
    }

    @Test
    fun `a resource id keeps the id whole and gives up its package from the start`() {
        val id = "io.yamsergey.dta.fixture.channel:id/form_submit"
        val m = { t: String -> t.length }
        assertEquals(id, ClassNameFit.resource(id, m, 100))
        val cut = ClassNameFit.resource(id, m, 30)
        assertTrue(cut.startsWith("…") && cut.endsWith(":id/form_submit") && cut.length <= 30, cut)
        assertEquals("…/form_submit", ClassNameFit.resource(id, m, 13))
        assertEquals("form_sub…", ClassNameFit.resource(id, m, 9))
    }
}
