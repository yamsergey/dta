package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.network.proto.SchemaException
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Envelope
import io.yamsergey.dta.protocol.network.FormatRule
import io.yamsergey.dta.protocol.network.FormatRuleException
import io.yamsergey.dta.protocol.network.FormatRuleSpec
import io.yamsergey.dta.protocol.network.FormatRules
import io.yamsergey.dta.protocol.network.FormatSample
import io.yamsergey.dta.protocol.network.FormatTestResult
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.WsFormats
import io.yamsergey.dta.protocol.network.WsMessageEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Feature 039: the user's WebSocket message format rules, per app package — the one owner of
 * that state (II), stored in `<dataDir>/formats/<package>.json`. [annotate] gives every text
 * message the envelope its rule (or 038's detection) reads, so every client shows the same.
 */
class MessageFormats(
    private val dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class RulesFile(val nextId: Int = 1, val rules: List<FormatRule> = emptyList())

    private val apps = HashMap<String, RulesFile>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    /** Adds a rule; throws [SchemaException] (invalid params) naming what is wrong. */
    @Synchronized
    fun add(pkg: String, spec: FormatRuleSpec): FormatRule {
        validate(spec)
        val file = state(pkg)
        val rule = spec.toRule("f${file.nextId}", clock())
        save(pkg, RulesFile(file.nextId + 1, file.rules + rule))
        return rule
    }

    @Synchronized
    fun list(pkg: String): List<FormatRule> = state(pkg).rules

    @Synchronized
    fun remove(pkg: String, id: String): Boolean {
        val file = state(pkg)
        val remaining = file.rules.filterNot { it.id == id }
        if (remaining.size == file.rules.size) return false
        save(pkg, file.copy(rules = remaining))
        return true
    }

    /** How [spec] reads [messages] (a connection's, oldest first): the latest [LATEST] text ones. */
    fun test(spec: FormatRuleSpec, messages: List<WsMessageEntry>): FormatTestResult {
        validate(spec)
        val rule = spec.toRule("test", clock())
        val texts = messages.filter { it.opcode != MessageOpcode.BINARY && !it.payload.binary && (rule.direction == null || it.direction == rule.direction) }
            .mapNotNull { it.payload.preview }.takeLast(LATEST)
        val samples = ArrayList<FormatSample>()
        val unread = ArrayList<String>()
        var read = 0
        texts.forEach { t ->
            val e = FormatRules.apply(rule, t)
            if (e != null) {
                read++
                if (samples.size < FormatTestResult.SAMPLES) samples += FormatSample(t.take(200), e)
            } else if (unread.size < FormatTestResult.SAMPLES) unread += t.take(200)
        }
        val slow = if (FormatRules.slow(rule)) "the pattern took longer than 50 ms on a message and was stopped: simplify it (avoid nested repetition like (a+)+)" else null
        return FormatTestResult(read, texts.size, samples, unread, slow)
    }

    /** The envelope of a text message of the socket at [url]: the app's rule first, then 038's formats. */
    fun envelope(pkg: String, url: String?, direction: Direction?, text: String): Envelope? {
        val rule = FormatRules.select(list(pkg), url, direction)
        return WsFormats.detect(text, rule)
    }

    fun annotate(pkg: String, entry: NetworkEntry): NetworkEntry {
        if (entry !is WsMessageEntry || entry.opcode == MessageOpcode.BINARY || entry.payload.binary) return entry
        val text = entry.payload.preview ?: return entry
        return entry.copy(envelope = envelope(pkg, entry.url, entry.direction, text))
    }

    fun annotate(pkg: String, page: NetworkPage): NetworkPage = page.copy(entries = page.entries.map { annotate(pkg, it) })

    fun annotate(pkg: String, detail: NetworkEntryDetail): NetworkEntryDetail = detail.copy(
        entry = annotate(pkg, detail.entry),
        messages = detail.messages?.map { m ->
            // A connection's messages carry no URL of their own: they are this connection's.
            val url = m.url ?: (detail.entry as? io.yamsergey.dta.protocol.network.WsConnectionEntry)?.url
            annotate(pkg, m.copy(url = url)) as WsMessageEntry
        },
    )

    /** A message body read whole: its envelope from the whole text. */
    fun annotate(pkg: String, entry: NetworkEntry?, body: BodyResult): BodyResult {
        val message = entry as? WsMessageEntry ?: return body
        val text = body.text ?: return body
        if (body.body.part != BodyPart.MESSAGE || body.body.binary) return body
        return body.copy(envelope = envelope(pkg, message.url, message.direction, text))
    }

    private fun validate(spec: FormatRuleSpec) {
        try {
            FormatRules.validate(spec)
        } catch (e: FormatRuleException) {
            throw SchemaException(e.message ?: "invalid format rule")
        }
    }

    private fun state(pkg: String): RulesFile = apps.getOrPut(pkg) {
        val f = file(pkg)
        if (f.isFile) runCatching { json.decodeFromString(RulesFile.serializer(), f.readText()) }.getOrElse { RulesFile() } else RulesFile()
    }

    private fun save(pkg: String, file: RulesFile) {
        apps[pkg] = file
        file(pkg).apply { parentFile.mkdirs() }.writeText(json.encodeToString(RulesFile.serializer(), file))
    }

    private fun file(pkg: String) = File(File(dataDir, "formats"), "$pkg.json")

    companion object {
        const val LATEST = 200
    }
}
