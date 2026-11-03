package io.yamsergey.dta.studio.data

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.Units
import io.yamsergey.dta.present.data.DataBrowser
import io.yamsergey.dta.present.data.DataNode
import io.yamsergey.dta.present.data.DataNodeKind
import io.yamsergey.dta.present.data.DataText
import io.yamsergey.dta.present.data.PrefsGrid
import io.yamsergey.dta.present.data.RowGrid
import io.yamsergey.dta.present.network.DataTrees
import io.yamsergey.dta.protocol.data.DbSchema
import io.yamsergey.dta.protocol.data.FileContent
import io.yamsergey.dta.protocol.data.FileListing
import io.yamsergey.dta.protocol.data.PrefType
import io.yamsergey.dta.protocol.data.PrefsContent
import io.yamsergey.dta.protocol.data.ProtoContent
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.SqlKind
import io.yamsergey.dta.protocol.data.SqlStatements
import io.yamsergey.dta.protocol.data.StoreList
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.studio.network.BodyEditor
import io.yamsergey.dta.studio.network.BodyTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The Data tab (feature 027 US6): the app's stores in a tree — databases with their tables,
 * preferences, DataStore, files — and the selected one on the right: a table grid edited in
 * place with a SQL console, a preferences grid, a Proto DataStore message, a folder or a file.
 * [call] runs one `data.*` operation for the chosen device and app and returns its result.
 */
