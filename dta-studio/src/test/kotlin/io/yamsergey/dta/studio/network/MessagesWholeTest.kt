package io.yamsergey.dta.studio.network

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.Component
import java.awt.Container

/**
 * D-101 (042 FR-002): a message expanded while its socket's next message arrives shows its whole text. The whole text
 * is fetched on expanding; the next message reads the socket again and the view takes new rows (D-091). The fetched
 * text must reach the row as it is now, not the row object the view had when it was asked (gate eap-11 follow-up:
 * the row stayed "▾ less" over its cut start, 1 run in 4 at 900 px).
 */
class MessagesWholeTest : BasePlatformTestCase() {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = JsonObject(mapOf("device" to JsonPrimitive("emulator-5554"), "app" to JsonPrimitive("com.example.shop")))

    override fun tearDown() {
        try { scope.cancel(); daemon.close() } finally { super.tearDown() }
    }

    private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::all) ?: emptyList())

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < end)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }

    fun `test the whole text fetched while the next message arrives reaches the expanded row`() {
        val client = runBlocking { daemon.connect(ClientKind.IDE).client }
        val pane = EntryDetailPane(project, scope)
        pane.show(client, target, "r1-1:1", redact = false, gone = false)
        waitFor("the entry shown") { daemon.ops.bodyCalls.any { it.second == BodyPart.RESPONSE } }
        val entry = Json.parseToJsonElement(javaClass.getResource("/entry-ws.json")!!.readText()).jsonObject
        pane.showLoaded("r2-1:6", entry)
        val view = all(pane).filterIsInstance<MessagesView>().single()
        val gate = CompletableDeferred<Unit>().also { daemon.ops.bodyGate = it }
        view.toggle(0)
        waitFor("the whole message asked") { daemon.ops.bodyCalls.any { it.second == BodyPart.MESSAGE } }
        // The next message of the socket: the entry read again while the whole text is on its way.
        pane.followLoaded(entry)
        assertSame("the same Messages view (D-091)", view, all(pane).filterIsInstance<MessagesView>().single())
        gate.complete(Unit)
        waitFor("the whole text on the expanded row: ${view.rowTexts(0)}") { view.rowTexts(0).any { "items" in it } }
        assertEquals("▾ less", view.rowTexts(0).last())
        runBlocking { client.close() }
    }
}
