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

    fun click(id: String, seq: Long, mono: Long, wall: Long, target: Target, via: String, action: InputAction = InputAction.CLICK): TimelineEvent {
        val text = if (target.secure) null else target.text?.takeIf { it.isNotBlank() }?.take(Bounds.FACET_VALUE)
        val key = listOfNotNull(target.name, target.className).joinToString("|")
        val verb = if (action == InputAction.TAP) "tap" else "click"
        return TimelineEvent(
            id, seq, mono, wall, Sources.INPUT, Kinds.CLICK,
            title = listOfNotNull(verb, target.name ?: target.className, text?.let { "\"$it\"" }).joinToString(" "),
            input = InputPayload(action, ElementRef(key = key, role = target.role), text, target.windowId, via),
            completeness = if (target.secure) listOf(DegradationMarker("input", "${DegradationCodes.UI_SECURE_WINDOW}: the element's text is not recorded")) else emptyList(),
        )
    }

    fun back(id: String, seq: Long, mono: Long, wall: Long, via: String) =
        TimelineEvent(id, seq, mono, wall, Sources.INPUT, Kinds.BACK, title = "back", input = InputPayload(InputAction.BACK, via = via))

    /** A back handed on from the activity to the dispatcher is one back, not two. */
    fun recordBack(insideKind: String?): Boolean = insideKind != Kinds.BACK
}
