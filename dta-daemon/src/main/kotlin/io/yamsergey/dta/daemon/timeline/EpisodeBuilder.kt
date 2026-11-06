package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.Episode
import io.yamsergey.dta.protocol.timeline.EpisodeLane
import io.yamsergey.dta.protocol.timeline.EpisodeLanes
import io.yamsergey.dta.protocol.timeline.EpisodeStep
import io.yamsergey.dta.protocol.timeline.EpisodesPage
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.SocketCount
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource

/**
 * Feature 045: kept timeline events and socket entries into episodes. A tap or back starts one; so
 * does a navigation when the current one already navigated, a new process, or any event after
 * [PAUSE_MS]. A socket message never starts one. Links come only from recorded causes.
 */
class EpisodeBuilder {
    private class Open(val first: TimelineEvent, val start: Boolean) {
        val events = ArrayList<TimelineEvent>()
        val steps = ArrayList<EpisodeStep>()
        var lastMs = first.wallMs
        var gaps = 0
        var trigger: TimelineEvent? = null
        var nav: TimelineEvent? = null
        val ids = HashSet<String>()
        var sent = 0
        var opened = false
        val counts = LinkedHashMap<String, Int>()
    }

    fun build(
        events: List<TimelineEvent>,
        sockets: List<NetworkEntry>,
        sources: List<SourceStatus> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
        withSteps: Boolean = true,
        socketsNote: String? = null,
    ): EpisodesPage {
        val hostLane = hostLanes(events)
        val open = group(events)
        val messages = sockets.filterIsInstance<WsMessageEntry>().sortedBy { it.timeMs }
        val connections = sockets.filterIsInstance<WsConnectionEntry>()
        open.forEach { o -> o.events.forEach { e -> step(e, o, hostLane)?.let { o.steps += it } } }
        addSockets(open, messages, connections)
        val all = open.map { finish(it, withSteps) }
        val lanes = buildList {
            add(EpisodeLane(EpisodeLanes.USER, "You"))
            add(EpisodeLane(EpisodeLanes.APP, "App"))
            hostLane.named.forEach { add(EpisodeLane(EpisodeLanes.host(it), it)) }
            if (hostLane.others) add(EpisodeLane(EpisodeLanes.OTHER, "other hosts"))
            if (sockets.isNotEmpty()) add(EpisodeLane(EpisodeLanes.SOCKET, "socket"))
        }
        return EpisodesPage(all.takeLast(limit), all.size, lanes, messages.size, all.firstOrNull()?.wallMs ?: 0, sources, socketsNote)
    }

    private fun group(events: List<TimelineEvent>): List<Open> {
        val out = ArrayList<Open>()
        var epoch: String? = null
        var seenEpochFirst = false
        // The host samples windows on its own clock, so kept order is not always time order.
        for (e in events.sortedBy { it.wallMs }) {
            // The host's own events (`host:n`, `<session>:h<n>`) belong to no process of the app.
            val own = e.id.startsWith("host:") || e.id.substringAfter(':').startsWith("h")
            val thisEpoch = if (own) epoch else e.id.substringBefore(':')
            if (thisEpoch != epoch) {
                epoch = thisEpoch
                seenEpochFirst = false
            }
            if (e.kind == Kinds.GAP) {
                // Elements are not drawn, so their back-off says nothing about what is.
                if (e.ui?.source != UiSource.ELEMENT) out.lastOrNull()?.let { it.gaps++ }
                continue
            }
            if (!shown(e)) continue
            val current = out.lastOrNull()
            val starts = current == null || !seenEpochFirst || when (e.kind) {
                Kinds.CLICK, Kinds.BACK, Kinds.PROCESS_ENDED -> true
                Kinds.NAV_NAVIGATE, Kinds.NAV_BACK -> current.nav != null || e.wallMs - current.lastMs >= PAUSE_MS
                else -> e.wallMs - current.lastMs >= PAUSE_MS
            }
            val o = if (starts) Open(e, !seenEpochFirst && e.seq <= START_SEQS).also { out += it } else current!!
            seenEpochFirst = true
            o.events += e
            o.ids += e.id
            o.lastMs = e.wallMs
            if (e.kind == Kinds.CLICK || e.kind == Kinds.BACK) o.trigger = o.trigger ?: e
            if (e.kind == Kinds.NAV_NAVIGATE || e.kind == Kinds.NAV_BACK) o.nav = o.nav ?: e
        }
        return out
    }

    private fun shown(e: TimelineEvent): Boolean = when {
        e.kind == Kinds.CLICK || e.kind == Kinds.BACK -> true
        e.kind == Kinds.NAV_NAVIGATE || e.kind == Kinds.NAV_BACK -> true
        e.kind == Kinds.NETWORK_REQUEST || e.kind == Kinds.NETWORK_RESPONSE || e.kind == Kinds.NETWORK_FAILED -> e.network != null
        e.kind == SCREEN_CHANGED -> true
        e.kind == Kinds.PROCESS_ENDED -> true
        e.source == Sources.APP -> e.kind != Kinds.DECLARED_CAUSE
        e.source == Sources.UI -> e.ui?.kind in UI_SHOWN
        else -> false
    }

