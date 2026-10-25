package io.yamsergey.dta.sidekick.network.okhttp

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
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString

/**
 * OkHttp WebSocket capture: entry hooks on `okhttp3.internal.ws.RealWebSocket`. WebSocket
 * calls bypass network interceptors, so the socket class itself is the observation point.
 */
internal object OkHttpWebSocketCapture {

    private const val SOCKET = "okhttp3.internal.ws.RealWebSocket"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators): LibraryState {
        val socketClass = EntryHooks.load(SOCKET, loader)
            ?: return LibraryState(NetworkLibraries.OKHTTP3_WS, present = false, installFailure = null)
        val sockets = WsSockets()
        val hooks = EntryHooks(socketClass)
        fun hook(method: String, descriptor: String, handler: (self: Any, params: Array<Any?>) -> Unit) =
            hooks.on(method, descriptor, handler)

        run {
            hook("connect", "(Lokhttp3/OkHttpClient;)V") { self, _ ->
                val request = (self as WebSocket).request()
                val headers = (0 until request.headers.size).map { Header(request.headers.name(it), request.headers.value(it)) }
                sockets.put(self, recorder.wsStarted(NetworkLibraries.OKHTTP3_WS, wsUrl(request.url.toString()), headers, initiators.capture(InitiatorSite.CONNECT)))
            }
            hook("checkUpgradeSuccess\$okhttp", "(Lokhttp3/Response;Lokhttp3/internal/connection/Exchange;)V") { self, params ->
                val id = sockets[self] ?: return@hook
                val response = params[0] as? Response ?: return@hook
                val headers = (0 until response.headers.size).map { Header(response.headers.name(it), response.headers.value(it)) }
                recorder.wsHandshake(id, headers)
            }
            hook("initReaderAndWriter", "(Ljava/lang/String;Lokhttp3/internal/ws/RealWebSocket\$Streams;)V") { self, _ ->
                sockets[self]?.let { recorder.wsOpened(it) }
            }
            hook("send", "(Ljava/lang/String;)Z") { self, params ->
                val id = sockets[self] ?: return@hook
                val text = params[0] as? String ?: return@hook
                recorder.wsMessage(id, Direction.OUT, MessageOpcode.TEXT, text.toByteArray(Charsets.UTF_8), initiators.capture(InitiatorSite.SEND))
            }
            hook("send", "(Lokio/ByteString;)Z") { self, params ->
                val id = sockets[self] ?: return@hook
                val bytes = params[0] as? ByteString ?: return@hook
                recorder.wsMessage(id, Direction.OUT, MessageOpcode.BINARY, bytes.toByteArray(), initiators.capture(InitiatorSite.SEND))
            }
            hook("onReadMessage", "(Ljava/lang/String;)V") { self, params ->
                val id = sockets[self] ?: return@hook
                val text = params[0] as? String ?: return@hook
                recorder.wsMessage(id, Direction.IN, MessageOpcode.TEXT, text.toByteArray(Charsets.UTF_8), initiator = null)
            }
            hook("onReadMessage", "(Lokio/ByteString;)V") { self, params ->
                val id = sockets[self] ?: return@hook
                val bytes = params[0] as? ByteString ?: return@hook
                recorder.wsMessage(id, Direction.IN, MessageOpcode.BINARY, bytes.toByteArray(), initiator = null)
            }
            hook("onReadClose", "(ILjava/lang/String;)V") { self, params ->
                sockets[self]?.let { recorder.wsClosed(it, params[0] as? Int, params[1] as? String) }
            }
            hook("failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;)V") { self, params ->
                val id = sockets[self] ?: return@hook
                recorder.wsFailed(id, params[0] as? Throwable ?: IllegalStateException("websocket failed"))
            }
        }
        return hooks.state(NetworkLibraries.OKHTTP3_WS)
    }

    /** OkHttp stores ws:/wss: requests as http:/https:; report the scheme the app used. */
    private fun wsUrl(url: String): String = when {
        url.startsWith("http:") -> "ws:" + url.removePrefix("http:")
        url.startsWith("https:") -> "wss:" + url.removePrefix("https:")
        else -> url
    }
}
