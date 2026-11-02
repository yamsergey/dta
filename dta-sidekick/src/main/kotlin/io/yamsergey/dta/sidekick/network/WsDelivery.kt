package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.InjectionSource
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.ScriptHandler

/** A message pushed to the app (feature 020): exactly one of [text] and [bytes]. */
class PushMessage(val text: String?, val bytes: ByteArray?) {
    init {
        require((text == null) != (bytes == null)) { "a pushed message is text or bytes" }
    }

    val opcode: MessageOpcode get() = if (text != null) MessageOpcode.TEXT else MessageOpcode.BINARY
    val payload: ByteArray get() = bytes ?: text!!.toByteArray(Charsets.UTF_8)
}

/** Who pushed a message. */
data class PushSource(val by: InjectionSource, val scriptId: String? = null, val handler: ScriptHandler? = null)

/**
 * One library's way to hand a pushed message to the app (research R1) and to ask the server
 * for a frame (R3). Holds the socket weakly.
 */
interface WsDelivery {
    /** False once the app's socket object was collected. */
    fun alive(): Boolean

    /** Calls the library's own receive method; throws what the library or the app's listener throws. */
    fun deliver(message: PushMessage)

    /** Sends a ping so a frame arrives on the reading thread; false when the library offers no safe way. */
    fun ping(): Boolean
}

/**
 * Set while DTA hands a pushed message to a library: the receive hooks then pass the call
 * through, since the injector already recorded it. Deliveries run with the shim's re-entry
 * guard lifted (`HookDispatcher.unguarded`), so what the app's listener does — a reply it
 * sends — is captured and scripted as usual; only the incoming direction is skipped.
 */
object WsPushGuard {
    private val flag = ThreadLocal<Boolean>()

    val delivering: Boolean get() = flag.get() == true

    internal inline fun <T> during(block: () -> T): T {
        flag.set(true)
        try {
            return block()
        } finally {
            flag.remove()
        }
    }
}
