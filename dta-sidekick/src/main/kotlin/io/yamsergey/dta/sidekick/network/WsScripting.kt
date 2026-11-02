package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.sidekick.network.script.Effect
import io.yamsergey.dta.sidekick.network.script.InterventionDiff
import io.yamsergey.dta.sidekick.network.script.ScriptConnection
import io.yamsergey.dta.sidekick.network.script.ScriptMessage
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime

/**
 * One WebSocket message through the network script (feature 005) and into capture. Shared
 * by all three WebSocket adapters, so every library records interventions the same way:
 * `payload` is what went on (or came off) the wire, `appPayload` what the app sent or
 * received when the script changed it, and a dropped message is never sent or delivered.
 */
internal class WsScripting(private val recorder: NetworkRecorder, private val scripts: ScriptRuntime) {

    /** What the adapter must do: deliver [text]/[bytes] instead (null = unchanged), or drop. */
    data class Decision(val text: String? = null, val bytes: ByteArray? = null, val dropped: Boolean = false)

    fun handle(connectionId: String, direction: Direction, opcode: MessageOpcode, text: String?, bytes: ByteArray?, initiator: Initiator?): Decision {
        // Feature 020: a message DTA is pushing; the injector recorded it, and pushes skip onWsReceive.
        if (direction == Direction.IN && WsPushGuard.delivering) return Decision()
        val payload = bytes ?: text?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val active = scripts.current()
        val handler = if (direction == Direction.OUT) ScriptHandler.WS_SEND else ScriptHandler.WS_RECEIVE
        val connection = recorder.connection(connectionId)
        if (active == null || handler !in active.script.handlers || connection == null) {
            recorder.wsMessage(connectionId, direction, opcode, payload, initiator)
            return Decision()
        }
        val message = if (opcode == MessageOpcode.TEXT) ScriptMessage(text, null, false) else ScriptMessage(null, payload, true)
        val scriptConnection = ScriptConnection(connection.url, connection.library, connectionId)
        val result = if (direction == Direction.OUT) active.script.onWsSend(message, scriptConnection) else active.script.onWsReceive(message, scriptConnection)
        val effect = result.effect
        val decision = when (effect) {
            Effect.Drop -> Decision(dropped = true)
            is Effect.Change -> {
                val change = effect.change
                if (opcode == MessageOpcode.TEXT) Decision(text = change.text ?: change.bytes?.toString(Charsets.UTF_8))
                else Decision(bytes = change.bytes ?: change.text?.toByteArray(Charsets.UTF_8))
            }
            else -> Decision()
        }
        val changed = decision.text?.toByteArray(Charsets.UTF_8) ?: decision.bytes
        // Outgoing: the wire carries the changed payload. Incoming: the wire carried the
        // original; the app receives the changed one.
        val wire = if (direction == Direction.OUT && changed != null) changed else payload
        val id = recorder.wsMessage(connectionId, direction, opcode, wire, initiator)
        if (id != null) {
            if (changed != null) recorder.wsAppPayload(id, if (direction == Direction.OUT) payload else changed)
            InterventionDiff.record(active.id, handler, result)?.let { recorder.recordIntervention(id, it) }
        }
        scripts.account(handler, result, id)
        return decision
    }
}
