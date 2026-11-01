package io.yamsergey.dta.daemon.ui

import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 015 (T022, research R11): windows that are not the app's, sampled by the host while someone reads. */
class HostWindowWatchTest {
    private val pkg = "io.yamsergey.dta.fixture.channel"
    private val withPrompt = javaClass.getResource("/windowdump/permission.txt")!!.readText()

    /** The same dump without the permission prompt's block. */
    private val withoutPrompt = withPrompt.lines().let { lines ->
        val start = lines.indexOfFirst { "GrantPermissionsActivity}:" in it }
        val end = (start + 1 until lines.size).first { lines[it].startsWith("  Window #") }
        (lines.take(start) + lines.drop(end)).joinToString("\n")
    }

    private var now = 1_000L
    private var dumps = ArrayDeque<String>()
    private var sampled = 0
    private val journal = io.yamsergey.dta.daemon.timeline.Timeline("s", clock = { now })
    private val watch = HostWindowWatch(pkg, journal, dumpsys = { sampled++; dumps.removeFirst() }, clock = { now })

    @Test
    fun `a permission prompt appears and goes, marked as not the app's`() = runBlocking {
        dumps += listOf(withoutPrompt, withPrompt, withoutPrompt)
        watch.touch()
        watch.sampleOnce()
        now = 2_000
        watch.sampleOnce()
        now = 4_500
        watch.sampleOnce()
        val events = journal.page(null, 10).events.map { it.ui!! }
        assertEquals(listOf(UiEventKind.WINDOW_APPEARED, UiEventKind.WINDOW_DISAPPEARED), events.map { it.kind })
        val prompt = events.first()
        assertEquals(UiSource.SCREEN_WINDOW, prompt.source)
        assertFalse(prompt.window!!.app)
        assertEquals(WindowKind.OTHER_APP, prompt.window!!.kind)
        assertEquals("com.google.android.permissioncontroller", prompt.window!!.owner)
        assertEquals("GrantPermissionsActivity", prompt.window!!.title)
        assertEquals(2_500, events[1].durationMs)
        assertEquals(prompt.id, events[1].appearedId)
    }

    @Test
    fun `the first sample is only the baseline`() = runBlocking {
        dumps += withPrompt
        watch.touch()
        watch.sampleOnce()
        assertTrue(journal.page(null, 10).events.isEmpty())
    }

    @Test
    fun `nobody reading means no polling, and the baseline starts again`() = runBlocking {
        dumps += listOf(withoutPrompt, withPrompt)
        watch.touch()
        watch.sampleOnce()
        now += HostWindowWatch.LEASE_MS + 1
        watch.sampleOnce()
        assertEquals(1, sampled)
        assertFalse(watch.leased())
        watch.touch()
        watch.sampleOnce()
        assertTrue(journal.page(null, 10).events.isEmpty(), "a new baseline, not an appearance")
    }
}
