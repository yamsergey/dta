package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 014 (T009, research R6): the screen as one image item, only when asked, bounded. */
class McpScreenTest {
    private fun png(w: Int, h: Int, noisy: Boolean = false): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        if (noisy) {
            val r = Random(1)
            for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, r.nextInt())
        } else {
            image.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, w, h); dispose() }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun capture(png: ByteArray?) = buildJsonObject {
        put("screen", buildJsonObject { put("widthPx", 1080); put("heightPx", 2400); put("density", 2.6) })
        put("source", "sidekick")
        put("windows", buildJsonArray { })
        if (png != null) {
            put("screenImage", buildJsonObject {
                put("state", "present"); put("widthPx", 1080); put("heightPx", 2400)
                put("data", Base64.getEncoder().encodeToString(png))
            })
        }
    }

    private fun tools(result: JsonObject, seen: MutableList<JsonObject> = mutableListOf()) =
        McpTools(call = { _, params -> seen += params; Selected(null, result) })

    @Test
    fun `the image comes as its own item, and the text keeps the metadata without the data`() = runBlocking {
        val png = png(1080, 2400)
        val outcome = tools(capture(png)).invoke("layout_tree", buildJsonObject { put("screen", true) })
        assertFalse(outcome.isError, outcome.text)
        assertEquals(1, outcome.images.size)
        assertEquals("image/png", outcome.images.single().mimeType)
        assertEquals(png.toList(), Base64.getDecoder().decode(outcome.images.single().data).toList())
        val image = DtaJson.parseToJsonElement(outcome.text).jsonObject["result"]!!.jsonObject["screenImage"]!!.jsonObject
        assertFalse("data" in image)
        assertEquals("present", image["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without asking there is no image`() = runBlocking {
        val outcome = tools(capture(null)).invoke("layout_tree", JsonObject(emptyMap()))
        assertEquals(emptyList(), outcome.images)
    }

    @Test
    fun `a large image is scaled down to fit, and the scale is stated`() = runBlocking {
        val outcome = tools(capture(png(1600, 3200, noisy = true))).invoke("layout_tree", buildJsonObject { put("screen", true) })
        val bytes = Base64.getDecoder().decode(outcome.images.single().data)
        assertTrue(bytes.size <= ScreenImageBound.MAX_BYTES, "${bytes.size}")
        val scaled = ImageIO.read(ByteArrayInputStream(bytes))
        assertTrue(maxOf(scaled.width, scaled.height) <= ScreenImageBound.MAX_SIDE, "${scaled.width}x${scaled.height}")
        val result = DtaJson.parseToJsonElement(outcome.text).jsonObject["result"]!!.jsonObject
        val scale = result["screenImage"]!!.jsonObject["scale"]!!.jsonPrimitive.content.toFloat()
        assertEquals(scaled.width.toFloat() / 1600, scale, 0.01f)
        assertTrue(result["degraded"]!!.jsonArray.any { "screen-scaled" in it.jsonObject["reason"]!!.jsonPrimitive.content }, "$result")
    }
}
