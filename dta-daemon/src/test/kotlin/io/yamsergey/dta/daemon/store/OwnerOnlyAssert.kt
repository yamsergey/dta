package io.yamsergey.dta.daemon.store

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals

/** 011 FR-012 (D-051): every file a store writes in the DTA home can be read only by its owner. */
fun assertOwnerOnly(root: File) {
    val others = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
    val open = root.walkTopDown().filter { it.isFile }
        .filter { f -> Files.getPosixFilePermissions(f.toPath()).any { it in others } }
        .map { it.relativeTo(root).path }.toList()
    assertEquals(emptyList(), open, "files others can read")
}
