package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.ScreenImageState
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 014 (T006, contract "Daemon order"): the screen around the tree, every branch stated. */
class ScreenCaptureTest {
    private val pkg = "io.yamsergey.dta.fixture.channel"
    private fun res(name: String) = javaClass.getResource("/windowdump/$name.txt")!!.readText()

    private fun png(w: Int = 1080, h: Int = 2400, spot: Rect? = null): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.WHITE; fillRect(0, 0, w, h)
            spot?.let { color = Color.RED; fillRect(it.left, it.top, it.width, it.height) }
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private val tree = LayoutCapture(
        screen = Screen(1080, 2400, 2.625f),
        source = CaptureSource.SIDEKICK,
        windows = listOf(Window(type = WindowType.APPLICATION, zOrder = 0, root = ViewNode(id = 1, bounds = Rect(0, 0, 1080, 2400), className = "DecorView"))),
    )

    /** A scripted device: each call takes the next answer; [log] records the order. */
    private inner class Device(
        windows: List<String> = listOf("portrait", "portrait"),
        private val policy: String = "policy-on",
        images: List<() -> ByteArray> = listOf({ png() }, { png() }),
    ) {
        val log = mutableListOf<String>()
        private val windows = windows.toMutableList()
        private val images = images.toMutableList()
        val capture = ScreenCapture(
            dumpsys = { _, args ->
                log += "dumpsys ${args.joinToString(" ")}"
                if (args == listOf("window", "policy")) res(policy) else res(this.windows.removeAt(0))
            },
            screencap = { _ -> log += "screencap"; this.images.removeAt(0)() },
            clock = { 1_000L },
        )

        fun run(output: String? = null, base: LayoutCapture = tree) = runBlocking {
            capture.capture("emulator-5554", pkg, output) { log += "tree"; base }
        }
    }

    private fun LayoutCapture.codes() = degraded.orEmpty().map { it.reason.substringBefore(':') }

    @Test
    fun `the order is windows, image A, tree, image B, windows - and image B is returned`() {
        val b = png(spot = Rect(0, 0, 1, 1))
        val d = Device(images = listOf({ png() }, { b }))
        val c = d.run()
        assertEquals(listOf("dumpsys window windows", "dumpsys window policy", "screencap", "tree", "screencap", "dumpsys window windows"), d.log)
        val image = assertNotNull(c.screenImage)
        assertEquals(ScreenImageState.PRESENT, image.state)
        assertEquals(1080 to 2400, image.widthPx to image.heightPx)
        assertEquals(b.toList(), Base64.getDecoder().decode(image.data).toList())
        assertEquals(AlignmentKind.ALIGNED, image.alignment?.kind)
        assertEquals(1_000L, image.takenAtMs)
        assertEquals(listOf("Taskbar", "StatusBar", "$pkg/$pkg.MainActivity"), c.screenWindows!!.map { it.name })
        assertEquals(0, c.screenWindows!!.last().treeWindow)
        assertEquals(tree.windows, c.windows)
    }

    @Test
    fun `a still screen has no statements`() {
        assertEquals(emptyList(), Device().run().codes())
    }

    @Test
    fun `a window that appeared during the capture is named`() {
        val c = Device(windows = listOf("portrait", "toast")).run()
        val changed = c.degraded!!.single { it.reason.startsWith(DegradationCodes.SCREEN_CHANGED_DURING_CAPTURE) }
        assertTrue("Toast appeared" in changed.reason, changed.reason)
    }

    @Test
    fun `pixels that changed in the app's window give the box`() {
        val c = Device(images = listOf({ png() }, { png(spot = Rect(200, 300, 400, 500)) })).run()
        val changed = c.degraded!!.single { it.reason.startsWith(DegradationCodes.SCREEN_CHANGED_DURING_CAPTURE) }
        assertTrue("[200,300][400,500]" in changed.reason, changed.reason)
    }

    @Test
    fun `a secure window blocks the image - nothing is taken, the tree is returned`() {
        val d = Device(windows = listOf("secure", "secure"), images = emptyList())
        val c = d.run()
        assertEquals(ScreenImageState.BLOCKED, c.screenImage!!.state)
        assertNull(c.screenImage!!.data)
        assertTrue("screencap" !in d.log)
        assertTrue(DegradationCodes.SCREEN_BLOCKED in c.codes())
        assertEquals(tree.windows, c.windows)
    }

    @Test
    fun `the screen off - not taken, stated, no screencap`() {
        val d = Device(policy = "policy-off", images = emptyList())
        val c = d.run()
        assertEquals(ScreenImageState.NOT_TAKEN, c.screenImage!!.state)
        assertTrue(DegradationCodes.SCREEN_OFF in c.codes())
        assertTrue("screencap" !in d.log)
    }

    @Test
    fun `the lock screen showing is stated, the image is still taken`() {
        val c = Device(policy = "policy-locked").run()
        assertEquals(ScreenImageState.PRESENT, c.screenImage!!.state)
        assertTrue(DegradationCodes.SCREEN_LOCKED in c.codes())
    }

    @Test
    fun `a failed screencap keeps the tree and says why`() {
        val c = Device(images = listOf({ png() }, { error("device offline") })).run()
        assertEquals(ScreenImageState.NOT_TAKEN, c.screenImage!!.state)
        assertTrue("device offline" in c.screenImage!!.reason!!)
        assertTrue(DegradationCodes.SCREEN_NOT_TAKEN in c.codes())
        assertEquals(tree.windows, c.windows)
    }

    @Test
    fun `bounds that do not match the windows on screen are stated`() {
        val odd = tree.copy(windows = listOf(tree.windows.single().copy(root = ViewNode(id = 1, bounds = Rect(0, 0, 700, 300), className = "D"))))
        val c = Device().run(base = odd)
        assertEquals(AlignmentKind.NOT_ALIGNED, c.screenImage!!.alignment!!.kind)
        assertTrue(DegradationCodes.SCREEN_NOT_ALIGNED in c.codes())
    }

    @Test
    fun `file mode writes the png and gives path, size and sha256 instead of data`() {
        val file = File(Files.createTempDirectory("screen").toFile(), "s.png")
        val c = Device().run(output = file.path)
        val image = c.screenImage!!
        assertNull(image.data)
        assertEquals(file.path, image.file!!.path)
        assertEquals(file.length(), image.file!!.sizeBytes)
        assertEquals(64, image.file!!.sha256.length)
        assertEquals(1080 to 2400, ScreenDiff.size(file.readBytes()))
    }
}
