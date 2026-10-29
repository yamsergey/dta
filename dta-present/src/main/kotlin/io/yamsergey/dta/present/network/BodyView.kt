package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.Units
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class BodyMode { FORMATTED, RAW, HEX }

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
data class BodyView(val header: BodyHeader, val content: BodyContent, val defaultMode: BodyMode, val modes: List<BodyMode>) {
    val text: String? get() = (content as? BodyContent.Text)?.text
    val tooLarge: Boolean get() = content is BodyContent.File && !binaryFile

    private val binaryFile: Boolean get() = modes == listOf(BodyMode.HEX)

    fun render(mode: BodyMode, bytes: ByteArray? = null): String = when (mode) {
        BodyMode.FORMATTED -> text?.let { PrettyJson.format(it) ?: it } ?: ""
        BodyMode.RAW -> text ?: ""
        BodyMode.HEX -> Hex.dump(bytes ?: text?.toByteArray() ?: ByteArray(0))
    }

    companion object {
        const val INLINE_LIMIT = 1_048_576L

        /** Bodies above the inline limit are fetched to a file and opened in a read-only editor tab (FR-009). */
        fun needsFile(storedBytes: Long) = storedBytes > INLINE_LIMIT

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
            val json = decoded || info.str("contentType")?.contains("json") == true || (text != null && PrettyJson.format(text) != null)
            val modes = when {
                binary -> listOf(BodyMode.HEX)
                json -> listOf(BodyMode.FORMATTED, BodyMode.RAW, BodyMode.HEX)
                else -> listOf(BodyMode.RAW, BodyMode.HEX)
            }
            return BodyView(BodyHeader(line, Statements.forBody(result)), content, modes.first(), modes)
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
