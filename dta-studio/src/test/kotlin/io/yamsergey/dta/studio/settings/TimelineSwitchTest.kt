package io.yamsergey.dta.studio.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.studio.inject.InjectionConfigurable
import io.yamsergey.dta.studio.inject.InjectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * D-035: turning the Timeline on in Settings › DTA must not wait for the daemon on the EDT. Apply only
 * flips the switch; the connected app is told later, off the EDT, however slowly the daemon answers.
 */
class TimelineSwitchTest : BasePlatformTestCase() {
    fun `test apply returns at once and a slow daemon gets the switch later, off the EDT`() {
        val settings = InjectionSettings.get()
        val before = settings.timeline
        val scope = CoroutineScope(SupervisorJob())
        // What the daemon was told, and whether it was told on the EDT.
        val sent = LinkedBlockingQueue<Pair<Boolean, Boolean>>()
        try {
            settings.timeline = false
            val state = MutableStateFlow(ContextState(device = "emulator-5554", app = "io.example.app", connection = Connection.CONNECTED))
            scope.forwardTimelineSwitch(state, settings.timelineFlow) { _, _, on ->
                val edt = ApplicationManager.getApplication().isDispatchThread
                Thread.sleep(SLOW_DAEMON_MS) // a daemon that answers slowly, blocking the calling thread
                sent.add(on to edt)
            }
            assertEquals("the app is told the switch as it is", false to false, sent.poll(5, TimeUnit.SECONDS))

            val page = InjectionConfigurable()
            val box = checkBoxes(page.createComponent()!!).single { it.text.startsWith("Timeline") }
            box.isSelected = true
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            val started = System.nanoTime()
            page.apply()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("apply took $tookMs ms on the EDT", tookMs < 200)
            assertTrue(settings.timeline)
            assertNull("the daemon answered before apply returned: apply waited for it", sent.poll())

            assertEquals("the app is told on, off the EDT", true to false, sent.poll(SLOW_DAEMON_MS + 5_000, TimeUnit.MILLISECONDS))
            page.disposeUIResources()
        } finally {
            scope.cancel()
            settings.timeline = before
        }
    }

    fun `test nothing is sent while no app is connected`() {
        val scope = CoroutineScope(SupervisorJob())
        val sent = LinkedBlockingQueue<Boolean>()
        try {
            val state = MutableStateFlow(ContextState(device = "emulator-5554", app = "io.example.app", connection = Connection.REFUSED))
            val switch = MutableStateFlow(false)
            scope.forwardTimelineSwitch(state, switch) { _, _, on -> sent.add(on) }
            switch.value = true
            assertNull(sent.poll(300, TimeUnit.MILLISECONDS))
            state.value = state.value.copy(connection = Connection.CONNECTED)
            assertEquals(true, sent.poll(5, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
        }
    }

    private fun checkBoxes(c: java.awt.Component): List<javax.swing.JCheckBox> =
        (if (c is javax.swing.JCheckBox) listOf(c) else emptyList()) + ((c as? java.awt.Container)?.components?.flatMap(::checkBoxes) ?: emptyList())

    private companion object {
        const val SLOW_DAEMON_MS = 2_000L
    }
}
