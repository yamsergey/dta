package io.yamsergey.dta.present.data

import io.yamsergey.dta.present.Units
import io.yamsergey.dta.protocol.data.FunctionList
import io.yamsergey.dta.protocol.data.HiltBindings
import io.yamsergey.dta.protocol.data.MemoryState
import io.yamsergey.dta.protocol.data.NavDestinationInfo
import io.yamsergey.dta.protocol.data.NavigationState
import io.yamsergey.dta.protocol.data.RuntimeActivities
import io.yamsergey.dta.protocol.data.ThreadList
import io.yamsergey.dta.protocol.data.ViewModelList

/** Feature 028: the running process as text (CLI), from the results agents get as JSON. */
object RuntimeText {
    fun activities(a: RuntimeActivities): String = buildString {
        append("pid ${a.pid} ${a.process}")
        if (a.activities.isEmpty()) append("\n  no activities")
        a.activities.forEach { x ->
            append("\n  ${x.className.substringAfterLast('.')}  ${x.state}  task ${x.task}")
            if (x.focused) append("  focused")
            append("  (${x.id})")
            x.intent?.takeIf { it.isNotBlank() }?.let { append("\n    intent $it") }
            x.fragments.forEach { f -> append("\n    fragment ${f.className.substringAfterLast('.')}${f.tag?.let { " #$it" } ?: ""}  ${f.state}") }
            x.fragmentsNote?.let { append("\n    $it") }
        }
    }

    fun navigation(n: NavigationState): String = buildString {
        n.note?.let { append(it) }
        n.hosts.forEachIndexed { i, h ->
            if (i > 0 || n.note != null) append("\n\n")
            append("${h.kind} ${h.id}\n  back stack (top last)")
            h.backStack.forEach { e ->
                append("\n    ").append(e.route ?: e.label ?: e.destinationId?.toString() ?: "?")
                if (e.arguments.isNotEmpty()) append("  ").append(e.arguments.entries.joinToString(" ") { "${it.key}=${it.value}" })
            }
            h.graph?.let { g -> append("\n  graph"); g.children.forEach { destination(it, 2) } }
        }
        n.completeness.forEach { append("\nDegraded: ${it.reason}") }
    }

    private fun StringBuilder.destination(d: NavDestinationInfo, depth: Int) {
        append("\n").append("  ".repeat(depth)).append(d.route ?: d.label ?: d.id.toString()).append("  ").append(d.navigator)
        if (d.arguments.isNotEmpty()) append("  args: ${d.arguments.joinToString()}")
        if (d.deepLinks.isNotEmpty()) append("  links: ${d.deepLinks.joinToString()}")
        if (d.start) append("  (start)")
        d.children.forEach { destination(it, depth + 1) }
    }

    fun viewModels(l: ViewModelList): String = buildString {
        if (l.viewModels.isEmpty()) append("No ViewModels alive.")
        l.viewModels.forEachIndexed { i, vm ->
            if (i > 0) append("\n\n")
            append("${vm.className.substringAfterLast('.')}  ${vm.id}")
            vm.owner?.let { append("  ($it)") }
            vm.properties.forEach { p -> append("\n  ${if (p.editable) "✎" else " "} ${p.path}  ${p.kind}  ${p.value}") }
            vm.savedState?.let { saved ->
                append("\n  saved state")
                saved.forEach { p -> append("\n  ✎ ${p.path}  ${p.value}") }
            }
        }
        l.completeness.forEach { append("\nDegraded: ${it.reason}") }
    }

    fun memory(m: MemoryState): String = "Java heap ${Units.bytes(m.javaUsedBytes)} of ${Units.bytes(m.javaMaxBytes)} · native ${Units.bytes(m.nativeAllocatedBytes)}" +
        (m.pssKb?.let { " · PSS ${Units.bytes(it * 1024L)}" } ?: "") +
        (m.gcCount?.let { " · $it collections" + (m.gcTimeMs?.let { t -> " ($t ms)" } ?: "") } ?: "") +
        (if (m.gcRun) " · after a GC DTA ran" else "")

    fun threads(t: ThreadList): String = t.threads.joinToString("\n") { th ->
        "${th.name}  ${th.state}${if (th.daemon) "  daemon" else ""}  prio ${th.priority}" + th.stack.take(12).joinToString("") { "\n    at $it" } +
            (if (th.stack.size > 12) "\n    … ${th.stack.size - 12} more" else "")
    }

    fun hilt(h: HiltBindings): String = if (!h.present) h.note ?: "No Hilt." else h.bindings.groupBy { it.component }.entries.joinToString("\n") { (c, list) ->
        c + list.joinToString("") { b -> "\n  ${b.declaredType}  ${b.name}" + (b.runtimeType?.let { "  → $it" } ?: if (!b.created) "  (not created yet)" else "") }
    }

    fun functions(f: FunctionList): String = buildString {
        f.functions.forEach { x -> append("${x.id}  ${x.source}${if (x.invocable) "" else "  (listed only)"}${x.description.takeIf { it.isNotEmpty() }?.let { "  — $it" } ?: ""}\n") }
        f.note?.let { append(it) }
    }.trimEnd()
}
