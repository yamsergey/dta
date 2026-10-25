package io.yamsergey.dta.sidekick.network.script

import java.io.File

/** A persisted script: what [ScriptStore.load] returns. */
data class StoredScript(val source: String, val installedAtMs: Long)

/**
 * `script.js` + `script.meta` next to the network history (app-private, removed with the
 * app's data). Writes go to a temp file and are renamed. Never throws: an unusable
 * location just cannot persist, which the status states.
 */
class ScriptStore(private val dir: File?) {

    private val source get() = dir?.let { File(it, "script.js") }
    private val meta get() = dir?.let { File(it, "script.meta") }

    fun save(text: String, installedAtMs: Long): Boolean {
        val target = dir ?: return false
        return try {
            if (!target.isDirectory && !target.mkdirs()) return false
            write(source!!, text)
            write(meta!!, installedAtMs.toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun load(): StoredScript? = try {
        val text = source?.takeIf { it.isFile }?.readText()
        val time = meta?.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        if (text != null && time != null) StoredScript(text, time) else null
    } catch (_: Throwable) {
        null
    }

    fun clear() {
        try {
            source?.delete()
            meta?.delete()
        } catch (_: Throwable) {
        }
    }

    private fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.name}" }
        }
    }
}
