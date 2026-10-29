package io.yamsergey.dta.protocol.wire

import java.util.concurrent.CompletableFuture

/**
 * Sends one at a time, in the order enqueued. The JDK WebSocket refuses a send while the
 * previous one is pending ("Send pending"); every sender of ours shares this queue instead of
 * guarding it alone. A failed send fails only its own future.
 */
class SendQueue {
    private val lock = Any()
    private var last: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    fun <T> enqueue(send: () -> CompletableFuture<T>): CompletableFuture<T> = synchronized(lock) {
        val next = last.handle { _, _ -> null }.thenCompose { send() }
        last = next
        next
    }
}
