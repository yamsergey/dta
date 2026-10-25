package io.yamsergey.dta.sidekick.network.nv

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.EntryHooks
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.WsSockets
import java.lang.reflect.Field

/**
 * nv-websocket-client capture: entry hooks on `WebSocket#connect`, `WebSocket#sendFrame`
 * and the `ListenerManager` callbacks. The library is not a compile dependency: its
 * objects are read reflectively.
 */
internal object NvWebSocketCapture {

    private const val SOCKET = "com.neovisionaries.ws.client.WebSocket"
    private const val LISTENERS = "com.neovisionaries.ws.client.ListenerManager"
    private const val FRAME = "Lcom/neovisionaries/ws/client/WebSocketFrame;"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators): LibraryState {
        val socketClass = EntryHooks.load(SOCKET, loader)
        val listenersClass = EntryHooks.load(LISTENERS, loader)
        if (socketClass == null || listenersClass == null) {
            return LibraryState(NetworkLibraries.NV_WEBSOCKET, present = false, installFailure = null)
        }
        val sockets = WsSockets()
        val owner: Field = listenersClass.getDeclaredField("mWebSocket").apply { isAccessible = true }
        fun idOfListeners(listeners: Any) = sockets[owner.get(listeners)]

        val socketHooks = EntryHooks(socketClass)
        socketHooks.on("connect", "()L${SOCKET.replace('.', '/')};") { self, _ ->
            if (sockets[self] != null) return@on
            val uri = self.javaClass.getMethod("getURI").invoke(self)
            sockets.put(self, recorder.wsStarted(NetworkLibraries.NV_WEBSOCKET, uri.toString(), emptyList(), initiators.capture(InitiatorSite.CONNECT)))
        }
        socketHooks.on("sendFrame", "($FRAME)L${SOCKET.replace('.', '/')};") { self, params ->
            val id = sockets[self] ?: return@on
            val frame = params[0] ?: return@on
            val opcode = when {
                frame.call("isTextFrame") == true -> MessageOpcode.TEXT
                frame.call("isBinaryFrame") == true -> MessageOpcode.BINARY
                else -> return@on
            }
            val payload = frame.call("getPayload") as? ByteArray ?: ByteArray(0)
            recorder.wsMessage(id, Direction.OUT, opcode, payload, initiators.capture(InitiatorSite.SEND))
        }

        val listenerHooks = EntryHooks(listenersClass)
        listenerHooks.on("callOnConnected", "(Ljava/util/Map;)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            val headers = (params[0] as? Map<*, *>)?.flatMap { (name, values) ->
                if (name == null) emptyList() else (values as? List<*>).orEmpty().map { Header(name.toString(), it.toString()) }
            }
            recorder.wsOpened(id, headers)
        }
        listenerHooks.on("callOnTextMessage", "(Ljava/lang/String;)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            val text = params[0] as? String ?: return@on
            recorder.wsMessage(id, Direction.IN, MessageOpcode.TEXT, text.toByteArray(Charsets.UTF_8), initiator = null)
        }
        listenerHooks.on("callOnBinaryMessage", "([B)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            recorder.wsMessage(id, Direction.IN, MessageOpcode.BINARY, params[0] as? ByteArray ?: return@on, initiator = null)
        }
        listenerHooks.on("callOnDisconnected", "(${FRAME}${FRAME}Z)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            val closeFrame = params[0] ?: params[1]
            recorder.wsClosed(id, closeFrame?.call("getCloseCode") as? Int, closeFrame?.call("getCloseReason") as? String)
        }
        listenerHooks.on("callOnConnectError", "(Lcom/neovisionaries/ws/client/WebSocketException;)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            recorder.wsFailed(id, params[0] as? Throwable ?: IllegalStateException("connect error"))
        }

        val failure = listOf(socketHooks, listenerHooks).firstNotNullOfOrNull { it.state(NetworkLibraries.NV_WEBSOCKET).installFailure }
        return LibraryState(NetworkLibraries.NV_WEBSOCKET, present = true, installFailure = failure)
    }

    private fun Any.call(method: String): Any? = javaClass.getMethod(method).invoke(this)
}
