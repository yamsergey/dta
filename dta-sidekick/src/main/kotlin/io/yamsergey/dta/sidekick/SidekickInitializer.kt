package io.yamsergey.dta.sidekick

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import io.yamsergey.dta.sidekick.layout.compose.ComposeInspection
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence
import io.yamsergey.dta.sidekick.network.NetworkRuntime
import io.yamsergey.dta.sidekick.server.SidekickServer

/**
 * AndroidX Startup entry point — runs in a ContentProvider before
 * Application.onCreate, the earliest userland hook. Early init is the
 * integration model's core advantage (plan D-series); keep this dependency-free
 * and fast.
 */
class SidekickInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        // Enable Compose slot-table exposure BEFORE any composition (the only
        // reliable time). Guarded so a Views-only app never loads Compose.
        if (ComposePresence().composePresent) {
            runCatching { ComposeInspection.enableEarly() }
                .onFailure { Log.w("DtaSidekick", "compose inspection enable failed: ${it.message}") }
        }
        // Normally already started by HooksProvider; idempotent, so the network methods
        // exist even if that provider was stripped from the manifest.
        runCatching { NetworkRuntime.start(context) }
        // The journal of short-lived UI (feature 015); idempotent like the network start.
        runCatching { io.yamsergey.dta.sidekick.ui.UiRuntime.start(context) { name, data -> SidekickServer.sessions.publish(name, data) } }
        // The timeline's other sources (feature 016): input with its handling scope, network.
        runCatching { io.yamsergey.dta.sidekick.timeline.TimelineSources.start(context) }
        // Links that reach an activity (feature 012), recorded into the same history.
        runCatching { io.yamsergey.dta.sidekick.links.LinkRecorder.start(context.applicationContext as android.app.Application) }
        SidekickServer.start(
            packageName = context.packageName,
            sidekickVersion = BuildConfig.VERSION,
            installation = { AppInstallations.of(context) },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
