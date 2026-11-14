package io.yamsergey.dta.sidekick.input

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.timeline.Bounds
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.ElementRef

/** Clicks and back as timeline events (feature 016 US7): what was clicked, never generated. */
object InputEvents {
    /** The element a click landed on, as the recorder read it. */
    data class Target(
        val name: String?,
        val className: String,
        val text: String?,
        val role: String?,
        val secure: Boolean,
        val windowId: String?,
    )

    /**
     * Feature 064: the pressed composable's chain as read at the tap. [frames] null when the read
     * was cut ([cut] says why); [partial] when the screen above a subcomposition was not matched.
     */
    data class TapChain(val frames: List<io.yamsergey.dta.protocol.layout.Frame>?, val partial: Boolean, val cut: String?, val mainThreadMs: Long)

    fun click(id: String, seq: Long, mono: Long, wall: Long, target: Target, via: String, action: InputAction = InputAction.CLICK, chain: TapChain? = null): TimelineEvent {
        val text = if (target.secure) null else target.text?.takeIf { it.isNotBlank() }?.take(Bounds.FACET_VALUE)
        val key = listOfNotNull(target.name, target.className).joinToString("|")
        val verb = if (action == InputAction.TAP) "tap" else "click"
        return TimelineEvent(
            id, seq, mono, wall, Sources.INPUT, Kinds.CLICK,
            title = listOfNotNull(verb, target.name ?: target.className, text?.let { "\"$it\"" }).joinToString(" "),
            input = InputPayload(action, ElementRef(key = key, role = target.role), text, target.windowId, via, chain?.frames, chain?.mainThreadMs),
            completeness = listOfNotNull(
                DegradationMarker("input", "${DegradationCodes.UI_SECURE_WINDOW}: the element's text is not recorded").takeIf { target.secure },
                chain?.cut?.let { DegradationMarker("input", "${DegradationCodes.TAP_CHAIN_UNAVAILABLE}: $it") },
                DegradationMarker("input", "${DegradationCodes.TAP_CHAIN_PARTIAL}: the chain starts at the subcomposition").takeIf { chain?.partial == true },
            ),
        )
    }

    /**
     * Feature 064: one scroll gesture ([g], from [ScrollTracker]) on [target], with the container's
     * chain read after it settled. Its time is the drag's start; [wallStart] is that start's wall time.
     */
    fun scroll(id: String, seq: Long, wallStart: Long, g: ScrollTracker.Gesture, target: Target, chain: TapChain?): TimelineEvent {
        val key = listOfNotNull(target.name, target.className).joinToString("|")
        val name = target.name ?: target.className
        return TimelineEvent(
            id, seq, g.startMono, wallStart, Sources.INPUT, io.yamsergey.dta.protocol.timeline.Kinds.SCROLL,
            title = listOfNotNull("scroll", g.direction?.name?.lowercase(), name).joinToString(" "),
            input = InputPayload(
                InputAction.SCROLL, ElementRef(key = key, role = target.role), null, target.windowId, "compose", chain?.frames, chain?.mainThreadMs,
                io.yamsergey.dta.protocol.timeline.ScrollInfo(g.endMono, g.direction, g.from, g.to, g.fling, g.stoppedByTouch),
            ),
            completeness = listOfNotNull(
                chain?.cut?.let { DegradationMarker("input", "${DegradationCodes.TAP_CHAIN_UNAVAILABLE}: $it") },
                DegradationMarker("input", "${DegradationCodes.TAP_CHAIN_PARTIAL}: the chain starts at the subcomposition").takeIf { chain?.partial == true },
            ),
        )
    }

    fun back(id: String, seq: Long, mono: Long, wall: Long, via: String) =
        TimelineEvent(id, seq, mono, wall, Sources.INPUT, Kinds.BACK, title = "back", input = InputPayload(InputAction.BACK, via = via))

    /** A back handed on from the activity to the dispatcher is one back, not two. */
    fun recordBack(insideKind: String?): Boolean = insideKind != Kinds.BACK
}
