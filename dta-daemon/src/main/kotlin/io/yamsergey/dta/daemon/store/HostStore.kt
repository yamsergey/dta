package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.ArchiveApp
import io.yamsergey.dta.protocol.network.ArchiveDeleted
import io.yamsergey.dta.protocol.network.ArchiveDevice
import io.yamsergey.dta.protocol.network.ArchiveEpoch
import io.yamsergey.dta.protocol.network.ArchiveInstallation
import io.yamsergey.dta.protocol.network.ArchiveListing
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.rpc.DtaJson
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/** What makes a device the same device over time (feature 011, research R4). */
data class DeviceIdentity(val key: String, val serial: String? = null, val model: String? = null, val release: String? = null)

/** One installed build of the app, from the sidekick's identity (research R5). */
data class InstallationInfo(
    val versionName: String? = null,
    val versionCode: Long? = null,
    val lastUpdateMs: Long? = null,
    val signingSha256: String? = null,
    val buildId: String? = null,
)

data class Epoch(val id: String, val installationId: Long, val journalId: String, val openedMs: Long, val closedMs: Long? = null, val reason: EpochCloseReason? = null)

data class Cursor(val journalId: String, val position: String?)

/** A body of an entry on the host: its content, or why it will never be here. */
data class BodyRef(val sha256: String?, val bytes: Long, val notArchived: String? = null)

sealed interface DeleteTarget {
    data class Epoch(val id: String) : DeleteTarget
    data class Installation(val id: Long) : DeleteTarget
    data class App(val deviceKey: String, val packageName: String) : DeleteTarget
    data class ClosedBefore(val ms: Long) : DeleteTarget
}

class LiveEpochException(message: String) : Exception(message)

/** The host could not store more; the live history keeps working from the device. */
class ArchivingStoppedException(message: String) : Exception(message)

/**
 * The daemon's network store (feature 011, research R2): every epoch of every app on every
 * device in one SQLite database, bodies by content in a [BodyStore]. The live epoch is the open
 * one; closed epochs are the archive, kept until deleted. Owner-only on disk (FR-021).
 */
class HostStore(private val root: File, private val bodies: BodyStore = FileBodyStore(File(root, "bodies"))) : AutoCloseable {
    private val db: Connection

    /** Set once archiving failed for lack of space or a write error; stated in every result. */
    @Volatile
    var archivingStopped: String? = null
        private set

