package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.ui.UiEventText
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.CurrentState
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.StateGraph
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.SourceState
import java.util.Locale

/**
 * How the timeline reads (feature 016): one line per event for the CLI and watch, the chain of
 * causes, the current state, the state graph, and the one-line summary an agent gets at the end
 * of every tool answer. Shared, so every surface says the same.
 */
object TimelineEventText {
    const val SUMMARY_MAX = UiEventText.SUMMARY_MAX

    /** What happened, without time or causes: `click delete_button "Delete"`. */
    fun describe(e: TimelineEvent): String {
        val base = when {
            e.ui != null && e.source != Sources.TIMELINE -> UiEventText.line(e.ui!!)
            e.input != null -> input(e)
            e.network != null -> network(e)
            e.nav != null -> "${e.nav!!.action} → ${e.nav!!.destination}" + args(e.nav!!.args)
            e.app != null -> e.app!!.name + args(e.app!!.attrs)
            e.kind == Kinds.PROCESS_ENDED -> "process ended"
            e.kind == Kinds.GAP -> "gap  " + listOfNotNull(
                e.gapCount?.let { "$it events dropped" },
                e.completeness.firstOrNull()?.reason?.substringAfter(": ", "")?.ifEmpty { null },
            ).joinToString(": ")
            else -> e.title.ifEmpty { e.kind }
        }
        val facets = e.facets.joinToString("") { c -> "  · ${c.name} ${c.previous ?: "—"} → ${c.value ?: "—"}" }
        return base + facets
    }

    /** One line: source, what happened, and its known causes (titles from [lookup] when it has them). */
    fun line(e: TimelineEvent, lookup: (String) -> TimelineEvent? = { null }): String {
        val causes = when {
            e.causes.isNotEmpty() -> "  ← " + e.causes.joinToString(", ") { c ->
                val from = lookup(c.from)?.let(::short) ?: c.from
                "$from (${basis(c.basis)})"
            }
            rootless(e) -> "  · no known cause"
            else -> ""
        }
        return "${e.source.padEnd(8)} ${describe(e)}$causes"
    }

    /** The chain of causes or effects, indented by link, each with its basis. */
    fun chain(c: CauseChain): String {
        val byId = c.nodes.associateBy { it.id }
        val children = c.links.groupBy { if (c.direction == ChainDirection.CAUSES) it.to else it.from }
        val out = StringBuilder()
        val seen = HashSet<String>()
        fun walk(id: String, depth: Int, via: String?) {
            val e = byId[id] ?: return
            val unknown = if (id in c.noKnownCause) {
                "  · no known cause" + (c.inferredCandidates[id]?.takeIf { !c.inferredIncluded }?.let { " ($it inferred, ask with --inferred)" } ?: "")
            } else {
                ""
            }
            out.append("  ".repeat(depth)).append(via?.let { "$it  " } ?: "").append("${e.id}  ${e.source}  ${describe(e)}").append(unknown).append('\n')
            if (!seen.add(id)) return
            for (l in children[id].orEmpty()) {
                val next = if (c.direction == ChainDirection.CAUSES) l.from else l.to
                val arrow = if (c.direction == ChainDirection.CAUSES) "←" else "→"
                val detail = l.link.detail?.let { ": $it" } ?: ""
                walk(next, depth + 1, "$arrow ${basis(l.link.basis)} ${l.link.rule}$detail")
            }
        }
        walk(c.root.id, 0, null)
        for (cut in c.cuts) out.append("cut at ${cut.at}: ${cut.reason.name.lowercase().replace('_', ' ')}${cut.detail?.let { " ($it)" } ?: ""}\n")
        return out.toString().trimEnd()
    }

    /** Facets grouped by source, then the sources that are not on. */
    fun state(s: CurrentState): String {
        val lines = ArrayList<String>()
        for ((source, facets) in s.facets.groupBy { it.source }) {
            lines += source
            for (f in facets) {
                val stale = f.stale?.let { "  (stale: ${it.reason})" } ?: ""
                lines += "  ${f.name} = ${f.value ?: "—"}$stale"
            }
        }
        if (s.facets.isEmpty()) lines += "No facets reported yet."
        for (d in s.disagreements) lines += "disagree: ${d.facets.joinToString()} — ${d.detail}"
        for (src in s.sources.filter { it.state != SourceState.ON }) {
            lines += "${src.name} ${src.state.name.lowercase()}: ${src.reason ?: "no reason given"}"
        }
        return lines.joinToString("\n")
    }

