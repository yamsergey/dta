package io.yamsergey.dta.present.data

import io.yamsergey.dta.present.Units
import io.yamsergey.dta.protocol.data.Cell
import io.yamsergey.dta.protocol.data.CellType
import io.yamsergey.dta.protocol.data.DbSchema
import io.yamsergey.dta.protocol.data.FileListing
import io.yamsergey.dta.protocol.data.PrefsContent
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.data.StoreList
import kotlinx.serialization.json.JsonPrimitive

/** Feature 027: the app's stores as text (CLI), from the same results agents get as JSON. */
object DataText {
    private val TITLES = mapOf(StoreKind.DATABASE to "Databases", StoreKind.PREFS to "Preferences", StoreKind.DATASTORE to "DataStore")

    fun stores(list: StoreList): String = buildString {
        if (list.stores.isEmpty()) append("No databases, preferences or DataStore files yet.")
        val rows = list.stores.map { s ->
            val entries = s.entries?.let { n ->
                when (s.kind) { StoreKind.DATABASE -> "$n ${if (n == 1) "table" else "tables"}"; else -> "$n ${if (n == 1) "key" else "keys"}" }
            } ?: ""
            listOf(s.id, s.flavor, s.sizeBytes?.let(Units::bytes) ?: "", entries, "${s.access.name.lowercase()}: ${s.accessNote}")
        }
        val widths = (0 until 4).map { c -> rows.maxOfOrNull { it[c].length } ?: 0 }
        for (kind in StoreKind.entries) {
            val group = list.stores.indices.filter { list.stores[it].kind == kind }
            if (group.isEmpty()) continue
            if (isNotEmpty()) append('\n')
            append(TITLES[kind])
            for (i in group) {
                val r = rows[i]
                append("\n  ").append((0 until 4).joinToString("  ") { r[it].padEnd(widths[it]) }).append("  ").append(r[4])
            }
        }
        if (list.roots.isNotEmpty()) append("\nFiles\n").append(list.roots.joinToString("\n") { "  ${it.name}:/  ${it.path}" })
        list.completeness.forEach { append("\n").append("Degraded: ${it.reason}") }
    }

    fun cell(c: Cell): String = when (c.type) {
        CellType.NULL -> "NULL"
        CellType.BLOB -> if (c.truncated || c.value?.length != (c.size ?: 0) * 2) "<${Units.bytes((c.size ?: 0).toLong())} ${c.value}${if (c.truncated) "…" else ""}>" else "<${Units.bytes((c.size ?: 0).toLong())} ${c.value}>"
        else -> c.value ?: ""
    }

    fun rows(page: QueryPage): String = buildString {
        val cells = page.rows.map { r -> r.map { cell(it).replace('\n', ' ').let { s -> if (s.length > 60) s.take(59) + "…" else s } } }
        val widths = page.columns.indices.map { c -> maxOf(page.columns[c].length, cells.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0) }
        append(page.columns.indices.joinToString("  ") { page.columns[it].padEnd(widths[it]) }.trimEnd())
        cells.forEach { r -> append('\n').append(r.indices.joinToString("  ") { r[it].padEnd(widths[it]) }.trimEnd()) }
        append('\n')
        append(
            when {
                page.rows.isEmpty() -> "No rows" + (page.total?.let { if (it > 0) " from ${page.offset + 1} (of $it)" else "" } ?: "") + "."
                else -> "Rows ${page.offset + 1}–${page.offset + page.rows.size}" + (page.total?.let { " of $it" } ?: "") + "."
            },
        )
    }

    fun schema(s: DbSchema): String = buildString {
        append("${s.store}" + (if (s.room) " (Room)" else "") + (s.userVersion?.let { ", version $it" } ?: "") + " — ${s.access.name.lowercase()}: ${s.accessNote}")
        for (o in s.objects) {
            append("\n\n").append(o.type).append(' ').append(o.name)
            o.rowCount?.let { append(" — $it ${if (it == 1L) "row" else "rows"}") }
            o.table?.let { append(" on $it") }
            if (o.withoutRowid) append(" (without rowid)")
            o.columns.forEach { c ->
                append("\n  ").append(c.name).append(' ').append(c.type.ifEmpty { "ANY" })
                if (c.primaryKey > 0) append(" PRIMARY KEY")
                if (c.notNull) append(" NOT NULL")
                c.defaultValue?.let { append(" DEFAULT $it") }
            }
        }
    }

    fun prefs(p: PrefsContent): String = buildString {
        val rows = p.entries.map { e -> Triple(e.key, e.type.wireName, (e.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: e.value.toString()) }
        val k = rows.maxOfOrNull { it.first.length } ?: 0
        val t = rows.maxOfOrNull { it.second.length } ?: 0
        rows.forEach { (key, type, value) -> append(key.padEnd(k)).append("  ").append(type.padEnd(t)).append("  ").append(value).append('\n') }
        if (rows.isEmpty()) append("No keys.\n")
        append("(${p.access.name.lowercase()}: ${p.accessNote})")
    }

    fun files(l: FileListing): String = buildString {
        append(l.path).append(" (${l.total} ${if (l.total == 1) "entry" else "entries"})")
        val names = l.entries.map { if (it.dir) it.name + "/" else it.name }
        val w = names.maxOfOrNull { it.length } ?: 0
        l.entries.forEachIndexed { i, e ->
            append("\n  ").append(names[i].padEnd(w)).append("  ")
            append(if (e.dir) "${e.children ?: 0} ${if (e.children == 1) "entry" else "entries"}" else Units.bytes(e.sizeBytes))
        }
        if (l.offset + l.entries.size < l.total) append("\n  … ${l.total - l.offset - l.entries.size} more (offset ${l.offset + l.entries.size})")
    }
}
