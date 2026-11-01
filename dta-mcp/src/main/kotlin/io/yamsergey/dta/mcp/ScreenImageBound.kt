package io.yamsergey.dta.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** One image item of a tool result. */
data class ToolImage(val data: String, val mimeType: String)

/**
 * Feature 014 (research R6): a layout result's inline screen image leaves the JSON and becomes
 * one image item, scaled down when it is larger than an agent should take, with the scale stated.
 * The bounds in the JSON stay in device pixels; `screenImage.scale` maps them.
 */
object ScreenImageBound {
    const val MAX_BYTES = 1_500_000
    const val MAX_SIDE = 2560

    /** The result without the image data, and the image, when [result] carries one inline. */
    fun split(result: JsonElement): Pair<JsonElement, ToolImage?> {
        val capture = result as? JsonObject ?: return result to null
        val image = capture["screenImage"] as? JsonObject ?: return result to null
        val data = (image["data"] as? JsonPrimitive)?.content ?: return result to null
        val png = Base64.getDecoder().decode(data)
        val (bytes, scale) = fit(png)
        val meta = JsonObject(image - "data" + listOfNotNull(scale?.let { "scale" to JsonPrimitive(it) }))
        var out = JsonObject(capture + ("screenImage" to meta))
        if (scale != null) {
            val statement = buildJsonObject { put("capability", "layout"); put("reason", "screen-scaled: the image was scaled by $scale to fit the tool result; multiply bounds by it") }
            out = JsonObject(out + ("degraded" to JsonArray((out["degraded"] as? JsonArray).orEmpty() + statement)))
        }
        return out to ToolImage(Base64.getEncoder().encodeToString(bytes), "image/png")
    }

    /** [png] unchanged when within the bounds, else scaled down; the factor when scaled. */
    private fun fit(png: ByteArray): Pair<ByteArray, Float?> {
        val source = ImageIO.read(ByteArrayInputStream(png)) ?: return png to null
        val longest = maxOf(source.width, source.height)
        if (png.size <= MAX_BYTES && longest <= MAX_SIDE) return png to null
        var scale = minOf(1f, MAX_SIDE.toFloat() / longest)
        while (true) {
            val w = maxOf(1, (source.width * scale).toInt())
            val h = maxOf(1, (source.height * scale).toInt())
            val target = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            target.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source, 0, 0, w, h, null)
                dispose()
            }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(target, "png", it) }.toByteArray()
            if (bytes.size <= MAX_BYTES || w <= 64) return bytes to w.toFloat() / source.width
            scale *= 0.75f
        }
    }
}