    /** States with entries and time held, then transitions with their triggers. */
    fun graph(g: StateGraph): String {
        val label = g.nodes.associate { it.state.id to it.state.label }
        val lines = ArrayList<String>()
        lines += "States by ${g.facets.joinToString(", ").ifEmpty { "no facets" }}:"
        for (n in g.nodes) {
            lines += "  ${if (n.current) "▸" else " "} ${n.state.label}  ×${n.entries}, held ${seconds(n.heldMs)} s  [${n.state.id}]"
        }
        lines += "Transitions:"
        for (t in g.transitions) {
            val why = when {
                t.noKnownCause -> "no known cause"
                t.causes.isNotEmpty() -> t.causes.joinToString(", ") { "${basis(it.basis)} ${it.rule}" }
                else -> "by the user"
            }
            val arrow = if (t.selfLoop) "↺" else "→"
            lines += "  ${label[t.from]} $arrow ${label[t.to]}  ×${t.count}  trigger ${t.triggers.firstOrNull() ?: "—"} ($why)"
        }
        if (g.transitions.isEmpty()) lines += "  none yet"
        val unknown = g.transitions.count { it.noKnownCause && !it.selfLoop }
        if (unknown > 0) lines += "$unknown transition${if (unknown == 1) "" else "s"} without a known cause."
        g.unavailable.takeIf { it.isNotEmpty() }?.let { lines += "Not available: ${it.joinToString()}." }
        return lines.joinToString("\n")
    }

    /** "Since your last call: N events — …." or null when there were none. */
    fun summary(events: List<TimelineEvent>): String? {
        val items = items(events)
        if (items.isEmpty()) return null
        val head = "Since your last call: ${items.size} event${if (items.size == 1) "" else "s"} — "
        val shown = ArrayList<String>()
        for ((i, item) in items.withIndex()) {
            val rest = items.size - i - 1
            val tail = if (rest > 0) ", and $rest more." else "."
            if ((head + (shown + item).joinToString(", ") + tail).length > SUMMARY_MAX && shown.isNotEmpty()) break
            shown += item
        }
        val left = items.size - shown.size
        return head + shown.joinToString(", ") + if (left > 0) ", and $left more." else "."
    }

    private fun items(events: List<TimelineEvent>): List<String> {
        val ui = events.mapNotNull { it.ui }
        val answered = events.filter { it.network != null && it.network!!.phase != NetworkPhase.REQUEST }.map { it.network!!.entryId }.toSet()
        return events.mapNotNull { e ->
            val item = when {
                e.ui != null && e.source != Sources.TIMELINE -> UiEventText.item(e.ui!!, ui)
                e.input != null -> input(e)
                e.network != null -> if (e.network!!.phase == NetworkPhase.REQUEST && e.network!!.entryId in answered) null else network(e)
                e.nav != null -> "${e.nav!!.action} → ${e.nav!!.destination}"
                e.app != null -> "app: ${e.app!!.name}"
                e.kind == Kinds.PROCESS_ENDED -> "the app's process ended"
                e.kind == Kinds.GAP -> "a gap" + (e.gapCount?.let { " ($it events lost)" } ?: "")
                else -> null
            }
            val facets = e.facets.filter { !it.name.startsWith("ui.") }.joinToString(", ") { "${it.name} ${it.previous ?: "—"} → ${it.value ?: "—"}" }
            listOfNotNull(item, facets.ifEmpty { null }).joinToString(", ").ifEmpty { null }
        }
    }

    private fun input(e: TimelineEvent): String {
        val i = e.input!!
        if (i.action == InputAction.BACK) return "back"
        val name = i.element?.key?.substringAfterLast('/')?.substringBefore('|')?.takeIf { it.isNotBlank() }
        val text = i.text?.let { "\"$it\"" }
        val verb = if (i.action == InputAction.TAP) "tap" else "click"
        return listOfNotNull(verb, name, text).joinToString(" ")
    }

    private fun network(e: TimelineEvent): String {
        val n = e.network!!
        val what = listOfNotNull(n.method, n.path ?: n.host).joinToString(" ")
        return when (n.phase) {
            NetworkPhase.REQUEST -> what
            NetworkPhase.RESPONSE -> "$what → ${n.status ?: "?"}" + (n.durationMs?.let { " in $it ms" } ?: "")
            NetworkPhase.FAILED -> "$what failed" + (n.error?.let { ": $it" } ?: "")
        }
    }

    private fun short(e: TimelineEvent): String = when {
        e.input != null -> input(e)
        e.network != null -> network(e).substringBefore(" in ")
        e.app != null -> e.app!!.name
        e.nav != null -> "${e.nav!!.action} ${e.nav!!.destination}"
        e.ui != null -> e.kind.substringAfter('/')
        else -> e.id
    }

    private fun rootless(e: TimelineEvent) = e.source != Sources.INPUT && e.source != Sources.TIMELINE && e.kind != Kinds.ui("window-settled")

    private fun basis(b: Basis) = b.name.lowercase()

    private fun args(map: Map<String, String>) = if (map.isEmpty()) "" else " " + map.entries.joinToString(" ") { "${it.key}=${it.value}" }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f", ms / 1000.0)
}
