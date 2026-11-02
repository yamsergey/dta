package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.sidekick.hooks.Hooks

/**
 * One known form of a hooked method (feature 019). [since] names the library versions that
 * have it, for diagnostics; the first form present in the app is the one hooked.
 */
data class HookSignature(val method: String, val descriptor: String, val since: String? = null)

/**
 * What one event of a library (in user terms: "handshake headers", "failures") came to:
 * [installed] is the form hooked, null when none was; [tried] every form attempted, in order.
 */
data class EventResult(val event: String, val installed: HookSignature?, val tried: List<Pair<HookSignature, Hooks.Install>>) {
    val captured: Boolean get() = installed != null

    companion object {
        /** The event's class is not in the app (a library split over several classes). */
        fun classAbsent(event: String, className: String, signature: HookSignature) =
            EventResult(event, null, listOf(signature to Hooks.Install.ClassAbsent(className)))

        /** One hook, one form: the event is captured when it installed. */
        fun of(event: String, signature: HookSignature, result: Hooks.Install) =
            EventResult(event, signature.takeIf { result is Hooks.Install.Installed }, listOf(signature to result))
    }
}

/** The events network capture names, in the words a reader of the capability report uses. */
object NetworkEvents {
    const val CONNECTIONS = "connections"
    const val HANDSHAKE = "handshake headers"
    const val OPEN = "connection open"
    const val SENT = "sent messages"
    const val SENT_TEXT = "sent text messages"
    const val SENT_BINARY = "sent binary messages"
    const val RECEIVED_TEXT = "received text messages"
    const val RECEIVED_BINARY = "received binary messages"
    const val CLOSE = "close"
    const val FAILURES = "failures"

    const val REQUESTS = "requests and responses"
    const val SCRIPT_AND_EARLY_FAILURES = "network script and failures before connecting"
    const val ASYNC_CALL_SITES = "where async calls were made"
    const val SYNC_CALL_SITES = "where sync calls were made"
    const val PROXY_CONNECTIONS = "connections through a proxy"
}
