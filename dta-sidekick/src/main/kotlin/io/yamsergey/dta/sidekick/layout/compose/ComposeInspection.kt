package io.yamsergey.dta.sidekick.layout.compose

import androidx.compose.ui.platform.isDebugInspectorInfoEnabled

/**
 * Enables Compose slot-table exposure for Tier-2 enrichment.
 *
 * Per `Wrapper.android.kt`, an AndroidComposeView only records its
 * CompositionData into the `inspection_slot_table_set` view tag when
 * `isDebugInspectorInfoEnabled` is true AT COMPOSITION TIME. So this MUST run
 * before any composition — which sidekick guarantees by calling it from its
 * AndroidX Startup initializer (a ContentProvider, before Application.onCreate).
 *
 * Imports Compose (compileOnly); loaded ONLY behind ComposePresence, so a
 * Views-only app never touches it (constitution I).
 */
object ComposeInspection {
    fun enableEarly() {
        isDebugInspectorInfoEnabled = true
    }
}
