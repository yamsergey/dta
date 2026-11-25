package io.yamsergey.dta.studio.network

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.testing.TestDaemon
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
 * D-003: with the Network tab's redaction on, the bodies the detail shows (Payload, Response, a
 * message read whole) are asked for redacted, like the entry above them — never the raw text.
 */
class EntryRedactionTest : BasePlatformTestCase() {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = JsonObject(mapOf("device" to JsonPrimitive("emulator-5554"), "app" to JsonPrimitive("com.example.shop")))

    override fun tearDown() {
        try {
            scope.cancel()
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::all) ?: emptyList())

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            assertTrue("timed out: $what; body calls ${daemon.ops.bodyCalls}", System.currentTimeMillis() < end)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }

    private fun bodiesAsked(redact: Boolean): List<Triple<String, BodyPart, Boolean>> {
        daemon.ops.bodyCalls.clear()
        val client = runBlocking { daemon.connect(ClientKind.IDE).client }
        val pane = EntryDetailPane(project, scope)
        pane.show(client, target, "r1-1:1", redact = redact, gone = false)
        waitFor("the response body") { daemon.ops.bodyCalls.any { it.second == BodyPart.RESPONSE } }
        // A message whose preview is cut is read whole when expanded.
        pane.showLoaded("r2-1:6", Json.parseToJsonElement(javaClass.getResource("/entry-ws.json")!!.readText()).jsonObject)
        val messages = all(pane).filterIsInstance<MessagesView>().single()
        messages.toggle(0)
        waitFor("the whole message") { daemon.ops.bodyCalls.any { it.second == BodyPart.MESSAGE } }
        runBlocking { client.close() }
        return daemon.ops.bodyCalls.toList()
    }

    fun `test with redaction on the detail asks for its bodies redacted`() {
        val calls = bodiesAsked(redact = true)
        assertTrue("every body asked redacted: $calls", calls.isNotEmpty() && calls.all { it.third })
        val plain = bodiesAsked(redact = false)
        assertTrue("twin: without redaction they are asked as is: $plain", plain.isNotEmpty() && plain.none { it.third })
    }

    /** D-099: switching Redact while an entry is open reads that entry again, as the switch now says (both ways). */
    fun `test switching redaction reads the open entry again`() {
        val client = runBlocking { daemon.connect(ClientKind.IDE).client }
        val pane = EntryDetailPane(project, scope)
        pane.show(client, target, "r1-1:1", redact = true, gone = false)
        waitFor("the entry redacted") { daemon.ops.entryCalls.lastOrNull() == ("r1-1:1" to true) }
        waitFor("the response body redacted") { daemon.ops.bodyCalls.any { it.second == BodyPart.RESPONSE && it.third } }
        daemon.ops.bodyCalls.clear()
        pane.show(client, target, "r1-1:1", redact = false, gone = false)
        waitFor("the entry read again plain") { daemon.ops.entryCalls.lastOrNull() == ("r1-1:1" to false) }
        waitFor("the response body plain") { daemon.ops.bodyCalls.any { it.second == BodyPart.RESPONSE && !it.third } }
        assertTrue("no body asked redacted after the switch: ${daemon.ops.bodyCalls}", daemon.ops.bodyCalls.none { it.third })
        // Twin: off to on redacts again.
        pane.show(client, target, "r1-1:1", redact = true, gone = false)
        waitFor("the entry read again redacted") { daemon.ops.entryCalls.lastOrNull() == ("r1-1:1" to true) }
        // Unchanged redaction: no new read.
        val reads = daemon.ops.entryCalls.size
        pane.show(client, target, "r1-1:1", redact = true, gone = false)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(200); PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("the same redaction does not read again", reads, daemon.ops.entryCalls.size)
        runBlocking { client.close() }
    }
}
