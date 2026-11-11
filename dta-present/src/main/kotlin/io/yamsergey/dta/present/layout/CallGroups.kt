package io.yamsergey.dta.present.layout

/**
 * Feature 059: a node's call chain (root → node) folded into runs of consecutive calls from one
 * source file of one package. A call without a source is a group of its own with no [file].
 */
data class CallGroup(
    /** Stable while the chain is the same: the plugin keeps opened groups by it. */
    val key: String,
    /** 1-based number of the group's first call in the chain. */
    val first: Int,
    val file: String?,
    val packageName: String?,
    val calls: List<NodeDetail.Call>,
) {
    val last: Int get() = first + calls.size - 1
    /** "3", or "2–4" for a run. */
    val range: String get() = if (calls.size == 1) "$first" else "$first–$last"
    /** "1 call" / "3 calls". */
    val count: String get() = if (calls.size == 1) "1 call" else "${calls.size} calls"
    /** The calls' names, root first: "Scaffold › NavHost". */
    val names: String get() = calls.joinToString(" › ") { it.text.substringBefore(" (") }
}

object CallGroups {
    fun of(chain: List<NodeDetail.Call>): List<CallGroup> {
        val out = mutableListOf<CallGroup>()
        var i = 0
        while (i < chain.size) {
            val s = chain[i].source
            var j = i + 1
            if (s != null) {
                while (j < chain.size && chain[j].source?.let { it.file == s.file && it.packageName == s.packageName } == true) j++
            }
            out += CallGroup("${i + 1}:${s?.file}:${s?.packageName}", i + 1, s?.file, s?.packageName, chain.subList(i, j).toList())
            i = j
        }
        return out
    }
}
