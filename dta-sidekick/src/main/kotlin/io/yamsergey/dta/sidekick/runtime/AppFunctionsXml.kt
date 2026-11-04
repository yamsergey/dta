package io.yamsergey.dta.sidekick.runtime

/**
 * The AndroidX AppFunctions the compiler listed in the APK's `app_functions(_v2).xml`: each
 * `<appfunction>`'s own `<id>` and `<description>` — its direct children only (parameters,
 * data types and responses have `<id>`s and descriptions of their own).
 */
object AppFunctionsXml {
    private val TAG = Regex("<(/?)([A-Za-z_][\\w.-]*)[^>]*?(/?)>|([^<]+)")

    fun functions(xml: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val path = ArrayList<String>()
        var id: String? = null
        var description = ""
        var text = StringBuilder()
        for (m in TAG.findAll(xml.replace(Regex("<\\?.*?\\?>"), ""))) {
            val (close, name, selfClose, chars) = m.destructured
            when {
                chars.isNotEmpty() -> text.append(chars)
                close.isEmpty() && selfClose.isEmpty() -> { path += name; text = StringBuilder() }
                close.isEmpty() -> Unit
                else -> {
                    // A direct child of <appfunction>: path is [..., appfunction, name].
                    val inFunction = path.size >= 2 && path[path.size - 2] == "appfunction"
                    if (inFunction && name == "id") id = unescape(text.toString().trim())
                    if (inFunction && name == "description") description = unescape(text.toString().trim())
                    if (name == "appfunction") { id?.let { out += it to description }; id = null; description = "" }
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                    text = StringBuilder()
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
