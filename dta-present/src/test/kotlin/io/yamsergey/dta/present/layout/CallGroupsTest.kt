package io.yamsergey.dta.present.layout

import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 059: consecutive calls from one file fold into one group. */
class CallGroupsTest {
    private fun call(name: String, file: String?, line: Int = 1, pkg: String? = "app") =
        NodeDetail.Call("$name ($file:$line)", file?.let { Source(it, line, pkg) })

    private fun shape(chain: List<NodeDetail.Call>) =
        CallGroups.of(chain).map { "${it.first}:${it.file}x${it.calls.size}" }

    @Test
    fun `a run of one file is one group, numbered by its first call`() {
        val chain = listOf(call("A", "A.kt"), call("S", "B.kt", 2), call("N", "B.kt", 5), call("I", "B.kt", 9), call("T", "C.kt"))
        assertEquals(listOf("1:A.ktx1", "2:B.ktx3", "5:C.ktx1"), shape(chain))
        assertEquals(listOf("S", "N", "I"), CallGroups.of(chain)[1].calls.map { it.text.substringBefore(' ') })
    }

    @Test
    fun `the same file apart is two groups and the order never changes`() {
        val chain = listOf(call("a", "B.kt"), call("b", "C.kt"), call("c", "B.kt"))
        assertEquals(listOf("1:B.ktx1", "2:C.ktx1", "3:B.ktx1"), shape(chain))
    }

    @Test
    fun `the same file name in another package is another group`() {
        val chain = listOf(call("a", "Text.kt", pkg = "app.ui"), call("b", "Text.kt", pkg = "app.ds"))
        assertEquals(listOf("1:Text.ktx1", "2:Text.ktx1"), shape(chain))
    }

    @Test
    fun `a call without a source stays on its own`() {
        val chain = listOf(call("a", "B.kt"), call("x", null), call("b", "B.kt"))
        assertEquals(listOf("1:B.ktx1", "2:nullx1", "3:B.ktx1"), shape(chain))
    }

    @Test
    fun `keys are stable for the same chain and differ between groups`() {
        val chain = listOf(call("a", "B.kt"), call("b", "B.kt"), call("c", "C.kt"))
        val keys = CallGroups.of(chain).map { it.key }
        assertEquals(keys, CallGroups.of(chain.toList()).map { it.key })
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("1–2", CallGroups.of(chain)[0].range)
        assertEquals("3", CallGroups.of(chain)[1].range)
    }

    @Test
    fun `an empty chain has no groups`() = assertEquals(emptyList(), CallGroups.of(emptyList()))
}
