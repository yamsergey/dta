package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.EntryKind
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.ParsedQuery
import io.yamsergey.dta.protocol.network.QueryLine
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.Flow
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.SequenceFlow
import io.yamsergey.dta.protocol.timeline.SequenceLane
import io.yamsergey.dta.protocol.timeline.SequenceLanes
import io.yamsergey.dta.protocol.timeline.SequencePage
import io.yamsergey.dta.protocol.timeline.SequenceStep
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource

/**
 * Feature 048: kept timeline events and socket entries as one flat sequence, in time order.
 * Nothing is grouped by any rule. Links come only from recorded causes; screens are named only
 * from what was recorded; a run of received socket messages is one row, as they are too many.
 * Feature 049: a row names the flows whose interval holds it, by time only; no row goes anywhere.
 */
class SequenceBuilder {
    /** What a request and its response are read as together. */
    private class Exchange(val method: String?, val host: String?, val path: String?) {
        var status: Int? = null
        var failed = false
        val url get() = "${host.orEmpty()}${path.orEmpty()}"
    }

    private class Row(
        val step: SequenceStep,
        val exchange: Exchange? = null,
        val socket: EntryKind? = null,
        val socketUrl: String? = null,
        /** A received socket message waiting to join its neighbours, by the name it calls itself. */
        val received: String? = null,
    ) {
        fun withStep(next: SequenceStep) = Row(next, exchange, socket, socketUrl, received)
    }

    fun build(
        events: List<TimelineEvent>,
        sockets: List<NetworkEntry>,
        sources: List<SourceStatus> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
        q: String? = null,
        sinceMs: Long? = null,
        socketsNote: String? = null,
        flows: List<Flow> = emptyList(),
        only: Flow? = null,
        flowsNote: String? = null,
    ): SequencePage {
        val query = parse(q)
        val hosts = hostLanes(events)
        val messages = sockets.filterIsInstance<WsMessageEntry>().sortedBy { it.timeMs }
        val connections = sockets.filterIsInstance<WsConnectionEntry>()
        val oldest = (events.map { it.wallMs } + sockets.map { it.timeMs }).minOrNull()
        val ordered = (eventRows(events, hosts) + socketRows(messages, connections)).sortedBy { it.step.wallMs }
        val rows = joinReceived(ordered, flows.flatMap { listOfNotNull(it.startMs, it.endMs) }.toSortedSet()).map { r ->
            val holding = flows.filter { it.holds(r.step.wallMs) }.map { it.id }
            if (holding.isEmpty()) r else r.withStep(r.step.copy(flows = holding))
        }
        val fromStart = rows.filter { (sinceMs == null || it.step.wallMs >= sinceMs) && (only == null || only.holds(it.step.wallMs)) }
        val matched = fromStart.filter { matches(it, query) }
        val steps = matched.takeLast(limit).map { r -> if (hit(r, query)) r.step.copy(hit = true) else r.step }
        val listed = flows.filter { only == null || it.id == only.id }.map { f ->
            SequenceFlow(
                flow = f,
                rows = rows.count { f.holds(it.step.wallMs) },
                shown = matched.count { f.holds(it.step.wallMs) },
                rowsGone = f.startMs > 0 && oldest != null && f.startMs < oldest,
            )
        }
        val lanes = buildList {
            add(SequenceLane(SequenceLanes.USER, "You"))
            add(SequenceLane(SequenceLanes.APP, "App"))
            hosts.named.forEach { add(SequenceLane(SequenceLanes.host(it), it)) }
            if (hosts.others) add(SequenceLane(SequenceLanes.OTHER, "other hosts"))
            if (sockets.isNotEmpty()) add(SequenceLane(SequenceLanes.SOCKET, "socket"))
        }
        return SequencePage(
            steps = steps,
            total = fromStart.size,
            lanes = lanes,
            matched = matched.size,
            beforeStart = rows.size - fromStart.size,
            hits = steps.count { it.hit },
            socketMessages = messages.size,
            startWallMs = rows.firstOrNull()?.step?.wallMs ?: 0,
            sources = sources,
            socketsNote = socketsNote,
            flows = listed,
            onlyFlow = only?.id,
            flowsNote = flowsNote,
        )
    }

