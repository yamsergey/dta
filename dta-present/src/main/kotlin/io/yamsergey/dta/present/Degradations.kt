package io.yamsergey.dta.present

import io.yamsergey.dta.protocol.layout.DegradationCodes as Codes

/**
 * What each degradation code means for the person: what is missing, why, and the next step
 * (constitution III — a statement names its cause and what to do). One table for every client
 * that renders a code: the plugin, the CLI.
 */
object Degradations {
    private val TEXT = mapOf(
        Codes.COMPOSE_TOOLING_ABSENT to
            "Compose screens show the semantics tree only: the app has no Compose tooling data, so composable names, " +
            "source lines and layout containers are missing. Add debugImplementation(\"androidx.compose.ui:ui-tooling\") " +
            "at the app's Compose version (the Compose BOM keeps them equal) and run the debug build again.",
        Codes.COMPOSE_TOOLING_VERSION_SKEW to
            "Compose screens show the semantics tree only: the app's Compose tooling version differs from its Compose UI " +
            "version (or could not be read), and DTA reads tooling data only when they match. Take ui-tooling and Compose UI " +
            "from the same Compose BOM and run the debug build again.",
        Codes.COMPOSE_ENRICHMENT_FAILED to
            "Reading Compose tooling data failed for a window, so it shows the semantics tree only. Capture again; " +
            "if it keeps failing, report it with the app's Compose version.",
        Codes.COMPOSE_FIRST_FRAME_PENDING to
            "A Compose window had not drawn its first frame yet, so it has no content. Capture again once the screen is shown.",
        Codes.DEPTH_CAP_REACHED to
            "The view tree is deeper than the capture limit; nodes below it are not captured. Filter to the part of the screen you need.",
        Codes.WINDOW_LOST_MID_CAPTURE to
            "A window closed while it was being captured, so it is missing. Capture again.",
        Codes.LEGACY_ROOT_DISCOVERY to
            "Windows were found through a reflection fallback for this Android version; a dialog or popup may be missing.",
        Codes.COMPOSE_DETAIL_REDUCED to
            "Less Compose detail than requested was delivered, because the app's Compose tooling data could not provide it. " +
            "The other statements on this capture say why.",
        Codes.COMPOSE_SOURCE_INFO_MISSING to
            "Compose source lines are missing: the app was compiled without Compose source information. Run the debug build; " +
            "if the project sets composeCompiler { includeSourceInformation = false }, turn it on.",
        Codes.FILTER_NEEDS_RICHER_DETAIL to
            "Nothing matched the type filter at this detail level, where layout containers are not captured. " +
            "Ask for detail layout or composition.",
        Codes.NETWORK_HOOKS_UNAVAILABLE to
            "Network capture could not attach in this process, so no traffic is captured. Run the app's debug build; " +
            "the capability report says why it failed.",
        Codes.NETWORK_LIBRARY_UNAVAILABLE to
            "A network library the app uses is not captured at all, so its traffic is missing. The capability report names the library and why.",
        Codes.NETWORK_LIBRARY_PARTIAL to
            "A network library is captured only in part: some of its events (for example handshake headers or failures) are missing, " +
            "the rest is captured. The capability report names the events and why.",
        Codes.NETWORK_HISTORY_EVICTED to
            "Older entries were removed by the history size limit.",
        Codes.NETWORK_HISTORY_NOT_PERSISTED to
            "The app could not write its network history to storage, so history is kept in memory only and ends with the process.",
        Codes.NETWORK_EVENTS_DROPPED to
            "Some entries arrived faster than the app could save them to storage: they are shown now, but will be missing " +
            "after the app restarts.",
        Codes.NETWORK_INITIATOR_MISSING to
            "Some entries have no initiator stack: they were captured without one, for example before the process restarted.",
        Codes.NETWORK_BODY_INCOMPLETE to
            "Some bodies were captured only in part: the app stopped reading them, or they passed the capture size limit. " +
            "The entries say which and how much was seen.",
        Codes.NETWORK_REDACTION_WITHHELD to
            "Part of the content was withheld by redaction. Turn redaction off to see it.",
        Codes.NETWORK_SCRIPT_UNAVAILABLE to
            "The network script is not applied in this process, so traffic passes unchanged. The capability report " +
            "says why: DTA's hooks are off, or the saved script no longer compiles.",
        Codes.NETWORK_SCRIPT_NOT_PERSISTED to
            "The network script could not be saved on the device, so it stops applying when the app restarts. Set it again after a restart.",
        Codes.SCREEN_BLOCKED to
            "The app shows a secure window, and the system blacks it out in screen captures, so there is no picture. The tree is complete; " +
            "open a screen without the secure flag to see the picture.",
        Codes.SCREEN_NOT_TAKEN to
            "The screen image could not be taken (see the reason); the tree is complete. Capture again.",
        Codes.SCREEN_LOCKED to
            "The lock screen is showing: the picture shows it, not the app. Unlock the device and capture again.",
        Codes.SCREEN_OFF to
            "The device's screen is off, so there is no picture. Wake the device and capture again.",
        Codes.SCREEN_CHANGED_DURING_CAPTURE to
            "The screen changed while the tree was captured (see what changed), so the picture and the tree may differ there. " +
            "Capture again when the screen is still; an animation that never stops always reports its area.",
        Codes.SCREEN_SCALED to
            "The picture and the tree's bounds differ by a uniform scale, which DTA applied. Outlines are placed with it.",
        Codes.SCREEN_NOT_ALIGNED to
            "The tree's bounds do not match the windows on the picture (see the reason), so elements are not outlined. Capture again; " +
            "if it persists, report it with the device model and Android version.",
        Codes.SCREEN_DISPLAY_UNKNOWN to
            "The display the app is on could not be determined, so the picture may show another display.",
        Codes.UI_NOT_SETTLED to
            "The window went before it settled: its content is what it held when it was added.",
        Codes.UI_SECURE_WINDOW to
            "The window is secure (the app blocks screen capture), so its texts are not recorded.",
        Codes.UI_CONTENT_TRUNCATED to
            "The content summary is cut at its bound; the number of elements left out is given.",
        Codes.UI_EVENTS_DROPPED to
            "Events were lost: the app's journal buffer overflowed, or a reader fell behind. The count or interval is given.",
        Codes.UI_BACKED_OFF to
            "The app was busy, so elements inside windows were not compared for a while; ones that came and went then may be missing.",
        Codes.UI_NOT_CONNECTED to
            "DTA was not connected to the app for this interval; anything beyond the app's own buffer is missing.",
        Codes.UI_FIELD_UNREADABLE to
            "The framework did not let DTA read this value on this Android version; the event is recorded without it.",
        Codes.UI_HISTORY_EVICTED to
            "Older events were dropped from the computer's journal to stay within its size; the reader was behind them.",
        Codes.TIMELINE_REJECTED_CAUSE to
            "A cause the app declared was not kept: it pointed forwards in time or across a restart of the app.",
        Codes.TIMELINE_VALUE_CUT to
            "A value was longer than DTA keeps, so it was cut at the bound.",
        Codes.TIMELINE_RATE_DROPPED to
            "The app reported its own events faster than DTA takes them; the excess was dropped and counted.",
        Codes.TIMELINE_CHAIN_CUT to
            "The chain of causes stops here: the event is no longer kept, was never reported, or is from an earlier run of the app.",
        Codes.STATE_FACET_UNAVAILABLE to
            "A chosen facet has no source that reports it now, so states are grouped without it.",
        Codes.STATE_WINDOW_PARTIAL to
            "Older events were dropped from the timeline, so the states and transitions before them are missing.",
        Codes.STATE_SHORT_MERGED to
            "States held for less than the chosen minimum were merged into the next state.",
    )

    /** The sentence for [code]; null for a code this client does not know. */
    fun explain(code: String?): String? = code?.let { TEXT[it] }

    /** The first known code that [text] mentions, as a capability reason may. */
    fun codeIn(text: String?): String? = text?.let { t -> TEXT.keys.firstOrNull { it in t } }
}
