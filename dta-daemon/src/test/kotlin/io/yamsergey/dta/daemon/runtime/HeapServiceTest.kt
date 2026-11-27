package io.yamsergey.dta.daemon.runtime

import io.yamsergey.dta.protocol.data.HeapClassRow
import io.yamsergey.dta.protocol.data.HeapOrigin
import io.yamsergey.dta.protocol.data.HeapRunInfo
import io.yamsergey.dta.protocol.data.HeapWalk
import io.yamsergey.dta.protocol.data.HeapField
import io.yamsergey.dta.protocol.data.HeapFields
import io.yamsergey.dta.protocol.data.HeapFieldsRead
import io.yamsergey.dta.protocol.data.HeapInstance
import io.yamsergey.dta.protocol.data.HeapInstancesPage
import io.yamsergey.dta.protocol.data.HeapLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 074 (C1): the operations over the store and a sidekick; nothing takes a snapshot as a side effect. */
class HeapServiceTest {
    private var now = 1_000_000L
    private val service = HeapService(HeapSnapshotStore(clock = { now }))
    private val app = "com.example"

    private class Fake(var walk: HeapWalk, var run: String = "r1") : HeapSidekick {
        var runCalls = 0
        var walks = 0
        var lastMax: Long? = null
        var lastGc: Boolean? = null
        var lastTimeout: kotlin.time.Duration? = null
        var page = HeapInstancesPage("com.x.Leak", 2, 0, listOf(
            HeapInstance(4294967297, "com.x.Leak", 24, HeapLink("activity", "ab12"), "destroyed"),
            HeapInstance(4294967298, "com.x.Leak", 24),
        ))
        var read = HeapFieldsRead(fields = HeapFields(4294967297, "com.x.Leak", listOf(
            HeapField("authToken", "com.x.Leak", "java.lang.String", false, JsonPrimitive("abc123"), 6),
            HeapField("title", "com.x.Leak", "java.lang.String", false, JsonPrimitive("Home"), 4),
        )))
        var lastValues: Boolean? = null
        var instanceCalls = 0
        override suspend fun instances(className: String, offset: Int, limit: Int) = page.also { instanceCalls++ }
        override suspend fun fields(instance: Long, values: Boolean) = read.also { lastValues = values }
        override suspend fun run() = HeapRunInfo(run, listOf("com.example")).also { runCalls++ }
        override suspend fun walk(maxPauseMs: Long, gc: Boolean, timeout: kotlin.time.Duration): HeapWalk { walks++; lastMax = maxPauseMs; lastGc = gc; lastTimeout = timeout; return walk.copy(run = run) }
    }

    private fun rows(n: Long) = (1..30).map { HeapClassRow("c.C$it", null, HeapOrigin.APP, n, n * it) }
    private fun ok(pause: Long = 20, n: Long = 1) = HeapWalk(run = "r1", classes = rows(n), pauseMs = pause)

    @Test fun snapshotStatesPauseAndSummarisesTop20() = runBlocking<Unit> {
        val a = service.snapshot(app, Fake(ok()), "base", false, null)
        assertEquals(20, a.pauseMs)
        assertNull(a.warning)
        assertEquals("base", a.snapshot.name)
        assertEquals(20, a.snapshot.top.size)
        assertEquals(30, a.snapshot.classCount)
        assertEquals("c.C30", a.snapshot.top.first().className)
        assertTrue(a.notKnown.any { it.contains("retained") })
    }

    @Test fun warnsAboveHundredMillis() = runBlocking<Unit> {
        val a = service.snapshot(app, Fake(ok(pause = 101)), null, false, null)
        assertTrue(a.warning!!.contains("101"))
        now += 5_000
        assertNull(service.snapshot(app, Fake(ok(pause = 100)), null, false, null).warning)
    }

    @Test fun passesBoundAndGcAndDefaultsTheBound() = runBlocking<Unit> {
        val f = Fake(ok())
        service.snapshot(app, f, null, true, 3000)
        assertEquals(3000L, f.lastMax); assertEquals(true, f.lastGc)
        now += 5_000
        service.snapshot(app, f, null, false, null)
        assertEquals(1000L, f.lastMax)
    }

    @Test fun requestTimeoutIsAboveTheBoundWithMargin() = runBlocking<Unit> {
        val f = Fake(ok())
        service.snapshot(app, f, null, false, null)
        assertTrue(f.lastTimeout!!.inWholeMilliseconds >= 1000 + 10_000)
        now += 5_000
        service.snapshot(app, f, null, false, 60_000)
        assertTrue(f.lastTimeout!!.inWholeMilliseconds >= 60_000 + 10_000, "timeout ${f.lastTimeout}")
    }

