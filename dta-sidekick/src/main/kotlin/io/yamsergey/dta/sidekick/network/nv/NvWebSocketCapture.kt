package io.yamsergey.dta.sidekick.network.nv

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.EntryHooks
import io.yamsergey.dta.sidekick.network.NetworkEvents
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.PushMessage
import io.yamsergey.dta.sidekick.network.WsDelivery
import io.yamsergey.dta.sidekick.network.WsInjector
import io.yamsergey.dta.sidekick.network.WsScripting
import io.yamsergey.dta.sidekick.network.WsSockets
import io.yamsergey.dta.shim.HookDispatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * nv-websocket-client capture: entry hooks on `WebSocket#connect`, `WebSocket#sendFrame`
 * and the `ListenerManager` callbacks. The library is not a compile dependency: its
 * objects are read reflectively.
 */
internal object NvWebSocketCapture {

    private const val SOCKET = "com.neovisionaries.ws.client.WebSocket"
    private const val LISTENERS = "com.neovisionaries.ws.client.ListenerManager"
    private const val FRAME = "Lcom/neovisionaries/ws/client/WebSocketFrame;"
    private const val FRAME_CLASS = "com.neovisionaries.ws.client.WebSocketFrame"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators, scripting: WsScripting, injector: WsInjector): LibraryState {
        val socketClass = EntryHooks.load(SOCKET, loader)
        val listenersClass = EntryHooks.load(LISTENERS, loader)
        if (socketClass == null || listenersClass == null) {
            return LibraryState(NetworkLibraries.NV_WEBSOCKET, present = false, installFailure = null)
        }
        val sockets = WsSockets()
        val owner: Field = listenersClass.getDeclaredField("mWebSocket").apply { isAccessible = true }
        fun idOfListeners(listeners: Any) = sockets[owner.get(listeners)]

        val socketHooks = EntryHooks(socketClass)
        socketHooks.on(NetworkEvents.CONNECTIONS, "connect", "()L${SOCKET.replace('.', '/')};") { self, _ ->
            if (sockets[self] != null) return@on
            val uri = self.javaClass.getMethod("getURI").invoke(self)
            val id = recorder.wsStarted(NetworkLibraries.NV_WEBSOCKET, uri.toString(), emptyList(), initiators.capture(InitiatorSite.CONNECT))
            sockets.put(self, id)
            injector.attach(id, Delivery(self, socketClass, listenersClass))
        }
        val frameClass = EntryHooks.load(FRAME_CLASS, loader)
        socketHooks.control(NetworkEvents.SENT, "sendFrame", "($FRAME)L${SOCKET.replace('.', '/')};") { self, params ->
            val id = sockets[self] ?: return@control EntryHooks.PROCEED
            val frame = params[0] ?: return@control EntryHooks.PROCEED
            val opcode = when {
                frame.call("isTextFrame") == true -> MessageOpcode.TEXT
                frame.call("isBinaryFrame") == true -> MessageOpcode.BINARY
                else -> return@control EntryHooks.PROCEED
            }
            val payload = frame.call("getPayload") as? ByteArray ?: ByteArray(0)
            val text = if (opcode == MessageOpcode.TEXT) String(payload, Charsets.UTF_8) else null
            val d = scripting.handle(id, Direction.OUT, opcode, text, if (text == null) payload else null, initiators.capture(InitiatorSite.SEND))
            if (d.dropped) return@control self
            when {
                d.text != null -> params[0] = frameClass!!.getMethod("createTextFrame", String::class.java).invoke(null, d.text)
                d.bytes != null -> params[0] = frameClass!!.getMethod("createBinaryFrame", ByteArray::class.java).invoke(null, d.bytes)
            }
            EntryHooks.PROCEED
        }

        val listenerHooks = EntryHooks(listenersClass)
        listenerHooks.on(NetworkEvents.OPEN, "callOnConnected", "(Ljava/util/Map;)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            val headers = (params[0] as? Map<*, *>)?.flatMap { (name, values) ->
                if (name == null) emptyList() else (values as? List<*>).orEmpty().map { Header(name.toString(), it.toString()) }
            }
            recorder.wsOpened(id, headers)
        }
        listenerHooks.control(NetworkEvents.RECEIVED_TEXT, "callOnTextMessage", "(Ljava/lang/String;)V") { self, params ->
            val id = idOfListeners(self) ?: return@control EntryHooks.PROCEED
            val text = params[0] as? String ?: return@control EntryHooks.PROCEED
            val d = scripting.handle(id, Direction.IN, MessageOpcode.TEXT, text, null, initiator = null)
            if (d.dropped) return@control Unit
            d.text?.let { params[0] = it }
            EntryHooks.PROCEED
        }
        listenerHooks.control(NetworkEvents.RECEIVED_BINARY, "callOnBinaryMessage", "([B)V") { self, params ->
            val id = idOfListeners(self) ?: return@control EntryHooks.PROCEED
            val bytes = params[0] as? ByteArray ?: return@control EntryHooks.PROCEED
            val d = scripting.handle(id, Direction.IN, MessageOpcode.BINARY, null, bytes, initiator = null)
            if (d.dropped) return@control Unit
            d.bytes?.let { params[0] = it }
            EntryHooks.PROCEED
        }
        listenerHooks.on(NetworkEvents.CLOSE, "callOnDisconnected", "(${FRAME}${FRAME}Z)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            val closeFrame = params[0] ?: params[1]
            recorder.wsClosed(id, closeFrame?.call("getCloseCode") as? Int, closeFrame?.call("getCloseReason") as? String)
        }
        listenerHooks.on(NetworkEvents.FAILURES, "callOnConnectError", "(Lcom/neovisionaries/ws/client/WebSocketException;)V") { self, params ->
            val id = idOfListeners(self) ?: return@on
            recorder.wsFailed(id, params[0] as? Throwable ?: IllegalStateException("connect error"))
        }

        // Feature 020: ReadingThread reports every frame to callOnFrame before handling it,
        // outside any lock: pushed messages go to the app there (research R2).
        listenerHooks.on(NetworkEvents.PUSH_ON_READER, "callOnFrame", "($FRAME)V") { self, _ ->
            idOfListeners(self)?.let(injector::flush)
        }

        return EntryHooks.state(NetworkLibraries.NV_WEBSOCKET, socketHooks, listenerHooks)
    }

    /** `ListenerManager.callOnTextMessage/callOnBinaryMessage`, as ReadingThread calls them; ping by `sendPing()`. */
    private class Delivery(socket: Any, socketClass: Class<*>, listenersClass: Class<*>) : WsDelivery {
        private val ref = WeakReference(socket)
        private val listeners: Method = socketClass.getDeclaredMethod("getListenerManager").apply { isAccessible = true }
        private val onText: Method = listenersClass.getDeclaredMethod("callOnTextMessage", String::class.java).apply { isAccessible = true }
        private val onBinary: Method = listenersClass.getDeclaredMethod("callOnBinaryMessage", ByteArray::class.java).apply { isAccessible = true }
        private val sendPing: Method = socketClass.getMethod("sendPing")

        override fun alive() = ref.get() != null

        override fun ping(): Boolean {
            sendPing.invoke(ref.get() ?: return false)
            return true
        }

        override fun deliver(message: PushMessage) {
            val socket = ref.get() ?: throw IllegalStateException("the socket was collected")
            val manager = listeners.invoke(socket)
            HookDispatcher.unguarded {
                if (message.text != null) onText.invoke(manager, message.text) else onBinary.invoke(manager, message.bytes)
            }
        }
    }

    private fun Any.call(method: String): Any? = javaClass.getMethod(method).invoke(this)
}
