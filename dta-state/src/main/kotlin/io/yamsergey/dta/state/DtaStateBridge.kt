package io.yamsergey.dta.state

/**
 * Where every call of [DtaState] ends (feature 016, research R8). FROZEN ABI: the DTA sidekick
 * hooks [emit] by its name and descriptor and sets [on] when it is in the process; add
 * overloads, never change these. Without the sidekick [on] stays false and nothing is built.
 */
object DtaStateBridge {
    const val VERSION = 1

    const val FACET = 1
    const val EVENT = 2
    const val CAUSE = 3
    const val SCOPE_ENTER = 4
    const val SCOPE_EXIT = 5

    @JvmField
    @Volatile
    var on: Boolean = false

    /** This module's tests only: what [emit] was called with. */
    @Volatile
    internal var recorder: ((op: Int, a: String?, b: String?, attrs: Array<String>?, id: Long, cause: Long) -> Unit)? = null

    /** Nothing to do here: the sidekick reads the arguments as the call enters. */
    @JvmStatic
    fun emit(op: Int, a: String?, b: String?, attrs: Array<String>?, id: Long, cause: Long) {
        recorder?.invoke(op, a, b, attrs, id, cause)
    }
}
