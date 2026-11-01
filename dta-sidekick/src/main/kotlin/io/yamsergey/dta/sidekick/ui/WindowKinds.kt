package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.WindowKind

/** A window's kind and title from what the framework hands over at addView (research R3). */
object WindowKinds {
    private const val FIRST_SUB_WINDOW = 1000
    private const val LAST_SUB_WINDOW = 1999
    private const val LAST_APPLICATION_WINDOW = 99
    private const val TYPE_APPLICATION = 2
    private const val TYPE_TOAST = 2005

    /** [owner] is the window callback's class and its superclasses, most specific first. */
    fun kind(type: Int, owner: List<String>): WindowKind = when {
        type == TYPE_TOAST -> WindowKind.TOAST
        owner.any { it.substringAfterLast('.').contains("BottomSheet") } -> WindowKind.BOTTOM_SHEET
        "android.app.Dialog" in owner -> WindowKind.DIALOG
        type in FIRST_SUB_WINDOW..LAST_SUB_WINDOW -> WindowKind.POPUP
        type == TYPE_APPLICATION -> WindowKind.DIALOG
        type in 1..LAST_APPLICATION_WINDOW -> WindowKind.ACTIVITY
        else -> WindowKind.OTHER
    }

    /** Activity windows by their activity; dialogs, sheets and popups by their heading or first text. */
    fun title(kind: WindowKind, owner: String?, heading: String?, firstText: String?): String? = when (kind) {
        WindowKind.ACTIVITY -> owner?.substringAfterLast('.')
        WindowKind.DIALOG, WindowKind.BOTTOM_SHEET, WindowKind.POPUP, WindowKind.TOAST -> heading ?: firstText
        else -> heading
    }
}
