package io.yamsergey.dta.daemon.layout

/** Feature 014: take the screen with the tree; [output] writes the PNG to that file instead of inline. */
data class ScreenRequest(val output: String? = null)
