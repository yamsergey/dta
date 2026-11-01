package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.WindowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 015 (T007, research R3): the kind of a window from its type and owner. */
class WindowKindsTest {
    private val activity = listOf("com.example.MainActivity", "androidx.activity.ComponentActivity", "android.app.Activity")
    private val dialog = listOf("android.app.AlertDialog", "android.app.Dialog")

    @Test
    fun kinds() {
        assertEquals(WindowKind.ACTIVITY, WindowKinds.kind(1, activity))
        assertEquals(WindowKind.DIALOG, WindowKinds.kind(2, dialog))
        assertEquals(WindowKind.DIALOG, WindowKinds.kind(2, listOf("androidx.compose.ui.window.DialogWrapper", "androidx.activity.ComponentDialog", "android.app.Dialog")))
        assertEquals(WindowKind.BOTTOM_SHEET, WindowKinds.kind(2, listOf("com.google.android.material.bottomsheet.BottomSheetDialog", "android.app.Dialog")))
        assertEquals(WindowKind.BOTTOM_SHEET, WindowKinds.kind(2, listOf("androidx.compose.material3.ModalBottomSheetDialogWrapper", "android.app.Dialog")))
        assertEquals(WindowKind.POPUP, WindowKinds.kind(1000, emptyList()))
        assertEquals(WindowKind.POPUP, WindowKinds.kind(1002, emptyList()))
        assertEquals(WindowKind.TOAST, WindowKinds.kind(2005, emptyList()))
        assertEquals(WindowKind.OTHER, WindowKinds.kind(2038, emptyList()))
        assertEquals(WindowKind.ACTIVITY, WindowKinds.kind(1, emptyList()))
    }

    @Test
    fun titles() {
        assertEquals("MainActivity", WindowKinds.title(WindowKind.ACTIVITY, owner = "com.example.MainActivity", heading = "Hello", firstText = "Hello"))
        assertEquals("Delete item?", WindowKinds.title(WindowKind.DIALOG, owner = "com.example.MainActivity", heading = "Delete item?", firstText = "x"))
        assertEquals("Are you sure?", WindowKinds.title(WindowKind.DIALOG, owner = null, heading = null, firstText = "Are you sure?"))
        assertEquals("Copy", WindowKinds.title(WindowKind.POPUP, owner = null, heading = null, firstText = "Copy"))
        assertNull(WindowKinds.title(WindowKind.OTHER, owner = null, heading = null, firstText = null))
    }
}
