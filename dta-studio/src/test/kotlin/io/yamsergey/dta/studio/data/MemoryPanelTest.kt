package io.yamsergey.dta.studio.data

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Feature 074 (D1): Runtime › Memory against canned daemon answers. Opening reads, never takes a snapshot (FR-000). */
class MemoryPanelTest : BasePlatformTestCase() {
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var snapshots = false
    private var refuse: String? = null

    private fun answer(op: String, p: JsonObject): JsonObject {
        refuse?.let { if (op == "runtime.heap.snapshot") throw RuntimeException(it) }
        return Json.parseToJsonElement(
            when (op) {
                "runtime.memory" -> """{"javaUsedBytes":43200000,"javaMaxBytes":201326592,"javaFreeBytes":1,"nativeAllocatedBytes":19300000,"nativeHeapBytes":2,"pssKb":98304,"gcCount":14,"gcTimeMs":210,"gcRun":false}"""
                "runtime.heap.snapshots" -> if (!snapshots) """{"notKnown":[]}""" else
                    """{"run":"r1","snapshots":[{"id":"s1","name":"before","takenAtMs":1,"pauseMs":21,"objects":10,"bytes":100},{"id":"s2","name":"after","takenAtMs":2,"pauseMs":38,"objects":40,"bytes":400}]}"""
                "runtime.heap.snapshot" -> { snapshots = true; """{"snapshot":{"id":"s2","name":"after","run":"r1","takenAtMs":2,"pauseMs":38,"gcBefore":false,"totals":{"objects":40,"bytes":400},"classCount":1,"top":[]},"pauseMs":38}""" }
                "runtime.heap.compare" -> """{"comparison":{"a":"s1","b":"s2","run":"r1","rows":[
                    {"className":"io.x.broker.ui.OrderActivity","origin":"app","countA":1,"countB":4,"bytesA":400,"bytesB":1600,"change":"grew"}],
                    "filters":{},"leftOut":{},"unchangedClasses":3,"totalsDeltaObjects":3,"totalsDeltaBytes":1200},"leftOut":"no changed class left out by the filters"}"""
                "runtime.heap.instances" -> """{"className":"io.x.broker.ui.OrderActivity","total":2,"offset":0,"instances":[
                    {"id":4294967297,"className":"io.x.broker.ui.OrderActivity","bytes":400,"link":{"kind":"activity","ref":"7a3c01"},"state":"resumed"},
                    {"id":4294967298,"className":"io.x.broker.ui.OrderActivity","bytes":400,"link":{"kind":"activity","ref":"5e12f4"},"state":"destroyed"}],"note":"now"}"""
                else -> "{}"
            },
        ).jsonObject.also { calls += op to p }
    }

    private fun panel(): MemoryPanel = MemoryPanel(project, CoroutineScope(Dispatchers.Unconfined), ui = { it.run() }, call = { op, p -> answer(op, p) })

    fun testOpeningShowsTheNumbersAndTakesNoSnapshot() {
        val p = panel()
        p.load()
        assertFalse(calls.any { it.first == "runtime.heap.snapshot" })
        assertFalse(p.hasSnapshots)
        assertEquals(setOf("runtime.memory", "runtime.heap.snapshots"), calls.map { it.first }.toSet())
    }

    fun testASnapshotIsTakenOnRequestWithItsNameAndThePauseIsShown() {
        val p = panel()
        p.load()
        p.takeSnapshot(gc = true, name = "after")
        val taken = calls.single { it.first == "runtime.heap.snapshot" }.second
        assertEquals("after", taken["name"]!!.jsonPrimitive.content)
        assertEquals("true", taken["gc"]!!.jsonPrimitive.content)
        assertTrue(p.statusLine, p.statusLine.contains("paused the app 38 ms"))
        assertTrue(p.hasSnapshots)
    }

    fun testTwoSnapshotsAreComparedAndTheirPauseStatedBesideB() {
        snapshots = true
        val p = panel()
        p.load()
        assertEquals(1, p.rowCount)
        assertEquals(listOf("…broker.ui.OrderActivity", "1", "4", "+3", "+1.2 KB"), (0..4).map { p.cell(0, it) })
        assertTrue(p.statementLine, p.statementLine.startsWith("B paused the app 38 ms"))
        val compare = calls.last { it.first == "runtime.heap.compare" }.second
        assertEquals("s1", compare["a"]!!.jsonPrimitive.content)
        assertEquals("app", compare["origin"]!!.jsonPrimitive.content)
    }

    fun testAClassShowsItsInstancesWithLinks() {
        snapshots = true
        val p = panel()
        p.load()
        p.table.setRowSelectionInterval(0, 0)
        assertEquals(2, p.instanceList.model.size)
        p.instanceList.selectedIndex = 1
        assertEquals("@100000002", p.instanceList.selectedValue.label)
        assertEquals("destroyed", p.instanceList.selectedValue.state)
    }

    fun testARefusalIsShownWithItsReason() {
        val p = panel()
        p.load()
        refuse = "too soon: one heap walk per 2000 ms per app; next allowed in 1442 ms (at 1795699995416)"
        p.takeSnapshot(gc = false, name = null)
        assertEquals(refuse, p.statusLine)
    }
}
