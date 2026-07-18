package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.DegradationCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Root-provider selection is deviceless via an injectable API level, so the
 * API<29 reflection path (impossible to hit on a modern emulator) is still
 * covered — its degradation code included (fixes analyze C2).
 */
class RootProviderTest {

    @Test
    fun `api 29+ selects the window-inspector provider with no degradation`() {
        val selection = RootProviderSelector.select(apiLevel = 29)
        assertEquals(RootProviderKind.WINDOW_INSPECTOR, selection.kind)
        assertNull(selection.degradation)

        val selection34 = RootProviderSelector.select(apiLevel = 34)
        assertEquals(RootProviderKind.WINDOW_INSPECTOR, selection34.kind)
    }

    @Test
    fun `below 29 selects reflection provider and attaches legacy-root-discovery`() {
        val selection = RootProviderSelector.select(apiLevel = 24)
        assertEquals(RootProviderKind.REFLECTION, selection.kind)
        assertTrue(selection.degradation != null)
        assertEquals(DegradationCodes.LEGACY_ROOT_DISCOVERY, selection.degradation!!.reason)
    }
}
