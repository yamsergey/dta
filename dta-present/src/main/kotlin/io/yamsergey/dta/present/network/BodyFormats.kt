package io.yamsergey.dta.present.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.StringReader
import java.net.URLDecoder
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** What a body is, for showing it (feature 022). */
enum class BodyFormat(val extension: String) {
    JSON("json"), XML("xml"), HTML("html"), FORM("txt"), IMAGE("bin"), TEXT("txt"),
    /** Feature 024: one JSON document per line (JSON Lines, NDJSON, SDK envelopes). */
    JSON_LINES("jsonl"),
}

/** A GraphQL request: its query text and its variables, re-indented. */
data class GraphqlRequest(val query: String, val variables: String?)

/**
 * Recognises well-known body formats and prepares them for reading (feature 022): by content
 * type first, by the text when the type is missing or vague. Nothing here changes what the body
 * says; formatting only re-indents or decodes the form encoding.
 */
object BodyFormats {
    fun detect(contentType: String?, text: String?): BodyFormat {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val start = text?.trimStart().orEmpty()
        return when {
            type.startsWith("image/") -> BodyFormat.IMAGE
            type == "application/x-www-form-urlencoded" -> BodyFormat.FORM
            type.contains("html") -> BodyFormat.HTML
            type.contains("json") || start.startsWith("{") || start.startsWith("[") -> when {
                text != null && PrettyJson.format(text) != null -> BodyFormat.JSON
                text != null && jsonLines(text) != null -> BodyFormat.JSON_LINES
                else -> BodyFormat.TEXT
            }
            type.contains("xml") -> BodyFormat.XML
            start.startsWith("<!doctype html", ignoreCase = true) || start.startsWith("<html", ignoreCase = true) -> BodyFormat.HTML
            start.startsWith("<?xml") -> BodyFormat.XML
            else -> BodyFormat.TEXT
        }
    }

    /**
     * The documents of JSON Lines text: two or more non-blank lines, each a JSON object or array;
     * null otherwise (one line is plain JSON; any other line keeps the body text).
     */
    fun jsonLines(text: String): List<kotlinx.serialization.json.JsonElement>? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return null
        return lines.map { line ->
            if (line[0] != '{' && line[0] != '[') return null
            runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: return null
        }
    }

    /** Re-indented XML, attributes in their order; null when it does not parse or declares a DOCTYPE (never resolved). */
    fun prettyXml(text: String): String? = parseXml(text)?.let(::prettyXmlOf)

    internal fun prettyXmlOf(e: XmlElement): String = buildString { write(e, 0) }.trimEnd()

    private fun StringBuilder.write(e: XmlElement, depth: Int) {
        val pad = "  ".repeat(depth)
        append(pad).append('<').append(e.name)
        e.attributes.forEach { (k, v) -> append(' ').append(k).append("=\"").append(escape(v, true)).append('"') }
        when {
            e.children.isEmpty() && e.text.isEmpty() -> append("/>\n")
            e.children.isEmpty() -> append('>').append(escape(e.text, false)).append("</").append(e.name).append(">\n")
            else -> {
                append(">\n")
                if (e.text.isNotEmpty()) append(pad).append("  ").append(escape(e.text, false)).append('\n')
                e.children.forEach { write(it, depth + 1) }
                append(pad).append("</").append(e.name).append(">\n")
            }
        }
    }

    private fun escape(s: String, attr: Boolean) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").let { if (attr) it.replace("\"", "&quot;") else it }

    /** A form body as `key = value` lines, decoded. */
    fun formLines(text: String): String = formFields(text).joinToString("\n") { (k, v) -> "$k = $v" }

    internal fun formFields(text: String): List<Pair<String, String>> = text.trim().split('&').filter { it.isNotEmpty() }.map {
        val key = it.substringBefore('=')
        val value = if ('=' in it) it.substringAfter('=') else ""
        decode(key) to decode(value)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

    /** The query of a GraphQL-over-HTTP request body, or null when it is not one. */
    fun graphql(text: String): GraphqlRequest? {
        val o = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val query = (o["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val variables = o["variables"]?.takeIf { it is JsonObject }?.let { PrettyJson.format(it.toString()) }
        return GraphqlRequest(query, variables)
    }

    /**
     * The document's elements in order, with attributes (namespace declarations included) in
     * the order written. A DOCTYPE refuses the document: the body is data from the network and
     * no entity of any kind is resolved.
     */
    internal fun parseXml(text: String): XmlElement? = runCatching {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        val reader = factory.createXMLStreamReader(StringReader(text))
        val stack = ArrayDeque<Triple<String, List<Pair<String, String>>, Pair<MutableList<XmlElement>, StringBuilder>>>()
        var root: XmlElement? = null
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD -> return@runCatching null
                    XMLStreamConstants.START_ELEMENT -> {
                        val attrs = (0 until reader.attributeCount).map { name(reader.getAttributePrefix(it), reader.getAttributeLocalName(it)) to reader.getAttributeValue(it) }
                        stack.addLast(Triple(name(reader.prefix, reader.localName), attrs, mutableListOf<XmlElement>() to StringBuilder()))
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> stack.lastOrNull()?.third?.second?.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        val (n, attrs, body) = stack.removeLast()
                        val element = XmlElement(n, attrs, body.first, body.second.toString().trim())
                        if (stack.isEmpty()) root = element else stack.last().third.first += element
                    }
                }
            }
        } finally {
            reader.close()
        }
        root
    }.getOrNull()

    private fun name(prefix: String?, local: String) = if (prefix.isNullOrEmpty()) local else "$prefix:$local"
}

/** One XML element as written: attributes in order (namespace declarations among them), child elements, own text. */
data class XmlElement(val name: String, val attributes: List<Pair<String, String>>, val children: List<XmlElement>, val text: String)
