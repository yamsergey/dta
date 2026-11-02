package io.yamsergey.dta.sidekick.capability

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.ScriptSummary
import io.yamsergey.dta.sidekick.hooks.AttachPreconditions
import io.yamsergey.dta.sidekick.hooks.Hooks.Install
import io.yamsergey.dta.sidekick.network.EventResult
import io.yamsergey.dta.sidekick.network.HookSignature
import io.yamsergey.dta.sidekick.network.LibraryCoverage
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkCapability
import io.yamsergey.dta.sidekick.network.store.StoreBounds
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Feature 019 (US4, FR-008): every reason the sidekick's pure builders can state says what is not
 * seen and why, without raw codes or exception text as its content. The full list is printed for
 * a human read (quickstart Results).
 */
class ReasonTextTest {

    private val hooksOff = listOf(
        (AttachPreconditions.check(26, true, "/x", listOf("arm64-v8a")) as AttachPreconditions.Result.Unavailable).reason,
        (AttachPreconditions.check(37, false, null, listOf("arm64-v8a")) as AttachPreconditions.Result.Unavailable).reason,
        (AttachPreconditions.check(37, true, null, listOf("arm64-v8a", "x86_64")) as AttachPreconditions.Result.Unavailable).reason,
        Reasons.AGENT_REFUSED, Reasons.SHIM_FAILED, Reasons.HOOKS_SETUP_FAILED, Reasons.HOOKS_STARTING,
    )

    private val socket = "okhttp3.internal.ws.RealWebSocket"
    private val sig = HookSignature("failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;)V")
    private fun lib(vararg events: EventResult) = LibraryState("okhttp3-ws", present = true, installFailure = null, events = events.toList())

    private fun all(): List<String> = buildList {
        addAll(hooksOff)
        hooksOff.forEach { add(Reasons.offBecauseHooks(Reasons.NO_TRAFFIC, it)) }
        add(Reasons.partOffBecauseHooks(Reasons.LIBRARY_NOT_CAPTURED))
        add(Reasons.scriptNotApplied("abc", Reasons.scriptHooksOff(hooksOff[1])))
        add(Reasons.scriptNotApplied("abc", Reasons.scriptDoesNotCompile("unexpected token")))
        listOf(Reasons.WINDOWS, Reasons.TOASTS, Reasons.ELEMENTS, Reasons.INPUT, Reasons.APP_SOURCE, Reasons.NAVIGATION).forEach {
            add(Reasons.offBecauseHooks(it, hooksOff[1]))
            add(Reasons.partOffBecauseHooks(it))
            add("$it: ${Reasons.SOURCE_FAILED}")
        }
        add(Reasons.JOURNAL_STARTING)
        add(Reasons.CHANNEL_FAILED)
        add(Reasons.STATUS_UNREADABLE)
        add(Reasons.composeToolingAbsent(DegradationCodes.COMPOSE_TOOLING_ABSENT))
        add(Reasons.composeToolingSkew(DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW))

        // Install results and library coverage.
        add(Install.ClassAbsent(socket).describe())
        add(Install.MethodAbsent(socket, sig.method, sig.descriptor).describe())
        add(Install.NotHookable(socket, sig.method, sig.descriptor, "abstract").describe())
        add(Install.refused("slicer: x\u001fRetransformClasses: JVMTI_ERROR_X").describe())
        add(Install.refused("\u001fRetransformClasses: JVMTI_ERROR_X").describe())
        val ok = EventResult.of("connections", sig.copy(method = "connect"), Install.Installed)
        val absent = EventResult.of("failures", sig, Install.MethodAbsent(socket, sig.method, sig.descriptor))
        val refused = EventResult.of("close", sig, Install.refused("\u001fRetransformClasses: JVMTI_ERROR_X"))
        listOf(lib(ok, absent), lib(absent), lib(ok, refused), LibraryState("x", false, null),
            LibraryState("x", true, Reasons.LIBRARY_SETUP_FAILED, installFailureDetail = "IllegalStateException: boom"))
            .forEach { add(LibraryCoverage.of(it).reason!!) }

        // The network capability, as each case composes it.
        val bounds = StoreBounds()
        add(NetworkCapability.status(hooksOff[1], listOf(lib(ok)), bounds).reason!!)
        add(NetworkCapability.status(hooksOff[1], listOf(lib(ok)), bounds).libraries!!.single().reason!!)
        add(NetworkCapability.status(null, listOf(lib(ok, absent)), bounds).reason!!)
        add(NetworkCapability.status(null, emptyList(), bounds, ScriptSummary("abc", 1, unavailable = Reasons.scriptHooksOff(hooksOff[1]))).reason!!)
    }

    private val raw = listOf(
        Regex("JVMTI_ERROR"), Regex("""\b[A-Z][A-Za-z]*(Exception|Error)\b:"""), Regex("not attached yet"),
        Regex("""^(failed|error|not started|unknown)$""", RegexOption.IGNORE_CASE), Regex("hooks unavailable:"),
    )
    private val saysWhat = Regex(
        """not (captured|recorded|read|applied|in|shown|a debuggable|used)|without|nothing is captured|no traffic|cannot|could not|did not|have not|has not|need|does not|is (abstract|native)|refused""",
    )

    @Test
    fun `every reason says what is not seen, without raw codes`() {
        val reasons = all()
        println("Reasons (${reasons.size}):\n" + reasons.joinToString("\n") { "- $it" })
        val problems = reasons.flatMap { r ->
            listOfNotNull(
                raw.firstOrNull { it.containsMatchIn(r) }?.let { "raw text (${it.pattern}): $r" },
                if (!saysWhat.containsMatchIn(r)) "does not say what is not seen: $r" else null,
            )
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `hook-related reasons that have an action name it`() {
        val actions = Regex("""run its debug build|rebuild|restart|ask again|update DTA|set it again|add debugImplementation|same Compose BOM|device log""")
        for (r in hooksOff + Reasons.CHANNEL_FAILED) assertTrue(actions.containsMatchIn(r) || "API" in r, "no action: $r")
    }
}
