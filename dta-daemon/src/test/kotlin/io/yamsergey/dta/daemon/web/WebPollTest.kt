package io.yamsergey.dta.daemon.web

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-059: how often a switched-on capture looks for its socket over adb. Often right after the app
 * starts and while it has just gone (its next start is caught fast); seldom once it has been gone a
 * while, so a switch left on for an app nobody runs does not load adbd for hours.
 */
class WebPollTest {
    private val now = 1_000_000L

    @Test
    fun `a new process is looked at often, a running one every 2 s`() {
        assertEquals(300, WebPoll.intervalMs(WebPoll.Source.WEBVIEW, now, fastUntil = now + 1, notRunningSince = null))
        assertEquals(2_000, WebPoll.intervalMs(WebPoll.Source.WEBVIEW, now, fastUntil = now - 1, notRunningSince = null))
        assertEquals(2_000, WebPoll.intervalMs(WebPoll.Source.CUSTOM_TAB, now, fastUntil = now + 1, notRunningSince = null))
    }

    @Test
    fun `an app that just went is looked for every half second, one gone a while every 10 s`() {
        assertEquals(500, WebPoll.intervalMs(WebPoll.Source.WEBVIEW, now, 0, notRunningSince = now - 5_000))
        assertEquals(10_000, WebPoll.intervalMs(WebPoll.Source.WEBVIEW, now, 0, notRunningSince = now - 31_000))
        assertEquals(2_000, WebPoll.intervalMs(WebPoll.Source.CUSTOM_TAB, now, 0, notRunningSince = now - 5_000))
        assertEquals(10_000, WebPoll.intervalMs(WebPoll.Source.CUSTOM_TAB, now, 0, notRunningSince = now - 31_000))
    }
}
