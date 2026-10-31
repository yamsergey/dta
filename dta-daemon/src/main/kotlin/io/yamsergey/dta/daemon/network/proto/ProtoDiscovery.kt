package io.yamsergey.dta.daemon.network.proto

import java.io.File

/**
 * `.proto` files under a folder (feature 013, research R2), keyed as `import` names them: a file
 * under `…/src/<set>/proto/` relative to that directory (the protobuf Gradle plugin and Wire
 * layout), any other relative to the folder. Build outputs and hidden folders are skipped; the
 * walk stops at [maxFiles] and says so.
 */
object ProtoDiscovery {
    data class Found(val files: Map<String, String>, val notes: List<String>)

    private val SKIP = setOf("build", "out", "target", "node_modules", "intermediates", "generated")
    private val SOURCE_ROOT = Regex("""(^|/)src/[^/]+/proto/""")

    fun discover(folder: File, maxFiles: Int = 5_000): Found {
        val files = LinkedHashMap<String, String>()
        var bounded = false
        folder.walkTopDown()
            .onEnter { d -> d == folder || (!d.name.startsWith(".") && d.name !in SKIP) }
            .filter { it.isFile && it.name.endsWith(".proto") }
            .forEach { f ->
                if (files.size >= maxFiles) {
                    bounded = true
                    return@forEach
                }
                val relative = f.relativeTo(folder).invariantSeparatorsPath
                val key = SOURCE_ROOT.find(relative)?.let { relative.substring(it.range.last + 1) } ?: relative
                if (key !in files) files[key] = f.readText()
            }
        val notes = buildList {
            if (bounded) add("stopped at the bound of $maxFiles .proto files under ${folder.path}; name a narrower folder")
            if (files.isEmpty()) add("no .proto files under ${folder.path}")
        }
        return Found(files, notes)
    }
}
