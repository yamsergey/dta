package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.protocol.network.ScriptLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptRuntimeTest {

    private var now = 1_000L
    private val runtime = ScriptRuntime(clock = { now++ }, logCapacity = 5)
    private val request = ScriptRequest("http://h/a", "GET", listOf(Header("A", "b")), "okhttp3", "r1-1:1", null)

    @Test
    fun `nothing is active until a script is installed`() {
        assertNull(runtime.current())
        assertEquals(false, runtime.status().active)
    }

    @Test
    fun `install makes a script current, with id, time and handlers`() {
        val status = runtime.install("function onRequest(r) { return drop(); }")
        val active = runtime.current()!!
        assertEquals(12, active.id.length)
        assertEquals(active.id, status.id)
        assertEquals(listOf(ScriptHandler.REQUEST), status.handlers)
        assertEquals(true, status.active)
        assertTrue(status.installedAtMs!! >= 1_000)
    }

    @Test
    fun `the id depends only on the source`() {
        val a = runtime.install("function onRequest(r) {}").id
        val b = ScriptRuntime(clock = { 9 }).install("function onRequest(r) {}").id
        assertEquals(a, b)
        assertNotEquals(a, runtime.install("function onRequest(r) { }").id)
    }

    @Test
    fun `an invalid script keeps the previous one`() {
        runtime.install("function onRequest(r) { return drop(); }")
        val before = runtime.current()
        assertFailsWith<ScriptCompileException> { runtime.install("function (") }
        assertEquals(before, runtime.current())
    }

    @Test
    fun `a call in flight keeps the script it started with`() {
        runtime.install("function onRequest(r) { return drop(); }")
        val captured = runtime.current()!!
        runtime.install("function onRequest(r) {}")
        assertEquals(Effect.Drop, captured.script.onRequest(request).effect)
        assertEquals(Effect.None, runtime.current()!!.script.onRequest(request).effect)
    }

    @Test
    fun `clear deactivates`() {
        runtime.install("function onRequest(r) {}")
        assertEquals(true, runtime.clear())
        assertNull(runtime.current())
        assertEquals(false, runtime.clear())
    }

    @Test
    fun `accounting counts invocations, changes and errors and logs errors with the entry`() {
        runtime.install("function onRequest(r) {}")
        runtime.account(ScriptHandler.REQUEST, ScriptResult<Any>(Effect.None), "r1-1:1")
        runtime.account(ScriptHandler.REQUEST, ScriptResult<Any>(Effect.Drop), "r1-1:2")
        runtime.account(ScriptHandler.REQUEST, ScriptResult<Any>(Effect.None, delayMs = 5), "r1-1:3")
        runtime.account(ScriptHandler.REQUEST, ScriptResult<Any>(Effect.None, error = "onRequest: boom"), "r1-1:4")
        val status = runtime.status()
        assertEquals(4, status.invocations)
        assertEquals(2, status.changes, "a drop and a delay are changes")
        assertEquals(1, status.errors)
        val error = runtime.logs(0).lines.single { it.level == ScriptLogLevel.ERROR }
        assertEquals("r1-1:4", error.entryId)
        assertEquals(ScriptHandler.REQUEST, error.handler)
        assertTrue("boom" in error.message)
    }

    @Test
    fun `a description of a call that changed nothing goes to the log with the entry, not lost`() {
        runtime.install("function onRequest(r) {}")
        runtime.account(ScriptHandler.REQUEST, ScriptResult<Any>(Effect.None, description = "looked at auth"), "r1-1:7")
        val line = runtime.logs(0).lines.single()
        assertEquals("r1-1:7", line.entryId)
        assertEquals("looked at auth (nothing changed)", line.message)
        assertEquals(0, runtime.status().changes)
    }

    @Test
    fun `script log lines reach the runtime log`() {
        runtime.install("function onRequest(r) { log('hello', r.url); }")
        runtime.current()!!.script.onRequest(request)
        assertEquals("hello http://h/a", runtime.logs(0).lines.single().message)
    }

    @Test
    fun `logs page from a position and state what the ring lost`() {
        repeat(8) { runtime.log(ScriptLogLevel.LOG, null, null, "line $it") }
        val all = runtime.logs(0)
        assertEquals(listOf("line 3", "line 4", "line 5", "line 6", "line 7"), all.lines.map { it.message })
        assertEquals(3, all.skipped)
        assertEquals(8, all.next)
        val newer = runtime.logs(6)
        assertEquals(listOf("line 6", "line 7"), newer.lines.map { it.message })
        assertEquals(0, newer.skipped)
        assertEquals(emptyList(), runtime.logs(8).lines)
    }

    @Test
    fun `status shows the source`() {
        runtime.install("function onRequest(r) {}")
        assertEquals("function onRequest(r) {}", runtime.status().source)
    }

    @Test
    fun `restoring a persisted script activates it with its original install time`() {
        runtime.restore(StoredScript("function onRequest(r) {}", installedAtMs = 7), hooksUnavailable = null)
        val active = runtime.current()!!
        assertEquals(7, active.installedAtMs)
        assertEquals(true, active.persisted)
        assertEquals(active.id, runtime.summary()!!.id)
        assertNull(runtime.summary()!!.unavailable)
    }

    @Test
    fun `a persisted script that no longer compiles is reported, not dropped silently`() {
        runtime.restore(StoredScript("function (", installedAtMs = 7), hooksUnavailable = null)
        assertNull(runtime.current())
        val summary = runtime.summary()!!
        assertEquals(ScriptRuntime.idOf("function ("), summary.id)
        assertTrue("does not compile" in summary.unavailable!!, summary.unavailable)
        assertEquals(listOf(io.yamsergey.dta.protocol.layout.DegradationCodes.NETWORK_SCRIPT_UNAVAILABLE), runtime.degradations())
    }

    @Test
    fun `a persisted script cannot apply without hooks, and says why`() {
        runtime.restore(StoredScript("function onRequest(r) {}", 7), hooksUnavailable = "requires API 28+")
        assertTrue("API 28" in runtime.summary()!!.unavailable!!)
    }
}
