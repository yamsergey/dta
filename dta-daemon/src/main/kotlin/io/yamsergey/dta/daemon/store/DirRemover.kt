package io.yamsergey.dta.daemon.store

import java.io.File
import java.util.UUID

/**
 * Removes a directory without waiting for it: it is renamed aside at once (nothing in it is served
 * any more) and [remove] deletes it. An archive cleared with millions of body files took minutes
 * to remove, the daemon's first answer waiting meanwhile (2026-11-20).
 */
class DirRemover(private val remove: (File) -> Unit = { dir -> Thread({ dir.deleteRecursively() }, "dta-dir-remover").apply { isDaemon = true }.start() }) {
    fun removeLater(dir: File) {
        if (!dir.exists()) return
        val aside = File(dir.parentFile, "${dir.name}$REMOVING${UUID.randomUUID()}")
        if (dir.renameTo(aside)) remove(aside) else dir.deleteRecursively()
    }

    /** Finishes removals in [root] cut short by a stop. */
    fun resume(root: File) {
        root.listFiles()?.filter { REMOVING in it.name }?.forEach(remove)
    }

    companion object {
        const val REMOVING = ".removing-"
    }
}
