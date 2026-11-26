package io.yamsergey.dta.daemon

import io.yamsergey.dta.protocol.network.FingerprintKey
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * Feature 072: what the daemon keeps in the DTA home for agents — the one setting "agents see
 * redacted data" (`agents.json`, on unless it says otherwise, FR-001) and the key fingerprints are
 * made under (`fingerprint.key`, 32 random bytes, owner only, made on first need, FR-007, R4).
 * Only the plugin changes the setting (FR-003); the operations check that.
 */
class AgentSettings(private val home: File?, redaction: Boolean? = null) {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Serializable
    private data class Stored(@kotlinx.serialization.EncodeDefault val redaction: Boolean = true)

    private val settingsFile get() = File(home!!, "agents.json")
    private val keyFile get() = File(home!!, "fingerprint.key")

    @Volatile var redaction: Boolean = redaction ?: readSetting()
        private set

    /** Sets the setting; the next answer and the next event of a running subscription follow it. */
    @Synchronized fun setRedaction(on: Boolean) {
        if (home != null) {
            home.mkdirs()
            val tmp = File(home, "agents.json.tmp")
            tmp.writeText(DtaJson.encodeToString(Stored.serializer(), Stored(on)))
            Files.move(tmp.toPath(), settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        redaction = on
    }

    /** The fingerprint key: read from the home, or made there on first need. */
    val key: FingerprintKey by lazy { loadOrCreateKey() }

    private fun readSetting(): Boolean {
        val file = settingsFile.takeIf { it.isFile } ?: return true
        // A file that cannot be read means "on": never open the data for a damaged setting.
        return runCatching { DtaJson.decodeFromString(Stored.serializer(), file.readText()).redaction }.getOrDefault(true)
    }

    @Synchronized private fun loadOrCreateKey(): FingerprintKey {
        if (home == null) return FingerprintKey(ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) })
        if (keyFile.exists()) {
            val bytes = keyFile.readBytes()
            check(bytes.size == KEY_BYTES) { "${keyFile.path} is ${bytes.size} bytes, not $KEY_BYTES: delete it to make a new key (earlier fingerprints stop matching)" }
            return FingerprintKey(bytes)
        }
        home.mkdirs()
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val tmp = File(home, "fingerprint.key.tmp")
        tmp.delete()
        Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return FingerprintKey(bytes)
    }

    companion object {
        const val KEY_BYTES = 32

        /** No files: the setting [redaction] (on by default) and a key of this process; for tests and tools that have no home. */
        fun inMemory(redaction: Boolean = true) = AgentSettings(null, redaction)
    }
}
