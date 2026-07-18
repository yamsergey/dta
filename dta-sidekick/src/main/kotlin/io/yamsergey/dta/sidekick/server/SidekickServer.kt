package io.yamsergey.dta.sidekick.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.rpc.ServerSessionConnectionListener
import io.yamsergey.dta.protocol.rpc.SessionIdentity
import io.yamsergey.dta.protocol.wire.WebSocketConnection
import io.yamsergey.dta.sidekick.capability.CapabilityRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.thread

private const val TAG = "DtaSidekick"

/**
 * On-device server: accept loop on `localabstract:dta_sidekick_<package>`,
 * one thread + one hello-gated session per client. Inert until a client
 * connects; hook/connection failures never crash the host app (constitution I).
 */
object SidekickServer {

    @Volatile
    private var started = false

    val capabilities = CapabilityRegistry()

    @Synchronized
    fun start(packageName: String, sidekickVersion: String) {
        if (started) return
        started = true

        capabilities.register("channel") {
            CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE)
        }

        val registry = MethodRegistry().apply {
            register("ping") { buildJsonObject { put("pong", true) } }
            register("identity") {
                DtaJson.encodeToJsonElement(
                    IdentityReport.serializer(),
                    capabilities.buildIdentity(packageName, sidekickVersion),
                )
            }
        }
        val identity = SessionIdentity(packageName = packageName, serverVersion = sidekickVersion)
        val socketName = "dta_sidekick_$packageName"

        thread(isDaemon = true, name = "dta-sidekick-accept") {
            val serverSocket = try {
                LocalServerSocket(socketName)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot listen on @$socketName: ${e.message}")
                capabilities.register("channel") {
                    CapabilityStatus(
                        "channel",
                        CapabilityStatus.Status.UNAVAILABLE,
                        "server socket failed: ${e.message}",
                    )
                }
                return@thread
            }
            Log.i(TAG, "sidekick $sidekickVersion listening on @$socketName")

            while (true) {
                val client = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    // Accept must survive client churn; only an unrecoverable
                    // socket error ends the loop (process teardown).
                    Log.w(TAG, "accept failed: ${e.message}")
                    continue
                }
                thread(isDaemon = true, name = "dta-sidekick-conn") {
                    serve(client, identity, registry)
                }
            }
        }
    }

    private fun serve(client: LocalSocket, identity: SessionIdentity, registry: MethodRegistry) {
        try {
            WebSocketConnection(
                client.inputStream,
                client.outputStream,
                ServerSessionConnectionListener(identity, registry),
            ).run()
        } catch (e: Exception) {
            Log.w(TAG, "connection ended with ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }
}