    private fun eventRows(events: List<TimelineEvent>, hosts: Hosts): List<Row> {
        val sorted = events.sortedBy { it.wallMs }
        val kept = sorted.mapTo(HashSet()) { it.id }
        val owners = sorted.mapNotNull { it.ui?.window?.owner }.associateBy { it.substringAfterLast('.') }
        val exchanges = HashMap<String, Exchange>()
        for (e in sorted) {
            val n = e.network ?: continue
            val x = exchanges.getOrPut(n.entryId) { Exchange(n.method, n.host, n.path) }
            if (n.phase == NetworkPhase.RESPONSE) x.status = n.status
            if (n.phase == NetworkPhase.FAILED) x.failed = true
        }
        val labels = HashMap<String, String>()
        val out = ArrayList<Row>()
        var epoch: String? = null
        var seenEpochs = 0
        var screens = Screens(null)
        for (e in sorted) {
            val own = e.id.startsWith("host:") || e.id.substringAfter(':').startsWith("h")
            val thisEpoch = if (own) epoch else e.id.substringBefore(':')
            if (thisEpoch != epoch) {
                val first = seenEpochs == 0
                seenEpochs++
                epoch = thisEpoch
                screens = Screens(firstPrevious(sorted, thisEpoch))
                if (e.kind != Kinds.PROCESS_ENDED && (!first || e.seq <= START_SEQS)) {
                    out += Row(SequenceStep(e.wallMs, SequenceLanes.APP, label = "process started", boundary = true))
                }
            }
            if (e.kind == Kinds.GAP) {
                // Elements are not drawn, so their back-off says nothing about what is.
                if (e.ui?.source != UiSource.ELEMENT) out += Row(SequenceStep(e.wallMs, SequenceLanes.APP, label = "events may be missing", boundary = true, eventId = e.id))
                continue
            }
            if (e.nav != null) screens.destination = e.nav!!.destination
            if (!shown(e)) continue
            val row = row(e, hosts, kept, labels, screens, owners, exchanges) ?: continue
            labels[e.id] = row.step.label
            out += row
        }
        return out
    }

    /** The screen in this process so far; what the first navigation left is its value before any is kept. */
    private class Screens(var destination: String?) {
        var last: Pair<String?, String?>? = null
    }

    private fun firstPrevious(events: List<TimelineEvent>, epoch: String?): String? =
        events.firstOrNull { it.nav != null && it.id.substringBefore(':') == epoch }
            ?.facets?.firstOrNull { it.name == Facets.NAV_DESTINATION || it.name == Facets.NAV_KEY }?.previous

    private fun shown(e: TimelineEvent): Boolean = when {
        e.input != null || e.nav != null || e.network != null -> true
        e.kind == SCREEN_CHANGED -> true
        e.kind == Kinds.PROCESS_ENDED -> true
        e.source == Sources.APP -> e.kind != Kinds.DECLARED_CAUSE
        e.source == Sources.UI -> e.ui?.kind in UI_SHOWN
        else -> false
    }

    private fun cause(e: TimelineEvent, kept: Set<String>) =
        e.causes.firstOrNull { it.basis != Basis.INFERRED && !it.fromUnknown && it.from in kept && it.from != e.id }

