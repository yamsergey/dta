package io.yamsergey.dta.sidekick.hooks

import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ExitHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URL
import java.net.URLConnection

/** App-loader class the self-test hooks; never used for anything else. */
class SelfTestProbe {
    fun value(x: Int): String = "original-$x"
}

/**
 * `hook_selftest` diagnostic (feature 004): proves the agent attached and that entry and
 * exit hooks fire — with return replacement — on an app-loader class and on a boot class.
 * The URL hook only acts on the reserved host [SELFTEST_HOST]; other URLs pass through.
 */
object HookSelfTest {

    private const val SELFTEST_HOST = "dta-selftest.invalid"
    private const val PROBE_DESCRIPTOR = "(I)Ljava/lang/String;"
    private const val OPEN_DESCRIPTOR = "()Ljava/net/URLConnection;"

    @Volatile private var probeEntry = false
    @Volatile private var urlEntry = false
    @Volatile private var urlExit = false
    @Volatile private var probeExit = false
    private val selftestUrlOnThread = ThreadLocal<URL?>()

    private class MarkerConnection(url: URL) : URLConnection(url) {
        override fun connect() {}
    }

    private var installs: Map<String, String>? = null

    @Synchronized
    fun run(): JsonObject {
        if (!HookAttach.attached) {
            return buildJsonObject { put("attach", HookAttach.reason ?: "not attached") }
        }
        val installed = installs ?: install().also { installs = it }
        probeEntry = false; probeExit = false; urlEntry = false; urlExit = false

        val probeResult = SelfTestProbe().value(7)
        val connection = URL("http://$SELFTEST_HOST/probe").openConnection()

        return buildJsonObject {
            put("attach", "ok")
            putJsonObject("targets") {
                putJsonObject("app-probe") {
                    put("install", installed.getValue("app-probe"))
                    put("entry", probeEntry)
                    put("exit", probeExit)
                    put("exitReplaced", probeResult == "replaced-7")
                }
                putJsonObject("java.net.URL#openConnection") {
                    put("install", installed.getValue("url"))
                    put("entry", urlEntry)
                    put("exit", urlExit)
                    put("exitReplaced", connection is MarkerConnection)
                }
            }
        }
    }

    private fun install(): Map<String, String> {
        fun describe(vararg results: Hooks.Install) =
            results.firstOrNull { it !is Hooks.Install.Installed }?.toString() ?: "installed"

        val probe = describe(
            Hooks.entry(SelfTestProbe::class.java, "value", PROBE_DESCRIPTOR, object : EntryHandler {
                override fun onEntry(self: Any?, params: Array<Any?>) {
                    if (self is SelfTestProbe && params.firstOrNull() == 7) probeEntry = true
                }
            }),
            Hooks.exit(SelfTestProbe::class.java, "value", PROBE_DESCRIPTOR, object : ExitHandler {
                override fun onExit(result: Any?): Any? {
                    if (result != "original-7") return result
                    probeExit = true
                    return "replaced-7"
                }
            }),
        )
        val url = describe(
            Hooks.entry(URL::class.java, "openConnection", OPEN_DESCRIPTOR, object : EntryHandler {
                override fun onEntry(self: Any?, params: Array<Any?>) {
                    val target = self as? URL
                    if (target?.host == SELFTEST_HOST) {
                        urlEntry = true
                        selftestUrlOnThread.set(target)
                    }
                }
            }),
            Hooks.exit(URL::class.java, "openConnection", OPEN_DESCRIPTOR, object : ExitHandler {
                override fun onExit(result: Any?): Any? {
                    val target = selftestUrlOnThread.get() ?: return result
                    selftestUrlOnThread.remove()
                    urlExit = true
                    return MarkerConnection(target)
                }
            }),
        )
        return mapOf("app-probe" to probe, "url" to url)
    }
}
