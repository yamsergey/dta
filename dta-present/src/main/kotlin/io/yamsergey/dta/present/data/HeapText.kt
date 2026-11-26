package io.yamsergey.dta.present.data

import io.yamsergey.dta.present.Units
import io.yamsergey.dta.protocol.data.HeapCompareAnswer
import io.yamsergey.dta.protocol.data.HeapListAnswer
import io.yamsergey.dta.protocol.data.HeapSnapshotAnswer

/** Feature 074: text of the `runtime.heap.*` answers for the CLI. Every answer says what step 1 does not know. */
object HeapText {
    private fun signed(n: Long) = if (n > 0) "+$n" else "$n"
    private fun signedBytes(n: Long) = (if (n > 0) "+" else if (n < 0) "-" else "") + Units.bytes(kotlin.math.abs(n))

    fun snapshot(a: HeapSnapshotAnswer): String = buildString {
        val s = a.snapshot
        append("Snapshot ${s.id}${s.name?.let { " ($it)" } ?: ""}: ${s.totals.objects} objects, ${Units.bytes(s.totals.bytes)}, ${s.classCount} classes; ")
        append("paused the app ${a.pauseMs} ms${if (s.gcBefore) " (after a garbage collection)" else ""}.")
        a.warning?.let { append("\nWARNING: ").append(it) }
        a.dropped?.let { append("\n").append(it) }
        append("\nLargest by bytes:")
        s.top.forEach { append("\n  ${it.className}  ${it.count}  ${Units.bytes(it.bytes)}  ${it.origin.name.lowercase()}") }
        s.notes.forEach { append("\n").append(it) }
        a.notKnown.forEach { append("\nNot known: ").append(it) }
    }

    fun list(l: HeapListAnswer): String = buildString {
        if (l.snapshots.isEmpty() && l.dropped.isEmpty()) append(l.note ?: "No snapshots.")
        else {
            append("Snapshots of run ${l.run}:")
            l.snapshots.forEach { append("\n  ${it.id}${it.name?.let { n -> "  $n" } ?: ""}  ${it.objects} objects  ${Units.bytes(it.bytes)}  paused ${it.pauseMs} ms") }
            if (l.dropped.isNotEmpty()) append("\nSnapshots dropped to stay within 20: ").append(l.dropped.joinToString { it.name?.let { n -> "${it.id} ($n)" } ?: it.id })
            l.note?.let { append("\n").append(it) }
        }
        l.notKnown.forEach { append("\nNot known: ").append(it) }
    }

    fun compare(a: HeapCompareAnswer): String = buildString {
        val c = a.comparison
        append("${c.a} → ${c.b}: ${signed(c.totalsDeltaObjects)} objects, ${signedBytes(c.totalsDeltaBytes)} in total; ${c.unchangedClasses} classes unchanged.")
        if (c.rows.isEmpty()) append("\nNo changed class to show.")
        c.rows.forEach {
            append("\n  ${it.className}  ${it.change.name.lowercase()}  count ${it.countA} → ${it.countB} (${signed(it.deltaCount)})  ${Units.bytes(it.bytesA)} → ${Units.bytes(it.bytesB)} (${signedBytes(it.deltaBytes)})")
        }
        append("\n").append(a.leftOut).append(".")
        a.notKnown.forEach { append("\nNot known: ").append(it) }
    }

    fun instances(a: io.yamsergey.dta.protocol.data.HeapInstancesAnswer): String = buildString {
        append("${a.className}: ${a.total} instances")
        if (a.offset > 0 || a.instances.size < a.total) append(" (showing ${a.instances.size} from ${a.offset})")
        append(". ").append(a.note)
        a.instances.forEach { i ->
            append("\n  ${i.id}  ${Units.bytes(i.bytes)}")
            if (i.className != a.className) append("  ${i.className}")
            i.link?.let { append("  ${it.kind} ${it.ref}") }
            i.state?.let { append("  $it") }
        }
        a.notKnown.forEach { append("\nNot known: ").append(it) }
    }

    fun fields(a: io.yamsergey.dta.protocol.data.HeapFieldsAnswer): String = buildString {
        val f = a.fields
        append("Instance ${f.instance} of ${f.className}: ${f.fields.size} fields. ").append(a.note)
        f.fields.forEach { x ->
            append("\n  ${if (x.static) "static " else ""}${x.name}: ${x.type}")
            if (x.declaredIn != f.className) append("  (${x.declaredIn})")
            x.length?.let { append("  length $it") }
            x.value?.let { append("  = ").append(it.toString()) }
        }
        f.notes.forEach { append("\n").append(it) }
        a.redaction?.let { r ->
            append("\n").append(if (r.masked.isEmpty()) "Redacted for agents: no value needed masking." else "Redacted for agents: ${r.masked.size} values masked (fingerprint and length shown in each mask).")
        }
        a.notKnown.forEach { append("\nNot known: ").append(it) }
    }
}
