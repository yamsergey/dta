package io.yamsergey.dta.protocol.timeline

import io.yamsergey.dta.protocol.network.MaskedLocation
import io.yamsergey.dta.protocol.network.RedactionInfo
import io.yamsergey.dta.protocol.network.Redactor
import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.UiEvent

/**
 * Decision 2026-11-22 (D-003): the timeline, the tap chain, the app's state and their summaries
 * are redacted as the network outputs are — with the one [Redactor], on request (`redact`), and
 * saying where. Free text (titles, errors, routes, element and toast text, cause details, facet
 * values) goes through [Redactor.text]; a value whose name is sensitive (a navigation argument,
 * an app event attribute, a facet) is masked whole. Ids and times are kept, so an answer
 * redacted and one not name the same events and states.
 */
object TimelineRedaction {

    private class Masks {
        val masked = mutableListOf<MaskedLocation>()
        fun text(value: String?, at: String): String? = value?.let { Redactor.text(it, at).also { (_, m) -> masked += m }.first }
        fun named(name: String, value: String?, at: String): String? = when {
            value == null -> null
            Redactor.isSensitive(name) && Redactor.isMasked(value) -> value
            Redactor.isSensitive(name) -> Redactor.hide(value, "$at $name", masked)
            else -> text(value, "$at $name")
        }
        fun map(values: Map<String, String>, at: String): Map<String, String> =
            values.mapValues { (k, v) -> named(k, v, at)!! }
        fun info() = RedactionInfo(masked = masked)
    }

    fun event(event: TimelineEvent): Pair<TimelineEvent, RedactionInfo> = Masks().let { m -> event(event, m) to m.info() }

    private fun event(e: TimelineEvent, m: Masks): TimelineEvent {
        val at = "event ${e.id}"
        return e.copy(
            title = m.text(e.title, "$at title")!!,
            ui = e.ui?.let { ui(it, "$at ui", m) },
            input = e.input?.let { it.copy(text = m.text(it.text, "$at input text")) },
            network = e.network?.let { it.copy(path = m.text(it.path, "$at network path"), error = m.text(it.error, "$at network error")) },
            nav = e.nav?.let { it.copy(destination = m.text(it.destination, "$at nav destination")!!, args = m.map(it.args, "$at nav arg")) },
            app = e.app?.let { it.copy(attrs = m.map(it.attrs, "$at app attr")) },
            facets = e.facets.map { f -> f.copy(value = m.named(f.name, f.value, "$at facet"), previous = m.named(f.name, f.previous, "$at facet previous")) },
            causes = e.causes.map { c -> c.copy(detail = m.text(c.detail, "$at cause detail")) },
        )
    }

    private fun ui(ui: UiEvent, at: String, m: Masks): UiEvent =
        ui.copy(text = m.text(ui.text, "$at text"), content = ui.content?.let { content(it, "$at content", m) })

    private fun content(c: ContentSummary, at: String, m: Masks): ContentSummary = c.copy(
        elements = c.elements.mapIndexed { i, el -> el.copy(text = m.text(el.text, "$at[$i] text"), description = m.text(el.description, "$at[$i] description")) },
    )

    private fun events(list: List<TimelineEvent>, m: Masks) = list.map { event(it, m) }

    fun page(p: TimelinePage): TimelinePage = Masks().let { m -> p.copy(events = events(p.events, m), redaction = m.info()) }

    fun chain(c: CauseChain): CauseChain = Masks().let { m ->
        c.copy(
            root = event(c.root, m),
            nodes = events(c.nodes, m),
            links = c.links.map { l -> l.copy(link = l.link.copy(detail = m.text(l.link.detail, "link ${l.from}→${l.to} detail"))) },
            cuts = c.cuts.map { cut -> cut.copy(detail = m.text(cut.detail, "cut ${cut.at} detail")) },
            redaction = m.info(),
        )
    }

    /** What a sequence is built from, so its rows and its query line read the redacted text (decision 1). */
    fun sequenceInput(events: List<TimelineEvent>, sockets: List<io.yamsergey.dta.protocol.network.NetworkEntry>): Triple<List<TimelineEvent>, List<io.yamsergey.dta.protocol.network.NetworkEntry>, RedactionInfo> {
        val m = Masks()
        val e = events(events, m)
        val s = sockets.map { entry -> Redactor.entry(entry).also { (_, info) -> m.masked += info.masked }.first }
        return Triple(e, s, m.info())
    }

    fun current(c: CurrentState): CurrentState = Masks().let { m ->
        c.copy(
            facets = c.facets.map { f -> f.copy(value = m.named(f.name, f.value, "facet")) },
            disagreements = c.disagreements.map { d -> d.copy(detail = m.text(d.detail, "disagreement")!!) },
            redaction = m.info(),
        )
    }

    private fun state(s: StateRef, m: Masks): StateRef {
        val values = s.values.mapValues { (k, v) -> m.named(k, v, "state ${s.id} facet") }
        // The label is the values joined (StateGrouper); one written otherwise is free text.
        val joined = s.values.values.joinToString(LABEL_SEPARATOR) { it ?: "—" }
        val label = if (s.label == joined) values.values.joinToString(LABEL_SEPARATOR) { it ?: "—" } else m.text(s.label, "state ${s.id} label")!!
        return s.copy(values = values, label = label)
    }

    private fun transition(t: Transition, m: Masks) = t.copy(causes = t.causes.map { c -> c.copy(detail = m.text(c.detail, "transition ${t.from}→${t.to} cause detail")) })

    fun graph(g: StateGraph): StateGraph = Masks().let { m ->
        g.copy(
            nodes = g.nodes.map { n -> n.copy(state = state(n.state, m)) },
            transitions = g.transitions.map { transition(it, m) },
            redaction = m.info(),
        )
    }

    fun path(p: StatePath): StatePath = Masks().let { m -> p.copy(steps = p.steps.map { transition(it, m) }, redaction = m.info()) }

    /** How StateGrouper joins a state's values into its label. */
    const val LABEL_SEPARATOR = " · "
}
