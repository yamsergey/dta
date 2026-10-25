package io.yamsergey.dta.sidekick.network.store

import java.io.File

/**
 * Where the network history lives: `<root>/segments` (JSON-lines log) and
 * `<root>/bodies` (one file per stored body). On device the root is
 * `noBackupFilesDir/dta/network`; tests pass a temp dir (constitution IV).
 */
class StorageDir(val root: File) {
    val segments = File(root, "segments")
    val bodies = File(root, "bodies")

    /** Creates the layout; false when the location cannot hold the history. */
    fun prepare(): Boolean = try {
        segments.mkdirs()
        bodies.mkdirs()
        segments.isDirectory && bodies.isDirectory && segments.canWrite() && bodies.canWrite()
    } catch (_: Throwable) {
        false
    }
}
