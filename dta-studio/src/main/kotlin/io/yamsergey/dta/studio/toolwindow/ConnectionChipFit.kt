package io.yamsergey.dta.studio.toolwindow

/**
 * D-108: the header's connection chip ("● connected") is whole or only its dot. The combos give way first
 * (down to [combosMin]); when the chip's whole text does not fit beside them, the dot stays alone and the
 * tooltip carries the state in full, so text is never cut mid-word.
 */
object ConnectionChipFit {
    data class Shown(val text: String, val tooltip: String?)

    fun of(text: String, reason: String?, fullWidth: Int, dotWidth: Int, others: Int, combosMin: Int, available: Int): Shown {
        if (text.isEmpty()) return Shown("", null)
        val tooltip = if (reason.isNullOrBlank()) text else "$text: $reason"
        val fits = available - others - fullWidth >= combosMin
        return Shown(if (fits) text else text.substringBefore(' '), tooltip)
    }
}
