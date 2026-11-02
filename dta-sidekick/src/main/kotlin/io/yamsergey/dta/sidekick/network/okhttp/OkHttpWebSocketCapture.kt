package io.yamsergey.dta.sidekick.network.okhttp

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.EntryHooks
import io.yamsergey.dta.sidekick.network.HookSignature
import io.yamsergey.dta.sidekick.network.NetworkEvents
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.PushMessage
import io.yamsergey.dta.sidekick.network.WsDelivery
import io.yamsergey.dta.sidekick.network.WsInjector
import io.yamsergey.dta.sidekick.network.WsScripting
import io.yamsergey.dta.sidekick.network.WsSockets
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.shim.HookDispatcher
import java.lang.ref.WeakReference
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp WebSocket capture: entry hooks on `okhttp3.internal.ws.RealWebSocket`. WebSocket
 * calls bypass network interceptors, so the socket class itself is the observation point.
 */
internal object OkHttpWebSocketCapture {

    private const val SOCKET = "okhttp3.internal.ws.RealWebSocket"
    private const val READER = "okhttp3.internal.ws.WebSocketReader"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators, scripting: WsScripting, injector: WsInjector): LibraryState {
        val socketClass = EntryHooks.load(SOCKET, loader)
            ?: return LibraryState(NetworkLibraries.OKHTTP3_WS, present = false, installFailure = null)
        val sockets = WsSockets()
        val hooks = EntryHooks(socketClass)

        run {
            hooks.on(NetworkEvents.CONNECTIONS, "connect", "(Lokhttp3/OkHttpClient;)V") { self, _ ->
                val request = (self as WebSocket).request()
                val headers = (0 until request.headers.size).map { Header(request.headers.name(it), request.headers.value(it)) }
                val id = recorder.wsStarted(NetworkLibraries.OKHTTP3_WS, wsUrl(request.url.toString()), headers, initiators.capture(InitiatorSite.CONNECT))
                sockets.put(self, id)
                injector.attach(id, Delivery(self))
            }
            // The response is params[0] in every form (research R1).
            hooks.on(NetworkEvents.HANDSHAKE, *HANDSHAKE) { self, params ->
                val id = sockets[self] ?: return@on
                val response = params[0] as? Response ?: return@on
                val headers = (0 until response.headers.size).map { Header(response.headers.name(it), response.headers.value(it)) }
                recorder.wsHandshake(id, headers)
            }
            hooks.on(NetworkEvents.OPEN, *OPEN) { self, _ ->
                sockets[self]?.let { recorder.wsOpened(it) }
            }
            // Send and receive go through the network script (feature 005): a dropped send
            // returns true as if queued; a dropped receive never reaches the listener.
            hooks.control(NetworkEvents.SENT_TEXT, "send", "(Ljava/lang/String;)Z") { self, params ->
                val id = sockets[self] ?: return@control EntryHooks.PROCEED
                val text = params[0] as? String ?: return@control EntryHooks.PROCEED
                val d = scripting.handle(id, Direction.OUT, MessageOpcode.TEXT, text, null, initiators.capture(InitiatorSite.SEND))
                if (d.dropped) return@control true
                d.text?.let { params[0] = it }
                EntryHooks.PROCEED
            }
            hooks.control(NetworkEvents.SENT_BINARY, "send", "(Lokio/ByteString;)Z") { self, params ->
                val id = sockets[self] ?: return@control EntryHooks.PROCEED
                val bytes = params[0] as? ByteString ?: return@control EntryHooks.PROCEED
                val d = scripting.handle(id, Direction.OUT, MessageOpcode.BINARY, null, bytes.toByteArray(), initiators.capture(InitiatorSite.SEND))
                if (d.dropped) return@control true
                d.bytes?.let { params[0] = it.toByteString() }
                EntryHooks.PROCEED
            }
            hooks.control(NetworkEvents.RECEIVED_TEXT, "onReadMessage", "(Ljava/lang/String;)V") { self, params ->
                val id = sockets[self] ?: return@control EntryHooks.PROCEED
                val text = params[0] as? String ?: return@control EntryHooks.PROCEED
                val d = scripting.handle(id, Direction.IN, MessageOpcode.TEXT, text, null, initiator = null)
                if (d.dropped) return@control Unit
                d.text?.let { params[0] = it }
                EntryHooks.PROCEED
            }
            hooks.control(NetworkEvents.RECEIVED_BINARY, "onReadMessage", "(Lokio/ByteString;)V") { self, params ->
                val id = sockets[self] ?: return@control EntryHooks.PROCEED
                val bytes = params[0] as? ByteString ?: return@control EntryHooks.PROCEED
                val d = scripting.handle(id, Direction.IN, MessageOpcode.BINARY, null, bytes.toByteArray(), initiator = null)
                if (d.dropped) return@control Unit
                d.bytes?.let { params[0] = it.toByteString() }
                EntryHooks.PROCEED
            }
            hooks.on(NetworkEvents.CLOSE, "onReadClose", "(ILjava/lang/String;)V") { self, params ->
                sockets[self]?.let { recorder.wsClosed(it, params[0] as? Int, params[1] as? String) }
            }
            // The exception is params[0] and the response params[1] in every form (research R1).
            hooks.on(NetworkEvents.FAILURES, *FAILURE) { self, params ->
                val id = sockets[self] ?: return@on
                recorder.wsFailed(id, params[0] as? Throwable ?: IllegalStateException("websocket failed"))
            }
        }
        // Feature 020: the reader loop enters processNextFrame after each frame, before the next
        // blocking read, outside any OkHttp lock: pushed messages go to the app there (research R2).
        val readerHooks = EntryHooks.load(READER, loader)?.let { readerClass ->
            EntryHooks(readerClass).apply {
                val callback = readerClass.declaredFields.firstOrNull { it.name == "frameCallback" }?.apply { isAccessible = true }
                if (callback == null) {
                    missing(NetworkEvents.PUSH_ON_READER, HookSignature("processNextFrame", "()V"), Hooks.Install.Failed("WebSocketReader has no frameCallback field in this OkHttp version"))
                } else {
                    on(NetworkEvents.PUSH_ON_READER, "processNextFrame", "()V") { self, _ ->
                        sockets[callback.get(self)]?.let(injector::flush)
                    }
                }
            }
        }
        return EntryHooks.state(NetworkLibraries.OKHTTP3_WS, *listOfNotNull(hooks, readerHooks).toTypedArray())
    }

    /**
     * Hands a pushed message to `RealWebSocket.onReadMessage`, the method the reader calls for a
     * server message (research R1). No ping: OkHttp writes pings on its writer task only (R3).
     */
    private class Delivery(socket: Any) : WsDelivery {
        private val ref = WeakReference(socket)
        override fun alive() = ref.get() != null
        override fun ping() = false
        override fun deliver(message: PushMessage) {
            val socket = ref.get() ?: throw IllegalStateException("the socket was collected")
            HookDispatcher.unguarded {
                if (message.text != null) {
                    socket.javaClass.getMethod("onReadMessage", String::class.java).invoke(socket, message.text)
                } else {
                    socket.javaClass.getMethod("onReadMessage", ByteString::class.java).invoke(socket, message.bytes!!.toByteString())
                }
            }
        }
    }

    /** `RealWebSocket` method forms by OkHttp version, newest first (feature 019, research R1). */
    private val HANDSHAKE = arrayOf(
        HookSignature("checkUpgradeSuccess\$okhttp", "(Lokhttp3/Response;)Lokio/Socket;", since = "OkHttp 5.2"),
        HookSignature("checkUpgradeSuccess\$okhttp", "(Lokhttp3/Response;Lokhttp3/internal/connection/Exchange;)V", since = "OkHttp 4"),
    )
    private val OPEN = arrayOf(
        HookSignature("initReaderAndWriter", "(Ljava/lang/String;Lokhttp3/internal/connection/BufferedSocket;Z)V", since = "OkHttp 5.2"),
        HookSignature("initReaderAndWriter", "(Ljava/lang/String;Lokhttp3/internal/ws/RealWebSocket\$Streams;)V", since = "OkHttp 4"),
    )
    private val FAILURE = arrayOf(
        HookSignature("failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;Z)V", since = "OkHttp 5.0"),
        HookSignature("failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;)V", since = "OkHttp 4"),
    )

    /** OkHttp stores ws:/wss: requests as http:/https:; report the scheme the app used. */
    private fun wsUrl(url: String): String = when {
        url.startsWith("http:") -> "ws:" + url.removePrefix("http:")
        url.startsWith("https:") -> "wss:" + url.removePrefix("https:")
        else -> url
    }
}
