package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Feature 020: while DTA delivers a pushed message, the receive path neither records nor scripts it. */
class WsScriptingTest {

    @TempDir
    lateinit var dir: File

    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7) }
    private val recorder by lazy { NetworkRecorder(store) { _, _ -> } }
    private val scripts = ScriptRuntime()

    @AfterEach
    fun close() = store.close()

    @Test
    fun `a pushed message passes through unrecorded and unscripted, the app's reply is still seen`() {
        scripts.install("""function onWsReceive(msg) { return drop(); } function onWsSend(msg) { msg.text = "changed"; return msg; }""")
        val scripting = WsScripting(recorder, scripts)
        val id = recorder.wsStarted("okhttp3-ws", "ws://h/ws", emptyList(), null)
        recorder.wsOpened(id)

        val (incoming, reply) = WsPushGuard.during {
            scripting.handle(id, Direction.IN, MessageOpcode.TEXT, "pushed", null, null) to
                scripting.handle(id, Direction.OUT, MessageOpcode.TEXT, "ack", null, null)
        }
        assertFalse(incoming.dropped, "not given to onWsReceive")
        assertEquals("changed", reply.text, "the app's reply goes through onWsSend")
        val messages = store.list(limit = 100).entries.filterIsInstance<WsMessageEntry>()
        assertEquals(listOf(Direction.OUT), messages.map { it.direction }, "only the reply is recorded by the hooks")
    }
}
