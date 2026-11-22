package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What only the app knows about its history, sent to whoever answers queries over it
 * (feature 011): the live run, the coverage counters, the capture's status and the script's
 * degradation codes.
 */
@Serializable
data class HistoryContext(
    val runId: String,
    val coverage: Coverage,
    val capture: CapabilityStatus,
    val scriptCodes: List<String> = emptyList(),
)

/**
 * The answers of `network_list` and `network_entry` over a history: paging, filters and match
 * locations, coverage and degradations. One implementation (constitution VI) for the daemon's
 * host index and the sidekick's memory-only fallback, so their answers cannot differ.
 *
 * @param entries the stored entries in chronological order (first record order)
 * @param byId one entry, or null when it is not stored
 * @param bodyBytes a stored body with its Content-Encoding undone where possible
 */
class NetworkHistoryView private constructor(
    private val sources: EntrySourceProvider,
    private val context: () -> HistoryContext,
    private val bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
    /** Decoded protobuf for body search; the host has the schemas, the app does not (feature 011). */
    private val decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
    /** Feature 051: a text message's envelope from its text and its socket's URL (the host has the app's rules). */
    private val readEnvelope: (WsMessageEntry, String) -> Envelope? = { _, text -> WsFormats.detect(text) },
    /** Feature 071: whether the answer under way was stopped; no time limit cuts it (FR-005). */
    private val patternStop: () -> PatternStop = { PatternStop.NONE },
) {
    /** The history as a list (the app's, the tests'): every count is exact. */
    constructor(
        entries: () -> List<NetworkEntry>,
        byId: (String) -> NetworkEntry?,
        context: () -> HistoryContext,
        bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
        decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
        readEnvelope: (WsMessageEntry, String) -> Envelope? = { _, text -> WsFormats.detect(text) },
        patternStop: () -> PatternStop = { PatternStop.NONE },
    ) : this(EntrySourceProvider { ListEntrySource(entries, byId) }, context, bodyBytes, decoded, readEnvelope, patternStop)

    /** The envelope of a text message for `type:`/`topic:`: the whole stored text when here, else the preview. */
    private fun envelopeOf(src: EntrySource, m: WsMessageEntry): Envelope? {
        if (m.opcode != MessageOpcode.TEXT || m.payload.binary) return null
        val text = bodyBytes(m, BodyPart.MESSAGE)?.takeIf { it.decoded }?.let { String(it.bytes, Charsets.UTF_8) } ?: m.payload.preview ?: return null
        val url = m.url ?: (src.byId(m.connectionId) as? WsConnectionEntry)?.url
        return readEnvelope(m.copy(url = url), text)
    }

    private fun evaluator(src: EntrySource, filter: NetworkFilter?, ctx: HistoryContext, redact: Boolean, check: () -> Unit): NetworkFilterEval? = filter?.let {
        NetworkFilterEval(it, ctx.runId, { id -> (src.byId(id) as? WsConnectionEntry)?.url }, decoded, { m -> envelopeOf(src, m) }, check, redact, bodyBytes)
    }

    /** Feature 071: the answer's stop — the view's ([patternStop]) and the caller's ([stop], which throws). */
    private fun checker(stop: () -> Unit): () -> Unit {
        val p = patternStop()
        return { if (p.stopped()) throw SearchStoppedException(); stop() }
    }

    /**
     * The page of [query]: entries from the cursor ([NetworkQuery.before]) that the filters keep
     * (and that hold the search words, unless every row is asked for), until [NetworkQuery.limit];
     * [NetworkPage.next] when one more follows. Feature 069: nothing is counted over the whole
     * history; what the page could not read is said for the entries it read.
     *
     * @param stop feature 067: called once per entry and while patterns match (071); throws to abandon a pass.
     */
    fun list(query: NetworkQuery, stop: () -> Unit = {}): NetworkPage = query.resolved().let { (filter, terms) -> sources.open(filter, WordNeeds.of(filter, terms, words = terms.isNotEmpty() && !query.allRows)).use { src ->
        val ctx = context()
        val check = checker(stop)
        val newest = query.order == Order.NEWEST_FIRST
        // Links (012) are in the same history; network views show them only when kinds asks.
        val links = filter?.kinds != null
        // Feature 041: search words leave only the entries that hold them, unless every row is asked for.
        val onlyHits = terms.isNotEmpty() && !query.allRows
        val eval = evaluator(src, filter, ctx, query.redact, check)
        val search = search(src, terms, query.redact)
        val walk = src.walk(newest, links, query.before)
            ?: throw InvalidParamsException("unknown cursor '${query.before}'; it may have been evicted or cleared")
        val page = ArrayList<NetworkEntry>(minOf(query.limit, 256))
        val locations = HashMap<String, List<MatchLocation>>()
        val pageHits = HashMap<String, List<SearchHit>>()
        var more = false
        for (entry in walk) {
            check()
            var matched: List<MatchLocation>? = null
            if (eval != null) matched = eval.matches(entry) ?: continue
            val h = if (onlyHits) search!!.hits(entry) ?: continue else null
            if (page.size == query.limit) { more = true; break }
            page += entry
            if (!matched.isNullOrEmpty()) locations[entry.id] = matched
            if (h != null) pageHits[entry.id] = h
        }
        val wordHits = when {
            search == null -> null
            onlyHits -> pageHits
            else -> page.mapNotNull { e -> search.hits(e)?.let { e.id to it } }.toMap()
        }
        // Feature 053: where the body patterns matched, next to the words' hits.
        val hits = withPatternHits(wordHits, eval, page.map { it.id })
        val coverage = ctx.coverage.copy(searched = src.count(links), notSearched = notSearched(eval, search))
        val earliest = src.first(links)
        NetworkPage(
            entries = page.map { it.withSocketUrl(src).summary() },
            matches = page.mapNotNull { e -> locations[e.id]?.let { e.id to it } }.toMap().ifEmpty { null },
            next = if (more) page.last().id else null,
            coverage = coverage,
            degraded = degradations(ctx, page).plusHistory(coverage, reachesEvicted = coverage.evicted > 0 && page.any { it.id == earliest }),
            fields = eval?.fieldPaths?.filterKeys { id -> page.any { it.id == id } }?.mapValues { it.value.toList() }?.ifEmpty { null },
            hits = hits?.ifEmpty { null },
        )
    } }

    /**
     * Feature 071 (FR-006): [query]'s rows as `list` gives them with every row asked for (search words
     * leave only their hits), each handed to [onItem] as it is found, newest first unless the order says
     * otherwise; until the scope ends, or [limit] rows (then [FindEnd.next]). No time limit: [stop] (and
     * the view's pattern stop) end it, also inside a match. What it holds per row is let go once given.
     */
    fun find(query: NetworkQuery, limit: Int? = null, stop: () -> Unit = {}, onItem: (FoundItem) -> Unit): FindEnd = query.resolved().let { (filter, terms) ->
        sources.open(filter, WordNeeds.of(filter, terms, words = terms.isNotEmpty() && !query.allRows)).use { src ->
            val ctx = context()
            val check = checker(stop)
            val links = filter?.kinds != null
            val onlyHits = terms.isNotEmpty() && !query.allRows
            val eval = evaluator(src, filter, ctx, query.redact, check)
            val search = search(src, terms, query.redact)
            val walk = src.walk(query.order == Order.NEWEST_FIRST, links, query.before)
                ?: throw InvalidParamsException("unknown cursor '${query.before}'; it may have been evicted or cleared")
            var given = 0
            var last: String? = null
            for (entry in walk) {
                check()
                val matched = if (eval != null) eval.matches(entry) ?: continue else null
                val words = if (search != null) search.hits(entry) else null
                val spans = eval?.patternHits?.remove(entry.id)
                val fields = eval?.fieldPaths?.remove(entry.id)
                if (onlyHits && words == null) continue
                if (limit != null && given == limit) return@use FindEnd(last, notSearched(eval, search))
                val hits = words.orEmpty() + spans.orEmpty()
                onItem(FoundItem(entry.withSocketUrl(src).summary(), hits.ifEmpty { null }, matched?.ifEmpty { null }, fields?.toList()?.ifEmpty { null }))
                given++
                last = entry.id
            }
            FindEnd(null, notSearched(eval, search))
        }
    }

    /** Feature 065: the ids a query lets through and the ids it looked at. */
    data class Passing(val ids: Set<String>, val seen: Set<String>)

    /**
     * Feature 065: every entry [list] would keep for [query], over all its pages, with the same
     * evaluator and search words (only the matches, as without `allRows`): the Timeline hides the
     * network rows Network hides. [Passing.seen] are the entries the history holds now.
     */
    fun passing(query: NetworkQuery): Passing = query.resolved().let { (filter, terms) -> sources.open(null).use { src ->
        val ctx = context()
        val search = search(src, terms, query.redact)
        val eval = evaluator(src, filter, ctx, query.redact, checker {})
        val seen = HashSet<String>()
        val ids = HashSet<String>()
        for (entry in src.walk(false, true, null)!!) {
            seen += entry.id
            if (entry is LinkEntry && filter?.kinds == null) continue
            if (eval != null && eval.matches(entry) == null) continue
            if (search != null && search.hits(entry) == null) continue
            ids += entry.id
        }
        Passing(ids, seen)
    } }

    /**
     * Feature 040: the entries the query's filters keep that hold every search word, in list
     * order, [NetworkQuery.limit] per page after [NetworkQuery.before] (an entry with hits).
     * Feature 069: only the page is read.
     */
    fun search(query: NetworkQuery, stop: () -> Unit = {}): SearchPage = query.resolved().let { (filter, terms) -> sources.open(filter, WordNeeds.of(filter, terms, words = true)).use { src ->
        val ctx = context()
        val check = checker(stop)
        val patterns = filter?.let { it.bodyPattern != null || it.requestPattern != null || it.responsePattern != null } == true
        if (terms.isEmpty() && !patterns) throw InvalidParamsException("query: no search words; give words, a \"phrase\" or a body:/pattern/ to find (filters alone are network.list)")
        val newest = query.order == Order.NEWEST_FIRST
        val links = filter?.kinds != null
        val unknown = { InvalidParamsException("unknown cursor '${query.before}'; it may have been evicted or cleared") }
        val eval = evaluator(src, filter, ctx, query.redact, check)
        val s = search(src, terms, query.redact)
        fun hitsOf(entry: NetworkEntry): List<SearchHit>? {
            if (eval != null && eval.matches(entry) == null) return null
            return (if (s != null) s.hits(entry) ?: return null else emptyList()) + eval?.patternHits?.get(entry.id).orEmpty()
        }
        // The cursor must be an entry with hits (feature 040).
        query.before?.let { before ->
            val cursor = src.byId(before)?.takeIf { links || it !is LinkEntry } ?: throw unknown()
            hitsOf(cursor) ?: throw unknown()
        }
        val walk = src.walk(newest, links, query.before) ?: throw unknown()
        val page = ArrayList<EntryHits>()
        var more = false
        for (entry in walk) {
            check()
            val h = hitsOf(entry) ?: continue
            if (page.size == query.limit) { more = true; break }
            page += EntryHits(entry.id, entry.timeMs, h)
        }
        SearchPage(page, if (more) page.last().id else null, notSearched(eval, s))
    } }

    /**
     * Feature 067: [query]'s decision for one entry, with the same evaluators as [list] (filters,
     * then the search words): a live event is decided as a full pass would decide it.
     */
    fun decide(query: NetworkQuery, entry: NetworkEntry, stop: () -> Unit = {}): EntryDecision = sources.open(null).use { src ->
        val ctx = context()
        val (filter, terms) = query.resolved()
        if (entry is LinkEntry && filter?.kinds == null) return@use EntryDecision.NO
        val s = search(src, terms, query.redact)
        val eval = evaluator(src, filter, ctx, query.redact, checker(stop))
        val locations = if (eval != null) eval.matches(entry) else emptyList()
        if (locations == null) return@use EntryDecision(false)
        val words = if (s != null) s.hits(entry) else emptyList()
        if (words == null) return@use EntryDecision(false, byPreview = s!!.byPreview > 0)
        val hits = words + eval?.patternHits?.get(entry.id).orEmpty()
        EntryDecision(true, locations.ifEmpty { null }, hits.ifEmpty { null }, byPreview = (s?.byPreview ?: 0) > 0)
    }

    private fun search(src: EntrySource, terms: List<Term>, redact: Boolean): NetworkSearch? = terms.takeIf { it.isNotEmpty() }?.let {
        NetworkSearch(it, { id -> (src.byId(id) as? WsConnectionEntry)?.url }, decoded, bodyBytes, redact)
    }

    /** Feature 053: the words' hits plus the pattern spans, for the entries in [ids]. */
    private fun withPatternHits(words: Map<String, List<SearchHit>>?, eval: NetworkFilterEval?, ids: List<String>): Map<String, List<SearchHit>>? {
        val spans = eval?.patternHits?.takeIf { it.isNotEmpty() } ?: return words
        return ids.mapNotNull { id ->
            val all = words?.get(id).orEmpty() + spans[id].orEmpty()
            all.takeIf { it.isNotEmpty() }?.let { id to it }
        }.toMap()
    }

    private fun notSearched(eval: NetworkFilterEval?, search: NetworkSearch?): List<String>? = listOfNotNull(
        eval?.patternBinaryAll?.takeIf { it > 0 }?.let { "binary bodies without a schema, not matched by patterns ($it)" },
        eval?.notRedactableAll?.takeIf { it > 0 }?.let { "bodies that cannot be redacted, not matched while redacting ($it)" },
        ((eval?.binaryNotSearched ?: 0) + (eval?.excludedNotSearched ?: 0)).takeIf { it > 0 }?.let { "binary bodies whose encoding cannot be undone ($it)" },
        eval?.binaryUndecoded?.takeIf { it > 0 }?.let { "binary bodies without a schema, searched as bytes only ($it)" },
        search?.notSearched?.takeIf { it > 0 }?.let { "bodies the search could not read: encoding not undone, or not inspectable while redacting ($it)" },
        search?.byPreview?.takeIf { it > 0 }?.let { "bodies searched by their first ${BodyInfo.PREVIEW_CHARS} characters: not copied from the app yet ($it)" },
        search?.truncated?.takeIf { it > 0 }?.let { "bodies stored cut, searched as far as stored ($it)" },
    ).ifEmpty { null }

    fun entry(id: String): NetworkEntryDetail = sources.open(null).use { src ->
        val ctx = context()
        val entry = src.byId(id) ?: throw InvalidParamsException(
            "unknown entry id $id; it may have been evicted (earliest stored: ${ctx.coverage.earliestMs?.let(::iso) ?: "nothing stored"})",
        )
        if (entry !is WsConnectionEntry) {
            return@use NetworkEntryDetail(entry = entry.withSocketUrl(src), degraded = degradations(ctx, listOf(entry)))
        }
        val messages = src.messagesOf(id).map { it.copy(url = it.url ?: entry.url) }
        NetworkEntryDetail(
            entry = entry,
            messages = messages.take(NetworkEntryDetail.MAX_MESSAGES),
            messagesTruncated = (messages.size - NetworkEntryDetail.MAX_MESSAGES).takeIf { it > 0 },
            degraded = degradations(ctx, listOf(entry) + messages),
        )
    }

    /** A message stored before messages carried their socket's URL takes its connection's, while that is stored. */
    private fun NetworkEntry.withSocketUrl(src: EntrySource): NetworkEntry =
        if (this is WsMessageEntry && url == null) copy(url = (src.byId(connectionId) as? WsConnectionEntry)?.url) else this

    private fun degradations(ctx: HistoryContext, entries: List<NetworkEntry>): List<DegradationMarker>? {
        val codes = buildList {
            when (ctx.capture.status) {
                CapabilityStatus.Status.UNAVAILABLE -> add(DegradationCodes.NETWORK_HOOKS_UNAVAILABLE)
                CapabilityStatus.Status.DEGRADED -> {
                    // Feature 019: a library captured in part is "partial"; one losing everything
                    // (notCaptured set) is "unavailable". Without the library list, as before.
                    val libraries = ctx.capture.libraries
                    if (libraries == null || libraries.any { it.status == CapabilityStatus.Status.UNAVAILABLE && it.notCaptured != null }) {
                        add(DegradationCodes.NETWORK_LIBRARY_UNAVAILABLE)
                    }
                    if (libraries?.any { it.status == CapabilityStatus.Status.DEGRADED } == true) add(DegradationCodes.NETWORK_LIBRARY_PARTIAL)
                }
                CapabilityStatus.Status.AVAILABLE -> Unit
            }
            if (ctx.coverage.dropped > 0) add(DegradationCodes.NETWORK_EVENTS_DROPPED)
            // A web page's request (feature 060) has no app stack by nature: not a capture gap.
            if (entries.any { it.initiator?.missing != null && it.library != NetworkLibraries.WEBVIEW && it.library != NetworkLibraries.CUSTOM_TAB }) add(DegradationCodes.NETWORK_INITIATOR_MISSING)
            if (entries.any { e -> bodiesOf(e).any { it.completeness.isGap } }) add(DegradationCodes.NETWORK_BODY_INCOMPLETE)
            addAll(ctx.scriptCodes)
        }
        return codes.map { DegradationMarker(CAPABILITY, it) }.ifEmpty { null }
    }

    /** Eviction (FR-026) and lost persistence (FR-029) as markers on the page. */
    private fun List<DegradationMarker>?.plusHistory(coverage: Coverage, reachesEvicted: Boolean): List<DegradationMarker>? {
        val extra = buildList {
            if (reachesEvicted) add(DegradationCodes.NETWORK_HISTORY_EVICTED)
            if (!coverage.persisted) add(DegradationCodes.NETWORK_HISTORY_NOT_PERSISTED)
        }.map { DegradationMarker(CAPABILITY, it) }
        return (orEmpty() + extra).ifEmpty { null }
    }

    companion object {
        const val CAPABILITY = "network"

        /** Feature 069: a history read from [sources] as an answer walks it. */
        fun over(
            sources: EntrySourceProvider,
            context: () -> HistoryContext,
            bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
            decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
            readEnvelope: (WsMessageEntry, String) -> Envelope? = { _, text -> WsFormats.detect(text) },
            patternStop: () -> PatternStop = { PatternStop.NONE },
        ) = NetworkHistoryView(sources, context, bodyBytes, decoded, readEnvelope, patternStop)

        /** The body info of one part of an entry, wire or app side; null when absent. */
        fun bodyOf(entry: NetworkEntry, part: BodyPart): BodyInfo? = when (part) {
            BodyPart.REQUEST -> (entry as? HttpEntry)?.requestBody
            BodyPart.RESPONSE -> (entry as? HttpEntry)?.responseBody
            BodyPart.MESSAGE -> (entry as? WsMessageEntry)?.payload
            BodyPart.APP_REQUEST -> (entry as? HttpEntry)?.appRequest?.body
            BodyPart.APP_RESPONSE -> (entry as? HttpEntry)?.appResponse?.body
            BodyPart.APP_MESSAGE -> (entry as? WsMessageEntry)?.appPayload
        }

        fun bodiesOf(entry: NetworkEntry): List<BodyInfo> = BodyPart.entries.mapNotNull { bodyOf(entry, it) }

        /** ISO-8601 UTC without java.time (the sidekick runs on API 24 without desugaring). */
        fun iso(epochMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
    }
}
