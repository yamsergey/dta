package io.yamsergey.dta.sidekick.runtime

import java.util.Collections
import java.util.WeakHashMap

/** Feature 028: the Navigation 3 back stacks the timeline's NavDisplay hook saw, held weakly. */
internal object Nav3Stacks {
    private val stacks: MutableSet<List<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun seen(stack: List<*>) {
        stacks += stack
    }

    fun all(): List<List<*>> = synchronized(stacks) { stacks.toList() }
}
