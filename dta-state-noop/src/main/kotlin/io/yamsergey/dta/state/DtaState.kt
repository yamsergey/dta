package io.yamsergey.dta.state

/** The `dta-state` API for release builds (feature 016): every call does nothing. */
class DtaEvent internal constructor(val localId: Long) {
    companion object {
        @JvmField
        val NONE = DtaEvent(0)
    }
}

@Suppress("UNUSED_PARAMETER")
object DtaState {
    const val MAX_NAME = 64
    const val MAX_VALUE = 120
    const val MAX_ATTRS = 8

    @JvmStatic
    fun facet(name: String, value: String?) {
    }

    @JvmStatic
    @JvmOverloads
    fun event(name: String, attrs: Map<String, Any?> = emptyMap(), causedBy: DtaEvent? = null): DtaEvent = DtaEvent.NONE

    fun event(name: String, vararg attrs: Pair<String, Any?>, causedBy: DtaEvent? = null): DtaEvent = DtaEvent.NONE

    @JvmStatic
    fun cause(from: DtaEvent, to: DtaEvent) {
    }

    inline fun <T> during(event: DtaEvent, block: () -> T): T = block()
}