    private fun row(
        e: TimelineEvent,
        hosts: Hosts,
        kept: Set<String>,
        labels: Map<String, String>,
        screens: Screens,
        owners: Map<String, String>,
        exchanges: Map<String, Exchange>,
    ): Row? {
        val cause = cause(e, kept)
        val because = cause?.let { "after " + (labels[it.from] ?: "an earlier event") }
        fun detail(vararg parts: String?) = parts.filterNotNull().joinToString(" · ").ifEmpty { null }
        fun note(label: String, more: String? = null, boundary: Boolean = false) = Row(
            SequenceStep(e.wallMs, SequenceLanes.APP, label = label, linked = cause != null, eventId = e.id, detail = detail(more, because), boundary = boundary),
        )
        return when {
            e.input != null -> Row(
                SequenceStep(
                    e.wallMs, SequenceLanes.USER, SequenceLanes.APP,
                    if (e.input!!.action == InputAction.BACK) "back" else "tap ${tapLabel(e)}",
                    eventId = e.id, detail = e.input!!.text?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
            e.nav != null -> note("→ " + simpleName(e.nav!!.destination), e.nav!!.destination)
            e.network != null -> {
                val n = e.network!!
                val lane = hosts.lane(n.host)
                val x = exchanges[n.entryId]
                when (n.phase) {
                    NetworkPhase.REQUEST -> Row(
                        SequenceStep(
                            e.wallMs, SequenceLanes.APP, lane, "${n.method ?: "?"} ${n.path ?: ""}".trim(),
                            linked = cause != null, eventId = e.id, entryId = n.entryId, detail = detail(x?.url, because),
                        ),
                        x,
                    )
                    NetworkPhase.RESPONSE -> Row(
                        SequenceStep(
                            e.wallMs, lane, SequenceLanes.APP,
                            listOfNotNull(n.status?.toString(), n.durationMs?.let { "$it ms" }).joinToString(" · ").ifEmpty { "response" },
                            back = true, failed = (n.status ?: 0) >= 400, eventId = e.id, entryId = n.entryId, detail = x?.url,
                        ),
                        x,
                    )
                    NetworkPhase.FAILED -> Row(
                        SequenceStep(
                            e.wallMs, lane, SequenceLanes.APP, "failed" + (n.error?.let { ": ${cut(it)}" } ?: ""),
                            back = true, failed = true, eventId = e.id, entryId = n.entryId, detail = detail(x?.url, n.error),
                        ),
                        x,
                    )
                }
            }
            e.kind == SCREEN_CHANGED -> {
                val activity = e.facets.firstOrNull { it.name == Facets.SCREEN_FINGERPRINT }?.value?.substringBefore('·')?.takeIf { it != "screen" }
                val destination = screens.destination
                val same = screens.last == (destination to activity)
                screens.last = destination to activity
                val names = listOfNotNull(destination?.let(::simpleName), activity).joinToString(" · ")
                val full = listOfNotNull(destination, activity?.let { owners[it] ?: it }).joinToString(" · ")
                note(
                    when {
                        names.isEmpty() -> "screen changed"
                        same -> "content changed · $names"
                        else -> "screen $names"
                    },
                    full.ifEmpty { null },
                )
            }
            e.kind == Kinds.PROCESS_ENDED -> note("process ended", boundary = true)
            e.ui != null -> {
                val u = e.ui!!
                val name = u.window?.let { w -> w.title ?: w.owner?.substringAfterLast('.') ?: w.kind.name.lowercase() } ?: "window"
                when (u.kind) {
                    UiEventKind.TOAST -> note("toast" + (u.text?.let { " \"${cut(it)}\"" } ?: ""), u.text)
                    UiEventKind.WINDOW_DISAPPEARED -> note("$name closed", u.window?.owner)
                    else -> note("window $name", u.window?.owner)
                }
            }
            else -> note(cut(e.title.ifBlank { e.kind }))
        }
    }

    private fun socketRows(messages: List<WsMessageEntry>, connections: List<WsConnectionEntry>): List<Row> {
        val out = ArrayList<Row>()
        val urls = connections.associate { it.id to it.url }
        for (c in connections) {
            out += Row(
                SequenceStep(c.timeMs, SequenceLanes.APP, SequenceLanes.SOCKET, "open " + c.url.substringAfter("://").substringAfter('/', "").let { "/$it" }, entryId = c.id, detail = c.url),
                socket = EntryKind.WS_CONNECTION, socketUrl = c.url,
            )
            val closed = c.closedAtMs
            if (closed != null || c.state == WsState.FAILED) {
                out += Row(
                    SequenceStep(
                        closed ?: c.timeMs, SequenceLanes.SOCKET, SequenceLanes.APP,
                        if (c.state == WsState.FAILED) "failed" + (c.failure?.let { ": ${cut(it)}" } ?: "") else "closed" + (c.closeCode?.let { " $it" } ?: ""),
                        back = true, failed = c.state == WsState.FAILED, entryId = c.id, detail = c.url,
                    ),
                    socket = EntryKind.WS_CONNECTION, socketUrl = c.url,
                )
            }
        }
        // The last sent message per connection still waiting for what came back right after it.
        val waiting = HashMap<String, Long>()
        for (m in messages) {
            val url = m.url ?: urls[m.connectionId]
            if (m.direction == Direction.OUT) {
                waiting[m.connectionId] = m.timeMs
                out += Row(SequenceStep(m.timeMs, SequenceLanes.APP, SequenceLanes.SOCKET, preview(m), entryId = m.id, detail = url), socket = EntryKind.WS_MESSAGE, socketUrl = url)
            } else {
                val after = waiting.remove(m.connectionId)
                val reply = after != null && m.timeMs - after <= REPLY_MS
                out += Row(
                    SequenceStep(m.timeMs, SequenceLanes.SOCKET, SequenceLanes.APP, if (reply) preview(m) else messageName(m), back = true, entryId = m.id, detail = url),
                    socket = EntryKind.WS_MESSAGE, socketUrl = url, received = if (reply) null else messageName(m),
                )
            }
        }
        return out
    }

    /** Neighbouring received messages are one row, `tick ×2, quote ×1`; any other row or a flow's boundary ends the run. */
    private fun joinReceived(rows: List<Row>, boundaries: java.util.SortedSet<Long>): List<Row> {
        val out = ArrayList<Row>()
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            if (r.received == null) {
                out += r
                i++
                continue
            }
            val counts = LinkedHashMap<String, Int>()
            var j = i
            // The run ends where the next boundary after its first message is reached.
            val until = boundaries.tailSet(r.step.wallMs + 1).firstOrNull()
            while (j < rows.size && rows[j].received != null && (until == null || rows[j].step.wallMs < until)) {
                counts.merge(rows[j].received!!, 1, Int::plus)
                j++
            }
            val shown = counts.entries.take(MAX_RUN_NAMES).joinToString(", ") { "${it.key} ×${it.value}" }
            val more = counts.size - MAX_RUN_NAMES
            out += Row(r.step.copy(label = if (more > 0) "$shown, +$more" else shown), socket = r.socket, socketUrl = r.socketUrl)
            i = j
        }
        return out
    }

    private fun matches(r: Row, q: ParsedQuery): Boolean {
        if (!passes(r, q.filter)) return false
        return q.exclude.none { it != NetworkFilter() && passes(r, it) }
    }

    /** The Network line's meaning over a row: an exchange is read whole, so a response stays with its request. */
    private fun passes(r: Row, f: NetworkFilter): Boolean {
        val x = r.exchange
        val url = x?.url ?: r.socketUrl
        f.kinds?.let { kinds ->
            val kind = if (x != null) EntryKind.HTTP else r.socket
            if (kind == null || kind !in kinds) return false
        }
        f.sinceMs?.let { if (r.step.wallMs < it) return false }
        f.untilMs?.let { if (r.step.wallMs > it) return false }
        f.method?.let { if (x == null || !x.method.equals(it, ignoreCase = true)) return false }
        f.status?.let { if (x == null || !statusMatches(x, it)) return false }
        f.url?.let { if (url == null || !url.contains(it, ignoreCase = true)) return false }
        f.host?.let { wanted ->
            val address = url?.let { if ("://" in it) it else "http://$it" }
            if (!NetworkFilterEval.hostMatches(address, wanted)) return false
        }
        return true
    }

    private fun statusMatches(x: Exchange, status: String): Boolean = when {
        status == "failed" -> x.failed
        status == "pending" -> !x.failed && x.status == null
        status.endsWith("xx") -> x.status?.let { it / 100 == status[0].digitToInt() } == true
        else -> x.status == status.toIntOrNull()
    }

    private fun hit(r: Row, q: ParsedQuery): Boolean {
        if (q.terms.isEmpty()) return false
        val text = (r.step.label + " " + r.step.detail.orEmpty()).lowercase()
        return q.terms.all { it.text.lowercase() in text }
    }

    /** A short text says what was tapped; a long one (a whole row) is worse than the element's key. */
    private fun tapLabel(e: TimelineEvent): String {
        val text = e.input?.text?.trim()?.takeIf { it.isNotEmpty() && it.length <= SHORT_TEXT }
        val key = e.input?.element?.key?.substringBefore('|')
        return when {
            text != null && (key == null || !key.contains(':')) -> "\"$text\""
            key != null -> key
            text != null -> "\"$text\""
            else -> "element"
        }
    }

    private class Hosts(val named: List<String>, val others: Boolean) {
        fun lane(host: String?) = if (host != null && host in named) SequenceLanes.host(host) else SequenceLanes.OTHER
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
        const val REPLY_MS = 1_000L
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 2_000
        const val MAX_HOST_LANES = 2
        const val SCREEN_CHANGED = "screen/changed"
        private const val MAX_RUN_NAMES = 3
        private const val SHORT_TEXT = 24
        private const val PREVIEW = 48

        /** The process's first event in the kept window: anything later means its start was evicted. */
        private const val START_SEQS = 16L
        private val UI_SHOWN = setOf(UiEventKind.WINDOW_APPEARED, UiEventKind.WINDOW_DISAPPEARED, UiEventKind.TOAST)
        private val NAME_FIELD = Regex("\"(type|event|op|action|kind|method|channel)\"\\s*:\\s*\"([^\"]{1,24})\"")

        /** The sequence holds no bodies, runs or interventions. */
        private val NOT_HERE = setOf("body", "run", "has")

        /** The query line, or a refusal naming what is wrong with it. */
        fun parse(q: String?, nowMs: Long = System.currentTimeMillis()): ParsedQuery {
            val line = q?.takeIf { it.isNotBlank() } ?: return ParsedQuery()
            val p = QueryLine.parse(line, nowMs)
            val wrong = p.problems.map { "${line.substring(it.start, it.end)} — ${it.message}" } +
                p.tokens.filter { it.key in NOT_HERE }.map { "${it.text} — the sequence holds no ${it.key} data; use network list" }
            if (wrong.isNotEmpty()) throw InvalidParamsException("query: " + wrong.joinToString("; "))
            return p
        }

        /** `a.b.ui.InstrumentRoute/{symbol}` to `InstrumentRoute/{symbol}`; arguments keep their dots. */
        fun simpleName(destination: String): String {
            val end = destination.indexOfFirst { it == '/' || it == '(' }.let { if (it < 0) destination.length else it }
            return destination.substring(0, end).substringAfterLast('.') + destination.substring(end)
        }
    }
}
