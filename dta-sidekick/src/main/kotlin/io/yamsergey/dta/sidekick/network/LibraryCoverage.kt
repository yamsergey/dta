package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.LibraryStatus
import io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status
import io.yamsergey.dta.sidekick.hooks.HookNames
import io.yamsergey.dta.sidekick.hooks.Hooks.Install

/**
 * A library's status from its per-event hook results (feature 019): available when every event
 * is captured, degraded when some are, unavailable when none are. The reason says what is not
 * captured, why, what is, and what to do; raw runtime causes go to `detail` only. Pure.
 */
object LibraryCoverage {

    const val ABSENT = "the app does not include this library"
    const val ALL_TRAFFIC = "all of its traffic"

    fun of(state: LibraryState): LibraryStatus {
        if (!state.present) return LibraryStatus(state.name, Status.UNAVAILABLE, ABSENT)
        state.installFailure?.let {
            return LibraryStatus(state.name, Status.UNAVAILABLE, "nothing is captured: $it", notCaptured = listOf(ALL_TRAFFIC), detail = state.installFailureDetail)
        }
        state.unsupported?.let { return LibraryStatus(state.name, Status.UNAVAILABLE, it) }

        val missing = state.events.filterNot { it.captured }
        if (missing.isEmpty()) return LibraryStatus(state.name, Status.AVAILABLE)
        val captured = state.events.filter { it.captured }
        val detail = missing.flatMap { e -> e.tried.mapNotNull { (_, r) -> (r as? Install.Failed)?.detail } }
            .distinct().joinToString("; ").ifEmpty { null }
        val why = missing.groupBy { why(it) }.entries.joinToString("; ") { (text, events) -> "${list(events.map { it.event })}: $text" }
        val action = if (missing.any { e -> e.tried.all { it.second is Install.MethodAbsent } }) {
            " The app's version of this library is not one DTA knows; update DTA."
        } else {
            ""
        }
        return if (captured.isEmpty()) {
            LibraryStatus(state.name, Status.UNAVAILABLE, "nothing is captured: $why.$action", notCaptured = missing.map { it.event }, detail = detail)
        } else {
            LibraryStatus(
                state.name, Status.DEGRADED,
                "${list(missing.map { it.event })} ${if (missing.size == 1) "is" else "are"} not captured ($why). " +
                    "${list(captured.map { it.event })} ${if (captured.size == 1) "is" else "are"} captured.$action",
                notCaptured = missing.map { it.event },
                captured = captured.map { it.event },
                detail = detail,
            )
        }
    }

    /** Why one event did not install, from the forms tried. */
    private fun why(event: EventResult): String {
        val results = event.tried.map { it.second }
        results.firstOrNull { it is Install.Failed }?.let { return it.describe() }
        val absent = results.filterIsInstance<Install.MethodAbsent>()
        if (absent.size == results.size && absent.size > 1) {
            return "no known form is in the app (" + absent.joinToString(", ") { HookNames.method(it.className, it.method, it.descriptor) } + ")"
        }
        return results.joinToString("; ") { it.describe() }.ifEmpty { "no hook was tried" }
    }

    private fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
