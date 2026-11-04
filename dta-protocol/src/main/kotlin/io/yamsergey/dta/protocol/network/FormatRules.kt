package io.yamsergey.dta.protocol.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

@Serializable
enum class PayloadFormat {
    @SerialName("json") JSON,
    @SerialName("text") TEXT,
}

/**
 * Feature 039: what an app's own WebSocket protocol looks like, for the sockets whose URL
 * matches [urlPattern] (glob, `*` any run of characters), optionally one [direction].
 * [pattern] is a regex over the whole message with named groups `kind`, `id`, `topic`,
 * `payload`; [kind], [idPath], [topic], [payload] are field paths (`a.b[0].c`) read from the
 * payload the pattern found, or from the whole message as JSON when there is no pattern.
 * [name] is shown as the message's format.
 */
@Serializable
data class FormatRule(
    val id: String,
    val name: String,
    val urlPattern: String,
    val direction: Direction? = null,
    val pattern: String? = null,
    val kind: String? = null,
    @SerialName("idPath") val idPath: String? = null,
    val topic: String? = null,
    val payload: String? = null,
    val payloadFormat: PayloadFormat = PayloadFormat.JSON,
    val addedAtMs: Long = 0,
)

/** What `network.format.add` and `network.format.test` take: a rule before it has an id. */
@Serializable
data class FormatRuleSpec(
    val name: String = "custom",
    val urlPattern: String,
    val direction: Direction? = null,
    val pattern: String? = null,
    val kind: String? = null,
    @SerialName("idPath") val idPath: String? = null,
    val topic: String? = null,
    val payload: String? = null,
    val payloadFormat: PayloadFormat = PayloadFormat.JSON,
) {
    fun toRule(id: String, addedAtMs: Long) = FormatRule(id, name, urlPattern, direction, pattern, kind, idPath, topic, payload, payloadFormat, addedAtMs)
}

/** Result of `network.format.test`: how the rule reads a connection's latest text messages. */
@Serializable
data class FormatTestResult(
    val read: Int,
    val of: Int,
    /** Up to [SAMPLES] messages it read, with what it read. */
    val samples: List<FormatSample>,
    /** Up to [SAMPLES] texts (cut to 200 characters) it did not read. */
    val unread: List<String>,
    /** Set when the pattern took too long on a message and was stopped. */
    val slow: String? = null,
) {
    companion object {
        const val SAMPLES = 5
    }
}

@Serializable
data class FormatSample(val text: String, val envelope: Envelope)

class FormatRuleException(message: String) : IllegalArgumentException(message)

object FormatRules {
    val GROUPS = listOf("kind", "id", "topic", "payload")
    private const val LIMIT_MS = 50L
    private val GROUP = Regex("""\(\?<([A-Za-z][A-Za-z0-9]*)>""")
    private val PATH = Regex("""^(\$\.)?[^.\[\]\s]+(\[\d+])*(\.[^.\[\]\s]+(\[\d+])*)*$""")
    private val json = Json { isLenient = false }
    private val compiled = ConcurrentHashMap<String, Regex>()
    private val slowRules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Throws [FormatRuleException] saying what is wrong with [spec]. */
    fun validate(spec: FormatRuleSpec) {
        if (spec.urlPattern.isBlank()) throw FormatRuleException("urlPattern is empty: give the socket's URL, * for any run of characters (wss://api.example.test/live*)")
        if (spec.name.isBlank()) throw FormatRuleException("name is empty: it is shown as the messages' format")
        val paths = listOf("kind" to spec.kind, "id" to spec.idPath, "topic" to spec.topic, "payload" to spec.payload)
        if (spec.pattern.isNullOrEmpty() && paths.all { it.second.isNullOrEmpty() }) {
            throw FormatRuleException("nothing to read: give a pattern (regex with groups kind, id, topic, payload) or field paths (kind, id, topic, payload)")
        }
        spec.pattern?.takeIf { it.isNotEmpty() }?.let { p ->
            try {
                Regex(p)
            } catch (e: PatternSyntaxException) {
                throw FormatRuleException("pattern does not compile: ${e.description} at position ${e.index} of $p")
            }
            val names = GROUP.findAll(p).map { it.groupValues[1] }.toList()
            names.firstOrNull { it !in GROUPS }?.let { throw FormatRuleException("pattern group '$it' is not one DTA reads; name groups kind, id, topic, payload") }
            if (names.isEmpty() && paths.all { it.second.isNullOrEmpty() }) {
                throw FormatRuleException("the pattern names no group: name groups kind, id, topic, payload, e.g. ^(?<id>\\d+)\\|(?<kind>\\w+)\\|(?<payload>.*)$")
            }
        }
        paths.forEach { (what, path) ->
            if (!path.isNullOrEmpty() && !PATH.matches(path)) throw FormatRuleException("$what path '$path' is not a field path: names joined by dots, [n] for an array item (h.op, items[0].id)")
        }
    }

