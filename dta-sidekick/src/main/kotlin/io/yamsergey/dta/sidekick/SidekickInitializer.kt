package io.yamsergey.dta.sidekick

import android.content.Context
import androidx.startup.Initializer
import io.yamsergey.dta.sidekick.server.SidekickServer

/**
 * AndroidX Startup entry point — runs in a ContentProvider before
 * Application.onCreate, the earliest userland hook. Early init is the
 * integration model's core advantage (plan D-series); keep this dependency-free
 * and fast.
 */
class SidekickInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        SidekickServer.start(
            packageName = context.packageName,
            sidekickVersion = BuildConfig.VERSION,
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
