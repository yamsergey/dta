package io.yamsergey.dta.daemon.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** Where a running daemon can be found (data-model DaemonState). */
@Serializable
data class DaemonState(
    val pid: Long,
    val port: Int,
    val version: String,
    val build: String,
    val protocolVersion: Int,
    val startedAtMs: Long,
)

/**
 * The daemon's files under `<home>/daemon/` (research R2): `daemon.json` (written atomically
 * once the socket listens, deleted on clean stop) and `token` (owner-only, new per start).
 * Shared by the daemon (writer) and the launcher (reader).
 */
class StateFile(val dir: File) {
    val stateFile = File(dir, "daemon.json")
    val tokenFile = File(dir, "token")
    val lockFile = File(dir, "start.lock")
    val logFile = File(dir, "daemon.log")

    private val json = Json { ignoreUnknownKeys = true }

    fun read(): DaemonState? = runCatching { json.decodeFromString(DaemonState.serializer(), stateFile.readText()) }.getOrNull()

    fun write(state: DaemonState) {
        dir.mkdirs()
        val tmp = File(dir, "daemon.json.tmp")
        tmp.writeText(json.encodeToString(DaemonState.serializer(), state))
        Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Deletes the state file only while it still describes [pid] (a successor may have written its own). */
    fun deleteIfOwnedBy(pid: Long) {
        if (read()?.pid == pid) stateFile.delete()
    }

    /** A fresh random token, readable only by the owning user. */
    fun newToken(): String {
        dir.mkdirs()
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val tmp = File(dir, "token.tmp")
        tmp.delete()
        runCatching {
            Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }.onFailure { tmp.createNewFile() }
        tmp.writeText(token)
        Files.move(tmp.toPath(), tokenFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return token
    }

    fun token(): String? = runCatching { tokenFile.readText().trim() }.getOrNull()
}
