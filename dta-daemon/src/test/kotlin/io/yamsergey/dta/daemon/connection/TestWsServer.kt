package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import io.yamsergey.dta.protocol.rpc.ServerSession
import io.yamsergey.dta.protocol.rpc.ServerSessionConnectionListener
import io.yamsergey.dta.protocol.rpc.SessionIdentity
import io.yamsergey.dta.protocol.wire.WebSocketConnection
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * In-process sidekick-server stand-in: the REAL protocol server logic
 * (ServerSession over WebSocketConnection) on a TCP socket, so the daemon
 * client is validated against the same code the device runs — deviceless.
 */
class TestWsServer(
    private val registry: MethodRegistry = MethodRegistry(),
    packageName: String = "com.example.fixture",
    protocolVersion: Int = PROTOCOL_VERSION,
    serverVersion: String = "0.1.0",
) : AutoCloseable {

    private val identity = SessionIdentity(
        packageName = packageName,
        serverVersion = serverVersion,
        protocolVersion = protocolVersion,
    )
    // Loopback, as every DTA server binds: a wildcard bind can share its port with another
    // test JVM's loopback server, and 127.0.0.1 then reaches that one instead.
    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    val port: Int get() = serverSocket.localPort
    val sessions: MutableList<ServerSession> = Collections.synchronizedList(mutableListOf())
    private val sockets = Collections.synchronizedList(mutableListOf<java.net.Socket>())

    private val acceptThread = thread(isDaemon = true, name = "test-ws-server") {
        try {
            while (true) {
                val socket = serverSocket.accept()
                sockets += socket
                thread(isDaemon = true) {
                    val listener = ServerSessionConnectionListener(identity, registry) { sessions += it }
                    WebSocketConnection(socket.getInputStream(), socket.getOutputStream(), listener).run()
                    runCatching { socket.close() }
                }
            }
        } catch (_: Exception) {
            // server closed
        }
    }

    /** Severs every open TCP connection without close handshakes (simulated app death). */
    fun severConnections() {
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        severConnections()
    }
}
