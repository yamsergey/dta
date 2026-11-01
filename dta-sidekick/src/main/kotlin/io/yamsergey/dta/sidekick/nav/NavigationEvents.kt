package io.yamsergey.dta.sidekick.nav

import io.yamsergey.dta.protocol.timeline.Bounds
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NavPayload
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.FacetState
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/**
 * Navigation as a source (feature 016 US5, research R9): a destination (AndroidX Navigation)
 * or a back stack's top key (Navigation 3) becomes facets and a navigate or back event, by
 * whether the stack grew or shrank. One source among others; nothing depends on it.
 */
class NavigationEvents(
    private val buffer: TimelineBuffer,
    private val facets: FacetState,
    private val mono: () -> Long,
    private val wall: () -> Long,
    private val scope: () -> CauseLink?,
) {
    private var depth: Int? = null

    @Synchronized
    fun destination(library: String, destination: String, args: Map<String, String>, depth: Int?) {
        val shown = args(args)
        val changes = listOfNotNull(
            facets.change(Facets.NAV_DESTINATION, destination, null),
            facets.change(Facets.NAV_ARGS, shown.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { null }, null),
        )
        if (changes.isEmpty()) return
        emit(library, destination, shown, depth, changes)
    }

    @Synchronized
    fun key(key: String, depth: Int?) {
        val change = facets.change(Facets.NAV_KEY, key, null) ?: return
        emit(LIBRARY_3, key, emptyMap(), depth, listOf(change))
    }

    private fun emit(library: String, destination: String, args: Map<String, String>, depth: Int?, changes: List<FacetChange>) {
        val before = this.depth
        this.depth = depth
        val back = before != null && depth != null && depth < before
        val action = if (back) "back" else "navigate"
        val e = buffer.append { id, seq ->
            TimelineEvent(
                id, seq, mono(), wall(), Sources.NAV, if (back) Kinds.NAV_BACK else Kinds.NAV_NAVIGATE,
                title = "$action → $destination" + args.entries.joinToString("") { " ${it.key}=${it.value}" },
                nav = NavPayload(library, action, destination, args, depth),
                facets = changes,
                causes = listOfNotNull(scope()),
            )
        }
        facets.attribute(changes.map { it.name }, e.id)
    }

    companion object {
        const val LIBRARY = "navigation"
        const val LIBRARY_3 = "navigation3"

        fun args(args: Map<String, String>): Map<String, String> =
            args.entries.take(Bounds.ATTRS).associate { (k, v) -> k.take(Bounds.NAME) to v.take(Bounds.FACET_VALUE) }

        /** A key's own text when it has one (a data class), else its class's name — never an object's identity. */
        fun keyName(key: Any, fallback: String): String {
            val text = runCatching { key.toString() }.getOrNull()
            val identity = text == null || Regex("@[0-9a-f]+$").containsMatchIn(text)
            return (if (identity) fallback else text!!).take(Bounds.FACET_VALUE)
        }
    }
}
