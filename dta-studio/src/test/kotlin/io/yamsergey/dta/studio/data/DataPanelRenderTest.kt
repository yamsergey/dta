package io.yamsergey.dta.studio.data

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.present.data.DataNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Feature 027 US6: the Data tab against canned daemon answers; pictures go to build/body-views/. */
class DataPanelRenderTest : BasePlatformTestCase() {
    private val out = File("build/body-views").apply { mkdirs() }
    private val calls = mutableListOf<Pair<String, JsonObject>>()

    private fun answer(op: String, params: JsonObject): JsonObject = Json.parseToJsonElement(
        when (op) {
            "data.list" -> """{"stores":[
                {"id":"db:notes.db","kind":"database","name":"notes.db","path":"/d/notes.db","sizeBytes":4096,"flavor":"room","access":"live","accessNote":"the app's open database","entries":3},
                {"id":"prefs:settings","kind":"prefs","name":"settings","path":"/p/settings.xml","sizeBytes":276,"flavor":"plain","access":"live","accessNote":"the app's own SharedPreferences","entries":2},
                {"id":"datastore:profile.pb","kind":"datastore","name":"profile.pb","path":"/f/profile.pb","sizeBytes":9,"flavor":"proto","access":"live","accessNote":"written through the app's DataStore"}],
                "roots":[{"name":"data","path":"/data/user/0/app"}]}"""
            "data.db.schema" -> """{"store":"db:notes.db","room":true,"access":"live","accessNote":"the app's open database","objects":[
                {"name":"notes","type":"table","rowCount":2,"columns":[{"name":"id","type":"INTEGER","primaryKey":1},{"name":"title","type":"TEXT","notNull":true},{"name":"done","type":"INTEGER"}]},
                {"name":"room_master_table","type":"table","rowCount":1,"columns":[{"name":"id","type":"INTEGER","primaryKey":1}]}]}"""
            "data.db.query" -> """{"store":"db:notes.db","columns":["_dta_rowid","id","title","done"],"offset":0,"total":2,"rows":[
                [{"type":"integer","value":"1"},{"type":"integer","value":"1"},{"type":"text","value":"Milk"},{"type":"integer","value":"0"}],
                [{"type":"integer","value":"2"},{"type":"integer","value":"2"},{"type":"text","value":"Bread"},{"type":"integer","value":"1"}]]}"""
            "data.db.exec" -> """{"exec":{"store":"db:notes.db","changed":1,"invalidated":1,"statement":"x"},"write":{"store":"db:notes.db","change":"1 row changed","access":"live"}}"""
            "data.prefs.read" -> """{"store":"prefs:settings","access":"live","accessNote":"the app's own SharedPreferences","entries":[
                {"key":"dark","type":"boolean","value":false},{"key":"theme","type":"string","value":"light"}]}"""
            "data.prefs.set" -> """{"store":"prefs:settings","change":"dark = true (boolean)","access":"live"}"""
            "data.proto.read" -> """{"store":"datastore:profile.pb","base64":"CgNBZGEQJA==","sizeBytes":9,"access":"live","accessNote":"flow","decoded":{"name":"Ada","age":36},"type":"fixture.Profile","decodeNote":"decoded as fixture.Profile"}"""
            "data.files.list" -> """{"path":"data:/","total":2,"entries":[{"name":"files","path":"data:/files","dir":true,"children":3},{"name":"note.json","path":"data:/note.json","dir":false,"sizeBytes":18,"modifiedMs":1793740387051}]}"""
            "data.files.read" -> """{"path":"data:/note.json","sizeBytes":18,"text":"{\"hello\": \"world\"}"}"""
            else -> error(op)
        },
    ).jsonObject.also { calls += op to params }

    private fun panel() = DataPanel(project, CoroutineScope(Dispatchers.Unconfined), ui = { it.run() }) { op, params -> answer(op, params) }

    private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::all) ?: emptyList())
    private fun layout(c: Component) { c.doLayout(); (c as? Container)?.components?.forEach(::layout) }
    private fun render(c: Component, name: String) {
        c.setSize(1000, 460)
        layout(c)
        val image = BufferedImage(1000, 460, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { c.paint(g) } finally { g.dispose() }
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

    private fun node(p: DataPanel, label: String): DefaultMutableTreeNode {
        val root = p.tree.model.root as DefaultMutableTreeNode
        return root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.first { (it.userObject as? DataNode)?.label == label }
    }

    private fun select(p: DataPanel, n: DefaultMutableTreeNode) {
        val path = TreePath(n.path)
        p.tree.expandPath(path.parentPath)
        p.tree.selectionPath = path
    }

    fun `test the tree lists stores by kind, a table opens as an editable grid`() {
        val p = panel()
        p.load()
        val db = node(p, "notes.db")
        p.tree.expandPath(TreePath(db.path))
        assertEquals(listOf("notes", "room_master_table"), (0 until db.childCount).map { ((db.getChildAt(it) as DefaultMutableTreeNode).userObject as DataNode).label })
        select(p, node(p, "notes"))
        assertEquals(2, p.table.rowCount)
        assertEquals(listOf("id", "title", "done"), (0 until p.table.columnCount).map { p.table.getColumnName(it) })
        render(p, "data-table")
        p.table.model.setValueAt("Oat milk", 0, 1)
        assertEquals("UPDATE \"notes\" SET \"title\" = 'Oat milk' WHERE rowid = 1", (calls.last { it.first == "data.db.exec" }.second["sql"] as JsonPrimitive).content)
        assertTrue(all(p).filterIsInstance<javax.swing.JLabel>().any { it.text.contains("Room told") })
    }

    fun `test preferences edit with their type, a proto message shows as a tree, a file in the viewer`() {
        val p = panel()
        p.load()
        select(p, node(p, "settings"))
        assertEquals(2, p.prefsTable.rowCount)
        render(p, "data-prefs")
        p.prefsTable.model.setValueAt("true", 0, 2)
        val set = calls.last { it.first == "data.prefs.set" }.second
        assertEquals(listOf("dark", "true", "boolean"), listOf("key", "value", "type").map { (set[it] as JsonPrimitive).content })
        p.prefsTable.model.setValueAt("yes", 0, 2)
        assertEquals(1, calls.count { it.first == "data.prefs.set" })

        select(p, node(p, "profile.pb"))
        render(p, "data-proto")
        assertTrue(all(p).any { it is io.yamsergey.dta.studio.network.BodyTree })

        select(p, node(p, "data:/"))
        assertEquals(2, p.folderTable.rowCount)
        p.openFile("data:/note.json")
        render(p, "data-file")
    }
}
