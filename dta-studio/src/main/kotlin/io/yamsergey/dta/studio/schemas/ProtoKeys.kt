package io.yamsergey.dta.studio.schemas

/**
 * The key a `.proto` file is compiled under: the path its `import`s use (feature 013, research
 * R3). Inside a jar, the path in the jar; under `…/src/<set>/proto/`, the path below it; else the
 * path below the project, or the file name.
 */
object ProtoKeys {
    private val SOURCE_ROOT = Regex("""(^|/)src/[^/]+/proto/""")

    fun of(path: String, projectBase: String?): String {
        val p = path.replace('\\', '/')
        p.indexOf("!/").takeIf { it >= 0 }?.let { return p.substring(it + 2) }
        SOURCE_ROOT.find(p)?.let { return p.substring(it.range.last + 1) }
        val base = projectBase?.replace('\\', '/')?.trimEnd('/')
        if (base != null && p.startsWith("$base/")) return p.removePrefix("$base/")
        return p.substringAfterLast('/')
    }
}
