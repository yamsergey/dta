package io.yamsergey.dta.studio.network

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.wm.StatusBar
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.network.EntryCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.JMenuItem

/**
 * Copy actions for one network entry (feature 026): from the list's context menu and the details'
 * Copy link. The full entry (headers) and bodies are fetched when an action runs, with the
 * view's redaction; what was copied, or why nothing was, shows in the status bar.
 */
class EntryCopyMenu(
    private val project: Project,
    private val scope: CoroutineScope,
    private val client: () -> DaemonClient?,
    /** Device and app, and `redact` when the view redacts. */
    private val target: () -> JsonObject,
) {
    private enum class What(val label: String) {
        URL("Copy URL"), CURL("Copy as cURL"), REQUEST_HEADERS("Copy Request Headers"), RESPONSE_HEADERS("Copy Response Headers"),
        HANDSHAKE_HEADERS("Copy Handshake Headers"), REQUEST_BODY("Copy Request Body"), RESPONSE_BODY("Copy Response Body"),
        PAYLOAD("Copy Payload"), JSON("Copy as JSON"),
    }

    /** Shows the menu for entry [id] of [type] (`http`, `ws-connection`, `ws-message`) at [x], [y] of [over]. */
    fun show(id: String, type: String?, over: Component, x: Int, y: Int) {
        val items = when (type) {
            "ws-connection" -> listOf(What.URL, What.HANDSHAKE_HEADERS, What.JSON)
            "ws-message" -> listOf(What.PAYLOAD, What.JSON)
            else -> listOf(What.URL, What.CURL, What.REQUEST_HEADERS, What.RESPONSE_HEADERS, What.REQUEST_BODY, What.RESPONSE_BODY, What.JSON)
        }
        val menu = JBPopupMenu()
        items.forEach { what -> menu.add(JMenuItem(what.label).apply { addActionListener { run(id, what) } }) }
        menu.show(over, x, y)
    }

    private fun run(id: String, what: What) {
        val c = client() ?: return say("No DTA daemon connected.")
        scope.launch {
            val text = runCatching { produce(c, id, what) }
            withContext(Dispatchers.Main) {
                text.onSuccess { t ->
                    if (t == null) say("Nothing to copy: ${what.label.removePrefix("Copy ").lowercase()} is empty or not text.")
                    else { CopyPasteManager.getInstance().setContents(StringSelection(t)); say("Copied ${what.label.removePrefix("Copy ").lowercase()}.") }
                }.onFailure { e -> say("Could not copy: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private suspend fun produce(c: DaemonClient, id: String, what: What): String? {
        val entry = c.call("network.entry", JsonObject(target() + ("id" to JsonPrimitive(id)))).result.jsonObject["entry"]!!.jsonObject
        return when (what) {
            What.URL -> EntryCopy.url(entry)
            What.CURL -> EntryCopy.curl(entry, if (entry["requestBody"] != null) body(c, id, "request") else null)
            What.REQUEST_HEADERS -> EntryCopy.headers(entry, "requestHeaders")
            What.RESPONSE_HEADERS -> EntryCopy.headers(entry, "responseHeaders")
            What.HANDSHAKE_HEADERS -> EntryCopy.headers(entry, "handshakeHeaders")
            What.REQUEST_BODY -> body(c, id, "request")
            What.RESPONSE_BODY -> body(c, id, "response")
            What.PAYLOAD -> body(c, id, "message")
            What.JSON -> EntryCopy.json(entry)
        }
    }

    /** A body as text; null when there is none or it is binary (the body viewer's Hex shows those). */
    private suspend fun body(c: DaemonClient, id: String, part: String): String? =
        runCatching { (c.call("network.body", JsonObject(target() + ("id" to JsonPrimitive(id)) + ("part" to JsonPrimitive(part)))).result.jsonObject["text"] as? JsonPrimitive)?.content }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    private fun say(text: String) = StatusBar.Info.set(text, project)
}
