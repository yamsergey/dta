package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.Episode
import io.yamsergey.dta.protocol.timeline.EpisodeLane
import io.yamsergey.dta.protocol.timeline.EpisodeLanes
import io.yamsergey.dta.protocol.timeline.EpisodeStep
import io.yamsergey.dta.protocol.timeline.EpisodesPage
import java.util.Locale

/** What an episode and a step say, for the plugin and the CLI alike (feature 045). */
object EpisodeText {
    /** `0:06.7` from the session's start. */
    fun clock(ms: Long, start: Long): String {
        val t = (ms - start).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d.%d", t / 60_000, t / 1_000 % 60, t / 100 % 10)
    }

    /** A step's time, finer: `6.70`; past 1000 s the clock. */
    fun seconds(ms: Long, start: Long): String {
        val t = (ms - start).coerceAtLeast(0)
        return if (t >= 1_000_000) clock(ms, start) else String.format(Locale.ROOT, "%.2f", t / 1_000.0)
    }

    fun meta(e: Episode): String = listOfNotNull(
        e.failures.takeIf { it > 0 }?.let { "$it failed" },
        e.requests.takeIf { it > 0 }?.let { if (it == 1) "1 request" else "$it requests" },
        "socket opened".takeIf { e.socketOpened },
        e.socketSent.takeIf { it > 0 }?.let { if (it == 1) "socket message sent" else "$it socket messages sent" },
        "screen changed".takeIf { e.screenChanged },
        "events may be missing".takeIf { e.gaps > 0 },
    ).joinToString(" · ")

    /** Received socket messages: `tick ×3`, `tick ×2, quote ×1, +2`. */
    fun stream(e: Episode): String {
        val shown = e.socketCounts.take(2).joinToString(", ") { "${it.name} ×${it.count}" }
        val rest = e.socketCounts.size - 2
        return if (rest > 0) "$shown, +$rest" else shown
    }

    /** `⤷` says it has a recorded cause in this episode. */
    fun label(s: EpisodeStep): String = if (s.linked) "⤷ " + s.label.removePrefix("→ ") else s.label

    fun lines(page: EpisodesPage): List<String> {
        if (page.episodes.isEmpty()) return listOf("No episodes yet.")
        val lanes = page.lanes.associate { it.id to it.title }
        val head = listOfNotNull(
            "${page.total} episodes · ${page.socketMessages} socket messages".takeIf { page.total > 0 },
            page.total.takeIf { it > page.episodes.size }?.let { "showing the newest ${page.episodes.size}" },
            page.socketsNote,
        )
        return head + page.episodes.flatMap { e ->
            val line = listOf(clock(e.wallMs, page.startWallMs), e.title, meta(e), stream(e)).filter { it.isNotEmpty() }
            val first = line[0] + "  " + line.drop(1).joinToString("  ·  ")
            listOf(first) + e.steps.map { s ->
                val where = lanes[s.from].orEmpty().ifEmpty { s.from } + (s.to?.let { " → " + (lanes[it] ?: it) } ?: "")
                "    ${seconds(s.wallMs, page.startWallMs)}  $where  ${label(s)}" + if (s.failed) "  (failed)" else ""
            }
        }
    }
}

sealed interface EpisodeRow {
    data class Head(val episode: Episode, val time: String, val meta: String, val stream: String, val open: Boolean) : EpisodeRow
    data class Step(val episodeId: String, val step: EpisodeStep, val time: String, val label: String) : EpisodeRow
}

/** The Episodes view (feature 045): the newest episode open, the rest closed, until the user says otherwise. */
data class EpisodesView(
    val page: EpisodesPage? = null,
    val toggled: Map<String, Boolean> = emptyMap(),
) {
    val lanes: List<EpisodeLane> get() = page?.lanes.orEmpty()

    fun isOpen(id: String): Boolean = toggled[id] ?: (page?.episodes?.lastOrNull()?.id == id)

    fun toggle(id: String) = copy(toggled = toggled + (id to !isOpen(id)))

    val summary: String
        get() = page?.takeIf { it.total > 0 }?.let { "${it.total} episodes · ${it.socketMessages} socket messages" }.orEmpty()

    val rows: List<EpisodeRow>
        get() {
            val p = page ?: return emptyList()
            return p.episodes.flatMap { e ->
                val open = isOpen(e.id)
                val head = EpisodeRow.Head(e, EpisodeText.clock(e.wallMs, p.startWallMs), EpisodeText.meta(e), EpisodeText.stream(e), open)
                if (!open) listOf(head) else listOf(head) + e.steps.map { EpisodeRow.Step(e.id, it, EpisodeText.seconds(it.wallMs, p.startWallMs), EpisodeText.label(it)) }
            }
        }

    /** Each lane's place across the diagram, 0..1 of the width after the time column. */
    fun positions(): Map<String, Double> {
        val ids = lanes.map { it.id }
        val out = LinkedHashMap<String, Double>()
        if (EpisodeLanes.USER in ids) out[EpisodeLanes.USER] = 0.06
        if (EpisodeLanes.APP in ids) out[EpisodeLanes.APP] = 0.32
        val rest = ids.filter { it != EpisodeLanes.USER && it != EpisodeLanes.APP }
        when (rest.size) {
            0 -> Unit
            1 -> out[rest[0]] = 0.70
            else -> {
                val from = 0.70 - (rest.size - 2) * 0.06
                rest.forEachIndexed { i, id -> out[id] = from + (0.94 - from) * i / (rest.size - 1) }
            }
        }
        return out
    }
}
