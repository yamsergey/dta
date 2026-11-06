package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.connection.ConnectionLostException
import io.yamsergey.dta.daemon.connection.RequestTimeoutException
import io.yamsergey.dta.daemon.store.ArchivingStoppedException
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.Epoch
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.daemon.store.InstallationInfo
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.BodyRangeResult
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HistoryContext
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.network.JournalIdentityResult
import io.yamsergey.dta.protocol.network.JournalPageResult
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.SearchPage
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One app's network history on the host (feature 011 US2): caught up from the app's journal
 * before every answer, kept in the [HostStore] (the live epoch) and decoded in memory, and
 * answered with the same [NetworkHistoryView] the app would use — so the answers are the app's.
 * An app without storage answers itself, as before (memory-only fallback, FR-011).
 */
class HostIndex(
    private val store: HostStore,
    private val device: DeviceIdentity,
    private val packageName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The app's protobuf decoding (its schemas and mappings), for search in decoded bodies (US6). */
    private val decoding: () -> io.yamsergey.dta.daemon.network.proto.BodyDecoding = { io.yamsergey.dta.daemon.network.proto.BodyDecoding.NONE },
    private val connection: suspend () -> AppConnection,
) {
    /** Journal records per request, and requests per answer before the rest is left to the next one. */
    var pageSize = 500
    var catchUpBudgetPages = 40

    private val mutex = Mutex()
    private var epoch: Epoch? = null
    private val live = LinkedHashMap<String, NetworkEntry>()
    private var context: HistoryContext? = null
    private var lag = 0L
    /** Entries the device cleared that the host never read, known when the clear went through the daemon. */
    private var neverReceived = 0

    private val view = NetworkHistoryView({ live.values.toList() }, { live[it] }, { context!! }, ::hostBodyBytes) { entry, part ->
        decodedJson(entry, part, epoch?.id, live::get)
    }

    suspend fun list(query: NetworkQuery): NetworkPage = mutex.withLock {
        query.archive?.let { return archived(query, it) }
        val app = connection()
        if (!sync(app)) return appAnswers(app, query)
        if (needsBodies(query)) fetchBodies()
        val page = view.list(query)
        page.copy(
            coverage = page.coverage.copy(
                hostLagRecords = lag.takeIf { it > 0 },
                bodiesPending = pending().size.takeIf { it > 0 },
                bodiesNotArchived = epoch?.let { store.countNotArchived(it.id) }?.takeIf { it > 0 },
                archivedEpochs = store.archivedCount(device.key, packageName).takeIf { it > 0 },
                archivingStopped = store.archivingStopped,
            ),
        )
    }

    /** Feature 040: the entries with search hits, over the host copy (live or archived). */
    suspend fun search(query: NetworkQuery): SearchPage = mutex.withLock {
        query.archive?.let { scope -> return archiveView(store.epochs(device.key, packageName, scope)).search(query) }
        val app = connection()
        if (!sync(app)) {
            return SearchPage(emptyList(), 0, searched = 0, degraded = listOf(DegradationMarker(NetworkHistoryView.CAPABILITY, DegradationCodes.NETWORK_SEARCH_UNAVAILABLE)))
        }
        fetchBodies()
        view.search(query)
    }

    private fun needsBodies(query: NetworkQuery): Boolean {
        val (filter, terms) = query.resolved()
        return terms.isNotEmpty() || filter?.bodyContains != null || filter?.exclude?.any { it.bodyContains != null } == true
    }

    /**
     * The app answers itself (no storage): it is given the filters every app version knows; what
     * only the host can do (search words, host:, exclusions) is left out and said so.
     */
    
    /** Catches up with the journal without answering anything (the copy worker's loop). */
    suspend fun refresh() = mutex.withLock { sync(connection()) }

    /** The device cleared [clearedEntries]: those the host never read are counted on the archived epoch. */
    suspend fun cleared(clearedEntries: Int) = mutex.withLock {
        neverReceived = (clearedEntries - live.size).coerceAtLeast(0)
    }

    /** An entry of the live history, else of the archive (marked); the archive answers with the app gone. */
    suspend fun entry(id: String): NetworkEntryDetail = mutex.withLock {
        val app = runCatching { connection() }.getOrNull()
        if (app != null) {
            if (!sync(app)) return DtaJson.decodeFromJsonElement(NetworkEntryDetail.serializer(), app.request("network_entry", buildJsonObject { put("id", id) }))
            if (live.containsKey(id)) return view.entry(id)
        }
        val (archivedEpoch, _) = store.findArchived(device.key, packageName, id) ?: run {
            if (app == null) connection() // the app is gone and the archive has nothing: say why the app cannot answer
            return view.entry(id)
        }
        archiveView(listOf(archivedEpoch)).entry(id).copy(archived = mark(archivedEpoch))
    }

    /** A body as the host holds it (live or archived), or null when it has not been copied. */
    suspend fun storedBody(id: String, part: BodyPart): Pair<BodyInfo, ByteArray>? = mutex.withLock {
        val (epochId, entry) = epoch?.takeIf { live.containsKey(id) }?.let { it.id to live.getValue(id) }
            ?: store.findArchived(device.key, packageName, id)?.let { (e, entry) -> e.id to entry }
            ?: return null
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        val sha = store.bodyRef(epochId, id, part)?.sha256 ?: return null
        val bytes = store.openBody(sha)?.use { it.readBytes() } ?: return null
        info to bytes
    }

    /** Archived epochs answer the same queries from the host alone (US4). */
    private fun archived(query: NetworkQuery, scope: io.yamsergey.dta.protocol.network.ArchiveScope): NetworkPage {
        val epochs = store.epochs(device.key, packageName, scope)
        val page = archiveView(epochs).list(query)
        val owner = HashMap<String, Epoch>()
        epochs.forEach { e -> store.entries(e.id).forEach { owner[it.id] = e } }
        val marks = epochs.associate { it.id to mark(it) }
        return page.copy(archived = page.entries.associate { it.id to marks.getValue(owner.getValue(it.id).id) }.ifEmpty { null })
    }

    private fun archiveView(epochs: List<Epoch>): NetworkHistoryView {
        val entries = LinkedHashMap<String, NetworkEntry>()
        val epochOf = HashMap<String, String>()
        epochs.forEach { e -> store.entries(e.id).forEach { entries[it.id] = it; epochOf[it.id] = e.id } }
        val context = HistoryContext(
            runId = "",
            coverage = io.yamsergey.dta.protocol.network.Coverage(searched = entries.size, stored = entries.size, persisted = true),
            capture = io.yamsergey.dta.protocol.rpc.CapabilityStatus("network", io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status.AVAILABLE),
        )
        return NetworkHistoryView(
            { entries.values.toList() }, { entries[it] }, { context },
            { entry, part ->
                val info = NetworkHistoryView.bodyOf(entry, part)
                val raw = info?.let { rawBody(epochOf.getValue(entry.id), entry.id, part) }
                raw?.let { BodyCodec.decode(it, info.encoding).let { d -> NetworkFilterEval.BodyBytes(d.bytes, d.decoded) } }
            },
        ) { entry, part -> decodedJson(entry, part, epochOf[entry.id], entries::get) }
    }

    private fun rawBody(epochId: String, entryId: String, part: BodyPart): ByteArray? {
        val sha = store.bodyRef(epochId, entryId, part)?.sha256 ?: return null
        return store.openBody(sha)?.use { it.readBytes() }
    }

    /** A host-held binary body as the app's schemas decode it; null when none applies or it is not here. */
    private fun decodedJson(entry: NetworkEntry, part: BodyPart, epochId: String?, byId: (String) -> NetworkEntry?): String? {
        val d = decoding()
        if (d === io.yamsergey.dta.daemon.network.proto.BodyDecoding.NONE || epochId == null) return null
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        val mappingPart = when (part) {
            BodyPart.REQUEST, BodyPart.APP_REQUEST -> io.yamsergey.dta.daemon.network.proto.MappingPart.REQUEST
            BodyPart.RESPONSE, BodyPart.APP_RESPONSE -> io.yamsergey.dta.daemon.network.proto.MappingPart.RESPONSE
            BodyPart.MESSAGE, BodyPart.APP_MESSAGE -> io.yamsergey.dta.daemon.network.proto.MappingPart.MESSAGE
        }
        val target = when (entry) {
            is HttpEntry -> io.yamsergey.dta.daemon.network.proto.BodyTarget(entry.url, entry.method, mappingPart, null, info.contentType)
            is io.yamsergey.dta.protocol.network.WsMessageEntry -> {
                val socket = byId(entry.connectionId) as? WsConnectionEntry ?: return null
                io.yamsergey.dta.daemon.network.proto.BodyTarget(socket.url, null, mappingPart, entry.direction, info.contentType)
            }
            else -> return null
        }
        val raw = rawBody(epochId, entry.id, part) ?: return null
        return runCatching { d.decode(target, raw, info).json }.getOrNull()
    }

    private fun mark(e: Epoch) = io.yamsergey.dta.protocol.network.ArchivedMark(e.id, e.reason ?: EpochCloseReason.UNKNOWN, e.closedMs ?: 0, store.versionOf(e.id))

    /** Catches up with the journal; false when the app keeps no journal (it answers itself). */
    private suspend fun sync(app: AppConnection): Boolean {
        val identity = DtaJson.decodeFromJsonElement(JournalIdentityResult.serializer(), app.request("network_identity"))
        if (!identity.persisted) return false
        context = identity.context
        var current = currentEpoch(app, identity)
        val saved = store.cursor(device.key, packageName)
        var position = saved?.position
        var lastId = saved?.lastId
        // The first page starts on the line of the last record read, so reading it also checks it (feature 046).
        var check = previousLine(position)
        repeat(catchUpBudgetPages) {
            val params = buildJsonObject {
                (check ?: position)?.let { put("after", it) }
                // One more for the record checked, so that every page moves on.
                put("limit", if (check != null) pageSize + 1 else pageSize)
            }
            val page = DtaJson.decodeFromJsonElement(JournalPageResult.serializer(), app.request("network_journal", params))
            // The journal changed while reading: the next answer starts the new epoch.
            if (page.journalId != identity.journalId) return true
            var records = page.records
            var next = page.next
            check?.let { line ->
                check = null
                when (continuity(app, line, lastId, records.firstOrNull()?.id, identity)) {
                    Continuity.SAME -> records = records.drop(1)
                    Continuity.GOES_ON -> if (records.isEmpty() && next == line) next = position!!
                    Continuity.ROLLED_BACK -> {
                        current = reopen(app, current, identity)
                        position = null
                        lastId = null
                        return@repeat
                    }
                }
            }
            if (records.isNotEmpty()) {
                store.putEntries(current.id, records)
                records.forEach { live[it.id] = it }
                lastId = records.last().id
            }
            position = next
            store.setCursor(device.key, packageName, identity.journalId, position, lastId)
            if (page.end) {
                lag = 0
                return true
            }
        }
        lag = (identity.entries - live.size).toLong().coerceAtLeast(0)
        return true
    }

    private enum class Continuity { SAME, GOES_ON, ROLLED_BACK }

    /**
     * Is this still the journal the daemon was reading (feature 046)? An emulator's snapshot takes
     * the journal back with its id, so the id alone cannot say. [first] is what the journal has on
     * the [line] of the last record read, [lastId].
     */
    private suspend fun continuity(app: AppConnection, line: String, lastId: String?, first: String?, identity: JournalIdentityResult): Continuity {
        val coverage = identity.context.coverage
        if (lastId == null) {
            // A position from before the record was remembered: only one beyond the journal shows.
            return if (first == null && coverage.evicted == 0) Continuity.ROLLED_BACK else Continuity.GOES_ON
        }
        if (first == lastId) return Continuity.SAME
        val time = live[lastId]?.timeMs
        val evicted = coverage.evicted > 0 && (coverage.earliestMs == null || time == null || time <= coverage.earliestMs!!)
        if (evicted) return Continuity.GOES_ON
        // That line was cut when read (being written, or a crash): the record is one line earlier.
        val earlier = previousLine(line) ?: return Continuity.ROLLED_BACK
        val params = buildJsonObject {
            put("after", earlier)
            put("limit", 1)
        }
        val before = DtaJson.decodeFromJsonElement(JournalPageResult.serializer(), app.request("network_journal", params))
        return if (before.records.firstOrNull()?.id == lastId) Continuity.GOES_ON else Continuity.ROLLED_BACK
    }

    /** The journal went back: what was read is archived, and it is read again from its start. */
    private suspend fun reopen(app: AppConnection, current: Epoch, identity: JournalIdentityResult): Epoch {
        store.closeEpoch(current.id, clock(), EpochCloseReason.ROLLED_BACK, neverReceived)
        neverReceived = 0
        val opened = store.openEpoch(device, packageName, installation(app), identity.journalId, clock())
        live.clear()
        store.setCursor(device.key, packageName, identity.journalId, null)
        epoch = opened
        return opened
    }

    /** The position one line earlier, for a `segment:line` position; null when there is none. */
    private fun previousLine(position: String?): String? {
        val parts = position?.split(':')?.takeIf { it.size == 2 } ?: return null
        val line = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return "${parts[0]}:${line - 1}"
    }

    /** The open epoch of this journal; a different journal closes the old one (the archive) and opens one. */
    private suspend fun currentEpoch(app: AppConnection, identity: JournalIdentityResult): Epoch {
        val known = epoch ?: store.liveEpoch(device.key, packageName)?.also { e ->
            live.clear()
            store.entries(e.id).forEach { live[it.id] = it }
        }
        val cursor = store.cursor(device.key, packageName)
        if (known != null && cursor?.journalId == identity.journalId && known.journalId == identity.journalId) return known.also { epoch = it }
        val installation = installation(app)
        known?.let {
            store.closeEpoch(it.id, clock(), closeReason(identity, store.installationOf(it.id), installation), neverReceived)
            neverReceived = 0
        }
        val opened = store.openEpoch(device, packageName, installation, identity.journalId, clock())
        live.clear()
        store.setCursor(device.key, packageName, identity.journalId, null)
        epoch = opened
        return opened
    }

    /**
     * Why the device forgot (research R7): a clear says so; new storage is a new build when the
     * version changed, a reinstall when only the install time did, else the app's data was wiped.
     */
    private fun closeReason(identity: JournalIdentityResult, before: InstallationInfo?, now: InstallationInfo): EpochCloseReason = when {
        identity.reason == "cleared" -> EpochCloseReason.CLEARED
        identity.reason != "storage-new" || before == null -> EpochCloseReason.UNKNOWN
        before.versionCode != now.versionCode || before.versionName != now.versionName || before.signingSha256 != now.signingSha256 -> EpochCloseReason.NEW_BUILD
        before.lastUpdateMs != now.lastUpdateMs -> EpochCloseReason.REINSTALLED
        else -> EpochCloseReason.DATA_WIPED
    }

    private suspend fun installation(app: AppConnection): InstallationInfo {
        val report = runCatching { DtaJson.decodeFromJsonElement(IdentityReport.serializer(), app.request("identity")) }.getOrNull()
        val a = report?.app ?: return InstallationInfo()
        return InstallationInfo(a.versionName, a.versionCode, a.lastUpdateMs, a.signingSha256)
    }

    /** A body of a finished entry not on the host yet. */
    data class PendingBody(val entryId: String, val part: BodyPart, val bytes: Long)

    /** Bodies of finished entries in the live epoch that the host has not copied (US7). */
    suspend fun pendingBodies(): List<PendingBody> = mutex.withLock { pending() }

    private fun pending(): List<PendingBody> {
        val e = epoch ?: return emptyList()
        return live.values.filter { !inFlight(it) }.flatMap { entry ->
            BodyPart.entries.mapNotNull { part ->
                val info = NetworkHistoryView.bodyOf(entry, part) ?: return@mapNotNull null
                if (info.storedBytes <= 0 || store.bodyRef(e.id, entry.id, part) != null) null else PendingBody(entry.id, part, info.storedBytes)
            }
        }
    }

    private fun inFlight(entry: NetworkEntry): Boolean = when (entry) {
        is HttpEntry -> entry.state == HttpState.PENDING
        is WsConnectionEntry -> entry.state == WsState.OPEN || entry.state == WsState.CONNECTING
        else -> false
    }

    /** Bodies a body search needs, copied to the host first (the copy worker does this for every body). */
    private suspend fun fetchBodies() {
        pending().forEach { copyBody(it.entryId, it.part) }
    }

    /**
     * Copies one body of the live epoch in [CHUNK] ranges over the app's channel, resuming a
     * partial copy; [paced] runs after each chunk with its bytes and round-trip (the worker's
     * budget). A lost link propagates (the copy resumes later); a body the app no longer has is
     * marked "not archived" with the reason.
     */
    suspend fun copyBody(entryId: String, part: BodyPart, paced: suspend (bytes: Long, ms: Long) -> Unit = { _, _ -> }) {
        val e = epoch ?: return
        val app = connection()
        val partial = store.partialFile(e.id, entryId, part)
        val partName = DtaJson.encodeToJsonElement(BodyPart.serializer(), part).jsonPrimitive.content
        try {
            while (true) {
                val offset = partial.length()
                val started = clock()
                val response = app.requestFull(
                    "network_body_range",
                    buildJsonObject {
                        put("id", entryId)
                        put("part", partName)
                        put("offset", offset)
                        put("length", CHUNK)
                    },
                )
                val range = DtaJson.decodeFromJsonElement(BodyRangeResult.serializer(), response.result)
                java.io.FileOutputStream(partial, true).use { out -> response.binaryPayloads.forEach(out::write) }
                paced(range.bytes, clock() - started)
                if (range.bytes <= 0 || partial.length() >= range.totalBytes) break
            }
            partial.inputStream().use { store.putBody(e.id, entryId, part, it) }
            partial.delete()
        } catch (lost: ConnectionLostException) {
            throw lost
        } catch (timeout: RequestTimeoutException) {
            throw timeout
        } catch (_: ArchivingStoppedException) {
            // Stated in coverage (archivingStopped).
        } catch (ex: Exception) {
            partial.delete()
            store.markNotArchived(e.id, entryId, part, "the app no longer had it: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    private fun hostBodyBytes(entry: NetworkEntry, part: BodyPart): NetworkFilterEval.BodyBytes? {
        val e = epoch ?: return null
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        val sha = store.bodyRef(e.id, entry.id, part)?.sha256 ?: return null
        val raw = store.openBody(sha)?.use { it.readBytes() } ?: return null
        val decoded = BodyCodec.decode(raw, info.encoding)
        return NetworkFilterEval.BodyBytes(decoded.bytes, decoded.decoded)
    }

    companion object {
        private const val CHUNK = 256L * 1024

        /**
         * Feature 040: the app answers a list itself — given the filters every app version knows;
         * search words, host: and exclusions are left out and the page says so.
         */
        suspend fun appAnswers(app: AppConnection, query: NetworkQuery): NetworkPage {
            val (filter, terms) = query.resolved()
            val unsupported = terms.isNotEmpty() || filter?.host != null || filter?.exclude != null
            val sent = query.copy(q = null, filter = filter?.copy(host = null, exclude = null)?.takeIf { it != NetworkFilter() })
            val page = DtaJson.decodeFromJsonElement(NetworkPage.serializer(), app.request("network_list", sent.toJson()))
            if (!unsupported) return page
            return page.copy(degraded = page.degraded.orEmpty() + DegradationMarker(NetworkHistoryView.CAPABILITY, DegradationCodes.NETWORK_SEARCH_UNAVAILABLE))
        }
    }
}
