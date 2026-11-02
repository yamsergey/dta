package io.yamsergey.dta.sidekick.capability

/**
 * Every capability reason the sidekick states, in one place (feature 019, research R6). A reason
 * says what is not seen, why, and what to do when anything can be done. Raw causes (runtime
 * codes, exception text) never make up a reason: they go to a `detail` field or the device log.
 * Pure; [ReasonTextTest] checks every text here against the rules.
 */
object Reasons {

    // --- Why DTA's hooks are off (one sentence, reused by every capability that needs hooks).

    fun hooksNeedApi28(sdk: Int) = "they need Android 9 (API 28) or later, and this device runs API $sdk"

    const val NOT_DEBUGGABLE =
        "the app is not a debuggable build; run its debug build, where DTA belongs (debugImplementation)"

    fun agentMissing(abis: List<String>) =
        "DTA's native agent is not in the app for this device's ABIs (${abis.joinToString(", ")}); rebuild the app with DTA for these ABIs"

    const val AGENT_REFUSED = "Android did not let DTA's agent attach to the app; the device log (tag DtaAgent) says why"
    const val SHIM_FAILED = "DTA could not load its hook support into the app; restarting the app may help"
    const val HOOKS_SETUP_FAILED = "DTA could not set them up; restarting the app may help"
    const val HOOKS_STARTING = "they have not started yet, the app is still starting; ask again in a moment"

    /** [lost] (what is not seen) because hooks are off, with why ([hooks], one of the above). */
    fun offBecauseHooks(lost: String, hooks: String) = "$lost, because DTA's hooks are off: $hooks"

    /** A part (a library, a source) whose capability already says why hooks are off. */
    fun partOffBecauseHooks(lost: String) = "$lost: DTA's hooks are off in this app (the capability says why)"

    // --- Network.

    const val NO_TRAFFIC = "no traffic is captured"
    const val LIBRARY_NOT_CAPTURED = "not captured"
    const val LIBRARY_SETUP_FAILED = "DTA could not set up its capture of this library; restarting the app may help"

    fun scriptNotApplied(id: String, why: String) = "network script $id is not applied, so traffic passes unchanged: $why"
    fun scriptHooksOff(why: String) = "DTA's hooks are off: $why"
    fun scriptDoesNotCompile(message: String?) =
        "the saved script no longer compiles (${message ?: "no message"}); set it again or clear it"

    // --- UI journal and timeline sources.

    const val WINDOWS = "window opens and closes are not recorded"
    const val TOASTS = "toasts are not recorded"
    const val ELEMENTS = "element changes are not recorded"
    const val INPUT = "clicks and back are not recorded"
    const val APP_SOURCE = "the app's own facets and events are not read"
    const val NAVIGATION = "navigation is not read"
    const val JOURNAL_STARTING = "the UI journal has not started yet, the app is still starting; ask again in a moment"
    const val SOURCE_FAILED = "DTA could not start it; the device log (tag DtaTimeline) has the cause, and restarting the app may help"

    // --- Channel and registry.

    const val CHANNEL_FAILED =
        "the computer cannot connect to the app: the app could not open DTA's local socket; restart the app"
    const val STATUS_UNREADABLE = "DTA could not work out this capability's status; the device log has the cause"

    // --- Layout.

    fun composeNamesMissing(why: String) = "composables are shown without their names, source lines and parameters: $why"

    /** The code stays in parentheses: clients turn it into their fuller advice (Degradations). */
    fun composeToolingAbsent(code: String) = composeNamesMissing(
        "the app has no Compose tooling data; add debugImplementation(\"androidx.compose.ui:ui-tooling\") at the app's Compose version ($code)",
    )

    fun composeToolingSkew(code: String) = composeNamesMissing(
        "the app's Compose tooling version differs from its Compose UI version; take both from the same Compose BOM ($code)",
    )
}
