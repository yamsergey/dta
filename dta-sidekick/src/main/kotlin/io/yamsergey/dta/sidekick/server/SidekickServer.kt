package io.yamsergey.dta.sidekick.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DiagnosticMethods
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.AppInstallation
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.rpc.ServerSessionConnectionListener
import io.yamsergey.dta.protocol.rpc.SessionIdentity
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutTreeParams
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.wire.WebSocketConnection
import io.yamsergey.dta.sidekick.capability.CapabilityRegistry
import io.yamsergey.dta.sidekick.hooks.HookSelfTest
import io.yamsergey.dta.sidekick.network.NetworkCapability
import io.yamsergey.dta.sidekick.network.NetworkMethods
import io.yamsergey.dta.sidekick.network.NetworkRuntime
import io.yamsergey.dta.sidekick.network.script.NetworkScriptMethods
import io.yamsergey.dta.sidekick.layout.LayoutCapability
import io.yamsergey.dta.sidekick.layout.LayoutCaptureMethod
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
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

    /** Pushes events (network.entry) to every connected session. */
    val sessions = SessionHub()

    @Synchronized
    fun start(packageName: String, sidekickVersion: String, installation: () -> AppInstallation? = { null }, context: android.content.Context? = null) {
        if (started) return
        started = true

        capabilities.register("channel") {
            CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE)
        }

        // layout capability status is computed from the SAME compose probes the
        // capture uses, so identity and captures never disagree (contract §5).
        val composePresence = ComposePresence()
        capabilities.register(LayoutCapability.NAME) { LayoutCapability.status(composePresence) }

        capabilities.register(NetworkCapability.NAME) { NetworkRuntime.capability() }
        capabilities.register(io.yamsergey.dta.sidekick.ui.UiRuntime.NAME) { io.yamsergey.dta.sidekick.ui.UiRuntime.capability() }
        capabilities.register(io.yamsergey.dta.sidekick.links.LinkRecorder.NAME) { io.yamsergey.dta.sidekick.links.LinkRecorder.capability() }
        if (context != null) capabilities.register(io.yamsergey.dta.sidekick.data.DataMethods.NAME) { io.yamsergey.dta.sidekick.data.DataMethods.capability() }
        if (context != null) capabilities.register(io.yamsergey.dta.sidekick.runtime.Runtime.NAME) { io.yamsergey.dta.sidekick.runtime.RuntimeMethods.capability() }

        val layoutMethod = LayoutCaptureMethod(
            presence = composePresence,
            // Feature 054: built on first use, on its own thread; a capture waits for it at most 1 s.
            packageIndex = { waitMs ->
                context?.let { io.yamsergey.dta.sidekick.layout.compose.PackageIndexHolder.of(it).await(waitMs) }
                    ?: io.yamsergey.dta.sidekick.layout.compose.PackageIndexHolder.State.Failed("no app context")
            },
            appPackage = packageName,
            screenProvider = { screenMetadata(packageName) },
        )

        val registry = MethodRegistry().apply {
            register("ping") { buildJsonObject { put("pong", true) } }
            register("identity") {
                DtaJson.encodeToJsonElement(
                    IdentityReport.serializer(),
                    capabilities.buildIdentity(packageName, sidekickVersion, runCatching(installation).getOrNull()),
                )
            }
            register("layout_tree") { ctx ->
                val params = LayoutTreeParams.parse(ctx.params)
                DtaJson.encodeToJsonElement(LayoutCapture.serializer(), layoutMethod.capture(params))
            }
            // Feature 057: "the screen changed", only while a watcher holds the lease.
            io.yamsergey.dta.sidekick.layout.ScreenSignalWatch({ name, data -> sessions.publish(name, data) }, composePresence).install(this)
            DiagnosticMethods.install(this)
            io.yamsergey.dta.sidekick.timeline.TimelineMethods.install(this)
            // Feature 056: the app's timeline switch.
            context?.let { c ->
                val sources = io.yamsergey.dta.sidekick.timeline.TimelineSources
                io.yamsergey.dta.sidekick.timeline.TimelineSwitchMethods(sources.store(c), { sources.running }, { sources.start(c) }).install(this)
            }
            // Feature 027: the app's stores, on request only.
            context?.let { io.yamsergey.dta.sidekick.data.DataMethods(it).install(this) }
            // Feature 028: what the running process holds.
            context?.let { io.yamsergey.dta.sidekick.runtime.RuntimeMethods(it).install(this) }
            // Diagnostic: proves JVMTI entry/exit hooks on app and boot classes (feature 004).
            register("hook_selftest") { HookSelfTest.run() }
            NetworkRuntime.store?.let { store ->
                NetworkMethods(store, { NetworkRuntime.scripts.degradations() }) { NetworkRuntime.capability() }.install(this)
            }
            NetworkScriptMethods(NetworkRuntime.scripts, NetworkRuntime.scriptStore) {
                if (io.yamsergey.dta.sidekick.hooks.HookAttach.attached) null else io.yamsergey.dta.sidekick.hooks.HookAttach.reason
            }.install(this)
            io.yamsergey.dta.sidekick.network.WsPushMethods({ NetworkRuntime.injector }) {
                if (io.yamsergey.dta.sidekick.hooks.HookAttach.attached) null else io.yamsergey.dta.sidekick.hooks.HookAttach.reason
            }.install(this)
        }
        val identity = SessionIdentity(packageName = packageName, serverVersion = sidekickVersion, serverBuild = io.yamsergey.dta.sidekick.BuildConfig.BUILD)
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
                        io.yamsergey.dta.sidekick.capability.Reasons.CHANNEL_FAILED,
                        detail = "${e.javaClass.name}: ${e.message}",
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
        var hubHandle: SessionHub.Handle? = null
        try {
            WebSocketConnection(
                client.inputStream,
                client.outputStream,
                ServerSessionConnectionListener(identity, registry) { session ->
                    hubHandle = sessions.add(object : SessionHub.Sink {
                        override val helloCompleted: Boolean get() = session.helloCompleted
                        override fun send(name: String, data: kotlinx.serialization.json.JsonElement?) {
                            session.sendEvent(name, data)
                        }
                    })
                },
            ).run()
        } catch (e: Exception) {
            Log.w(TAG, "connection ended with ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            hubHandle?.close()
            runCatching { client.close() }
        }
    }

    /** Screen metadata for the foreground display. */
    private fun screenMetadata(packageName: String): Screen {
        val app = currentApplication()
        val metrics = app?.resources?.displayMetrics
        return Screen(
            widthPx = metrics?.widthPixels ?: 0,
            heightPx = metrics?.heightPixels ?: 0,
            density = metrics?.density ?: 1f,
            activity = currentActivityName(),
        )
    }

    private fun currentApplication(): android.app.Application? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getApplication").invoke(current) as? android.app.Application
    } catch (_: Throwable) {
        null
    }

    private fun currentActivityName(): String? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val activities = activityThread.getDeclaredField("mActivities").apply { isAccessible = true }
            .get(current) as? Map<Any, Any>
        activities?.values?.firstNotNullOfOrNull { record ->
            val paused = record.javaClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
            if (paused) null else {
                val activity = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(record)
                activity?.javaClass?.name
            }
        }
    } catch (_: Throwable) {
        null
    }
}
