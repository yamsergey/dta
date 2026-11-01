package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.Rect
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Feature 014 (research R3): compares the images taken before and after the tree, inside the
 * app's windows only — the status bar clock and other apps do not make the tree stale.
 */
object ScreenDiff {
    sealed interface Result {
        data object Same : Result
        /** Pixels changed inside the app's windows; [box] bounds them, in image pixels. */
        data class Changed(val box: Rect) : Result
        data class Resized(val widthA: Int, val heightA: Int, val widthB: Int, val heightB: Int) : Result
    }

    fun compare(a: ByteArray, b: ByteArray, appWindows: List<Rect>): Result {
        val imageA = ImageIO.read(ByteArrayInputStream(a)) ?: error("image A is not a PNG")
        val imageB = ImageIO.read(ByteArrayInputStream(b)) ?: error("image B is not a PNG")
        if (imageA.width != imageB.width || imageA.height != imageB.height) {
            return Result.Resized(imageA.width, imageA.height, imageB.width, imageB.height)
        }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (window in appWindows) {
            val l = window.left.coerceIn(0, imageA.width)
            val t = window.top.coerceIn(0, imageA.height)
            val r = window.right.coerceIn(0, imageA.width)
            val btm = window.bottom.coerceIn(0, imageA.height)
            if (l >= r || t >= btm) continue
            val rowA = IntArray(r - l)
            val rowB = IntArray(r - l)
            for (y in t until btm) {
                imageA.getRGB(l, y, r - l, 1, rowA, 0, r - l)
                imageB.getRGB(l, y, r - l, 1, rowB, 0, r - l)
                for (i in rowA.indices) {
                    if (rowA[i] != rowB[i]) {
                        val x = l + i
                        if (x < left) left = x
                        if (x + 1 > right) right = x + 1
                        if (y < top) top = y
                        if (y + 1 > bottom) bottom = y + 1
                    }
                }
            }
        }
        return if (right == Int.MIN_VALUE) Result.Same else Result.Changed(Rect(left, top, right, bottom))
    }

    /** Width and height from the PNG header, or null when [png] is not a PNG. */
    fun size(png: ByteArray): Pair<Int, Int>? {
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        if (png.size < 24 || !png.copyOfRange(0, 4).contentEquals(signature)) return null
        fun int(at: Int) = (png[at].toInt() and 0xff shl 24) or (png[at + 1].toInt() and 0xff shl 16) or
            (png[at + 2].toInt() and 0xff shl 8) or (png[at + 3].toInt() and 0xff)
        return int(16) to int(20)
    }
}
