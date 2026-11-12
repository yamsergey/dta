package io.yamsergey.dta.daemon.web

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Feature 061: the DevTools forwards this daemon made (device → port → socket), kept in its home
 * so a daemon that crashed leaves nothing behind: the next one removes the recorded forwards
 * still listed with the same socket. A forward another tool made is never recorded, so never removed.
 */
class ForwardRecord(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val shape = MapSerializer(String.serializer(), MapSerializer(Int.serializer(), String.serializer()))

    @Synchronized fun add(serial: String, port: Int, socket: String) = write(all().toMutableMap().also { m -> m[serial] = (m[serial].orEmpty() + (port to socket)) })

    @Synchronized fun remove(serial: String, port: Int) = write(all().mapValues { (_, ports) -> ports - port }.filterValues { it.isNotEmpty() })

    @Synchronized fun clear() = write(emptyMap())

    @Synchronized fun all(): Map<String, Map<Int, String>> =
        runCatching { if (file.isFile) json.decodeFromString(shape, file.readText()) else emptyMap() }.getOrDefault(emptyMap())

    /** The recorded forwards [forwardList] (`adb forward --list`) still has, to the same socket. */
    fun leftovers(forwardList: String): List<Pair<String, Int>> {
        val recorded = all()
        return forwardList.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) return@mapNotNull null
            val port = parts[1].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
            val socket = parts[2].removePrefix("localabstract:")
            (parts[0] to port).takeIf { recorded[parts[0]]?.get(port) == socket }
        }.toList()
    }

    private fun write(m: Map<String, Map<Int, String>>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(shape, m))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
