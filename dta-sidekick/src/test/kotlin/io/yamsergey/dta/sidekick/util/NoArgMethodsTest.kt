package io.yamsergey.dta.sidekick.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 062: a class's methods are scanned once, not per call. */
class NoArgMethodsTest {
    class Node(private val id: Int) {
        fun getSemanticsId(): Int = id
        fun withArg(x: Int) = x
    }

    @Test
    fun `the same method for every instance, scanned once per class and name`() {
        val before = NoArgMethods.scans
        val ids = (1..1000).map { NoArgMethods.call(Node(it), "getSemanticsId") }
        assertEquals((1..1000).toList(), ids)
        assertEquals(before + 1, NoArgMethods.scans)
    }

    @Test
    fun `a missing method or one taking arguments is null, also remembered`() {
        assertNull(NoArgMethods.of(Node::class.java, "getNothing"))
        val before = NoArgMethods.scans
        assertNull(NoArgMethods.of(Node::class.java, "getNothing"))
        assertNull(NoArgMethods.of(Node::class.java, "withArg").also { })
        assertEquals(before + 1, NoArgMethods.scans)
    }
}
