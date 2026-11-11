package io.yamsergey.dta.sidekick.web

import android.content.Context
import android.util.Log
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.web.Web
import io.yamsergey.dta.protocol.web.WebIngest as WebIngestParams
import io.yamsergey.dta.protocol.web.WebLaunch
import io.yamsergey.dta.protocol.web.WebLaunches
import io.yamsergey.dta.protocol.web.WebSwitch
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.sidekick.hooks.HookAttach
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.network.NetworkRuntime
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.concurrent.thread

/** Feature 060: the app's two browser-capture switches, marker files in the sidekick's no-backup storage (as 056). */
class WebSwitchStore(private val dir: File) {
    private fun marker(name: String) = File(dir, name)
    private fun on(name: String) = runCatching { marker(name).exists() }.getOrDefault(false)
    private fun set(name: String, on: Boolean) {
        if (on) { dir.mkdirs(); marker(name).createNewFile() } else marker(name).delete()
    }

    val webview: Boolean get() = on(WEBVIEW)
    val customTabs: Boolean get() = on(TABS)
    fun setWebview(on: Boolean) = set(WEBVIEW, on)
    fun setCustomTabs(on: Boolean) = set(TABS, on)

    private companion object {
        const val WEBVIEW = "webview-on"
        const val TABS = "customtabs-on"
    }
}

/**
 * Feature 060: what the app does for browser capture, only when a switch is on. WebView on: the
 * app's WebViews are made debuggable at start, on a background thread (a debuggable app's are by
 * default, but only once a WebView exists; early, pages opened right after start are caught).
 * Custom Tabs on: an entry hook on `CustomTabsIntent#launchUrl` remembers which pages the app opened.
 */
object WebRuntime {
    private const val TAG = "DtaWeb"
    private const val TABS_CLASS = "androidx.browser.customtabs.CustomTabsIntent"
    private const val LAUNCH = "launchUrl"
    private const val LAUNCH_DESC = "(Landroid/content/Context;Landroid/net/Uri;)V"
    private const val MAX_LAUNCHES = 32

    fun store(context: Context) = WebSwitchStore(File(context.noBackupFilesDir, "dta"))

    @Volatile var debuggingEnabled = false
        private set
    @Volatile private var launchHook: String? = null
    private val launches = ArrayDeque<WebLaunch>()
    private var ingest: WebIngest? = null

    /** At app start: one or two file checks; the work itself is off the main thread. */
    fun startIfOn(context: Context) {
        val store = store(context)
        if (store.webview) enableDebugging()
        if (store.customTabs) hookLaunches(context.classLoader)
    }

    private fun enableDebugging() {
        if (debuggingEnabled) return
        thread(isDaemon = true, name = "dta-web-debugging") {
            runCatching {
                Class.forName("android.webkit.WebView").getMethod("setWebContentsDebuggingEnabled", Boolean::class.javaPrimitiveType).invoke(null, true)
                debuggingEnabled = true
            }.onFailure { Log.w(TAG, "WebView debugging not enabled: $it") }
        }
    }

    @Synchronized
    private fun hookLaunches(loader: ClassLoader?) {
        if (launchHook == "installed") return
        if (!HookAttach.attached) { launchHook = "hooks unavailable: ${HookAttach.reason}"; return }
        val install = Hooks.entry(TABS_CLASS, loader, LAUNCH, LAUNCH_DESC, object : EntryHandler {
            override fun onEntry(self: Any?, params: Array<out Any?>) {
                val url = params.getOrNull(1)?.toString() ?: return
                synchronized(launches) {
                    launches.addLast(WebLaunch(url, System.currentTimeMillis()))
                    while (launches.size > MAX_LAUNCHES) launches.removeFirst()
                }
            }
        })
        launchHook = when (install) {
            Hooks.Install.Installed -> "installed"
            is Hooks.Install.ClassAbsent -> "the app has no androidx.browser Custom Tabs"
            else -> "not installed: ${install.describe()}"
        }
    }

    fun install(registry: MethodRegistry, context: Context) {
        val store = store(context)
        registry.register(Web.CAPTURE) { ctx ->
            val webview = ctx.params?.get("webview")?.jsonPrimitive?.booleanOrNull
            val tabs = ctx.params?.get("customTabs")?.jsonPrimitive?.booleanOrNull
            val notes = mutableListOf<String>()
            webview?.let { on ->
                store.setWebview(on)
                if (on) { enableDebugging(); notes += "WebView pages: debuggable now and from every app start" }
                else notes += "WebView pages: the app keeps its WebViews debuggable until it restarts"
            }
            tabs?.let { on ->
                store.setCustomTabs(on)
                if (on) hookLaunches(context.classLoader) else notes += "Custom Tabs: launches are no longer used"
            }
            DtaJson.encodeToJsonElement(
                WebSwitch.serializer(),
                WebSwitch(store.webview, store.customTabs, debuggingEnabled, launchHook, notes.joinToString("; ").ifEmpty { null }),
            )
        }
        registry.register(Web.INGEST) { ctx ->
            val recorder = NetworkRuntime.recorder ?: throw IllegalStateException("network capture is not running in this app")
            val params = DtaJson.decodeFromJsonElement(WebIngestParams.serializer(), ctx.params!!)
            val sink = synchronized(this) { ingest ?: WebIngest(recorder).also { ingest = it } }
            DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.web.WebIngestResult.serializer(), sink.apply(params.steps))
        }
        registry.register(Web.LAUNCHES) { ctx ->
            val since = ctx.params?.get("sinceMs")?.jsonPrimitive?.longOrNull ?: 0L
            val list = synchronized(launches) { launches.filter { it.timeMs >= since } }
            DtaJson.encodeToJsonElement(WebLaunches.serializer(), WebLaunches(list))
        }
    }
}
