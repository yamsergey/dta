package io.yamsergey.dta.sidekick.timeline

import io.yamsergey.dta.protocol.timeline.TimelineSwitch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Feature 056: the app keeps whether it records the timeline; off by default. */
class TimelineSwitchTest {
    private val dir = Files.createTempDirectory("dta-switch").toFile()

    @Test
    fun `off by default, kept across instances (restarts)`() {
        assertFalse(TimelineSwitchStore(dir).on)
        TimelineSwitchStore(dir).set(true)
        assertTrue(TimelineSwitchStore(dir).on)
        TimelineSwitchStore(dir).set(false)
        assertFalse(TimelineSwitchStore(dir).on)
    }

    @Test
    fun `the method reads, turns on (starting now) and turns off (from the next start)`() {
        var running = false
        val methods = TimelineSwitchMethods(TimelineSwitchStore(dir), running = { running }, start = { running = true })
        fun call(on: Boolean?) = methods.answer(on)

        assertEquals(TimelineSwitch(on = false, recording = false), call(null))
        val on = call(true)
        assertTrue(on.on && on.recording && running)
        assertNotNull(on.note)
        val off = call(false)
        assertFalse(off.on)
        assertTrue(off.recording, "hooks in place keep running until the app restarts")
        assertTrue(off.note!!.contains("next start"), off.note)
        assertFalse(TimelineSwitchStore(dir).on)
    }

    @Test
    fun `while off the timeline states it, with how to turn it on`() {
        val status = TimelineSwitchMethods.offStatus()
        assertEquals("timeline", status.name)
        assertTrue(status.reason!!.contains("Settings › Tools › DTA"))
    }
}