    @Test fun stoppedWalkIsRefusedWithHowToRaiseAndStoresNothing() = runBlocking<Unit> {
        val f = Fake(HeapWalk(run = "r1", pauseMs = 1004, stopped = true, maxPauseMs = 1000))
        val e = assertFailsWith<HeapRefusal> { service.snapshot(app, f, null, false, null) }
        assertTrue(e.message!!.contains("1004") && e.message!!.contains("maxPause"))
        assertTrue(service.list(app).snapshots.isEmpty())
    }

    @Test fun sidekickRefusalPassesItsReason() = runBlocking<Unit> {
        val f = Fake(HeapWalk(run = "r1", refusal = "the app is not debuggable"))
        assertEquals("the app is not debuggable", assertFailsWith<HeapRefusal> { service.snapshot(app, f, null, false, null) }.message)
    }

    @Test fun tooSoonIsRefusedBeforeTheWalk() = runBlocking<Unit> {
        val f = Fake(ok())
        service.snapshot(app, f, null, false, null)
        val e = assertFailsWith<HeapRefusal> { service.snapshot(app, f, null, false, null) }
        assertTrue(e.message!!.contains("too soon"))
        assertEquals(1, f.walks)
    }

    @Test fun listAndCompareTakeNoSnapshot() = runBlocking<Unit> {
        val f = Fake(ok(n = 1))
        service.snapshot(app, f, "a", false, null)
        now += 3000
        f.walk = ok(n = 3)
        service.snapshot(app, f, "b", false, null)
        val l = service.list(app)
        assertEquals(listOf("s1", "s2"), l.snapshots.map { it.id })
        val c = service.compare(app, "a", "b", null, null, null)
        assertEquals(30, c.comparison.rows.size)
        assertEquals("c.C30", c.comparison.rows.first().className)
        assertTrue(c.leftOut.contains("no changed class"))
        assertEquals(2, f.walks)
        assertEquals(2, f.runCalls)
    }

    @Test fun compareFiltersAndSaysWhatItLeftOut() = runBlocking<Unit> {
        val f = Fake(ok(n = 1))
        service.snapshot(app, f, "a", false, null)
        now += 3000
        f.walk = ok(n = 2)
        service.snapshot(app, f, "b", false, null)
        val c = service.compare(app, "a", "b", "platform", null, 5)
        assertEquals(0, c.comparison.rows.size)
        assertTrue(c.leftOut.contains("30") && c.leftOut.contains("origin"))
        val d = service.compare(app, "a", "b", "all", "c", 3)
        assertEquals(3, d.comparison.rows.size)
        assertFailsWith<IllegalArgumentException> { service.compare(app, "a", "b", "nonsense", null, null) }
    }

    @Test fun compareCarriesTheNotesOfB() = runBlocking<Unit> {
        val f = Fake(ok(n = 1).copy(notes = listOf("3 objects of DTA itself left out (96 bytes)")))
        service.snapshot(app, f, "a", false, null)
        now += 3000
        service.snapshot(app, f, "b", false, null)
        val c = service.compare(app, "a", "b", null, null, null)
        assertTrue(c.notes.any { it.contains("of DTA itself left out") }, c.notes.toString())
        assertTrue(service.compare(app, "empty", "b", "all", null, null).notes.any { it.contains("of DTA itself left out") })
    }

    @Test fun listAfterANewProcessSaysTheCurrentRunHasNoSnapshot() = runBlocking<Unit> {
        val f = Fake(ok())
        service.snapshot(app, f, "a", false, null)
        now += 3000
        service.snapshot(app, f, "b", false, null)
        f.run = "r2"
        val l = service.list(app, f)
        assertEquals("r2", l.currentRun)
        assertEquals("r1", l.run)
        assertEquals(2, l.snapshots.size)
        assertTrue(l.note!!.contains("r2") && l.note!!.contains("no snapshot") && l.note!!.contains("earlier run (r1)"), l.note)
        assertFailsWith<HeapRefusal> { service.compare(app, "a", "b", null, null, null, f) }.also { assertTrue(it.message!!.contains("earlier run"), it.message) }
        assertEquals(0, f.walks - 2, "listing and comparing take no snapshot")
        // The same run: no statement of an earlier run.
        f.run = "r1"
        assertEquals(null, service.list(app, f).note)
    }

