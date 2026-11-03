package io.yamsergey.dta.state

import java.util.concurrent.atomic.AtomicLong

/** An event the app reported; pass it as a cause of later ones. [NONE] when DTA is not there. */
class DtaEvent internal constructor(val localId: Long) {
    companion object {
        @JvmField
        val NONE = DtaEvent(0)
    }
}

/**
 * The app's own source for the DTA timeline (feature 016): what the app is in (facets), what
 * happened (events) and what caused what, where only the app knows. Every call returns at once
 * and keeps nothing when the DTA sidekick is not in the process; release builds use the
 * `dta-state-noop` artifact, whose calls do nothing at all. DTA gets only the short strings
 * passed here, never the app's objects.
 */
object DtaState {
    const val MAX_NAME = 64
    const val MAX_VALUE = 120
    const val MAX_ATTRS = 8

    private val ids = AtomicLong()

    /** The facet [name] (e.g. `cart`) is now [value] (e.g. `empty`); null clears it. */
    @JvmStatic
    fun facet(name: String, value: String?) {
        if (!DtaStateBridge.on) return
        DtaStateBridge.emit(DtaStateBridge.FACET, name.take(MAX_NAME), value?.take(MAX_VALUE), null, 0, 0)
    }

    /** Something happened, with a few attributes and, when the app knows it, what caused it. */
    @JvmStatic
    @JvmOverloads
    fun event(name: String, attrs: Map<String, Any?> = emptyMap(), causedBy: DtaEvent? = null): DtaEvent {
        if (!DtaStateBridge.on) return DtaEvent.NONE
        val id = ids.incrementAndGet()
        val flat = attrs.entries.take(MAX_ATTRS).flatMap { (k, v) -> listOf(k.take(MAX_NAME), v.toString().take(MAX_VALUE)) }
        DtaStateBridge.emit(DtaStateBridge.EVENT, name.take(MAX_NAME), null, flat.toTypedArray(), id, causedBy?.localId ?: 0)
        return DtaEvent(id)
    }

    fun event(name: String, vararg attrs: Pair<String, Any?>, causedBy: DtaEvent? = null): DtaEvent =
        // The flag first: without DTA not even the map is built.
        if (!DtaStateBridge.on) DtaEvent.NONE else event(name, attrs.toMap(), causedBy)

    /** [from] caused [to], learned after both happened. */
    @JvmStatic
    fun cause(from: DtaEvent, to: DtaEvent) {
        if (!DtaStateBridge.on || from.localId == 0L || to.localId == 0L) return
        DtaStateBridge.emit(DtaStateBridge.CAUSE, null, null, null, to.localId, from.localId)
    }

    /** What happens in [block] — windows, requests, other events — is caused by [event] (declared). */
    inline fun <T> during(event: DtaEvent, block: () -> T): T {
        if (!DtaStateBridge.on || event.localId == 0L) return block()
        DtaStateBridge.emit(DtaStateBridge.SCOPE_ENTER, null, null, null, event.localId, 0)
        try {
            return block()
        } finally {
            DtaStateBridge.emit(DtaStateBridge.SCOPE_EXIT, null, null, null, event.localId, 0)
        }
    }
}