    private fun linked(e: TimelineEvent, o: Open) =
        e.causes.any { it.basis != Basis.INFERRED && !it.fromUnknown && it.from in o.ids && it.from != e.id }

    private fun step(e: TimelineEvent, o: Open, hosts: Hosts): EpisodeStep? {
        fun note(label: String) =
            EpisodeStep(e.wallMs, EpisodeLanes.APP, label = label, linked = linked(e, o), eventId = e.id)
        return when {
            e.input != null -> EpisodeStep(
                e.wallMs, EpisodeLanes.USER, EpisodeLanes.APP,
                if (e.input!!.action == InputAction.BACK) "back" else "tap ${tapLabel(e, quoted = true)}",
                eventId = e.id,
            )
            e.nav != null -> note("→ " + simpleName(e.nav!!.destination))
            e.network != null -> {
                val n = e.network!!
                val lane = hosts.lane(n.host)
                when (n.phase) {
                    NetworkPhase.REQUEST -> EpisodeStep(
                        e.wallMs, EpisodeLanes.APP, lane, "${n.method ?: "?"} ${n.path ?: ""}".trim(),
                        linked = linked(e, o), eventId = e.id, entryId = n.entryId,
                    )
                    NetworkPhase.RESPONSE -> EpisodeStep(
                        e.wallMs, lane, EpisodeLanes.APP, listOfNotNull(n.status?.toString(), n.durationMs?.let { "$it ms" }).joinToString(" · ").ifEmpty { "response" },
                        back = true, failed = (n.status ?: 0) >= 400, eventId = e.id, entryId = n.entryId,
                    )
                    NetworkPhase.FAILED -> EpisodeStep(
                        e.wallMs, lane, EpisodeLanes.APP, "failed" + (n.error?.let { ": ${cut(it)}" } ?: ""),
                        back = true, failed = true, eventId = e.id, entryId = n.entryId,
                    )
                }
            }
            e.kind == SCREEN_CHANGED -> note("screen changed")
            e.kind == Kinds.PROCESS_ENDED -> note("process ended")
            e.ui != null -> {
                val u = e.ui!!
                val name = u.window?.let { w -> w.title ?: w.owner?.substringAfterLast('.') ?: w.kind.name.lowercase() } ?: "window"
                when (u.kind) {
                    UiEventKind.TOAST -> note("toast" + (u.text?.let { " \"${cut(it)}\"" } ?: ""))
                    UiEventKind.WINDOW_DISAPPEARED -> note("$name closed")
                    else -> note("window $name")
                }
            }
            else -> note(cut(e.title.ifBlank { e.kind }))
        }
    }

    private fun addSockets(open: List<Open>, messages: List<WsMessageEntry>, connections: List<WsConnectionEntry>) {
        if (open.isEmpty()) return
        fun episodeAt(ms: Long): Open = open.lastOrNull { it.first.wallMs <= ms } ?: open.first()
        for (c in connections) {
            episodeAt(c.timeMs).let { o ->
                o.opened = true
                o.steps += EpisodeStep(c.timeMs, EpisodeLanes.APP, EpisodeLanes.SOCKET, "open " + c.url.substringAfter("://").substringAfter('/', "").let { "/$it" }, entryId = c.id)
            }
            val closed = c.closedAtMs
            if (closed != null || c.state == WsState.FAILED) {
                val at = closed ?: c.timeMs
                episodeAt(at).steps += EpisodeStep(
                    at, EpisodeLanes.SOCKET, EpisodeLanes.APP,
                    if (c.state == WsState.FAILED) "failed" + (c.failure?.let { ": ${cut(it)}" } ?: "") else "closed" + (c.closeCode?.let { " $it" } ?: ""),
                    back = true, failed = c.state == WsState.FAILED, entryId = c.id,
                )
            }
        }
        // The last sent message per connection still waiting for what came back right after it.
        val waiting = HashMap<String, Long>()
        for (m in messages) {
            val o = episodeAt(m.timeMs)
            if (m.direction == Direction.OUT) {
                o.sent++
                waiting[m.connectionId] = m.timeMs
                o.steps += EpisodeStep(m.timeMs, EpisodeLanes.APP, EpisodeLanes.SOCKET, preview(m), entryId = m.id)
            } else {
                val after = waiting.remove(m.connectionId)
                if (after != null && m.timeMs - after <= REPLY_MS) {
                    o.steps += EpisodeStep(m.timeMs, EpisodeLanes.SOCKET, EpisodeLanes.APP, preview(m), back = true, entryId = m.id)
                } else {
                    o.counts.merge(messageName(m), 1, Int::plus)
                }
            }
        }
    }