    /** The rule for a message of the socket at [url]: the most literal URL pattern, then the newest. */
    fun select(rules: List<FormatRule>, url: String?, direction: Direction?): FormatRule? {
        if (url == null) return null
        return rules
            .filter { (it.direction == null || it.direction == direction) && glob(it.urlPattern).matches(url) }
            .maxWithOrNull(compareBy<FormatRule>({ it.urlPattern.count { c -> c != '*' } }, { it.direction != null }, { it.addedAtMs }, { it.id.drop(1).toIntOrNull() ?: 0 }))
    }

    /** True once [rule]'s pattern was stopped for taking too long on a message. */
    fun slow(rule: FormatRule): Boolean = key(rule) in slowRules

    /** What [rule] reads of [text]; null when the text is not this rule's message. */
    fun apply(rule: FormatRule, text: String): Envelope? {
        val groups = HashMap<String, String>()
        val pattern = rule.pattern?.takeIf { it.isNotEmpty() }
        if (pattern != null) {
            val regex = compiled.getOrPut(pattern) { runCatching { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }.getOrNull() ?: return null }
            val match = try {
                regex.find(Deadline(text, System.nanoTime() + LIMIT_MS * 1_000_000))
            } catch (_: Deadline.Expired) {
                slowRules += key(rule)
                return null
            } ?: return null
            val names = GROUP.findAll(pattern).map { it.groupValues[1] }.toSet()
            names.forEach { n -> match.groups[n]?.value?.let { groups[n] = it } }
        }
        val paths = listOf("kind" to rule.kind, "id" to rule.idPath, "topic" to rule.topic, "payload" to rule.payload).filter { !it.second.isNullOrEmpty() }
        if (paths.isNotEmpty()) {
            val source = groups["payload"] ?: text
            val tree = if (isJson(source)) json.parseToJsonElement(source.trim()) else null
            if (tree == null && pattern == null) return null
            if (tree != null) paths.forEach { (what, path) ->
                if (what in groups && what != "payload") return@forEach
                val v = read(tree, path!!) ?: return@forEach
                if (what == "payload") groups["payload"] = if (v is JsonPrimitive && v.isString) v.content else v.toString()
                else (v as? JsonPrimitive)?.content?.let { groups[what] = it }
            }
            if (pattern == null && listOf("kind", "id", "topic", "payload").none { it in groups }) return null
            if (pattern == null && "payload" !in groups) groups["payload"] = text
        }
        val payload = groups["payload"]
        val note = if (payload != null && rule.payloadFormat == PayloadFormat.JSON && !isJson(payload)) "payload is not JSON" else null
        return Envelope(rule.name, groups["kind"], groups["id"], groups["topic"], payload, rule = rule.id, note = note)
    }

    /** An object or an array: what a JSON payload is (a bare word would parse as a literal). */
    private fun isJson(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("{") || t.startsWith("[")) && runCatching { json.parseToJsonElement(t) }.isSuccess
    }

    private fun read(root: JsonElement, path: String): JsonElement? {
        var at: JsonElement? = root
        path.removePrefix("$.").split('.').forEach { part ->
            val name = part.substringBefore('[')
            at = (at as? JsonObject)?.get(name) ?: return null
            Regex("""\[(\d+)]""").findAll(part).forEach { i -> at = (at as? JsonArray)?.getOrNull(i.groupValues[1].toInt()) ?: return null }
        }
        return at
    }

    private fun key(rule: FormatRule) = "${rule.id}\u0000${rule.pattern}"

    private fun glob(pattern: String) = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })

    /** A text that stops a regex once its time is up: catastrophic backtracking ends, the rule is reported slow. */
    private class Deadline(private val text: CharSequence, private val until: Long) : CharSequence {
        class Expired : RuntimeException(null, null, false, false)
        private var reads = 0
        override val length get() = text.length
        override fun get(index: Int): Char {
            if (++reads and 0xFFF == 0 && System.nanoTime() > until) throw Expired()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Deadline(text.subSequence(startIndex, endIndex), until)
        override fun toString() = text.toString()
    }
}