    init {
        root.mkdirs()
        ownerOnly(root, directory = true)
        val file = File(root, "dta.db")
        db = DriverManager.getConnection("jdbc:sqlite:${file.path}")
        ownerOnly(file, directory = false)
        db.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            SCHEMA.forEach(st::execute)
        }
    }

    @Synchronized
    fun openEpoch(device: DeviceIdentity, packageName: String, installation: InstallationInfo, journalId: String, openedMs: Long): Epoch = tx {
        update(
            "INSERT INTO device(key, serial, model, release) VALUES (?,?,?,?) ON CONFLICT(key) DO UPDATE SET serial=excluded.serial, model=excluded.model, release=excluded.release",
            device.key, device.serial, device.model, device.release,
        )
        update(
            """INSERT INTO installation(device_key, package, version_name, version_code, last_update_ms, signing_sha256, build_id) VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(device_key, package, version_name, version_code, last_update_ms, signing_sha256) DO UPDATE SET build_id=coalesce(excluded.build_id, build_id)""",
            device.key, packageName, installation.versionName, installation.versionCode, installation.lastUpdateMs, installation.signingSha256, installation.buildId,
        )
        val installationId = query(
            "SELECT id FROM installation WHERE device_key=? AND package=? AND version_name IS ? AND version_code IS ? AND last_update_ms IS ? AND signing_sha256 IS ?",
            device.key, packageName, installation.versionName, installation.versionCode, installation.lastUpdateMs, installation.signingSha256,
        ) { it.getLong(1) }.single()
        val epoch = Epoch(UUID.randomUUID().toString(), installationId, journalId, openedMs)
        update("INSERT INTO epoch(id, installation_id, journal_id, opened_ms) VALUES (?,?,?,?)", epoch.id, installationId, journalId, openedMs)
        epoch
    }

    @Synchronized
    fun liveEpoch(deviceKey: String, packageName: String): Epoch? = query(
        "SELECT ${EPOCH_COLUMNS} FROM epoch e JOIN installation i ON e.installation_id=i.id WHERE i.device_key=? AND i.package=? AND e.closed_ms IS NULL ORDER BY e.opened_ms DESC LIMIT 1",
        deviceKey, packageName, map = ::epochOf,
    ).firstOrNull()

    @Synchronized
    fun closeEpoch(epochId: String, closedMs: Long, reason: EpochCloseReason, neverReceived: Int) {
        update("UPDATE epoch SET closed_ms=?, close_reason=?, never_received=? WHERE id=? AND closed_ms IS NULL", closedMs, reason.name, neverReceived, epochId)
    }

    /** Adds or replaces entries of [epochId]; a new entry gets the next sequence number. */
    @Synchronized
    fun putEntries(epochId: String, entries: List<NetworkEntry>) = tx {
        entries.forEach { e ->
            update(
                """INSERT INTO entry(epoch_id, entry_id, seq, time_ms, kind, url, status, json) VALUES (?,?,(SELECT coalesce(max(seq),0)+1 FROM entry WHERE epoch_id=?),?,?,?,?,?)
                   ON CONFLICT(epoch_id, entry_id) DO UPDATE SET time_ms=excluded.time_ms, kind=excluded.kind, url=excluded.url, status=excluded.status, json=excluded.json""",
                epochId, e.id, epochId, e.timeMs, kindOf(e), urlOf(e), (e as? HttpEntry)?.status, DtaJson.encodeToString(NetworkEntry.serializer(), e),
            )
        }
    }

    @Synchronized
    fun entries(epochId: String, afterSeq: Long = 0, limit: Int = Int.MAX_VALUE): List<NetworkEntry> = query(
        "SELECT json FROM entry WHERE epoch_id=? AND seq>? ORDER BY seq LIMIT ?", epochId, afterSeq, limit,
    ) { DtaJson.decodeFromString(NetworkEntry.serializer(), it.getString(1)) }

    @Synchronized
    fun entryCount(epochId: String): Int = query("SELECT count(*) FROM entry WHERE epoch_id=?", epochId) { it.getInt(1) }.single()

    /** Stores a body's bytes by content and refers [entryId]'s [part] to it. */
    fun putBody(epochId: String, entryId: String, part: BodyPart, input: InputStream): BodyRef {
        archivingStopped?.let { throw ArchivingStoppedException(it) }
        val stored = try {
            bodies.put(input)
        } catch (e: IOException) {
            val reason = "archiving stopped: the host could not store a body (${e.message})"
            archivingStopped = reason
            throw ArchivingStoppedException(reason)
        }
        synchronized(this) {
            tx {
                update("INSERT INTO body(sha256, bytes, ref_count) VALUES (?,?,0) ON CONFLICT(sha256) DO NOTHING", stored.sha256, stored.bytes)
                val previous = query("SELECT sha256 FROM body_ref WHERE epoch_id=? AND entry_id=? AND part=?", epochId, entryId, part.name) { it.getString(1) }.firstOrNull()
                if (previous != stored.sha256) {
                    update("UPDATE body SET ref_count=ref_count+1 WHERE sha256=?", stored.sha256)
                    previous?.let { update("UPDATE body SET ref_count=ref_count-1 WHERE sha256=?", it) }
                }
                update(
                    "INSERT INTO body_ref(epoch_id, entry_id, part, sha256, bytes, not_archived) VALUES (?,?,?,?,?,NULL) ON CONFLICT(epoch_id, entry_id, part) DO UPDATE SET sha256=excluded.sha256, bytes=excluded.bytes, not_archived=NULL",
                    epochId, entryId, part.name, stored.sha256, stored.bytes,
                )
            }
        }
        return BodyRef(stored.sha256, stored.bytes)
    }

    @Synchronized
    fun markNotArchived(epochId: String, entryId: String, part: BodyPart, reason: String) {
        update(
            "INSERT INTO body_ref(epoch_id, entry_id, part, sha256, bytes, not_archived) VALUES (?,?,?,NULL,0,?) ON CONFLICT(epoch_id, entry_id, part) DO UPDATE SET not_archived=excluded.not_archived",
            epochId, entryId, part.name, reason,
        )
    }

    @Synchronized
    fun bodyRef(epochId: String, entryId: String, part: BodyPart): BodyRef? = query(
        "SELECT sha256, bytes, not_archived FROM body_ref WHERE epoch_id=? AND entry_id=? AND part=?", epochId, entryId, part.name,
    ) { BodyRef(it.getString(1), it.getLong(2), it.getString(3)) }.firstOrNull()

    fun openBody(sha256: String): InputStream? = bodies.open(sha256)

    @Synchronized
    fun cursor(deviceKey: String, packageName: String): Cursor? =
        query("SELECT journal_id, position FROM cursor WHERE device_key=? AND package=?", deviceKey, packageName) { Cursor(it.getString(1), it.getString(2)) }.firstOrNull()

    @Synchronized
    fun setCursor(deviceKey: String, packageName: String, journalId: String, position: String?) {
        update(
            "INSERT INTO cursor(device_key, package, journal_id, position) VALUES (?,?,?,?) ON CONFLICT(device_key, package) DO UPDATE SET journal_id=excluded.journal_id, position=excluded.position",
            deviceKey, packageName, journalId, position,
        )
    }

    /** Closed epochs of the app that [scope] selects; the live epoch never is. */
    @Synchronized
    fun epochs(deviceKey: String, packageName: String, scope: ArchiveScope): List<Epoch> {
        val (clause, args) = when {
            scope.epoch != null -> "e.id=?" to listOf<Any?>(scope.epoch)
            scope.version != null -> "i.version_name=?" to listOf<Any?>(scope.version)
            scope.all == true -> "1=1" to emptyList()
            else -> "e.opened_ms<=? AND e.closed_ms>=?" to listOf<Any?>(scope.toMs ?: Long.MAX_VALUE, scope.fromMs ?: Long.MIN_VALUE)
        }
        return query(
            "SELECT $EPOCH_COLUMNS FROM epoch e JOIN installation i ON e.installation_id=i.id WHERE i.device_key=? AND i.package=? AND e.closed_ms IS NOT NULL AND $clause ORDER BY e.opened_ms",
            deviceKey, packageName, *args.toTypedArray(), map = ::epochOf,
        )
    }

    @Synchronized
    fun archivedCount(deviceKey: String, packageName: String): Int = query(
        "SELECT count(*) FROM epoch e JOIN installation i ON e.installation_id=i.id WHERE i.device_key=? AND i.package=? AND e.closed_ms IS NOT NULL",
        deviceKey, packageName,
    ) { it.getInt(1) }.single()

    /** The version of the installation an epoch belongs to. */
    @Synchronized
    fun versionOf(epochId: String): String? =
        query("SELECT i.version_name FROM epoch e JOIN installation i ON e.installation_id=i.id WHERE e.id=?", epochId) { it.getString(1) }.firstOrNull()

    @Synchronized
    fun listing(): ArchiveListing {
        data class Row(val device: String, val serial: String?, val model: String?, val pkg: String, val inst: Long, val vName: String?, val vCode: Long?, val update: Long?, val epoch: ArchiveEpoch)
        val rows = query(
            """SELECT d.key, d.serial, d.model, i.package, i.id, i.version_name, i.version_code, i.last_update_ms,
                      e.id, e.opened_ms, e.closed_ms, e.close_reason, e.never_received,
                      (SELECT count(*) FROM entry WHERE epoch_id=e.id),
                      (SELECT coalesce(sum(b.bytes),0) FROM body_ref b WHERE b.epoch_id=e.id)
               FROM epoch e JOIN installation i ON e.installation_id=i.id JOIN device d ON i.device_key=d.key ORDER BY d.key, i.package, i.id, e.opened_ms""",
        ) { r ->
            Row(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5), r.getString(6), r.getLongOrNull(7), r.getLongOrNull(8),
                ArchiveEpoch(r.getString(9), r.getLong(10), r.getLongOrNull(11), r.getString(12)?.let(EpochCloseReason::valueOf), r.getInt(14), r.getLong(15), r.getInt(13)),
            )
        }
        val devices = rows.groupBy { it.device }.map { (key, d) ->
            ArchiveDevice(key, d.first().serial, d.first().model, d.groupBy { it.pkg }.map { (pkg, a) ->
                ArchiveApp(pkg, a.groupBy { it.inst }.map { (_, i) -> ArchiveInstallation(i.first().vName, i.first().vCode, i.first().update, i.map { it.epoch }) })
            })
        }
        return ArchiveListing(devices, rows.sumOf { it.epoch.bytes })
    }

    /** Deletes archived epochs; never the live one (it is cleared on the device, which archives it). */
    @Synchronized
    fun delete(target: DeleteTarget): ArchiveDeleted {
        val ids: List<String> = when (target) {
            is DeleteTarget.Epoch -> {
                val closed = query("SELECT closed_ms FROM epoch WHERE id=?", target.id) { it.getLongOrNull(1) }
                if (closed.isNotEmpty() && closed.single() == null) {
                    throw LiveEpochException("Epoch ${target.id} is the live history; it is not deleted from the archive. Clear it on the device (dta network clear): that archives it.")
                }
                closed.map { target.id }
            }
            is DeleteTarget.Installation -> query("SELECT id FROM epoch WHERE installation_id=? AND closed_ms IS NOT NULL", target.id) { it.getString(1) }
            is DeleteTarget.App -> query(
                "SELECT e.id FROM epoch e JOIN installation i ON e.installation_id=i.id WHERE i.device_key=? AND i.package=? AND e.closed_ms IS NOT NULL",
                target.deviceKey, target.packageName,
            ) { it.getString(1) }
            is DeleteTarget.ClosedBefore -> query("SELECT id FROM epoch WHERE closed_ms IS NOT NULL AND closed_ms<?", target.ms) { it.getString(1) }
        }
        var entries = 0
        val freed = mutableListOf<String>()
        tx {
            ids.forEach { id ->
                entries += query("SELECT count(*) FROM entry WHERE epoch_id=?", id) { it.getInt(1) }.single()
                query("SELECT sha256 FROM body_ref WHERE epoch_id=? AND sha256 IS NOT NULL", id) { it.getString(1) }.forEach { sha ->
                    update("UPDATE body SET ref_count=ref_count-1 WHERE sha256=?", sha)
                }
                update("DELETE FROM body_ref WHERE epoch_id=?", id)
                update("DELETE FROM entry WHERE epoch_id=?", id)
                update("DELETE FROM epoch WHERE id=?", id)
            }
            freed += query("SELECT sha256 FROM body WHERE ref_count<=0") { it.getString(1) }
            update("DELETE FROM body WHERE ref_count<=0")
        }
        val bytes = freed.sumOf { bodies.delete(it) }
        return ArchiveDeleted(ids.size, entries, freed.size, bytes)
    }

    override fun close() = db.close()

    // --- plumbing

    private fun <T> tx(block: () -> T): T {
        db.autoCommit = false
        try {
            return block().also { db.commit() }
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    private fun update(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeUpdate()
    }

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    private fun epochOf(r: ResultSet) = Epoch(r.getString(1), r.getLong(2), r.getString(3), r.getLong(4), r.getLongOrNull(5), r.getString(6)?.let(EpochCloseReason::valueOf))

    private fun ResultSet.getLongOrNull(i: Int): Long? = getLong(i).takeUnless { wasNull() }

    private fun kindOf(e: NetworkEntry) = when (e) {
        is HttpEntry -> "http"
        is WsConnectionEntry -> "ws-connection"
        is WsMessageEntry -> "ws-message"
    }

    private fun urlOf(e: NetworkEntry): String? = when (e) {
        is HttpEntry -> e.url
        is WsConnectionEntry -> e.url
        is WsMessageEntry -> null
    }

    private companion object {
        const val EPOCH_COLUMNS = "e.id, e.installation_id, e.journal_id, e.opened_ms, e.closed_ms, e.close_reason"

        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS device(key TEXT PRIMARY KEY, serial TEXT, model TEXT, release TEXT)",
            """CREATE TABLE IF NOT EXISTS installation(id INTEGER PRIMARY KEY, device_key TEXT NOT NULL REFERENCES device(key), package TEXT NOT NULL,
               version_name TEXT, version_code INTEGER, last_update_ms INTEGER, signing_sha256 TEXT, build_id TEXT,
               UNIQUE(device_key, package, version_name, version_code, last_update_ms, signing_sha256))""",
            """CREATE TABLE IF NOT EXISTS epoch(id TEXT PRIMARY KEY, installation_id INTEGER NOT NULL REFERENCES installation(id), journal_id TEXT NOT NULL,
               opened_ms INTEGER NOT NULL, closed_ms INTEGER, close_reason TEXT, never_received INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS entry(epoch_id TEXT NOT NULL REFERENCES epoch(id), entry_id TEXT NOT NULL, seq INTEGER NOT NULL,
               time_ms INTEGER NOT NULL, kind TEXT NOT NULL, url TEXT, status INTEGER, json TEXT NOT NULL, PRIMARY KEY(epoch_id, entry_id))""",
            "CREATE INDEX IF NOT EXISTS entry_seq ON entry(epoch_id, seq)",
            "CREATE INDEX IF NOT EXISTS entry_time ON entry(epoch_id, time_ms)",
            "CREATE TABLE IF NOT EXISTS body(sha256 TEXT PRIMARY KEY, bytes INTEGER NOT NULL, ref_count INTEGER NOT NULL)",
            """CREATE TABLE IF NOT EXISTS body_ref(epoch_id TEXT NOT NULL, entry_id TEXT NOT NULL, part TEXT NOT NULL, sha256 TEXT, bytes INTEGER NOT NULL,
               not_archived TEXT, PRIMARY KEY(epoch_id, entry_id, part))""",
            "CREATE TABLE IF NOT EXISTS cursor(device_key TEXT NOT NULL, package TEXT NOT NULL, journal_id TEXT NOT NULL, position TEXT, PRIMARY KEY(device_key, package))",
        )

        fun ownerOnly(file: File, directory: Boolean) {
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            }
        }
    }
}
