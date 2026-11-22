package io.yamsergey.dta.studio.network

import com.intellij.ide.util.PropertiesComponent

/**
 * D-024: the daemon knows a network script by the hash of its source (`abc123def456`); the plugin
 * remembers the file it installed it from, so the Network tab names it ("auth-header.js") and Edit opens it.
 */
object ScriptNames {
    private const val KEY = "dta.script.file."

    fun remember(id: String, path: String) = PropertiesComponent.getInstance().setValue(KEY + id, path)

    /** The file [id] was installed from here; null when it came from elsewhere (an agent, the CLI). */
    fun path(id: String?): String? = id?.let { PropertiesComponent.getInstance().getValue(KEY + it) }

    /** The file's name, else the id. */
    fun name(id: String?): String? = path(id)?.substringAfterLast('/') ?: id
}
