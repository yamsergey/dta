package io.yamsergey.dta.studio.network

import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.present.network.DataTrees
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Feature 022: the body views draw on the platform the plugin runs on (no API it lacks, no
 * exception while painting), and the editor builds fold regions. Pictures are written to
 * build/body-views/ to be looked at.
 */
class BodyViewsRenderTest : BasePlatformTestCase() {
    private val catalog = """{"store":{"name":"Fixture shop","open":true,"owner":null,"address":{"city":"Berlin","geo":{"lat":52.53,"lon":13.38}}},""" +
        """"items":[${(1..60).joinToString(",") { """{"id":$it,"name":"Item $it","price":${it * 10 + 0.99},"tags":["sale"]}""" }}],"page":{"next":"cursor-2"}}"""
    private val out = File("build/body-views").apply { mkdirs() }

    private fun render(c: Component, name: String, w: Int = 1200, h: Int = 700): BufferedImage {
        c.setSize(w, h)
        layout(c)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { c.paint(g) } finally { g.dispose() }
        ImageIO.write(image, "png", File(out, "$name.png"))
        return image
    }

    private fun layout(c: Component) {
        c.doLayout()
        (c as? Container)?.components?.forEach(::layout)
    }

    private fun colours(image: BufferedImage): Int {
        val seen = HashSet<Int>()
        for (x in 0 until image.width step 3) for (y in 0 until image.height step 3) seen += image.getRGB(x, y)
        return seen.size
    }

    fun `test the graph draws its cards`() {
        val graph = BodyGraph(DataTrees.json(catalog)!!) {}
        val image = render(graph, "graph")
        assertTrue("the graph drew nothing: ${colours(image)} colours", colours(image) > 20)
    }

    fun `test the tree draws coloured values`() {
        val tree = BodyTree(DataTrees.json(catalog)!!) {}
        tree.expandAll(true)
        val image = render(tree, "tree", 600, 500)
        assertTrue(colours(image) > 10)
    }

    fun `test the editor highlights and folds JSON and XML`() {
        val editor = BodyEditor(project)
        editor.show(io.yamsergey.dta.present.network.PrettyJson.format(catalog)!!, "json")
        try {
            editor.addNotify() // displayable without a window: the editor is created
            repeat(50) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(20) }
            val created = EditorFactory.getInstance().allEditors.last()
            assertTrue("JSON fold regions: ${created.foldingModel.allFoldRegions.size}", created.foldingModel.allFoldRegions.size > 3)
            render(editor, "editor-json", 900, 500)

            editor.show("<feed lang=\"en\">\n  <entry id=\"1\">\n    <title>One</title>\n  </entry>\n</feed>", "xml")
            repeat(50) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(20) }
            val xml = EditorFactory.getInstance().allEditors.last()
            assertEquals("xml", xml.virtualFile?.extension ?: com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(xml.document)?.extension)
        } finally {
            editor.removeNotify()
        }
    }

    @Suppress("unused")
    private val background = Color.WHITE
}
