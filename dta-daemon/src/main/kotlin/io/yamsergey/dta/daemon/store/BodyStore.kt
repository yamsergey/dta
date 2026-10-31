package io.yamsergey.dta.daemon.store

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest

/** Bodies on the host by content (feature 011, FR-019): one file per distinct body. */
interface BodyStore {
    data class Stored(val sha256: String, val bytes: Long)

    /** Stores the stream's bytes; throws IOException when the host cannot (disk full). */
    fun put(input: InputStream): Stored

    fun open(sha256: String): InputStream?

    /** Removes the body; returns the bytes freed. */
    fun delete(sha256: String): Long
}

class FileBodyStore(private val root: File) : BodyStore {
    override fun put(input: InputStream): BodyStore.Stored {
        root.mkdirs()
        val temp = File.createTempFile("body-", ".part", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = DigestInputStream(input, digest).use { stream -> temp.outputStream().use { stream.copyTo(it) } }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val target = file(sha)
            if (target.isFile) {
                temp.delete()
            } else {
                target.parentFile.mkdirs()
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                runCatching { Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------")) }
            }
            return BodyStore.Stored(sha, bytes)
        } finally {
            temp.delete()
        }
    }

    override fun open(sha256: String): InputStream? = file(sha256).takeIf { it.isFile }?.inputStream()

    override fun delete(sha256: String): Long {
        val f = file(sha256)
        val size = f.length()
        return if (f.delete()) size else 0
    }

    private fun file(sha: String) = File(root, "${sha.take(2)}/$sha")
}
