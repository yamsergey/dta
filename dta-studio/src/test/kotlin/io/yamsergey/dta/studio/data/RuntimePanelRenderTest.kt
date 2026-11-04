package io.yamsergey.dta.studio.data

import com.intellij.testFramework.fixtures.BasePlatformTestCase
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

/** Feature 028 US5: the Runtime tab against canned daemon answers; pictures go to build/body-views/. */
class RuntimePanelRenderTest : BasePlatformTestCase() {
    private val out = File("build/body-views").apply { mkdirs() }
    private val calls = mutableListOf<Pair<String, JsonObject>>()

    private fun answer(op: String, params: JsonObject): JsonObject = Json.parseToJsonElement(
        when (op) {
            "runtime.navigation" -> """{"hosts":[{"id":"c1","kind":"navigation","backStack":[{"route":"catalog"},{"route":"product/{id}","arguments":{"id":"42"}}],
                "graph":{"navigator":"navigation","children":[{"route":"catalog","id":1,"navigator":"composable","start":true},{"route":"product/{id}","id":2,"navigator":"composable","arguments":["id"]}]}}]}"""
            "runtime.viewmodels" -> """{"viewModels":[{"id":"v1","className":"app.CartViewModel","owner":"activity MainActivity","properties":[
                {"path":"state","kind":"stateflow","type":"CartState","value":{"items":2,"coupon":null},"editable":true},
                {"path":"banner","kind":"livedata","type":"String","value":"Welcome","editable":true}],
                "savedState":[{"path":"step","kind":"savedstate","type":"Integer","value":1,"editable":true}]}]}"""
            "runtime.functions" -> """{"functions":[{"id":"cart.clear","source":"dta-state","description":"Empty the cart","invocable":true}]}"""
            "runtime.vm.set", "runtime.saved.set" -> """{"store":"viewmodel v1","change":"set","access":"live"}"""
            "runtime.memory" -> """{"javaUsedBytes":12582912,"javaMaxBytes":268435456,"javaFreeBytes":0,"nativeAllocatedBytes":4194304,"nativeHeapBytes":0,"pssKb":81920,"gcCount":7,"gcTimeMs":35}"""
            else -> error(op)
        },
    ).jsonObject.also { calls += op to params }

    private fun panel() = RuntimePanel(project, CoroutineScope(Dispatchers.Unconfined), ui = { it.run() }) { op, params -> answer(op, params) }

    private fun layout(c: Component) { c.doLayout(); (c as? Container)?.components?.forEach(::layout) }
    private fun render(c: Component, name: String) {
        c.setSize(1000, 420)
        layout(c)
        val image = BufferedImage(1000, 420, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { c.paint(g) } finally { g.dispose() }
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

    private fun select(p: RuntimePanel, label: String) {
        val root = p.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.first { it.userObject.toString().contains(label) }
        p.tree.selectionPath = TreePath(node.path)
    }

    fun `test a ViewModel's properties are edited in place as JSON, saved state too`() {
        val p = panel()
        p.load()
        select(p, "CartViewModel")
        assertEquals(3, p.propsTable.rowCount)
        render(p, "runtime-viewmodel")
        p.propsTable.model.setValueAt("{\"items\":0,\"coupon\":\"X\"}", 0, 2)
        assertEquals(listOf("v1", "state", "{\"items\":0,\"coupon\":\"X\"}"), listOf("viewModel", "path", "value").map { (calls.last { it.first == "runtime.vm.set" }.second[it] as JsonPrimitive).content })
        p.propsTable.model.setValueAt("3", 2, 2)
        assertEquals("step", (calls.last { it.first == "runtime.saved.set" }.second["key"] as JsonPrimitive).content)
        p.propsTable.model.setValueAt("not json", 1, 2)
        assertEquals(1, calls.count { it.first == "runtime.vm.set" })
    }

    fun `test the navigation host and memory show as text with their actions`() {
        val p = panel()
        p.load()
        select(p, "product/{id}")
        render(p, "runtime-navigation")
        select(p, "MEMORY")
        render(p, "runtime-memory")
    }

    fun `test the selected value is shown formatted below the table and edited there`() {
        val p = panel()
        p.load()
        select(p, "CartViewModel")
        assertEquals("All properties of CartViewModel", p.valueView.header)
        render(p, "runtime-viewmodel-whole")
        assertEquals("{2} {\"items\":2,\"coupon\":null}", p.propsTable.getValueAt(0, 2))
        assertFalse(p.propsTable.model.isCellEditable(0, 2))
        assertTrue(p.propsTable.model.isCellEditable(1, 2))
        p.propsTable.setRowSelectionInterval(0, 0)
        assertEquals("state · stateflow · CartState · editable", p.valueView.header)
        render(p, "runtime-viewmodel-value")
        p.valueView.beginEdit()
        assertTrue(p.valueView.isEditing)
        render(p, "runtime-viewmodel-edit")
        p.valueView.submit("{\n  \"items\": 5,\n  \"coupon\": \"X\"\n}")
        assertEquals("{\"items\":5,\"coupon\":\"X\"}", (calls.last { it.first == "runtime.vm.set" }.second["value"] as JsonPrimitive).content)
        assertEquals(0, p.propsTable.selectedRow)
        val before = calls.size
        p.valueView.beginEdit()
        p.valueView.submit("{ items: ")
        assertEquals(before, calls.size)
    }
}
