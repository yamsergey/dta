package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.InjectionDelivery
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsInjection
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.sidekick.util.TaskScheduler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock

/** What the injector needs from the history. */
interface PushRecorder {
    val runId: String
    fun connection(id: String): WsConnectionEntry?

    /** Records the pushed message as an incoming message of [connectionId]; its entry id, or null. */
    fun record(connectionId: String, message: PushMessage, injection: WsInjection): String?
}

/** A push that cannot start; [message] is the plain reason (contract §1). */
class PushException(message: String) : Exception(message)

sealed interface PushOutcome {
    data class Delivered(val entryId: String, val connectionId: String, val delivery: InjectionDelivery, val waitedMs: Long, val listenerError: String? = null) : PushOutcome
    data class Failed(val reason: String) : PushOutcome
}

/**
 * Pushes messages into the app's WebSocket connections as if the server sent them (feature 020).
 * One FIFO queue and one lock per connection: a push is taken and delivered under the lock, so
 * pushes reach the app once, in order, never two at a time (research R2). The reading thread
 * delivers at its flush point when it gets the lock without waiting; otherwise DTA's scheduler
 * delivers at the push's deadline (R4). Every delivered push is recorded first, marked (R5).
 */
class WsInjector(private val recorder: PushRecorder, private val scheduler: TaskScheduler) {

    private class Pending(
        val connectionId: String,
        val message: PushMessage,
        val source: PushSource,
        val queuedAtMs: Long,
        val future: CompletableFuture<PushOutcome>,
        val onFailure: ((String) -> Unit)?,
    )

    private class Live(val delivery: WsDelivery) {
        val lock = ReentrantLock()
        val queue = ArrayDeque<Pending>()
    }

    private val live = ConcurrentHashMap<String, Live>()

    /** A socket the adapter saw connect, under its history id. */
    fun attach(connectionId: String, delivery: WsDelivery) {
        live[connectionId] = Live(delivery)
    }

    /** The running process's open connections whose sockets the app still holds, oldest first. */
    fun openConnections(): List<WsConnectionEntry> =
        live.entries.mapNotNull { (id, socket) ->
            recorder.connection(id)?.takeIf { it.state == WsState.OPEN && it.runId == recorder.runId && socket.delivery.alive() }
        }.sortedBy { seq(it.id) }

    /**
     * Pushes [message] to [connectionId] (or the only open connection). Throws [PushException]
     * when it cannot start; the returned future completes when delivered or failed. [onFailure]
     * hears a failure after the call returned (a script logs it).
     */
    fun push(
        connectionId: String?,
        message: PushMessage,
        source: PushSource,
        waitMs: Long,
        delayMs: Long = 0,
        onFailure: ((String) -> Unit)? = null,
    ): Future<PushOutcome> {
        val id = connectionId ?: onlyOpen()
        val socket = check(id)
        val future = CompletableFuture<PushOutcome>()
        val enqueue = {
            val pending = Pending(id, message, source, scheduler.nowMs(), future, onFailure)
            val failure = deliverable(id, socket, before = true)
            if (failure != null) {
                fail(pending, failure)
            } else {
                synchronized(socket.queue) { socket.queue.addLast(pending) }
                if (waitMs > 0) {
                    try {
                        socket.delivery.ping()
                    } catch (_: Throwable) {
                        // No frame comes sooner; the deadline delivers it.
                    }
                }
                scheduler.schedule(waitMs.coerceAtLeast(0)) { deliverUpTo(socket, pending) }
            }
        }
        if (delayMs > 0) scheduler.schedule(delayMs) { enqueue() } else enqueue()
        return future
    }

    /** The reading thread's flush point (research R2): never waits for DTA's thread. */
    fun flush(connectionId: String) {
        val socket = live[connectionId] ?: return
        if (synchronized(socket.queue) { socket.queue.isEmpty() }) return
        if (!socket.lock.tryLock()) return
        try {
            while (true) {
                val next = synchronized(socket.queue) { socket.queue.removeFirstOrNull() } ?: break
                deliver(socket, next, InjectionDelivery.READING_THREAD)
            }
        } finally {
            socket.lock.unlock()
        }
    }

