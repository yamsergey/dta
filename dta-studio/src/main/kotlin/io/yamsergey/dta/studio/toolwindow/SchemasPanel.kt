package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import io.yamsergey.dta.studio.ui.textRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.present.schemas.SchemasView
import io.yamsergey.dta.present.schemas.TypeChoice
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.StatementMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The Schemas view (FR-014): the app's protobuf schema sets — added from `.proto` files of the
 * project — and the mappings that say which bodies decode with which type; broken ones stated.
 */
class SchemasPanel(
    private val project: Project,
    private val controller: ContextController,
    private val scope: CoroutineScope,
    /** Schemas and mappings from the project (feature 013). */
    private val sync: io.yamsergey.dta.studio.schemas.SchemaSync? = null,
) : JPanel(BorderLayout()) {
    private val syncPanel = JPanel(BorderLayout())
    private val body = JPanel(BorderLayout())
    private val problem = JBLabel().apply { foreground = DtaColors.of("status-server"); setCopyable(true) }
    private var target: JsonObject? = null
    private var view: SchemasView? = null

    init {
        val addSet = JButton("Add .proto Files…").apply { addActionListener { addSet() } }
        val addMapping = JButton("Add Mapping…").apply { addActionListener { addMapping() } }
        val refresh = JButton("Refresh").apply { addActionListener { refresh() } }
        val syncNow = JButton("Sync from Project").apply {
            toolTipText = "Take the .proto files of the project and its dependencies, and mappings from its Retrofit declarations."
            addActionListener { sync?.syncNow() }
            isVisible = sync != null
        }
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(syncNow); add(addSet); add(addMapping); add(refresh) }, BorderLayout.NORTH)
            add(syncPanel, BorderLayout.CENTER)
            add(problem, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(body), BorderLayout.CENTER)
        sync?.let { s ->
            scope.launch {
                s.state.collect { state -> withContext(Dispatchers.Main) { showSync(state); if (!state.running) refresh() } }
            }
        }
    }

    /** What the last sync from the project did: failures, notes, what nothing maps yet (013). */
    private fun showSync(state: io.yamsergey.dta.studio.schemas.SchemaSyncState) {
        syncPanel.removeAll()
        val lines = buildList {
            if (state.running) add(JBLabel("Syncing schemas from the project…").apply { foreground = JBColor.GRAY })
            state.error?.let { add(JBLabel("Sync failed: $it").apply { foreground = DtaColors.of("status-server") }) }
            val result = state.result
            (result?.get("failed") as? JsonArray)?.forEach { f ->
                val o = f as JsonObject
                add(row(StatementMark(io.yamsergey.dta.present.Statement(io.yamsergey.dta.present.StatementKind.DEGRADED, detail = "not compiled")),
                    JBLabel("${(o["file"] as? JsonPrimitive)?.content}: ${(o["reason"] as? JsonPrimitive)?.content}")))
            }
            (result?.get("notes") as? JsonArray)?.forEach { add(JBLabel((it as JsonPrimitive).content).apply { foreground = JBColor.GRAY }) }
            (result?.get("rejectedMappings") as? JsonArray)?.forEach { add(JBLabel("Not mapped: ${(it as JsonPrimitive).content}").apply { foreground = JBColor.GRAY }) }
            state.unmappedEndpoints.forEach { add(JBLabel("Not mapped: $it").apply { foreground = JBColor.GRAY }) }
            (result?.get("unmappedTypes") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { types ->
                add(JBLabel("No mapping yet for: ${types.joinToString(", ") { (it as JsonPrimitive).content }}. Add one, or let an agent map them from the code.").apply { foreground = JBColor.GRAY })
            }
        }
        if (lines.isNotEmpty()) syncPanel.add(JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            lines.forEach { add(it) }
        }, BorderLayout.NORTH)
        syncPanel.revalidate()
        syncPanel.repaint()
    }

    fun show(state: ContextState) {
        target = if (state.device != null && state.app != null) JsonObject(mapOf("device" to JsonPrimitive(state.device), "app" to JsonPrimitive(state.app))) else null
        refresh()
    }

    fun refresh() {
        val client = controller.client
        val params = target
        if (client == null || params == null) return render(null, "Choose an app with DTA inside.")
        scope.launch {
            val result = runCatching {
                SchemasView.from(client.call("network.schema.list", params).result.jsonArray, client.call("network.mapping.list", params).result.jsonArray)
            }
            withContext(Dispatchers.Main) { render(result.getOrNull(), result.exceptionOrNull()?.message) }
        }
    }

    private fun render(next: SchemasView?, failure: String?) {
        view = next
        body.removeAll()
        val form = FormBuilder.createFormBuilder()
        if (next == null) {
            form.addComponent(JBLabel(failure.orEmpty()))
        } else {
            form.addComponent(JBLabel("Schema sets").apply { font = JBUI.Fonts.label().asBold() })
            next.empty?.let { form.addComponent(JBLabel(it).apply { foreground = JBColor.GRAY }) }
            next.sets.forEach { set ->
                form.addComponent(row(JBLabel(set.name), JBLabel(set.detail).apply { foreground = JBColor.GRAY }, ActionLink("Remove") { removeSet(set.name) }))
                set.types.forEach { form.addComponent(JBLabel(it).apply { border = JBUI.Borders.emptyLeft(24) }) }
            }
            form.addSeparator()
            form.addComponent(JBLabel("Mappings").apply { font = JBUI.Fonts.label().asBold() })
            if (next.mappings.isEmpty()) form.addComponent(JBLabel("No mappings.").apply { foreground = JBColor.GRAY })
            next.mappings.forEach { m ->
                val parts = buildList {
                    add(JBLabel(m.text))
                    m.broken?.let { add(StatementMark(it)); add(JBLabel(it.detail).apply { foreground = JBColor.GRAY }) }
                    if (m.builtin) add(JBLabel("built-in").apply { foreground = JBColor.GRAY })
                    m.location?.let { location -> add(ActionLink(location) { openLocation(location) }) }
                    if (m.removable) add(ActionLink("Remove") { call("network.mapping.remove", "id" to JsonPrimitive(m.id)) })
                }
                form.addComponent(row(*parts.toTypedArray()))
            }
        }
        body.add(form.panel.apply { border = JBUI.Borders.empty(0, 12, 12, 12) }, BorderLayout.NORTH)
        body.revalidate()
        body.repaint()
    }

    private fun row(vararg parts: javax.swing.JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { parts.forEach(::add) }

    private fun addSet() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withFileFilter { it.isDirectory || it.extension == "proto" }
            .withTitle("Add Protobuf Schema Set")
            .withDescription("Choose .proto files, or folders of them, from the project.")
        val files = FileChooser.chooseFiles(descriptor, project, project.guessProjectDir())
        if (files.isEmpty()) return
        val suggested = files.first().let { if (it.isDirectory) it.name else it.parent?.name ?: it.nameWithoutExtension }
        val name = Messages.showInputDialog(project, "Name of the schema set:", "Add Protobuf Schema Set", null, suggested, null) ?: return
        call("network.schema.add", "name" to JsonPrimitive(name), "paths" to JsonArray(files.map { JsonPrimitive(it.path) }))
    }

    private fun removeSet(name: String) {
        val answer = Messages.showYesNoDialog(project, "Mappings that use '$name' stop decoding until it is added again.", "Remove Schema Set '$name'", null)
        if (answer == Messages.YES) call("network.schema.remove", "name" to JsonPrimitive(name))
    }

    private fun addMapping() {
        val types = view?.types.orEmpty()
        if (types.isEmpty()) return problem("Add a schema set first: a mapping names one of its types.")
        val url = JBTextField("*/", 40)
        val part = ComboBox(arrayOf("response", "request", "message"))
        val method = JBTextField(8)
        val direction = ComboBox(arrayOf("any", "in", "out"))
        val type = ComboBox(types.toTypedArray()).apply {
            renderer = textRenderer { "${it.type}  (${it.schema})" }
        }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("URL pattern", url)
            .addComponent(JBLabel("* matches any run of characters, e.g. */v1/cart").apply { foreground = JBColor.GRAY })
            .addLabeledComponent("Part", part)
            .addLabeledComponent("HTTP method (optional)", method)
            .addLabeledComponent("WebSocket direction", direction)
            .addLabeledComponent("Message type", type)
            .panel
        val dialog = DialogBuilder(project).title("Add Protobuf Mapping").centerPanel(form)
        if (dialog.show() != DialogWrapper.OK_EXIT_CODE) return
        val chosen = type.selectedItem as TypeChoice
        val params = buildList<Pair<String, JsonElement>> {
            add("urlPattern" to JsonPrimitive(url.text.trim()))
            add("part" to JsonPrimitive(part.selectedItem as String))
            add("schema" to JsonPrimitive(chosen.schema))
            add("type" to JsonPrimitive(chosen.type))
            method.text.trim().takeIf { it.isNotEmpty() }?.let { add("method" to JsonPrimitive(it.uppercase())) }
            (direction.selectedItem as String).takeIf { it != "any" && part.selectedItem == "message" }?.let { add("direction" to JsonPrimitive(it)) }
        }
        call("network.mapping.add", *params.toTypedArray())
    }

    /** Jumps to `File.kt:12` of a derived mapping. */
    private fun openLocation(location: String) {
        val name = location.substringBefore(':')
        val line = location.substringAfter(':').toIntOrNull()?.minus(1) ?: 0
        val file = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(name, com.intellij.psi.search.GlobalSearchScope.projectScope(project)).firstOrNull() ?: return
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, line, 0).navigate(true)
    }

    private fun problem(message: String?) {
        problem.text = message.orEmpty()
    }

    private fun call(operation: String, vararg extra: Pair<String, JsonElement>) {
        val client = controller.client ?: return
        val params = target ?: return problem("Choose an app with DTA inside.")
        problem(null)
        scope.launch {
            val result = runCatching { client.call(operation, JsonObject(params + extra)) }
            withContext(Dispatchers.Main) {
                result.onFailure { problem(it.message) }
                refresh()
            }
        }
    }
}
