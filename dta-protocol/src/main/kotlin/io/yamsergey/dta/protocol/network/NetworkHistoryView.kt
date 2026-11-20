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
    /** Feature 053: a fresh time budget for the body patterns of one answer. */
    private val patternBudget: () -> PatternBudget = { PatternBudget() },
    /** Feature 069: how long an answer counts after its page, when the query does not say; null: no limit. */
    private val countBudgetMs: Long? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** The history as a list (the app's, the tests'): every count is exact. */
    constructor(
        entries: () -> List<NetworkEntry>,
        byId: (String) -> NetworkEntry?,
        context: () -> HistoryContext,
        bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
        decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
        readEnvelope: (WsMessageEntry, String) -> Envelope? = { _, text -> WsFormats.detect(text) },
        patternBudget: () -> PatternBudget = { PatternBudget() },
    ) : this(EntrySourceProvider { ListEntrySource(entries(), byId) }, context, bodyBytes, decoded, readEnvelope, patternBudget, null)

    /** The envelope of a text message for `type:`/`topic:`: the whole stored text when here, else the preview. */
    private fun envelopeOf(src: EntrySource, m: WsMessageEntry): Envelope? {
        if (m.opcode != MessageOpcode.TEXT || m.payload.binary) return null
        val text = bodyBytes(m, BodyPart.MESSAGE)?.takeIf { it.decoded }?.let { String(it.bytes, Charsets.UTF_8) } ?: m.payload.preview ?: return null
        val url = m.url ?: (src.byId(m.connectionId) as? WsConnectionEntry)?.url
        return readEnvelope(m.copy(url = url), text)
    }

    private fun evaluator(src: EntrySource, filter: NetworkFilter?, ctx: HistoryContext): NetworkFilterEval? = filter?.let {
        NetworkFilterEval(it, ctx.runId, { id -> (src.byId(id) as? WsConnectionEntry)?.url }, decoded, { m -> envelopeOf(src, m) }, patternBudget(), bodyBytes)
    }

    /** Feature 069: when counting must stop (nanoTime), or null for no limit. */
    private fun countDeadline(query: NetworkQuery): Long? {
        val ms = query.countBudgetMs?.let { if (it < 0) null else it } ?: countBudgetMs?.takeIf { query.countBudgetMs == null }
        return ms?.let { nanoTime() + it * 1_000_000 }
    }

    /**
     * @param candidates feature 067: only these entries can match (a narrowed pass); the others are
     *   skipped unread. Null: every entry is checked.
     * @param stop feature 067: called once per entry; throws to abandon a pass a newer query replaced.
     * @param onMatch feature 067: every match with its number of search hits, for the view's match set;
     *   called only when the counts are complete (else [NetworkPage.counts] says they are not).
     */
    fun list(
        query: NetworkQuery,
        candidates: ((NetworkEntry) -> Boolean)? = null,
        stop: () -> Unit = {},
        onMatch: ((NetworkEntry, Int) -> Unit)? = null,
    ): NetworkPage = query.resolved().let { (filter, terms) -> sources.open(filter).use { src ->
        val ctx = context()
        val newest = query.order == Order.NEWEST_FIRST
        // Links (012) are in the same history; network views show them only when kinds asks.
        val links = filter?.kinds != null
        // Feature 041: search words leave only the entries that hold them, unless every row is asked for.
        val onlyHits = terms.isNotEmpty() && !query.allRows

        // The page: from the cursor until one more match than the limit (feature 069).
        val pageEval = evaluator(src, filter, ctx)
        val pageSearch = search(src, terms, query.redact)
        val walk = src.walk(newest, links, query.before)
            ?: throw InvalidParamsException("unknown cursor '${query.before}'; it may have been evicted or cleared")
        val page = ArrayList<NetworkEntry>(minOf(query.limit, 256))
        val locations = HashMap<String, List<MatchLocation>>()
        val pageHits = HashMap<String, List<SearchHit>>()
        var more = false
        for (entry in walk) {
            stop()
            if (candidates != null && !candidates(entry)) continue
            var matched: List<MatchLocation>? = null
            if (pageEval != null) matched = pageEval.matches(entry) ?: continue
            val h = if (onlyHits) pageSearch!!.hits(entry) ?: continue else null
            if (page.size == query.limit) { more = true; break }
            page += entry
            if (!matched.isNullOrEmpty()) locations[entry.id] = matched
            if (h != null) pageHits[entry.id] = h
        }

        // The counts: every match of the history, as long as the budget lasts.
        val deadline = countDeadline(query)
        val eval = evaluator(src, filter, ctx)
        val search = search(src, terms, query.redact)
        var total = 0
        var counted = 0
        var through: String? = null
        var complete = true
        val matches = if (onMatch != null) ArrayList<Pair<NetworkEntry, Int>>() else null
        for (entry in src.walk(newest, links, null)!!) {
            if (deadline != null && nanoTime() >= deadline) { complete = false; break }
            stop()
            counted++
            through = entry.id
            if (candidates != null && !candidates(entry)) continue
            if (eval != null && eval.matches(entry) == null) continue
            var hitCount = 0
            if (onlyHits) hitCount = (search!!.hits(entry) ?: continue).size
            matches?.add(entry to hitCount)
            total++
        }
        if (complete) matches?.forEach { (e, n) -> onMatch!!(e, n) }
        val wordHits = when {
            terms.isEmpty() -> null
            onlyHits -> pageHits
            // Read with the counting search, as one pass would: what it could not read is counted once more.
            else -> page.mapNotNull { e -> (search ?: pageSearch)!!.hits(e)?.let { e.id to it } }.toMap()
        }
        // Feature 053: where the body patterns matched, next to the words' hits.
        val hits = withPatternHits(wordHits, pageEval, page.map { it.id })
        val coverage = ctx.coverage.copy(searched = src.count(links), notSearched = notSearched(eval, search))
        val earliest = src.first(links)
        NetworkPage(
            entries = page.map { it.withSocketUrl(src).summary() },
            matches = page.mapNotNull { e -> locations[e.id]?.let { e.id to it } }.toMap().ifEmpty { null },
            total = if (complete) total else maxOf(total, page.size + (if (more) 1 else 0)),
            next = if (more) page.last().id else null,
            coverage = coverage,
            degraded = degradations(ctx, page).plusHistory(coverage, reachesEvicted = coverage.evicted > 0 && page.any { it.id == earliest }),
            fields = pageEval?.fieldPaths?.filterKeys { id -> page.any { it.id == id } }?.mapValues { it.value.toList() }?.ifEmpty { null },
            hits = hits?.ifEmpty { null },
            counts = if (complete) null else Counts(complete = false, countedThrough = through, countedEntries = counted),
        )
    } }

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
        val eval = evaluator(src, filter, ctx)
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
     * order, [NetworkQuery.limit] per page after [NetworkQuery.before] (an entry id).
     *
     * @param candidates feature 067: only these entries can hold the words (a narrowed pass); the
     *   others are still counted in [SearchPage.searched] when the filters keep them, which a caller
     *   may only allow for filters that read no bodies.
     */
    fun search(query: NetworkQuery, candidates: ((NetworkEntry) -> Boolean)? = null, stop: () -> Unit = {}): SearchPage = query.resolved().let { (filter, terms) -> sources.open(filter).use { src ->
        val ctx = context()
        val patterns = filter?.let { it.bodyPattern != null || it.requestPattern != null || it.responsePattern != null } == true
        if (terms.isEmpty() && !patterns) throw InvalidParamsException("query: no search words; give words, a \"phrase\" or a body:/pattern/ to find (filters alone are network.list)")
        val newest = query.order == Order.NEWEST_FIRST
        val links = filter?.kinds != null
        val unknown = { InvalidParamsException("unknown cursor '${query.before}'; it may have been evicted or cleared") }

        // The page: after the cursor, which must be a hit (feature 040).
        val pageEval = evaluator(src, filter, ctx)
        val pageSearch = search(src, terms, query.redact)
        fun hitsOf(entry: NetworkEntry): List<SearchHit>? {
            if (pageEval != null && pageEval.matches(entry) == null) return null
            if (candidates != null && !candidates(entry)) return null
            return (if (pageSearch != null) pageSearch.hits(entry) ?: return null else emptyList()) + pageEval?.patternHits?.get(entry.id).orEmpty()
        }
        query.before?.let { before ->
            val cursor = src.byId(before)?.takeIf { links || it !is LinkEntry } ?: throw unknown()
            hitsOf(cursor) ?: throw unknown()
        }
        val walk = src.walk(newest, links, query.before) ?: throw unknown()
        val page = ArrayList<EntryHits>()
        var more = false
        for (entry in walk) {
            stop()
            val h = hitsOf(entry) ?: continue
            if (page.size == query.limit) { more = true; break }
            page += EntryHits(entry.id, entry.timeMs, h)
        }

        // The counts, as long as the budget lasts.
        val deadline = countDeadline(query)
        val eval = evaluator(src, filter, ctx)
        val s = search(src, terms, query.redact)
        var searched = 0
        var total = 0
        var counted = 0
        var through: String? = null
        var complete = true
        for (entry in src.walk(newest, links, null)!!) {
            if (deadline != null && nanoTime() >= deadline) { complete = false; break }
            stop()
            counted++
            through = entry.id
            if (eval != null && eval.matches(entry) == null) continue
            searched++
            if (candidates != null && !candidates(entry)) continue
            if (s != null && s.hits(entry) == null) continue
            total++
        }
        SearchPage(
            page, if (complete) total else maxOf(total, page.size + (if (more) 1 else 0)), if (more) page.last().id else null, searched, notSearched(eval, s),
            counts = if (complete) null else Counts(complete = false, countedThrough = through, countedEntries = counted),
        )
    } }

    /**
     * Feature 067: [query]'s decision for one entry, with the same evaluators as [list] (filters,
     * then the search words): a live event is decided as a full pass would decide it.
     */
    fun decide(query: NetworkQuery, entry: NetworkEntry): EntryDecision = sources.open(null).use { src ->
        val ctx = context()
        val (filter, terms) = query.resolved()
        if (entry is LinkEntry && filter?.kinds == null) return@use EntryDecision.NO
        val s = search(src, terms, query.redact)
        val eval = evaluator(src, filter, ctx)
        val locations = if (eval != null) eval.matches(entry) else emptyList()
        val unchecked = (eval?.budget?.skipped ?: 0) > 0
        if (locations == null) return@use EntryDecision(false, unchecked = unchecked)
        val words = if (s != null) s.hits(entry) else emptyList()
        if (words == null) return@use EntryDecision(false, byPreview = s!!.byPreview > 0, unchecked = unchecked)
        val hits = words + eval?.patternHits?.get(entry.id).orEmpty()
        EntryDecision(true, locations.ifEmpty { null }, hits.ifEmpty { null }, byPreview = (s?.byPreview ?: 0) > 0, unchecked = unchecked)
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
        eval?.budget?.skipped?.takeIf { it > 0 }?.let { "bodies skipped: pattern too slow ($it)" },
        eval?.patternBinaryAll?.takeIf { it > 0 }?.let { "binary bodies without a schema, not matched by patterns ($it)" },
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

        /** Feature 069: a history read from [sources] as an answer walks it; counts stop at [countBudgetMs]. */
        fun over(
            sources: EntrySourceProvider,
            context: () -> HistoryContext,
            bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
            decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
            readEnvelope: (WsMessageEntry, String) -> Envelope? = { _, text -> WsFormats.detect(text) },
            patternBudget: () -> PatternBudget = { PatternBudget() },
            countBudgetMs: Long? = null,
            nanoTime: () -> Long = System::nanoTime,
        ) = NetworkHistoryView(sources, context, bodyBytes, decoded, readEnvelope, patternBudget, countBudgetMs, nanoTime)

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
