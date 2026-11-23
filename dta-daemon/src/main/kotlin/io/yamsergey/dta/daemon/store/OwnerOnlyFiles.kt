package io.yamsergey.dta.daemon.store

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * 011 FR-012 (D-051): a file in the DTA home is readable only by its owner. It is written to a
 * sibling created owner-only before it holds anything, then moved into place, so a reader never
 * sees it half written and it is never open to others, whatever the umask.
 */
object OwnerOnlyFiles {
    fun write(file: File, text: String) = write(file, text.toByteArray())

    fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.delete()
        runCatching { Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
        tmp.writeBytes(bytes)
        runCatching { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
