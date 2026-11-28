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
    private var pauseB = 38
    private var currentRun: String? = null
    private var notes = """"3 objects of DTA itself left out (96 bytes)""""

    private fun answer(op: String, p: JsonObject): JsonObject {
        refuse?.let { if (op == "runtime.heap.snapshot") throw RuntimeException(it) }
        return Json.parseToJsonElement(
            when (op) {
                "runtime.memory" -> """{"javaUsedBytes":43200000,"javaMaxBytes":201326592,"javaFreeBytes":1,"nativeAllocatedBytes":19300000,"nativeHeapBytes":2,"pssKb":98304,"gcCount":14,"gcTimeMs":210,"gcRun":false}"""
                "runtime.heap.snapshots" -> if (!snapshots) """{"notKnown":[]}""" else
                    """{"run":"r1"${currentRun?.let { ""","currentRun":"$it"""" } ?: ""},"snapshots":[{"id":"s1","name":"before","takenAtMs":1,"pauseMs":21,"objects":10,"bytes":100},{"id":"s2","name":"after","takenAtMs":2,"pauseMs":$pauseB,"objects":40,"bytes":400}]}"""
                "runtime.heap.snapshot" -> { snapshots = true; """{"snapshot":{"id":"s2","name":"after","run":"r1","takenAtMs":2,"pauseMs":38,"gcBefore":false,"totals":{"objects":40,"bytes":400},"classCount":1,"top":[]},"pauseMs":38}""" }
                "runtime.heap.compare" -> """{"comparison":{"a":"s1","b":"s2","run":"r1","rows":[
                    {"className":"io.x.broker.ui.OrderActivity","origin":"app","countA":1,"countB":4,"bytesA":400,"bytesB":1600,"change":"grew"}],
                    "filters":{},"leftOut":{},"unchangedClasses":3,"totalsDeltaObjects":3,"totalsDeltaBytes":1200},"leftOut":"no changed class left out by the filters","notes":[$notes]}"""
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
        assertEquals(listOf("…broker.ui.OrderActivity", "1", "4", "+3", "+1.2 KB"), (0..4).map { p.cell(0, it) }.let { listOf("…broker.ui.OrderActivity") + it.drop(1) }.let { cells -> assertEquals("io.x.broker.ui.OrderActivity", p.cell(0, 0)); cells })
        assertTrue(p.statementLine, p.statementLine.startsWith("B paused the app 38 ms"))
        val compare = calls.last { it.first == "runtime.heap.compare" }.second
        assertEquals("s1", compare["a"]!!.jsonPrimitive.content)
        assertEquals("app", compare["origin"]!!.jsonPrimitive.content)
    }

    fun testTheSnapshotNotesAreShownBesideTheRows() {
        snapshots = true
        val p = panel()
        p.load()
        assertTrue(p.leftOutLine, p.leftOutLine.contains("3 objects of DTA itself left out (96 bytes)"))
    }

    fun testAfterANewRunTheEarlierSnapshotsAreNotOfferedAndTheEmptyStateSaysSo() {
        snapshots = true
        currentRun = "r2"
        val p = panel()
        p.load()
        assertFalse(p.hasSnapshots)
        assertEquals(0, p.rowCount)
        assertEquals("2 snapshots of the previous run (r1); this run has none yet", p.earlierLine)
        assertFalse(calls.any { it.first == "runtime.heap.compare" })
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

    private fun layout(c: java.awt.Component) { c.doLayout(); (c as? java.awt.Container)?.components?.forEach(::layout) }

    /** D-138: the long lines around the table (pause statement, left out, notes) never take the table's height. */
    fun testAtNarrowWidthLongStatementsLeaveTheTableItsHeight() {
        snapshots = true
        pauseB = 850
        notes = (1..4).joinToString(",") { "\"note $it: this snapshot was walked while the app was busy and counts objects reachable or not, boot image and zygote included\"" }
        for (h in listOf(700, 500, 400)) {
            val p = panel()
            p.setSize(420, h)
            layout(p)
            p.load()
            repeat(3) { layout(p); com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() }
            val viewport = p.table.parent
            assertTrue("table viewport ${viewport.height} px of $h", viewport.height >= 60)
        }
    }

    fun testAtNarrowWidthTheSizeColumnStaysVisible() {
        snapshots = true
        val p = panel()
        p.setSize(420, 700)
        layout(p)
        p.load()
        layout(p)
        val cm = p.table.columnModel
        assertEquals(3, cm.columnCount)
        val total = (0 until 3).sumOf { cm.getColumn(it).width }
        assertTrue("columns $total of ${p.table.width}", total <= p.table.width + 1)
        assertTrue("Δ size ${cm.getColumn(2).width}", cm.getColumn(2).width >= 60)
        assertTrue("Δ count ${cm.getColumn(1).width}", cm.getColumn(1).width >= 50)
    }

    fun testAClassHasItsFullNameAsATooltipAndIsElidedInTheMiddle() {
        snapshots = true
        val p = panel()
        p.setSize(420, 700)
        layout(p)
        p.load()
        layout(p)
        val e = java.awt.event.MouseEvent(p.table, 0, 0, 0, 5, p.table.getCellRect(0, 0, true).y + 2, 0, false)
        assertEquals("io.x.broker.ui.OrderActivity", p.table.getToolTipText(e))
        val shown = p.table.prepareRenderer(p.table.getCellRenderer(0, 0), 0, 0) as javax.swing.JLabel
        assertTrue(shown.text, shown.text.endsWith("OrderActivity"))
    }

    fun testTheOriginChoiceIsSentToTheComparison() {
        snapshots = true
        val p = panel()
        p.load()
        p.setOrigin(all = true)
        assertEquals("all", calls.last { it.first == "runtime.heap.compare" }.second["origin"]!!.jsonPrimitive.content)
        p.setOrigin(all = false)
        assertEquals("app", calls.last { it.first == "runtime.heap.compare" }.second["origin"]!!.jsonPrimitive.content)
    }

    /** V1 (D-138 residue): the instance list always shows at least three whole rows, however little height the table pane leaves. */
    fun testTheInstanceListKeepsThreeRowsAtNarrowWidth() {
        snapshots = true
        for (h in listOf(700, 500, 400)) {
            val p = panel()
            p.setSize(420, h)
            layout(p)
            p.load()
            p.table.setRowSelectionInterval(0, 0)
            repeat(3) { layout(p); com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() }
            val rows = 3 * p.instanceList.getFontMetrics(p.instanceList.font).height
            assertTrue("instance viewport ${p.instanceScroll.viewport.height} px of $h, three rows are $rows", p.instanceScroll.viewport.height >= rows)
        }
    }

    /** V1 again: in the IDE a row is ~25 px (the renderer's height), not the font's 17: three rows are measured by the renderer. */
    fun testTheInstanceListKeepsThreeRowsOfTheRealRowHeight() {
        snapshots = true
        val p = panel()
        val plain = p.instanceList.cellRenderer
        p.instanceList.cellRenderer = javax.swing.ListCellRenderer { list, row, i, sel, focus ->
            (plain.getListCellRendererComponent(list, row, i, sel, focus) as javax.swing.JComponent).apply { preferredSize = java.awt.Dimension(100, 25) }
        }
        p.setSize(420, 400)
        layout(p)
        p.load()
        p.table.setRowSelectionInterval(0, 0)
        repeat(3) { layout(p); com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() }
        assertEquals(25 * 3, p.instanceScroll.minimumSize.height - p.instanceScroll.insets.top - p.instanceScroll.insets.bottom)
        assertTrue("viewport ${p.instanceScroll.viewport.height}", p.instanceScroll.viewport.height >= 75)
    }

    /** V1, third time: the IDE's list draws a 24 px row for a 17 px renderer, and the narrow (vertical) splitter must give the list its minimum. */
    fun testTheInstanceListKeepsThreeRowsThatTheListDrawsInAVerticalSplitter() {
        snapshots = true
        for (h in listOf(700, 500, 420)) {
            val p = panel()
            p.instanceList.fixedCellHeight = 24 // what the list's UI draws; the renderer's component asks for less
            p.setSize(420, h)
            layout(p)
            p.load()
            p.table.setRowSelectionInterval(0, 0)
            repeat(4) { layout(p); com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() }
            assertEquals("minimum at $h", 3 * 24, p.instanceScroll.minimumSize.height - p.instanceScroll.insets.top - p.instanceScroll.insets.bottom)
            assertTrue("narrow: the splitter is vertical", p.splitter.isVertical)
            val second = p.splitter.secondComponent
            if (h >= 500) assertTrue("second part ${second.height} px of its minimum ${second.minimumSize.height} at $h", second.height >= second.minimumSize.height)
            assertTrue("viewport ${p.instanceScroll.viewport.height} px at $h (3 rows = 72)", p.instanceScroll.viewport.height >= 72)
        }
    }

    /** D-148: the real IDE geometry (404 x 583, 24 px list rows): the comparison table keeps two whole rows under its header in the narrow splitter. */
    fun testTheTableKeepsTwoWholeRowsInTheRealNarrowGeometry() {
        snapshots = true
        pauseB = 850
        notes = (1..2).joinToString(",") { "\"note $it: this snapshot was walked while the app was busy\"" }
        for (h in listOf(583, 520)) {
            val p = panel()
            p.instanceList.fixedCellHeight = 24
            p.table.rowHeight = 17
            p.table.tableHeader.preferredSize = java.awt.Dimension(100, 25)
            p.setSize(404, h)
            layout(p)
            p.load()
            p.table.setRowSelectionInterval(0, 0)
            repeat(4) { layout(p); com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() }
            assertTrue("minimum ${p.tableScroll.minimumSize.height} px is under header + two rows", p.tableScroll.minimumSize.height >= 25 + 2 * 17)
            val viewport = p.table.parent
            assertTrue("table viewport ${viewport.height} px of $h, two rows are ${2 * p.table.rowHeight}", viewport.height >= 2 * p.table.rowHeight)
        }
    }

    /**
     * D-148 with the IDE's numbers (404 x 583, rows 19 / list rows 24, 12 lines left out): the splitter has room for the table's header and two rows
     * (min 65) and the instances (min 167) with the divider, so "Read fields" is not half-cut and the table shows its two rows.
     */
    fun testTheSplitterGetsItsMinimumsWithALongLeftOutBlock() {
        snapshots = true
        for (h in listOf(583, 620)) {
            val p = panel()
            p.instanceList.fixedCellHeight = 24
            p.table.rowHeight = 19
            p.table.tableHeader.preferredSize = java.awt.Dimension(100, 25)
            p.setSize(404, h)
            layout(p)
            p.load()
            p.table.setRowSelectionInterval(0, 0)
            p.leftOutArea.text = (1..12).joinToString("\n") { "left out line $it" }
            repeat(4) { layout(p); com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() }
            val first = p.splitter.firstComponent; val second = p.splitter.secondComponent
            assertTrue("table part ${first.height} px of its minimum ${first.minimumSize.height} at $h", first.height >= first.minimumSize.height)
            assertTrue("instances part ${second.height} px of its minimum ${second.minimumSize.height} at $h", second.height >= second.minimumSize.height)
        }
    }

    /** The rendered row of the first instance at [room] px, as the list's own renderer draws it. */
    private fun rowText(p: MemoryPanel, room: Int): String {
        p.instanceList.setSize(room, 200)
        val c = p.instanceList.cellRenderer.getListCellRendererComponent(p.instanceList, p.instanceList.model.getElementAt(0), 0, false, false) as javax.swing.JLabel
        return c.text
    }

    /** At 900 light the link column showed "Ac": a link label is whole or not shown (its tooltip and the link under the list say it), never a stub. */
    fun testTheLinkTextOfAnInstanceRowIsWholeOrLeftOutNeverAStub() {
        snapshots = true
        val p = panel()
        p.load()
        p.table.setRowSelectionInterval(0, 0)
        val metrics = p.instanceList.getFontMetrics(p.instanceList.font)
        val line = (0 until 1).map { rowText(p, 1000) }.single()
        assertTrue(line, line.endsWith("Activities ›"))
        val base = line.substringBefore("   Activities ›")
        // From a room that holds the id and the state but not the link, down to nothing: never a part of the link's word.
        for (room in (metrics.stringWidth(base) + 8) until metrics.stringWidth(line) + 8 step 3) {
            val text = rowText(p, room)
            val ok = text == line || text == base || text.endsWith("…")
            assertTrue("room $room px: '$text'", ok && !(text.contains("Ac") && !text.contains("Activities")))
        }
    }
}
