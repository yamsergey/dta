package io.yamsergey.dta.sidekick.network.javaws

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.EntryHooks
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.WsSockets
import java.nio.ByteBuffer

/**
 * Java-WebSocket capture. Incoming events hook the concrete, final
 * `WebSocketClient#onWebsocket*` methods (the original hooked the abstract `onClose`,
 * which never fired); outgoing messages hook `WebSocketImpl#send`, where
 * `send(byte[])` funnels into `send(ByteBuffer)` — so each message is seen once.
 */
internal object JavaWebSocketCapture {

    private const val CLIENT = "org.java_websocket.client.WebSocketClient"
    private const val IMPL = "org.java_websocket.WebSocketImpl"
    private const val CONN = "Lorg/java_websocket/WebSocket;"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators): LibraryState {
        val clientClass = EntryHooks.load(CLIENT, loader)
        val implClass = EntryHooks.load(IMPL, loader)
        if (clientClass == null || implClass == null) {
            return LibraryState(NetworkLibraries.JAVA_WEBSOCKET, present = false, installFailure = null)
        }
        // Both the client and its engine (WebSocketImpl) map to the connection id.
        val sockets = WsSockets()

        val client = EntryHooks(clientClass)
        client.on("connect", "()V") { self, _ ->
            val uri = self.javaClass.getMethod("getURI").invoke(self)
            val id = recorder.wsStarted(NetworkLibraries.JAVA_WEBSOCKET, uri.toString(), emptyList(), initiators.capture(InitiatorSite.CONNECT))
            sockets.put(self, id)
            self.javaClass.getMethod("getConnection").invoke(self)?.let { sockets.put(it, id) }
        }
        client.on("onWebsocketOpen", "(${CONN}Lorg/java_websocket/handshake/Handshakedata;)V") { self, _ ->
            sockets[self]?.let { recorder.wsOpened(it) }
        }
        client.on("onWebsocketMessage", "(${CONN}Ljava/lang/String;)V") { self, params ->
            val id = sockets[self] ?: return@on
            val text = params[1] as? String ?: return@on
            recorder.wsMessage(id, Direction.IN, MessageOpcode.TEXT, text.toByteArray(Charsets.UTF_8), initiator = null)
        }
        client.on("onWebsocketMessage", "(${CONN}Ljava/nio/ByteBuffer;)V") { self, params ->
            val id = sockets[self] ?: return@on
            recorder.wsMessage(id, Direction.IN, MessageOpcode.BINARY, (params[1] as? ByteBuffer)?.copyBytes() ?: return@on, initiator = null)
        }
        client.on("onWebsocketClose", "(${CONN}ILjava/lang/String;Z)V") { self, params ->
            sockets[self]?.let { recorder.wsClosed(it, params[1] as? Int, params[2] as? String) }
        }
        client.on("onWebsocketError", "(${CONN}Ljava/lang/Exception;)V") { self, params ->
            sockets[self]?.let { recorder.wsFailed(it, params[1] as? Throwable ?: IllegalStateException("websocket error")) }
        }

        val impl = EntryHooks(implClass)
        impl.on("send", "(Ljava/lang/String;)V") { self, params ->
            val id = sockets[self] ?: return@on
            val text = params[0] as? String ?: return@on
            recorder.wsMessage(id, Direction.OUT, MessageOpcode.TEXT, text.toByteArray(Charsets.UTF_8), initiators.capture(InitiatorSite.SEND))
        }
        impl.on("send", "(Ljava/nio/ByteBuffer;)V") { self, params ->
            val id = sockets[self] ?: return@on
            recorder.wsMessage(id, Direction.OUT, MessageOpcode.BINARY, (params[0] as? ByteBuffer)?.copyBytes() ?: return@on, initiators.capture(InitiatorSite.SEND))
        }

        val failure = listOf(client, impl).firstNotNullOfOrNull { it.state(NetworkLibraries.JAVA_WEBSOCKET).installFailure }
        return LibraryState(NetworkLibraries.JAVA_WEBSOCKET, present = true, installFailure = failure)
    }

    /** Reads without moving the app's buffer position. */
    private fun ByteBuffer.copyBytes(): ByteArray = duplicate().let { ByteArray(it.remaining()).also(it::get) }
}
