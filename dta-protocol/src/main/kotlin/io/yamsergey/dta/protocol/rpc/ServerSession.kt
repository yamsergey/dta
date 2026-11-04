package io.yamsergey.dta.protocol.rpc

import io.yamsergey.dta.protocol.wire.WebSocketConnection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a server session says about itself during hello (contract §3). */
data class SessionIdentity(
    val packageName: String,
    val serverVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    /** When the server was built (UTC): tells two builds of one snapshot version apart (feature 036). */
    val serverBuild: String? = null,
)

/**
 * Hello-gated RPC session (contract §3): the mandatory first exchange is
 * `hello`; before it succeeds every other request is refused with NOT_READY.
 */
class ServerSession(
    private val identity: SessionIdentity,
    private val registry: MethodRegistry,
    private val sendText: (String) -> Unit,
    private val requestClose: (code: Int, reason: String) -> Unit = { _, _ -> },
    sendBinary: (ByteArray) -> Unit = {},
) {
    private val rpc = RpcConnection(registry, sendText, sendBinary)

    @Volatile
    var helloCompleted: Boolean = false
        private set

    fun handleText(text: String) {
        val message = try {
            DtaJson.decodeFromString(Message.serializer(), text)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        if (message !is Request) return

        when {
            message.method == "hello" -> handleHello(message)
            !helloCompleted -> send(
                Response(
                    id = message.id,
                    error = ErrorInfo(
                        code = ErrorCodes.NOT_READY,
                        message = "Say hello first: no request is served before a successful version handshake",
                    ),
                ),
            )
            else -> rpc.handleText(text)
        }
    }

    private fun handleHello(request: Request) {
        val params = request.params?.let {
            try {
                DtaJson.decodeFromJsonElement(HelloParams.serializer(), it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (params == null) {
            send(
                Response(
                    id = request.id,
                    error = ErrorInfo(
                        code = ErrorCodes.INVALID_PARAMS,
                        message = "hello requires params {protocolVersion, client, clientVersion}",
                    ),
                ),
            )
            return
        }

        if (params.protocolVersion != identity.protocolVersion) {
            send(
                Response(
                    id = request.id,
                    error = ErrorInfo(
                        code = ErrorCodes.VERSION_INCOMPATIBLE,
                        message = "sidekick protocol ${identity.protocolVersion} != daemon protocol " +
                            "${params.protocolVersion} — rebuild the app with the matching dta-sidekick",
                        details = buildJsonObject {
                            put("daemon", params.protocolVersion)
                            put("sidekick", identity.protocolVersion)
                        },
                    ),
                ),
            )
            requestClose(1000, "version incompatible")
            return
        }

        helloCompleted = true
        send(
            Response(
                id = request.id,
                result = DtaJson.encodeToJsonElement(
                    HelloResult.serializer(),
                    HelloResult(
                        protocolVersion = identity.protocolVersion,
                        server = "dta-sidekick",
                        serverVersion = identity.serverVersion,
                        packageName = identity.packageName,
                        serverBuild = identity.serverBuild,
                    ),
                ),
            ),
        )
    }

    fun sendEvent(name: String, data: JsonElement? = null): Long = rpc.sendEvent(name, data)

    private fun send(response: Response) =
        sendText(DtaJson.encodeToString(Message.serializer(), response))
}

/** Thin adapter: one ServerSession per accepted WebSocket connection. */
class ServerSessionConnectionListener(
    private val identity: SessionIdentity,
    private val registry: MethodRegistry,
    private val onSession: (ServerSession) -> Unit = {},
) : WebSocketConnection.Listener {

    private var session: ServerSession? = null

    override fun onOpen(connection: WebSocketConnection) {
        session = ServerSession(
            identity = identity,
            registry = registry,
            sendText = connection::sendText,
            requestClose = { code, reason -> connection.close(code, reason) },
            sendBinary = connection::sendBinary,
        ).also(onSession)
    }

    override fun onText(connection: WebSocketConnection, text: String) {
        session?.handleText(text)
    }
}
