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
        SidekickServer.start(
            packageName = context.packageName,
            sidekickVersion = BuildConfig.VERSION,
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