    private fun finish(o: Open, withSteps: Boolean): Episode {
        val steps = o.steps.sortedBy { it.wallMs }
        return Episode(
            id = o.first.id,
            wallMs = o.first.wallMs,
            endWallMs = maxOf(o.lastMs, steps.lastOrNull()?.wallMs ?: 0),
            title = title(o),
            requests = o.events.count { it.network?.phase == NetworkPhase.REQUEST },
            failures = steps.count { it.failed },
            screenChanged = o.events.any { it.kind == SCREEN_CHANGED },
            socketOpened = o.opened,
            socketSent = o.sent,
            socketCounts = o.counts.map { (name, count) -> SocketCount(name, count) },
            gaps = o.gaps,
            steps = if (withSteps) steps else emptyList(),
        )
    }

    private fun title(o: Open): String {
        val to = o.nav?.nav?.let { shortName(it.destination) }
        val trigger = o.trigger
        val head = when {
            trigger?.input?.action == InputAction.BACK -> "Back"
            trigger != null -> "Tap ${tapLabel(trigger, quoted = false)}"
            o.start -> "App start"
            else -> null
        }
        if (head != null) return if (to != null) "$head → $to" else head
        if (to != null) return "→ $to"
        val f = o.events.first()
        return when {
            f.network != null -> "${f.network!!.method ?: "?"} ${f.network!!.path ?: ""}".trim()
            f.kind == SCREEN_CHANGED -> "Screen changed"
            f.ui?.kind == UiEventKind.TOAST -> "Toast" + (f.ui!!.text?.let { " \"${cut(it)}\"" } ?: "")
            f.ui != null -> f.ui!!.window?.let { w -> "Window " + (w.title ?: w.owner?.substringAfterLast('.') ?: "") }?.trim() ?: "Window"
            f.kind == Kinds.PROCESS_ENDED -> "Process ended"
            else -> cut(f.title.ifBlank { f.kind })
        }
    }

    /** A short text says what was tapped; a long one (a whole row) is worse than the element's key. */
    private fun tapLabel(e: TimelineEvent, quoted: Boolean): String {
        val text = e.input?.text?.trim()?.takeIf { it.isNotEmpty() && it.length <= SHORT_TEXT }
        val key = e.input?.element?.key?.substringBefore('|')
        return when {
            text != null && (key == null || !key.contains(':')) -> if (quoted) "\"$text\"" else text
            key != null -> key
            text != null -> text
            else -> "element"
        }
    }

    private class Hosts(val named: List<String>, val others: Boolean) {
        fun lane(host: String?) = if (host != null && host in named) EpisodeLanes.host(host) else EpisodeLanes.OTHER
    }

    private fun hostLanes(events: List<TimelineEvent>): Hosts {
        val counts = events.mapNotNull { it.network?.takeIf { n -> n.phase == NetworkPhase.REQUEST } }
            .groupingBy { it.host }.eachCount()
        val named = counts.entries.filter { it.key != null }.sortedByDescending { it.value }.take(MAX_HOST_LANES).mapNotNull { it.key }
        return Hosts(named, counts.keys.any { it !in named })
    }

    private fun preview(m: WsMessageEntry): String =
        if (m.payload.binary) "binary, ${m.payload.storedBytes} B" else cut(m.payload.preview?.replace(Regex("\\s+"), " ")?.trim() ?: "message", PREVIEW)

    private fun messageName(m: WsMessageEntry): String {
        if (m.payload.binary) return "binary"
        return NAME_FIELD.find(m.payload.preview ?: "")?.groupValues?.get(2) ?: "message"
    }

    private fun cut(s: String, max: Int = SHORT_TEXT * 2) = if (s.length <= max) s else s.take(max - 1) + "…"

    companion object {
        const val PAUSE_MS = 2_000L
        const val REPLY_MS = 1_000L
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1_000
        const val MAX_HOST_LANES = 2
        const val SCREEN_CHANGED = "screen/changed"
        private const val SHORT_TEXT = 24
        private const val PREVIEW = 48

        /** The process's first event in the kept window: anything later means its start was evicted. */
        private const val START_SEQS = 16L
        private val UI_SHOWN = setOf(UiEventKind.WINDOW_APPEARED, UiEventKind.WINDOW_DISAPPEARED, UiEventKind.TOAST)
        private val NAME_FIELD = Regex("\"(type|event|op|action|kind|method|channel)\"\\s*:\\s*\"([^\"]{1,24})\"")

        /** `a.b.ui.InstrumentRoute/{symbol}` to `InstrumentRoute/{symbol}`; arguments keep their dots. */
        fun simpleName(destination: String): String {
            val end = destination.indexOfFirst { it == '/' || it == '(' }.let { if (it < 0) destination.length else it }
            return destination.substring(0, end).substringAfterLast('.') + destination.substring(end)
        }

        /** For a title: `Instrument`, `ForecastKey(id=lisbon)`. */
        fun shortName(destination: String): String {
            val s = simpleName(destination)
            val plain = s.substringBefore('/')
            return if ('(' in s) s else plain.removeSuffix("Route").ifEmpty { plain }
        }
    }
}
