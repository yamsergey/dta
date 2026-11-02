package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.protocol.network.ScriptLogLevel
import io.yamsergey.dta.sidekick.util.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Feature 020 (US3): script timers run on their own and stop with the script. */
class ScriptTimerTest {

    private val scheduler = ManualScheduler()
    private val logs = mutableListOf<Triple<ScriptLogLevel, ScriptHandler?, String>>()
    private val pushes = mutableListOf<Pair<String?, ScriptHandler?>>()
    private val host = object : ScriptHost {
        override fun push(connectionId: String, text: String?, bytes: ByteArray?, scriptId: String, handler: ScriptHandler?, delayMs: Long, onFailure: (String) -> Unit) {
            pushes += text to handler
        }
        override fun connections() = listOf(ScriptConnection("ws://h", "nv-websocket", "r1-1:3"))
    }

    private fun compile(source: String) =
        ScriptEngine.compile(source, host = { host }, scheduler = scheduler) { level, handler, message -> logs += Triple(level, handler, message) }

    @Test
    fun `every runs until the script is closed, its pushes are marked timer`() {
        val script = compile("""var n = 0; every(300, function () { n++; connections().forEach(function (c) { push(c, "tick:" + n); }); });""")
        assertEquals(emptySet(), script.handlers, "a timer-only script compiles")
        scheduler.advance(299)
        assertTrue(pushes.isEmpty())
        scheduler.advance(1)
        scheduler.advance(600)
        assertEquals(listOf("tick:1", "tick:2", "tick:3"), pushes.map { it.first })
        assertTrue(pushes.all { it.second == ScriptHandler.TIMER })
        script.close()
        scheduler.advance(3000)
        assertEquals(3, pushes.size, "closed: no more ticks")
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `after runs once, cancel stops a timer, timers can be set from handlers`() {
        val script = compile(
            """
            var t = every(100, function () { log("never"); });
            cancel(t);
            function onWsSend(msg, conn) { after(200, function () { conn.push("later"); }); }
            """,
        )
        script.onWsSend(ScriptMessage.text("sub"), ScriptConnection("ws://h", "okhttp3-ws", "r1-1:5"))
        scheduler.advance(1000)
        assertEquals(listOf<Pair<String?, ScriptHandler?>>("later" to ScriptHandler.TIMER), pushes)
        assertTrue(logs.none { it.third == "never" })
    }

    @Test
    fun `a timer that throws is logged and keeps running`() {
        compile("""var n = 0; every(100, function () { n++; if (n === 1) throw new Error("bad tick"); log("tick " + n); });""")
        scheduler.advance(300)
        assertEquals(Triple(ScriptLogLevel.ERROR, ScriptHandler.TIMER, "timer: Error: bad tick (line 1)"), logs.first())
        assertEquals(listOf("tick 2", "tick 3"), logs.drop(1).map { it.third })
    }

    @Test
    fun `an interval under 1 ms or a missing function is an authoring error`() {
        val e = assertFailsWith<ScriptCompileException> { compile("""every(0, function () {});""") }
        assertTrue("every needs an interval of 1 ms or more" in e.message!!, e.message)
        assertTrue("every needs a function" in assertFailsWith<ScriptCompileException> { compile("""every(10);""") }.message!!)
    }

    @Test
    fun `a script with neither handlers nor timers is still refused`() {
        val e = assertFailsWith<ScriptCompileException> { compile("""var x = 1;""") }
        assertTrue("defines none of" in e.message!! && "every" in e.message!!, e.message)
    }

    @Test
    fun `without a scheduler timers say they are not available`() {
        val e = assertFailsWith<ScriptCompileException> {
            ScriptEngine.compile("""every(10, function () {});""") { _, _, _ -> }
        }
        assertTrue("timers are not available" in e.message!!, e.message)
    }
}
