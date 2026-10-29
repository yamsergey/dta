package io.yamsergey.dta.present.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One entry of the list: its id, start time and the entry JSON as the daemon sent it. */
data class Row(val id: String, val timeMs: Long, val entry: JsonObject) {
    companion object {
        fun of(entry: JsonObject) = Row(
            (entry["id"] as JsonPrimitive).content,
            (entry["timeMs"] as? JsonPrimitive)?.longOrNull ?: 0,
            entry,
        )
    }
}

/** What changes the live list (data-model LiveListState). */
sealed interface LiveEvent {
    /** A `network.list` result: the newest page for the current filters. */
    data class Loaded(val page: JsonObject) : LiveEvent
    /** An older page (`before` cursor), shown above. */
    data class Older(val page: JsonObject) : LiveEvent
    data class Arrived(val entry: JsonObject) : LiveEvent
    data class Updated(val entry: JsonObject) : LiveEvent
    data class Selected(val id: String?) : LiveEvent
    data object ScrolledAway : LiveEvent
    data object JumpToEnd : LiveEvent
    data object Pause : LiveEvent
    data object Resume : LiveEvent
    data object Cleared : LiveEvent
}

/**
 * The live list (patterns.md): oldest to newest, new rows at the end. It follows the end until
 * the person scrolls away or selects a row; then rows still arrive but the view stays put and
 * counts them as new. Paused, nothing is applied (capture goes on) until resumed. Updates replace
 * a row in place. The selection survives updates and re-queries, or is marked gone.
 */
data class LiveList(
    val rows: List<Row> = emptyList(),
    val following: Boolean = true,
    val paused: Boolean = false,
    val newCount: Int = 0,
    val selectedId: String? = null,
    val selectedGone: Boolean = false,
    val total: Int = 0,
    /** Cursor of the next older page; null when everything matching is loaded. */
    val older: String? = null,
    private val pending: List<LiveEvent> = emptyList(),
    private val index: Map<String, Int> = emptyMap(),
) {
    val shown: Int get() = rows.size

    /** Events waiting while paused. */
    val backlog: Int get() = pending.size

    val selected: Row? get() = selectedId?.let { id -> index[id]?.let { rows[it] } }

    fun reduce(event: LiveEvent): LiveList = when (event) {
        is LiveEvent.Loaded -> {
            val loaded = entries(event.page).sortedWith(ORDER)
            copy(
                rows = loaded, index = indexOf(loaded), total = total(event.page, loaded.size), older = cursor(event.page),
                newCount = 0, pending = emptyList(), selectedGone = selectedId != null && loaded.none { it.id == selectedId },
            )
        }
        is LiveEvent.Older -> {
            val known = index.keys
            val merged = (entries(event.page).filter { it.id !in known } + rows).sortedWith(ORDER)
            copy(rows = merged, index = indexOf(merged), older = cursor(event.page))
        }
        is LiveEvent.Arrived, is LiveEvent.Updated -> if (paused) copy(pending = pending + event) else apply(event)
        is LiveEvent.Selected -> copy(selectedId = event.id, selectedGone = false, following = if (event.id != null) false else following)
        LiveEvent.ScrolledAway -> copy(following = false)
        LiveEvent.JumpToEnd -> copy(following = true, newCount = 0)
        LiveEvent.Pause -> copy(paused = true)
        LiveEvent.Resume -> pending.fold(copy(paused = false, pending = emptyList())) { list, e -> list.apply(e) }
        LiveEvent.Cleared -> LiveList()
    }

    private fun apply(event: LiveEvent): LiveList {
        val entry = when (event) {
            is LiveEvent.Arrived -> event.entry
            is LiveEvent.Updated -> event.entry
            else -> return this
        }
        val row = Row.of(entry)
        val at = index[row.id]
        if (at != null) {
            // Known row (an update, or a page/event overlap): replace in place, never move.
            val updated = rows.toMutableList().also { it[at] = row }
            return copy(rows = updated, selectedGone = if (row.id == selectedId) false else selectedGone)
        }
        val added = if (rows.isEmpty() || ORDER.compare(rows.last(), row) <= 0) {
            ArrayList<Row>(rows.size + 1).apply { addAll(rows); add(row) }.let { it to (index + (row.id to rows.size)) }
        } else {
            (rows + row).sortedWith(ORDER).let { it to indexOf(it) }
        }
        return copy(rows = added.first, index = added.second, total = total + 1, newCount = if (following) 0 else newCount + 1)
    }

    private companion object {
        val ORDER = compareBy<Row>({ it.timeMs }, { it.id })

        fun entries(page: JsonObject): List<Row> = (page["entries"] as? JsonArray).orEmpty().map { Row.of(it as JsonObject) }

        fun indexOf(rows: List<Row>): Map<String, Int> = HashMap<String, Int>(rows.size * 2).apply { rows.forEachIndexed { i, r -> put(r.id, i) } }

        fun total(page: JsonObject, fallback: Int) = (page["total"] as? JsonPrimitive)?.intOrNull ?: fallback

        fun cursor(page: JsonObject) = (page["next"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
