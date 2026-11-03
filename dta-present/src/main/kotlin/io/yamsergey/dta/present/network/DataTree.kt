package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.network.BodyFormats.childElements
import io.yamsergey.dta.present.network.BodyFormats.ownText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

enum class NodeKind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, ELEMENT, ATTRIBUTE }

/**
 * One node of a body read as data (feature 022): what Tree and Graph show. [value] is the
 * preview of a leaf (strings quoted, cut at [DataTrees.PREVIEW]); [copyValue] the whole value;
 * [path] JSONPath-like (`$.items[3].price`) or XPath-like (`/feed/entry[2]/@id`).
 */
class DataNode(
    val key: String,
    val kind: NodeKind,
    val value: String?,
    val children: List<DataNode>,
    val path: String,
    val copyValue: String,
) {
    val container: Boolean get() = children.isNotEmpty() || kind == NodeKind.OBJECT || kind == NodeKind.ARRAY

    /** Item counts: `{3}` for an object, `[120]` for an array, `<2>` for an element with children. */
    val summary: String
        get() = when (kind) {
            NodeKind.OBJECT -> "{${children.size}}"
            NodeKind.ARRAY -> "[${children.size}]"
            NodeKind.ELEMENT -> if (children.isEmpty()) value ?: "" else "<${children.size}>"
            else -> value ?: ""
        }

    /** The card title in the graph and the label of the root in the tree. */
    val title: String get() = if (kind == NodeKind.ELEMENT) "<$key>" else key

    // What tree speed search matches: the key and the value.
    override fun toString() = "$key ${value ?: summary}"
}

/** Builds [DataNode] trees from JSON, XML and form bodies; null when the text is not one. */
object DataTrees {
    const val PREVIEW = 120
    private val SIMPLE_KEY = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun json(text: String): DataNode? {
        val start = text.trimStart().firstOrNull()
        if (start != '{' && start != '[') return null
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        return json("$", element, "$")
    }

    private fun json(key: String, e: JsonElement, path: String): DataNode = when (e) {
        is JsonObject -> DataNode(key, NodeKind.OBJECT, null, e.entries.map { (k, v) -> json(k, v, member(path, k)) }, path, e.toString())
        is JsonArray -> DataNode(key, NodeKind.ARRAY, null, e.mapIndexed { i, v -> json("[$i]", v, "$path[$i]") }, path, e.toString())
        JsonNull -> DataNode(key, NodeKind.NULL, "null", emptyList(), path, "null")
        is JsonPrimitive -> when {
            e.isString -> DataNode(key, NodeKind.STRING, quote(e.content), emptyList(), path, e.content)
            e.booleanOrNull != null -> DataNode(key, NodeKind.BOOLEAN, e.content, emptyList(), path, e.content)
            else -> DataNode(key, NodeKind.NUMBER, e.content, emptyList(), path, e.content)
        }
    }

    private fun member(path: String, key: String) =
        if (SIMPLE_KEY.matches(key)) "$path.$key" else "$path[\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"

    fun xml(text: String): DataNode? {
        val doc = BodyFormats.parseXml(text) ?: return null
        val root = doc.documentElement
        return element(root, "/${root.tagName}")
    }

    private fun element(e: org.w3c.dom.Element, path: String): DataNode {
        val children = ArrayList<DataNode>()
        val attrs = e.attributes
        for (i in 0 until attrs.length) attrs.item(i).let {
            children += DataNode("@${it.nodeName}", NodeKind.ATTRIBUTE, quote(it.nodeValue), emptyList(), "$path/@${it.nodeName}", it.nodeValue)
        }
        val elements = e.childElements()
        val counts = elements.groupingBy { it.tagName }.eachCount()
        val seen = HashMap<String, Int>()
        elements.forEach { child ->
            val n = seen.merge(child.tagName, 1, Int::plus)!!
            val step = if (counts.getValue(child.tagName) > 1) "${child.tagName}[$n]" else child.tagName
            children += element(child, "$path/$step")
        }
        val text = e.ownText()
        val value = if (text.isNotEmpty()) quote(text) else null
        if (text.isNotEmpty() && elements.isNotEmpty()) children += DataNode("#text", NodeKind.STRING, quote(text), emptyList(), "$path/text()", text)
        return DataNode(e.tagName, NodeKind.ELEMENT, if (elements.isEmpty()) value else null, children, path, if (elements.isEmpty() && attrs.length == 0) text else BodyFormats.prettyXmlOf(e))
    }

    fun form(text: String): DataNode? {
        val fields = BodyFormats.formFields(text)
        if (fields.isEmpty()) return null
        val children = fields.map { (k, v) -> DataNode(k, NodeKind.STRING, quote(v), emptyList(), member("$", k), v) }
        return DataNode("$", NodeKind.OBJECT, null, children, "$", text)
    }

    private fun quote(s: String): String {
        val cut = if (s.length > PREVIEW) s.take(PREVIEW) + "…" else s
        return "\"" + cut.replace("\n", "⏎") + "\""
    }
}
