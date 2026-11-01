package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.Parameter

/**
 * How a composable parameter's value is written (feature 018, research R4): what the value is,
 * or that it has no readable form — never an identity hash, never a packed number that looks
 * like a real one. Pure: JVM objects only.
 */
object ParameterValues {
    const val COLOR = "androidx.compose.ui.graphics.Color"
    const val DP = "androidx.compose.ui.unit.Dp"
    const val TEXT_UNIT = "androidx.compose.ui.unit.TextUnit"
    const val MAX_VALUE = 160

    private val IDENTITY = Regex("^[\\w.$]+@[0-9a-f]+$")
    private const val UNSPECIFIED_COLOR = 16L

    /** The parameter as shown, or null for a `null` value (left out). */
    fun render(name: String, value: Any?, inlineClass: String?, fromDefault: Boolean): Parameter? {
        value ?: return null
        val (text, type) = when {
            inlineClass != null -> inline(value, inlineClass)
            isLambda(value) -> "<lambda>" to "lambda"
            else -> {
                val simple = simpleName(value)
                val s = runCatching { value.toString() }.getOrElse { "<$simple>" }
                (if (IDENTITY.matches(s)) "<$simple>" else s) to simple
            }
        }
        val cut = text.length > MAX_VALUE
        return Parameter(name, if (cut) text.take(MAX_VALUE - 1) + "…" else text, type, cut = cut, default = fromDefault)
    }

    private fun inline(value: Any, inlineClass: String): Pair<String, String> {
        val simple = inlineClass.substringAfterLast('.')
        val raw = value as? Number ?: return value.toString() to "$simple (packed, not decoded)"
        return when (inlineClass) {
            COLOR -> color(raw.toLong())
            DP -> raw.toFloat().let { if (it.isNaN()) "Unspecified" else "$it.dp" } to "Dp"
            TEXT_UNIT -> textUnit(raw.toLong())
            else -> raw.toString() to "$simple (packed, not decoded)"
        }
    }

    /** A packed Color: sRGB keeps ARGB in the high 32 bits when the low 6 bits (the color space) are 0. */
    private fun color(v: Long): Pair<String, String> = when {
        v == UNSPECIFIED_COLOR -> "Unspecified" to "Color"
        v and 0x3F == 0L -> "#%08X".format((v ushr 32).toInt()) to "Color"
        else -> v.toULong().toString() to "Color (packed, not decoded)"
    }

    /** A packed TextUnit: the unit type in bits 32–39, the float value in the low 32 bits. */
    private fun textUnit(v: Long): Pair<String, String> {
        val value = Float.fromBits((v and 0xFFFFFFFFL).toInt())
        return when (v and 0xFF_0000_0000L) {
            0x1_0000_0000L -> "$value.sp" to "TextUnit"
            0x2_0000_0000L -> "$value.em" to "TextUnit"
            0L -> "Unspecified" to "TextUnit"
            else -> v.toString() to "TextUnit (packed, not decoded)"
        }
    }

    private fun isLambda(value: Any): Boolean =
        value is Function<*> || value.javaClass.name.endsWith("ComposableLambdaImpl") || value.javaClass.name.contains("\$\$Lambda")

    private fun simpleName(value: Any): String =
        value.javaClass.simpleName.ifEmpty { value.javaClass.name.substringAfterLast('.') }
}
