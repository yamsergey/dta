package io.yamsergey.dta.sidekick.hooks

import io.yamsergey.dta.shim.ControlHandler
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.HookDispatcher
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

    /** Control-hook probe: also has a primitive param, to exercise unboxing writeback. */
    fun echo(text: String, times: Int): String = "echo-" + text.repeat(times)
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
    private const val ECHO_DESCRIPTOR = "(Ljava/lang/String;I)Ljava/lang/String;"

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
        val rewritten = SelfTestProbe().echo("a", 1)
        val early = SelfTestProbe().echo("stop", 1)
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
                putJsonObject("control-probe") {
                    put("install", installed.getValue("control"))
                    // "a"×1 is rewritten to "b"×2 by the handler before the body runs.
                    put("argumentRewritten", rewritten == "echo-bb")
                    put("returnedEarly", early == "early")
                    put("observed", "$rewritten / $early")
                }
                // Feature 019: an absent method is found absent without a class reload, and the
                // class's other hooks (installed before it) keep firing.
                putJsonObject("absent-probe") {
                    put("install", installed.getValue("absent"))
                    put("othersStillFire", probeEntry && probeExit && rewritten == "echo-bb")
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
        val control = describe(
            Hooks.control(SelfTestProbe::class.java, "echo", ECHO_DESCRIPTOR, object : ControlHandler {
                override fun onEntry(self: Any?, params: Array<Any?>): Any? = when (params[0]) {
                    "a" -> {
                        params[0] = "b"
                        params[1] = 2
                        HookDispatcher.PROCEED
                    }
                    "stop" -> "early"
                    else -> HookDispatcher.PROCEED
                }
            }),
        )
        val absent = Hooks.entry(SelfTestProbe::class.java, "missing", "()V", object : EntryHandler {
            override fun onEntry(self: Any?, params: Array<Any?>) {}
        }).code
        return mapOf("app-probe" to probe, "url" to url, "control" to control, "absent" to absent)
    }
}
