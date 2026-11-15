package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.network.SavedFiltersApplied

/**
 * Feature 065: what the Timeline tab shows of Network's saved filters, the same in Sequence and
 * List: the chips (one set with Network's; × removes a filter there too), the line under the view
 * that says what was hidden (never silent, constitution III), its action, and a banner when the
 * filters could not be applied. Pure; the plugin draws it.
 */
data class TimelineSavedBar(
    val chips: List<String>,
    val label: String,
    /** Shown in this view: the chips are drawn as off, the filters stay saved. */
    val off: Boolean,
    val caption: String?,
    val action: Action?,
    val banner: String?,
) {
    enum class Action(val label: String) { SHOW("Show them"), HIDE("Hide them again") }

    val visible: Boolean get() = chips.isNotEmpty() || banner != null

    companion object {
        val NONE = TimelineSavedBar(emptyList(), LABEL, off = false, caption = null, action = null, banner = null)
        private const val LABEL = "Network's filters:"

        /**
         * [saved] is the answer's `saved`; [showing] the view asked for everything. The List counts
         * its own rows ([hiddenRows], [notJudgedRows]) where the answer counts entries.
         */
        fun of(saved: SavedFiltersApplied?, showing: Boolean, hiddenRows: Int? = null, notJudgedRows: Int? = null): TimelineSavedBar {
            saved ?: return NONE
            saved.problem?.let {
                return NONE.copy(chips = saved.words, banner = "Network's saved filters could not be read ($it). Nothing is hidden.")
            }
            saved.unavailable?.let {
                return NONE.copy(chips = saved.words, banner = "Network's saved filters are not applied: $it. Nothing is hidden.")
            }
            if (saved.ignored || showing) {
                return TimelineSavedBar(saved.words, "Network's filters, off here:", off = true, caption = "Network's filters are off in this view", action = Action.HIDE, banner = null)
            }
            val hidden = hiddenRows ?: saved.hidden ?: 0
            val notJudged = notJudgedRows ?: saved.notJudged ?: 0
            val unjudged = when (notJudged) {
                0 -> null
                1 -> "1 not judged: its request is no longer kept"
                else -> "$notJudged not judged: their requests are no longer kept"
            }
            val caption = when {
                hidden > 0 -> listOfNotNull("$hidden network row${if (hidden == 1) "" else "s"} hidden by Network's filters", unjudged).joinToString(" · ")
                unjudged != null -> "Network's filters hid nothing here · $unjudged"
                else -> null
            }
            return TimelineSavedBar(saved.words, LABEL, off = false, caption = caption, action = Action.SHOW.takeIf { hidden > 0 }, banner = null)
        }
    }
}
