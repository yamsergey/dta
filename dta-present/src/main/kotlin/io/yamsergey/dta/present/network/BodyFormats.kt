package io.yamsergey.dta.present.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URLDecoder
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** What a body is, for showing it (feature 022). */
enum class BodyFormat(val extension: String) {
    JSON("json"), XML("xml"), HTML("html"), FORM("txt"), IMAGE("bin"), TEXT("txt"),
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
            type.contains("json") || start.startsWith("{") || start.startsWith("[") ->
                if (text != null && PrettyJson.format(text) != null) BodyFormat.JSON else BodyFormat.TEXT
            type.contains("xml") -> BodyFormat.XML
            start.startsWith("<!doctype html", ignoreCase = true) || start.startsWith("<html", ignoreCase = true) -> BodyFormat.HTML
            start.startsWith("<?xml") -> BodyFormat.XML
            else -> BodyFormat.TEXT
        }
    }

    /** Re-indented XML; null when it does not parse or declares a DOCTYPE (never resolved). */
    fun prettyXml(text: String): String? {
        val doc = parseXml(text) ?: return null
        return buildString { write(doc.documentElement, 0) }.trimEnd()
    }

    internal fun prettyXmlOf(e: Element): String = buildString { write(e, 0) }.trimEnd()

    private fun StringBuilder.write(e: Element, depth: Int) {
        val pad = "  ".repeat(depth)
        append(pad).append('<').append(e.tagName)
        val attrs = e.attributes
        for (i in 0 until attrs.length) attrs.item(i).let { append(' ').append(it.nodeName).append("=\"").append(escape(it.nodeValue, true)).append('"') }
        val elements = e.childElements()
        val text = e.ownText()
        when {
            elements.isEmpty() && text.isEmpty() -> append("/>\n")
            elements.isEmpty() -> append('>').append(escape(text, false)).append("</").append(e.tagName).append(">\n")
            else -> {
                append(">\n")
                if (text.isNotEmpty()) append(pad).append("  ").append(escape(text, false)).append('\n')
                elements.forEach { write(it, depth + 1) }
                append(pad).append("</").append(e.tagName).append(">\n")
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

    internal fun parseXml(text: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // No DOCTYPE, so no entities of any kind: the body is data from the network.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val builder = factory.newDocumentBuilder().apply { setErrorHandler(null) }
        builder.parse(org.xml.sax.InputSource(StringReader(text)))
    }.getOrNull()

    internal fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    /** The element's own text (not its children's), trimmed. */
    internal fun Element.ownText(): String {
        val sb = StringBuilder()
        val nodes = childNodes
        for (i in 0 until nodes.length) nodes.item(i).let { if (it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE) sb.append(it.nodeValue) }
        return sb.toString().trim()
    }
}
