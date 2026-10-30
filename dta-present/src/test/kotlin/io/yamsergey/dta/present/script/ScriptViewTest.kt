package io.yamsergey.dta.present.script

import io.yamsergey.dta.present.StatementKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T053: the Script view from canned `network.script.status` / `logs` results. */
class ScriptViewTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `an active script shows its id, handlers and counters`() {
        val view = ScriptView.from(
            json("""{"active":true,"id":"ab12cd34ef56","installedAtMs":1700000000000,"persisted":true,"handlers":["onRequest","onResponse"],"invocations":12,"changes":3,"errors":1}"""),
            ZoneOffset.UTC,
        )
        assertEquals("Script ab12cd34ef56 active since 22:13:20", view.headline)
        assertEquals("onRequest, onResponse", view.handlers)
        assertEquals("12 calls · 3 changed · 1 error", view.counters)
        assertTrue(view.statements.isEmpty())
        assertTrue(view.canClear)
    }

    @Test
    fun `no script says how to add one`() {
        val view = ScriptView.from(json("""{"active":false}"""), ZoneOffset.UTC)
        assertEquals("No network script active", view.headline)
        assertNull(view.handlers)
        assertNull(view.counters)
        assertTrue(!view.canClear)
    }

    @Test
    fun `a script that is not persisted or cannot apply is stated`() {
        val view = ScriptView.from(json("""{"active":true,"id":"x","persisted":false,"unavailable":"hooks not attached"}"""), ZoneOffset.UTC)
        assertEquals(listOf(StatementKind.DEGRADED, StatementKind.DEGRADED), view.statements.map { it.kind })
        assertTrue(view.statements.any { "lost when the app restarts" in it.detail })
        assertTrue(view.statements.any { it.detail == "hooks not attached" })
    }

    @Test
    fun `a compile error gives its line`() {
        val error = ScriptView.installError("script does not compile: missing ; before statement at line 7, column 12")
        assertEquals(7, error.line)
        assertEquals("missing ; before statement at line 7, column 12", error.message)
        assertNull(ScriptView.installError("network intervention unavailable: hooks").line)
    }

    @Test
    fun `log lines show time, handler, entry and message, errors marked, lost lines stated`() {
        val log = ScriptView.log(
            json("""{"lines":[{"seq":1,"timeMs":1700000000000,"level":"log","handler":"onRequest","entryId":"r1-1:4","message":"hello"},{"seq":2,"timeMs":1700000001000,"level":"error","message":"boom"}],"next":3,"skipped":5}"""),
            ZoneOffset.UTC,
        )
        assertEquals(listOf("22:13:20.000  onRequest  r1-1:4  hello", "22:13:21.000  -  boom"), log.lines.map { it.text })
        assertEquals(listOf(false, true), log.lines.map { it.error })
        assertEquals(3, log.next)
        assertEquals(StatementKind.EVICTED, log.lost!!.kind)
        assertEquals("5 lines lost to the log buffer", log.lost!!.detail)
    }
}
