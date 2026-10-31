package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.store.ArchivingStoppedException
import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.Epoch
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.daemon.store.InstallationInfo
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.BodyRangeResult
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HistoryContext
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

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

    private val view = NetworkHistoryView({ live.values.toList() }, { live[it] }, { context!! }, ::hostBodyBytes)

    suspend fun list(query: NetworkQuery): NetworkPage = mutex.withLock {
        val app = connection()
        if (!sync(app)) return DtaJson.decodeFromJsonElement(NetworkPage.serializer(), app.request("network_list", query.toJson()))
        if (query.filter?.bodyContains != null) fetchBodies(app)
        val page = view.list(query)
        page.copy(
            coverage = page.coverage.copy(
                hostLagRecords = lag.takeIf { it > 0 },
                archivedEpochs = store.archivedCount(device.key, packageName).takeIf { it > 0 },
                archivingStopped = store.archivingStopped,
            ),
        )
    }

    /** The device cleared [clearedEntries]: those the host never read are counted on the archived epoch. */
    suspend fun cleared(clearedEntries: Int) = mutex.withLock {
        neverReceived = (clearedEntries - live.size).coerceAtLeast(0)
    }

    suspend fun entry(id: String): NetworkEntryDetail = mutex.withLock {
        val app = connection()
        if (!sync(app)) return DtaJson.decodeFromJsonElement(NetworkEntryDetail.serializer(), app.request("network_entry", buildJsonObject { put("id", id) }))
        view.entry(id)
    }

    /** Catches up with the journal; false when the app keeps no journal (it answers itself). */
    private suspend fun sync(app: AppConnection): Boolean {
        val identity = DtaJson.decodeFromJsonElement(JournalIdentityResult.serializer(), app.request("network_identity"))
        if (!identity.persisted) return false
        context = identity.context
        val current = currentEpoch(app, identity)
        var position = store.cursor(device.key, packageName)?.position
        repeat(catchUpBudgetPages) {
            val params = buildJsonObject {
                position?.let { put("after", it) }
                put("limit", pageSize)
            }
            val page = DtaJson.decodeFromJsonElement(JournalPageResult.serializer(), app.request("network_journal", params))
            // The journal changed while reading: the next answer starts the new epoch.
            if (page.journalId != identity.journalId) return true
            if (page.records.isNotEmpty()) {
                store.putEntries(current.id, page.records)
                page.records.forEach { live[it.id] = it }
            }
            position = page.next
            store.setCursor(device.key, packageName, identity.journalId, position)
            if (page.end) {
                lag = 0
                return true
            }
        }
        lag = (identity.entries - live.size).toLong().coerceAtLeast(0)
        return true
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

    /** Bodies a body search needs, copied to the host first (the copy worker of US7 does this for every body). */
    private suspend fun fetchBodies(app: AppConnection) {
        val e = epoch ?: return
        for (entry in live.values) {
            for (part in BodyPart.entries) {
                val info = NetworkHistoryView.bodyOf(entry, part) ?: continue
                if (info.storedBytes <= 0 || store.bodyRef(e.id, entry.id, part) != null) continue
                copyBody(app, e, entry.id, part)
            }
        }
    }

    private suspend fun copyBody(app: AppConnection, e: Epoch, id: String, part: BodyPart) {
        val out = ByteArrayOutputStream()
        val partName = DtaJson.encodeToJsonElement(BodyPart.serializer(), part).jsonPrimitive.content
        try {
            var offset = 0L
            while (true) {
                val response = app.requestFull(
                    "network_body_range",
                    buildJsonObject {
                        put("id", id)
                        put("part", partName)
                        put("offset", offset)
                        put("length", CHUNK)
                    },
                )
                val range = DtaJson.decodeFromJsonElement(BodyRangeResult.serializer(), response.result)
                response.binaryPayloads.forEach(out::write)
                offset += range.bytes
                if (range.bytes <= 0 || offset >= range.totalBytes) break
            }
            store.putBody(e.id, id, part, out.toByteArray().inputStream())
        } catch (_: ArchivingStoppedException) {
            // Stated in coverage (archivingStopped); the search goes on without this body.
        } catch (ex: Exception) {
            store.markNotArchived(e.id, id, part, "the device no longer had it: ${ex.message}")
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

    private companion object {
        const val CHUNK = 256L * 1024
    }
}
