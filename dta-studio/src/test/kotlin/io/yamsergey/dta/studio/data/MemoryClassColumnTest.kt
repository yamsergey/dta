package io.yamsergey.dta.studio.data

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

/** Truncation rules, rule 1 (mockup "Truncation rules" 1b/1d): the Memory Class column keeps the simple name, shortens the package. */
class MemoryClassColumnTest : BasePlatformTestCase() {
    private val pkg = "io.yamsergey.dta.channel"

    private fun answer(op: String): JsonObject = Json.parseToJsonElement(
        when (op) {
            "runtime.memory" -> """{"javaUsedBytes":1,"javaMaxBytes":2,"javaFreeBytes":1,"nativeAllocatedBytes":1,"nativeHeapBytes":2,"pssKb":1,"gcCount":1,"gcTimeMs":1,"gcRun":false}"""
            "runtime.heap.snapshots" -> """{"run":"r1","snapshots":[{"id":"s1","name":"a","takenAtMs":1,"pauseMs":2,"objects":1,"bytes":1},{"id":"s2","name":"b","takenAtMs":2,"pauseMs":2,"objects":4,"bytes":4}]}"""
            "runtime.heap.compare" -> """{"comparison":{"a":"s1","b":"s2","run":"r1","rows":[
                {"className":"$pkg.HeapLeakActivity","origin":"app","countA":1,"countB":4,"bytesA":4,"bytesB":16,"change":"grew"},
                {"className":"$pkg.HeapLeakService","origin":"app","countA":1,"countB":3,"bytesA":4,"bytesB":12,"change":"grew"}],
                "filters":{},"leftOut":{},"unchangedClasses":0,"totalsDeltaObjects":0,"totalsDeltaBytes":0},"leftOut":"none"}"""
            else -> "{}"
        },
    ).jsonObject

    private fun layout(c: Component) { c.doLayout(); (c as? Container)?.components?.forEach(::layout) }

    private fun shown(width: Int): List<String> {
        val p = MemoryPanel(project, CoroutineScope(Dispatchers.Unconfined), ui = { it.run() }, call = { op, _ -> answer(op) })
        p.setSize(width, 700); layout(p); p.load(); layout(p)
        return (0 until 2).map { (p.table.prepareRenderer(p.table.getCellRenderer(it, 0), it, 0) as JLabel).text }
    }

    private val allowed = Regex("""^(io\.yamsergey\.dta\.channel\.|io\.…(dta\.)?channel\.|…)HeapLeak(Activity|Service)$""")

    fun testAt900TheSimpleNamesAreWholeAndDifferent() {
        val (a, b) = shown(900)
        assertTrue(a, allowed.matches(a)); assertTrue(b, allowed.matches(b))
        assertTrue(a.endsWith("HeapLeakActivity") && b.endsWith("HeapLeakService"))
        assertTrue("$a vs $b", a != b)
    }

    fun testAt420ThePackageGivesWayAndTheSimpleNamesStayWhole() {
        val (a, b) = shown(420)
        assertTrue(a, allowed.matches(a)); assertTrue(b, allowed.matches(b))
        assertTrue("$a vs $b", a != b)
        assertTrue(a, a.length < "$pkg.HeapLeakActivity".length)
    }
}