class DataPanel(
    private val project: Project,
    private val scope: CoroutineScope,
    private val ui: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    private val call: suspend (String, JsonObject) -> JsonObject,
) : JPanel(BorderLayout()) {
    private val status = JBLabel(" ").apply { foreground = JBColor.GRAY; setCopyable(true) }
    private val root = DefaultMutableTreeNode("Data")
    private val treeModel = DefaultTreeModel(root)
    val tree = Tree(treeModel)
    private val cards = CardLayout()
    private val right = JPanel(cards)

    // table
    private val sql = JBTextArea(3, 60).apply { lineWrap = true; emptyText.text = "SQL — Ctrl+Enter runs it" }
    private var grid: RowGrid? = null
    private var gridStore: String? = null
    private var queryPage: QueryPage? = null
    private var consoleQuery: String? = null
    private val gridModel = object : AbstractTableModel() {
        override fun getRowCount() = grid?.rowCount ?: queryPage?.rows?.size ?: 0
        override fun getColumnCount() = grid?.columns?.size ?: queryPage?.columns?.size ?: 0
        override fun getColumnName(column: Int) = grid?.columns?.get(column) ?: queryPage?.columns?.get(column) ?: ""
        override fun getValueAt(row: Int, column: Int): Any = grid?.text(row, column) ?: DataText.cell(queryPage!!.rows[row][column])
        override fun isCellEditable(row: Int, column: Int) = consoleQuery == null && grid?.editable != null
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val g = grid ?: return
            val statement = runCatching { g.update(row, column, value?.toString() ?: "") }.getOrElse { status.text = it.message; return }
            exec(gridStore!!, statement) { reloadPage() }
        }
    }
    val table = JBTable(gridModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION); autoResizeMode = JBTable.AUTO_RESIZE_OFF; emptyText.text = "No rows" }
    private val pageLabel = JBLabel(" ")
    private var offset = 0

    // preferences
    private var prefs: PrefsGrid? = null
    private var prefsStore: String? = null
    private val prefsModel = object : AbstractTableModel() {
        override fun getRowCount() = prefs?.entries?.size ?: 0
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Key", "Type", "Value")[column]
        override fun getValueAt(row: Int, column: Int): Any = prefs!!.row(row)[column]
        override fun isCellEditable(row: Int, column: Int) = column == 2
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val (key, text, type) = runCatching { prefs!!.edit(row, value?.toString() ?: "") }.getOrElse { status.text = it.message; return }
            setPref(prefsStore!!, key, text, type)
        }
    }
    val prefsTable = JBTable(prefsModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION); emptyText.text = "No keys" }

    // proto, files
    private val protoType = ComboBox<String>().apply { isEditable = true; toolTipText = "A message type of the app's protobuf schemas (Schemas tab), to decode with names" }
    private val protoContent = JPanel(BorderLayout())
    private var protoStore: String? = null
    private var protoJson: String? = null
    private val folderModel = FolderModel()

    private class FolderModel : AbstractTableModel() {
        var listing: FileListing? = null
        override fun getRowCount() = listing?.entries?.size ?: 0
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Name", "Size", "Modified")[column]
        override fun getValueAt(row: Int, column: Int): Any = listing!!.entries[row].let { e ->
            listOf(if (e.dir) e.name + "/" else e.name, if (e.dir) "${e.children ?: 0} entries" else Units.bytes(e.sizeBytes), if (e.modifiedMs > 0) java.time.Instant.ofEpochMilli(e.modifiedMs).toString().replace('T', ' ').take(19) else "")[column]
        }
    }

    val folderTable = JBTable(folderModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private var folderPath: String? = null
    private val fileHeader = JBLabel(" ")
    private val fileContent = JPanel(BorderLayout())
    private val editor by lazy { BodyEditor(project) }
    private var filePath: String? = null

    init {
        val refresh = JButton("Refresh").apply { addActionListener { load() } }
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(refresh) }, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val node = (value as? DefaultMutableTreeNode)?.userObject as? DataNode ?: run { append(value.toString()); return }
                append(node.label, if (node.kind == DataNodeKind.GROUP) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (node.detail.isNotEmpty() && node.kind != DataNodeKind.FOLDER) append("  " + node.detail, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        tree.addTreeSelectionListener { (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.let { open(it) } }
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) { (event.path.lastPathComponent as? DefaultMutableTreeNode)?.let { expand(it) } }
            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        right.add(JBLabel("Choose a store on the left.").apply { border = JBUI.Borders.empty(12); foreground = JBColor.GRAY }, EMPTY)
        right.add(tableCard(), TABLE)
        right.add(prefsCard(), PREFS)
        right.add(protoCard(), PROTO)
        right.add(folderCard(), FOLDER)
        right.add(fileCard(), FILE)
        add(JBSplitter(false, "dta.data.split", 0.28f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = right
        }, BorderLayout.CENTER)
    }

    private fun tableCard() = JPanel(BorderLayout()).apply {
        sql.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && (e.isControlDown || e.isMetaDown)) { e.consume(); runConsole() }
            }
        })
        val run = JButton("Run").apply { addActionListener { runConsole() } }
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(JBScrollPane(sql), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { add(run, BorderLayout.NORTH) }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(ActionLink("◀ Previous") { offset = maxOf(0, offset - PAGE); reloadPage() })
            add(pageLabel)
            add(ActionLink("Next ▶") { offset += PAGE; reloadPage() })
            add(ActionLink("+ Row") { grid?.editable?.let { g -> exec(gridStore!!, g.insert()) { reloadPage() } } ?: run { status.text = grid?.readOnlyReason ?: "Run a query on a table to edit it" } })
            add(ActionLink("− Row") { deleteRow() })
        }, BorderLayout.SOUTH)
    }

    private fun prefsCard() = JPanel(BorderLayout()).apply {
        add(JBScrollPane(prefsTable), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(ActionLink("+ Key") { addKey() })
            add(ActionLink("− Key") { removeKey() })
            add(JBLabel("Double-click a value to edit it.").apply { foreground = JBColor.GRAY })
        }, BorderLayout.SOUTH)
    }

    private fun protoCard() = JPanel(BorderLayout()).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(JBLabel("Type:")); add(protoType)
            add(JButton("Decode").apply { addActionListener { protoStore?.let { readProto(it) } } })
            add(ActionLink("Edit JSON…") { editProto() })
        }, BorderLayout.NORTH)
        add(protoContent, BorderLayout.CENTER)
    }

    private fun folderCard() = JPanel(BorderLayout()).apply {
        folderTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) folderModel.listing?.entries?.getOrNull(folderTable.selectedRow)?.let { if (it.dir) openFolder(it.path) else openFile(it.path) }
            }
        })
        add(JBScrollPane(folderTable), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(ActionLink("Upload…") { upload(folderPath ?: return@ActionLink) })
            add(ActionLink("New folder…") { newFolder() })
            add(ActionLink("Delete…") { folderModel.listing?.entries?.getOrNull(folderTable.selectedRow)?.let { delete(it.path) } })
        }, BorderLayout.SOUTH)
    }

    private fun fileCard() = JPanel(BorderLayout()).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(fileHeader)
            add(ActionLink("Save to…") { saveFile() })
            add(ActionLink("Replace from…") { filePath?.let { upload(it, replace = true) } })
            add(ActionLink("Delete…") { filePath?.let { delete(it) } })
        }, BorderLayout.NORTH)
        add(fileContent, BorderLayout.CENTER)
    }

    // ---- loading ----

    /** Reads the app's stores again and rebuilds the tree. */
    fun load(): Unit = request("data.list", StoreList.serializer(), JsonObject(emptyMap())) { list ->
        root.removeAllChildren()
        for (group in DataBrowser.tree(list)) {
            val g = DefaultMutableTreeNode(group)
            group.children.forEach { c -> g.add(DefaultMutableTreeNode(c).also { n -> if (c.kind == DataNodeKind.DATABASE || c.kind == DataNodeKind.FOLDER) n.add(DefaultMutableTreeNode(LOADING)) }) }
            root.add(g)
        }
        treeModel.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i).also { if (i > 3) return@also }
        status.text = list.completeness.firstOrNull()?.reason ?: "${list.stores.size} stores."
    }

    private fun expand(n: DefaultMutableTreeNode) {
        val node = n.userObject as? DataNode ?: return
        if (n.childCount != 1 || (n.firstChild as DefaultMutableTreeNode).userObject != LOADING) return
        when (node.kind) {
            DataNodeKind.DATABASE -> request("data.db.schema", DbSchema.serializer(), obj("store" to node.store!!)) { schema ->
                val store = node.store!!
                schemas[store] = schema
                n.removeAllChildren()
                DataBrowser.tables(store, schema.objects).forEach { n.add(DefaultMutableTreeNode(it)) }
                treeModel.nodeStructureChanged(n)
            }
            DataNodeKind.FOLDER -> request("data.files.list", FileListing.serializer(), obj("path" to node.path!!)) { listing ->
                n.removeAllChildren()
                listing.entries.forEach { e ->
                    n.add(DefaultMutableTreeNode(DataNode(if (e.dir) DataNodeKind.FOLDER else DataNodeKind.FILE, e.name, if (e.dir) "" else Units.bytes(e.sizeBytes), path = e.path)).also { if (e.dir) it.add(DefaultMutableTreeNode(LOADING)) })
                }
                treeModel.nodeStructureChanged(n)
            }
            else -> Unit
        }
    }

    private val schemas = HashMap<String, DbSchema>()

    private fun open(n: DefaultMutableTreeNode) {
        val node = n.userObject as? DataNode ?: return
        when (node.kind) {
            DataNodeKind.TABLE -> openTable(node.store!!, node.table!!)
            DataNodeKind.DATABASE -> { expand(n); showDatabase(node.store!!) }
            DataNodeKind.PREFS -> openPrefs(node.store!!)
            DataNodeKind.PROTO -> { protoStore = node.store; loadProtoTypes(); readProto(node.store!!) }
            DataNodeKind.FOLDER -> openFolder(node.path!!)
            DataNodeKind.FILE -> openFile(node.path!!)
            DataNodeKind.GROUP -> cards.show(right, EMPTY)
        }
    }

    private fun showDatabase(store: String) {
        gridStore = store
        grid = null
        queryPage = null
        consoleQuery = null
        sql.text = ""
        gridModel.fireTableStructureChanged()
        pageLabel.text = "Choose a table, or run SQL on $store."
        cards.show(right, TABLE)
    }

    fun openTable(store: String, name: String) {
        val table = schemas[store]?.objects?.firstOrNull { it.name == name } ?: return
        gridStore = store
        grid = RowGrid(table)
        consoleQuery = null
        offset = 0
        sql.text = "SELECT * FROM ${SqlStatements.identifier(name)}"
        cards.show(right, TABLE)
        reloadPage()
        status.text = grid!!.readOnlyReason ?: "Double-click a cell to edit it; the change is an UPDATE through ${schemas[store]?.accessNote ?: "the database"}."
    }

    private fun reloadPage() {
        val store = gridStore ?: return
        val select = consoleQuery ?: grid?.select ?: return
        request("data.db.query", QueryPage.serializer(), obj("store" to store, "sql" to select, "offset" to offset, "limit" to PAGE)) { page ->
            if (consoleQuery == null) grid?.show(page) else queryPage = page
            gridModel.fireTableStructureChanged()
            pageLabel.text = if (page.rows.isEmpty()) "No rows" else "Rows ${page.offset + 1}–${page.offset + page.rows.size}" + (page.total?.let { " of $it" } ?: "")
        }
    }

    private fun runConsole() {
        val store = gridStore ?: return
        val text = sql.text.trim().takeIf { it.isNotEmpty() } ?: return
        if (SqlStatements.kind(text) == SqlKind.QUERY) {
            // The table's own page stays editable; any other query is a read-only result.
            val own = grid?.let { text.equals("SELECT * FROM ${SqlStatements.identifier(it.columns.let { _ -> currentTable() ?: "" })}", ignoreCase = true) } == true
            consoleQuery = if (own) null else text
            offset = 0
            reloadPage()
        } else {
            exec(store, text) { reloadPage(); load() }
        }
    }

    private fun currentTable(): String? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.let { (it.userObject as? DataNode)?.table }

    private fun deleteRow() {
        val g = grid?.editable ?: return run { status.text = grid?.readOnlyReason ?: "Choose a table" }
        val row = table.selectedRow.takeIf { it >= 0 } ?: return run { status.text = "Select a row first." }
        if (Messages.showYesNoDialog(project, "Delete this row from ${currentTable()}?", "Delete Row", null) != Messages.YES) return
        exec(gridStore!!, g.delete(row)) { reloadPage() }
    }

    private fun exec(store: String, statement: String, then: () -> Unit) {
        status.text = "Running…"
        scope.launch {
            val result = runCatching { call("data.db.exec", obj("store" to store, "sql" to statement)) }
            ui.invoke(Runnable {
                result.fold({ r ->
                    val w = r["write"] as? JsonObject
                    status.text = ((w?.get("change") as? JsonPrimitive)?.content ?: "Done") +
                        (((r["exec"] as? JsonObject)?.get("invalidated") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }?.let { " · Room told, the app's screens follow" } ?: "")
                    then()
                }, { status.text = it.message ?: it.javaClass.simpleName })
            })
        }
    }

    // ---- preferences ----

    fun openPrefs(store: String): Unit = request("data.prefs.read", PrefsContent.serializer(), obj("store" to store)) { content ->
        prefsStore = store
        prefs = PrefsGrid(content.entries)
        prefsModel.fireTableDataChanged()
        cards.show(right, PREFS)
        status.text = "${content.access.name.lowercase()}: ${content.accessNote}"
    }

    private fun setPref(store: String, key: String, value: String, type: PrefType): Unit = write("data.prefs.set", obj("store" to store, "key" to key, "value" to value, "type" to type.wireName)) { openPrefs(store) }

    private fun addKey() {
        val store = prefsStore ?: return
        val key = Messages.showInputDialog(project, "Key:", "Add Key", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val types = PrefType.entries.map { it.wireName }.toTypedArray()
        val type = Messages.showEditableChooseDialog("Type of $key:", "Add Key", null, types, "string", null) ?: return
        val value = Messages.showInputDialog(project, "Value ($type):", "Add Key", null) ?: return
        runCatching { PrefType.ofWire(type).parseFor(key, value) }.onFailure { status.text = it.message; return }
        setPref(store, key, value, PrefType.ofWire(type))
    }

    private fun removeKey() {
        val store = prefsStore ?: return
        val key = prefs?.entries?.getOrNull(prefsTable.selectedRow)?.key ?: return run { status.text = "Select a key first." }
        if (Messages.showYesNoDialog(project, "Remove $key from $store?", "Remove Key", null) != Messages.YES) return
        write("data.prefs.remove", JsonObject(mapOf("store" to JsonPrimitive(store), "keys" to JsonArray(listOf(JsonPrimitive(key)))))) { openPrefs(store) }
    }

    // ---- Proto DataStore ----

    private fun readProto(store: String) {
        val type = (protoType.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() }
        request("data.proto.read", ProtoContent.serializer(), JsonObject(obj("store" to store) + (type?.let { mapOf("type" to JsonPrimitive(it)) } ?: emptyMap()))) { content ->
            protoJson = content.decoded?.toString()
            content.type?.let { protoType.editor.item = it }
            protoContent.removeAll()
            val node = protoJson?.let { DataTrees.json(it) }
            protoContent.add(if (node != null) BodyTree(node) { } else JBLabel("Nothing decoded.").apply { border = JBUI.Borders.empty(12) }, BorderLayout.CENTER)
            protoContent.add(JBLabel("${content.sizeBytes} bytes · ${content.decodeNote ?: ""}").apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
            protoContent.revalidate()
            cards.show(right, PROTO)
            status.text = "${content.access.name.lowercase()}: ${content.accessNote}"
        }
    }

    /** The message types of the app's schema sets (Schemas tab), offered in the Type box; [call] wraps the list as {"items": […]}. */
    private fun loadProtoTypes() {
        scope.launch {
            val types = runCatching {
                (call("network.schema.list", JsonObject(emptyMap()))["items"] as? JsonArray ?: JsonArray(emptyList())).flatMap { set ->
                    ((set as? JsonObject)?.get("types") as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
                }
            }.getOrDefault(emptyList())
            ui.invoke(Runnable {
                val current = protoType.editor.item
                protoType.removeAllItems()
                types.sorted().forEach(protoType::addItem)
                protoType.editor.item = current
            })
        }
    }

    private fun editProto() {
        val store = protoStore ?: return
        val type = (protoType.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return run { status.text = "Give the message type first (from the Schemas tab)." }
        val area = JBTextArea(protoJson ?: "{}", 16, 60)
        val ok = com.intellij.openapi.ui.DialogBuilder(project).apply {
            setTitle("Replace $store with a $type")
            setCenterPanel(JBScrollPane(area))
        }.showAndGet()
        if (!ok) return
        write("data.proto.write", obj("store" to store, "type" to type, "json" to area.text)) { readProto(store) }
    }

    // ---- files ----

    fun openFolder(path: String): Unit = request("data.files.list", FileListing.serializer(), obj("path" to path)) { listing ->
        folderPath = listing.path
        folderModel.listing = listing
        folderModel.fireTableDataChanged()
        cards.show(right, FOLDER)
        status.text = "${listing.path}: ${listing.total} entries. Double-click to open."
    }

    fun openFile(path: String): Unit = request("data.files.read", FileContent.serializer(), obj("path" to path)) { content ->
        filePath = path
        fileHeader.text = "$path · ${Units.bytes(content.sizeBytes)}"
        fileContent.removeAll()
        val text = content.text
        if (text != null) {
            editor.show(text, path.substringAfterLast('.', "txt").takeIf { it.length in 1..5 } ?: "txt")
            fileContent.add(editor, BorderLayout.CENTER)
        } else {
            fileContent.add(JBLabel("Binary file: use Save to… to open it on this computer.").apply { border = JBUI.Borders.empty(12) }, BorderLayout.NORTH)
        }
        fileContent.revalidate()
        cards.show(right, FILE)
    }

    private fun saveFile() {
        val path = filePath ?: return
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save File", "Where to save $path"), project)
        val target = wrapper.save(path.substringAfterLast('/'))?.file ?: return
        write("data.files.read", obj("path" to path, "output" to target.path)) { status.text = "Saved to ${target.path}." }
    }

    private fun upload(path: String, replace: Boolean = false) {
        val chosen = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(), project, null) ?: return
        val target = if (replace) path else path.trimEnd('/') + "/" + chosen.name
        write("data.files.write", obj("path" to target, "input" to chosen.path)) { if (replace) openFile(target) else openFolder(path) }
    }

    private fun newFolder() {
        val base = folderPath ?: return
        val name = Messages.showInputDialog(project, "Folder name:", "New Folder", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        write("data.files.mkdir", obj("path" to base.trimEnd('/') + "/" + name)) { openFolder(base) }
    }

    private fun delete(path: String) {
        if (Messages.showYesNoDialog(project, "Delete $path (with everything in it)?", "Delete", null) != Messages.YES) return
        write("data.files.delete", obj("path" to path)) { openFolder(path.substringBeforeLast('/').let { if (it.endsWith(":")) "$it/" else it }) }
    }

    // ---- plumbing ----

    private fun write(op: String, params: JsonObject, then: () -> Unit) {
        status.text = "Working…"
        scope.launch {
            val result = runCatching { call(op, params) }
            ui.invoke(Runnable {
                result.fold({ r ->
                    status.text = (r["change"] as? JsonPrimitive)?.content?.let { c -> "${(r["store"] as? JsonPrimitive)?.content}: $c" + if ((r["access"] as? JsonPrimitive)?.content == "file") " (written to the file; the app reads it on next open)" else "" } ?: "Done."
                    then()
                }, { status.text = it.message ?: it.javaClass.simpleName })
            })
        }
    }

    private fun <T> request(op: String, s: KSerializer<T>, params: JsonObject, show: (T) -> Unit) {
        scope.launch {
            val result = runCatching { DtaJson.decodeFromJsonElement(s, call(op, params)) }
            ui.invoke(Runnable { result.fold(show) { status.text = it.message ?: it.javaClass.simpleName } })
        }
    }

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) as JsonElement })

    companion object {
        const val PAGE = 100
        private const val EMPTY = "empty"
        private const val TABLE = "table"
        private const val PREFS = "prefs"
        private const val PROTO = "proto"
        private const val FOLDER = "folder"
        private const val FILE = "file"
        private val LOADING = DataNode(DataNodeKind.GROUP, "Loading…")
    }
}