    @Test fun listWhenTheAppCannotBeAskedKeepsTheOldAnswer() = runBlocking<Unit> {
        val f = Fake(ok())
        service.snapshot(app, f, "a", false, null)
        val broken = object : HeapSidekick by f { override suspend fun run(): HeapRunInfo = throw RuntimeException("not connected") }
        val l = service.list(app, broken)
        assertEquals(1, l.snapshots.size)
        assertEquals(null, l.note, "no statement of an earlier run when the app cannot be asked")
    }

    @Test fun compareRefusalsCarryTheReason() = runBlocking<Unit> {
        val e = assertFailsWith<HeapRefusal> { service.compare(app, "x", "y", null, null, null) }
        assertTrue(e.message!!.contains("no snapshot"))
    }

    @Test fun droppedSnapshotIsSaidInTheAnswer() = runBlocking<Unit> {
        val f = Fake(ok())
        repeat(21) { service.snapshot(app, f, null, false, null); now += 3000 }
        val l = service.list(app)
        assertEquals(20, l.snapshots.size)
        assertEquals(listOf("s1"), l.dropped.map { it.id })
        assertNotNull(l.run)
    }

    @Test fun instancesAreLiveNowAndNeverTakeASnapshot() = runBlocking<Unit> {
        val f = Fake(ok())
        val a = service.instances(app, f, "com.x.Leak", null, null)
        assertEquals(2, a.total)
        assertEquals("destroyed", a.instances[0].state)
        assertEquals("activity", a.instances[0].link!!.kind)
        assertTrue(a.note.contains("now") && a.note.contains("not as they were"))
        assertTrue(a.notKnown.any { it.contains("retained") })
        assertEquals(0, f.walks)
        assertTrue(service.list(app).snapshots.isEmpty())
    }

    @Test fun instancesRefusalCarriesTheReason() = runBlocking<Unit> {
        val f = Fake(ok()); f.page = HeapInstancesPage("x", 0, 0, emptyList(), refusal = "no class 'x' is loaded")
        assertEquals("no class 'x' is loaded", assertFailsWith<HeapRefusal> { service.instances(app, f, "x", null, null) }.message)
    }

    @Test fun fieldsShowNoValuesUnlessAsked() = runBlocking<Unit> {
        val f = Fake(ok())
        val a = service.fields(app, f, 4294967297, values = false, redact = false)
        assertEquals(false, f.lastValues)
        assertTrue(!a.valuesShown)
        assertTrue(a.note.contains("values"))
        service.fields(app, f, 4294967297, values = true, redact = false)
        assertEquals(true, f.lastValues)
    }

    @Test fun fieldsForAgentsAreMaskedByValueAndTheAnswerSaysSo() = runBlocking<Unit> {
        val f = Fake(ok())
        val a = service.fields(app, f, 4294967297, values = true, redact = true)
        val v = a.fields.fields.map { (it.value as JsonPrimitive).content }
        assertTrue(v.all { Regex("""\[redacted len=\d+ #[0-9a-f]{12}]""").matches(it) }, v.toString())
        assertEquals(6, a.fields.fields[0].length)
        assertTrue(a.redaction!!.applied)
        assertEquals(2, a.redaction!!.masked.size)
        assertTrue(a.note.contains("redacted"))
        val plain = service.fields(app, f, 4294967297, values = true, redact = false)
        assertEquals(JsonPrimitive("abc123"), plain.fields.fields[0].value)
        assertNull(plain.redaction)
    }

    @Test fun fieldsForAgentsWithoutValuesStillStateTheRule() = runBlocking<Unit> {
        val f = Fake(ok()); f.read = HeapFieldsRead(fields = HeapFields(1, "com.x.Leak", listOf(HeapField("title", "com.x.Leak", "java.lang.String", false, null, 4))))
        val a = service.fields(app, f, 1, values = false, redact = true)
        assertTrue(a.redaction!!.applied)
        assertTrue(a.redaction!!.masked.isEmpty())
    }

    @Test fun fieldsOfAGoneInstanceAreRefused() = runBlocking<Unit> {
        val f = Fake(ok()); f.read = HeapFieldsRead(refusal = "instance 5 is gone: the object was collected")
        assertTrue(assertFailsWith<HeapRefusal> { service.fields(app, f, 5, values = false, redact = false) }.message!!.contains("gone"))
    }
}
