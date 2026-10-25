package io.yamsergey.dta.sidekick.network

import java.util.Collections
import java.util.WeakHashMap

/**
 * Library socket object → connection entry id, held weakly: a socket the app drops is
 * collected (the original kept strong references and leaked every connection).
 */
class WsSockets {
    private val ids: MutableMap<Any, String> = Collections.synchronizedMap(WeakHashMap())

    fun put(socket: Any, id: String) {
        ids[socket] = id
    }

    operator fun get(socket: Any?): String? = socket?.let { ids[it] }
}
