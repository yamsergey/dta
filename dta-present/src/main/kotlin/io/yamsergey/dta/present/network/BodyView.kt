package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.Units
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** How a body can be shown (feature 022 added tree, graph, query and image). */
enum class BodyMode(val label: String) {
    FORMATTED("Formatted"), TREE("Tree"), GRAPH("Graph"), QUERY("Query"), IMAGE("Image"), RAW("Raw"), HEX("Hex"),
}

/** The fixed header above a body (patterns.md body viewer): facts in one line, statements beside it. */
data class BodyHeader(val line: String, val statements: List<Statement>)

sealed interface BodyContent {
    data class Text(val text: String) : BodyContent
    /** Written to a file by `network.body` with `output`: shown in an editor, never inline. */
    data class File(val path: String, val sha256: String?, val bytes: Long) : BodyContent
}

/**
 * One body from a `network.body` result (information.md "Body viewer"). Modes are formatted,
 * raw and hex; the default is formatted when decoding succeeded or the text is JSON, raw text
 * otherwise, hex for binary that did not decode. Nothing is decoded here: the text is the
 * daemon's, formatting only re-indents it.
 */
data class BodyView(
    val header: BodyHeader,
    val content: BodyContent,
    val defaultMode: BodyMode,
    val modes: List<BodyMode>,
    /** What the body is, for highlighting and structure (feature 022). */
    val format: BodyFormat = BodyFormat.TEXT,
    /** The body read as data, parsed once when the view is made (off the UI thread). */
    private val structure: DataNode? = null,
    /** Feature 038: a WebSocket message's envelope; its payload is what Formatted, Tree and Graph show. */
    val envelope: Envelope? = null,
) {
    val text: String? get() = (content as? BodyContent.Text)?.text
    val tooLarge: Boolean get() = content is BodyContent.File && !binaryFile

    private val binaryFile: Boolean get() = BodyMode.RAW !in modes

    /** The body as data for Tree and Graph; null when it has no structure to show. */
    fun tree(): DataNode? = structure

    fun render(mode: BodyMode, bytes: ByteArray? = null): String = when (mode) {
        BodyMode.FORMATTED -> if (envelope?.payload != null) {
            // The envelope is in the header line; the editor shows the payload as the JSON it is.
            PrettyJson.format(envelope.payload) ?: envelope.payload
        } else text?.let {
            when (format) {
                BodyFormat.XML -> BodyFormats.prettyXml(it)
                BodyFormat.FORM -> BodyFormats.formLines(it)
                BodyFormat.JSON_LINES -> BodyFormats.jsonLines(it)?.joinToString("\n\n") { d -> PrettyJson.format(d.toString()) ?: d.toString() }
                else -> PrettyJson.format(it)
            } ?: it
        } ?: ""
        BodyMode.QUERY -> text?.let(BodyFormats::graphql)?.let { q -> q.query + (q.variables?.let { "\n\n# variables\n$it" } ?: "") } ?: ""
        BodyMode.RAW -> text ?: ""
        BodyMode.HEX -> Hex.dump(bytes ?: text?.toByteArray() ?: ByteArray(0))
        BodyMode.TREE, BodyMode.GRAPH, BodyMode.IMAGE -> ""
    }

    companion object {
        const val INLINE_LIMIT = 1_048_576L

        /** Bodies above the inline limit are fetched to a file and opened in a read-only editor tab (FR-009). */
        fun needsFile(storedBytes: Long) = storedBytes > INLINE_LIMIT

        /**
         * Fetches a body the way the viewer needs it: large ones to a file; others as text, and a
         * binary one that did not come back as text (no schema decodes it) to a file for the hex view.
         */
        inline fun fetch(storedBytes: Long, binary: Boolean, inline: () -> JsonObject, toFile: () -> JsonObject): JsonObject {
            if (needsFile(storedBytes)) return toFile()
            val result = runCatching { inline() }
            if (binary && result.getOrNull()?.get("text") == null) return toFile()
            return result.getOrThrow()
        }

        fun of(result: JsonObject): BodyView {
            val info = result.obj("body") ?: JsonObject(emptyMap())
            val size = listOfNotNull(
                info.long("storedBytes")?.let(Units::bytes),
                info.long("declaredBytes")?.takeIf { it != info.long("storedBytes") }?.let { "declared ${Units.bytes(it)}" },
            ).joinToString(", ")
            val line = listOfNotNull(info.str("part"), info.str("contentType"), info.str("encoding"), size.ifEmpty { null }).joinToString(" · ")
            val outcome = result.obj("decoding")?.str("outcome")
            val decoded = outcome == "decoded" || outcome == "decoded-with-warnings"
            val text = result.str("text")
            val file = result.str("file")
            val content = if (file != null) BodyContent.File(file, result.str("sha256"), result.long("bytes") ?: 0) else BodyContent.Text(text ?: "")
            val binary = info.bool("binary") && !decoded
            // Decoded protobuf comes as JSON text whatever the content type says.
            val format = if (decoded) BodyFormat.JSON else BodyFormats.detect(info.str("contentType"), text)
            val structure = text?.let {
                when (format) {
                    BodyFormat.JSON -> DataTrees.json(it)
                    BodyFormat.JSON_LINES -> DataTrees.jsonLines(it)
                    BodyFormat.XML -> DataTrees.xml(it)
                    BodyFormat.FORM -> DataTrees.form(it)
                    else -> null
                }
            }
            // Feature 038: a message whose text is an envelope around JSON (STOMP, a name then JSON, Socket.IO…).
            val envelope = if (info.str("part") == "message" && text != null) WsFormats.detect(text) else null
            val payloadTree = if (structure == null) envelope?.payload?.let { DataTrees.json(it) } else null
            val tree = structure ?: payloadTree
            val shownEnvelope = envelope?.takeIf { payloadTree != null || it.format != "json" }
            val structured = tree != null
            val modes = when {
                binary && format == BodyFormat.IMAGE -> listOf(BodyMode.IMAGE, BodyMode.HEX)
                binary -> listOf(BodyMode.HEX)
                structured -> listOfNotNull(
                    BodyMode.FORMATTED, BodyMode.TREE, BodyMode.GRAPH,
                    BodyMode.QUERY.takeIf { format == BodyFormat.JSON && BodyFormats.graphql(text!!) != null },
                    BodyMode.RAW, BodyMode.HEX,
                )
                // A large JSON body comes as a file: formatted in the editor tab.
                format == BodyFormat.JSON && text == null -> listOf(BodyMode.FORMATTED, BodyMode.RAW, BodyMode.HEX)
                else -> listOf(BodyMode.RAW, BodyMode.HEX)
            }
            val headerLine = shownEnvelope?.let { "$line · ${it.format} ${it.label()}".trimEnd() } ?: line
            return BodyView(BodyHeader(headerLine, Statements.forBody(result)), content, modes.first(), modes, if (payloadTree != null) BodyFormat.JSON else format, tree, shownEnvelope?.takeIf { payloadTree != null })
        }
    }
}

/** Re-indents JSON text; null when it is not JSON (then it stays raw). */
object PrettyJson {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun format(text: String): String? {
        // Only documents: a bare word would otherwise parse as a literal.
        val start = text.trimStart().firstOrNull()
        if (start != '{' && start != '[') return null
        return runCatching { pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrNull()
    }
}

/** Offsets, sixteen bytes in two groups of eight, and the printable column. */
object Hex {
    fun dump(bytes: ByteArray): String = bytes.toList().chunked(16).mapIndexed { row, chunk ->
        val hex = chunk.mapIndexed { i, b -> "%02x".format(b) + if (i == 7) "  " else " " }.joinToString("")
        val ascii = chunk.joinToString("") { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar().toString() else "." }
        "%08x  %-49s |%s|".format(row * 16, hex, ascii)
    }.joinToString("\n")
}