    /** Tests: hold a connection's delivery lock as DTA's thread would. */
    internal fun lockFor(connectionId: String): ReentrantLock = live.getValue(connectionId).lock

    private fun deliverUpTo(socket: Live, target: Pending) {
        socket.lock.lock()
        try {
            if (synchronized(socket.queue) { target !in socket.queue }) return
            while (true) {
                val next = synchronized(socket.queue) { socket.queue.removeFirstOrNull() } ?: return
                deliver(socket, next, InjectionDelivery.DTA_THREAD)
                if (next === target) return
            }
        } finally {
            socket.lock.unlock()
        }
    }

    private fun deliver(socket: Live, pending: Pending, how: InjectionDelivery) {
        deliverable(pending.connectionId, socket, before = false)?.let { return fail(pending, it) }
        val waited = (scheduler.nowMs() - pending.queuedAtMs).coerceAtLeast(0)
        val injection = WsInjection(pending.source.by, pending.source.scriptId, pending.source.handler, how, waited)
        val entryId = recorder.record(pending.connectionId, pending.message, injection)
            ?: return fail(pending, "connection ${pending.connectionId} is no longer in the app's history")
        val listenerError = try {
            WsPushGuard.during { socket.delivery.deliver(pending.message) }
            null
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            "${cause.javaClass.simpleName}: ${cause.message}"
        }
        pending.future.complete(PushOutcome.Delivered(entryId, pending.connectionId, how, waited, listenerError))
    }

    private fun fail(pending: Pending, reason: String) {
        pending.future.complete(PushOutcome.Failed(reason))
        pending.onFailure?.invoke(reason)
    }

    private fun onlyOpen(): String {
        val open = openConnections()
        return when (open.size) {
            0 -> throw PushException("the app has no open WebSocket connection")
            1 -> open.single().id
            else -> throw PushException(
                "the app has ${open.size} open WebSocket connections; name one with connection: " + open.joinToString(", ") { "${it.id} (${it.url})" },
            )
        }
    }

    private fun check(id: String): Live {
        val entry = recorder.connection(id)
            ?: throw PushException("$id is not a WebSocket connection of the app; push to an open connection (network_list, type ws-connection, state open)")
        if (entry.runId != recorder.runId) {
            throw PushException("connection $id is from an earlier run of the app (${entry.runId}); only connections of the running process can receive pushes")
        }
        if (entry.state != WsState.OPEN) throw PushException("connection $id is ${state(entry)}; push to an open connection")
        val socket = live[id]
            ?: throw PushException("DTA did not see the socket of connection $id open, so it cannot deliver to it")
        if (!socket.delivery.alive()) throw PushException(collected(id))
        return socket
    }

    /** Why [id] cannot take a message now, or null. */
    private fun deliverable(id: String, socket: Live, before: Boolean): String? {
        val entry = recorder.connection(id) ?: return "connection $id is no longer in the app's history"
        if (entry.state != WsState.OPEN) {
            return if (entry.state == WsState.CLOSED) "connection $id closed before the message was delivered" + closeDetail(entry)
            else "connection $id ${state(entry)} before the message was delivered"
        }
        if (!socket.delivery.alive()) return collected(id)
        return null
    }

    private fun state(entry: WsConnectionEntry): String = when (entry.state) {
        WsState.CLOSED -> "closed" + closeDetail(entry)
        WsState.FAILED -> "failed" + (entry.failure?.let { " ($it)" } ?: "")
        WsState.CONNECTING -> "still connecting"
        WsState.PROCESS_ENDED -> "from a process that ended"
        WsState.OPEN -> "open"
    }

    private fun closeDetail(entry: WsConnectionEntry): String =
        listOfNotNull(entry.closeCode?.let { "code $it" }, entry.closeReason?.takeIf { it.isNotEmpty() }).joinToString(" ").let { if (it.isEmpty()) "" else " ($it)" }

    private fun collected(id: String) = "the app no longer holds connection $id (its socket was garbage-collected without being closed)"

    private fun seq(id: String): Long = id.substringAfterLast(':').toLongOrNull() ?: Long.MAX_VALUE
}
