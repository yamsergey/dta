package io.yamsergey.dta.cli.format

import io.yamsergey.dta.present.network.label
import io.yamsergey.dta.present.timeline.TimelineEventText

import io.yamsergey.dta.cli.format.Style.Token
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.layout.NodeKind
import io.yamsergey.dta.present.layout.NodeRow
import io.yamsergey.dta.present.network.EntryKind
import io.yamsergey.dta.present.network.EntryRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Human-readable output per operation (design system: information.md, statements.md).
 * Works on the operation's JSON result, the same document `--json` prints, so the two can
 * never disagree on content; statements come from dta-present, shared with the plugin.
 * Unknown operations print indented JSON.
 */
class Formatters(private val style: Style) {

    fun format(operation: String, result: JsonElement): String = when (operation) {
        "devices.list" -> devices(result.arr)
        "apps.list" -> apps(result.obj)
        "capability.report" -> capability(result.obj)
        "layout.tree" -> layout(result.obj)
        "network.list" -> networkList(result.obj)
        "network.entry" -> networkEntry(result.obj)
        "network.body" -> body(result.obj)
        "network.clear" -> "Cleared ${result.obj.long("clearedEntries")} entries."
        "network.export" -> result.obj.let { o ->
            "Exported ${o.long("entries")} entries, ${o.long("webSocketMessages")} WebSocket messages and ${o.long("bodies")} bodies to ${o.str("file")}" +
                (o.long("bodiesOmitted")?.takeIf { it > 0 }?.let { "; $it bodies left out (the file says why)" } ?: "") +
                (o.long("messagesNotExported")?.takeIf { it > 0 }?.let { "; $it messages beyond a connection's first 1000 not exported" } ?: "") + "."
        }
        "network.script.set", "network.script.status" -> scriptStatus(result.obj)
        "network.script.clear" -> if (result.obj.bool("cleared")) "Network script removed." else "No network script was active."
        "network.script.logs" -> scriptLogs(result.obj)
        "network.ws.push" -> wsPush(result.obj)
        "network.schema.add" -> schemaSet(result.obj)
        "network.schema.list" -> result.arr.joinToString("\n\n") { schemaSet(it.obj) }.ifEmpty { "No schema sets. Add one with 'dta network schema add'." }
        "network.schema.remove" -> schemaRemoved(result.obj)
        "network.schema.sync" -> schemaSync(result.obj)
        "network.mapping.add" -> mappingRow(result.obj)
        "network.mapping.list" -> result.arr.joinToString("\n") { mappingRow(it.obj) }.ifEmpty { "No mappings." }
        "network.mapping.remove" -> "Removed mapping ${result.obj.str("removed")}."
        "links.list" -> linksList(result.obj)
        "links.entry" -> linkEntry(result.obj.obj("entry") ?: JsonObject(emptyMap()))
        "links.open" -> linkOpen(result.obj)
        "links.verification" -> verification(result.obj)
        "timeline.events" -> timelineEvents(result)
        "timeline.causes" -> TimelineEventText.chain(decode(io.yamsergey.dta.protocol.timeline.CauseChain.serializer(), result))
        "state.current" -> TimelineEventText.state(decode(io.yamsergey.dta.protocol.timeline.CurrentState.serializer(), result))
        "state.graph" -> TimelineEventText.graph(decode(io.yamsergey.dta.protocol.timeline.StateGraph.serializer(), result))
        "state.path" -> statePath(decode(io.yamsergey.dta.protocol.timeline.StatePath.serializer(), result))
        "data.list" -> io.yamsergey.dta.present.data.DataText.stores(decode(io.yamsergey.dta.protocol.data.StoreList.serializer(), result))
        "data.db.schema" -> io.yamsergey.dta.present.data.DataText.schema(decode(io.yamsergey.dta.protocol.data.DbSchema.serializer(), result))
        "data.db.query" -> io.yamsergey.dta.present.data.DataText.rows(decode(io.yamsergey.dta.protocol.data.QueryPage.serializer(), result))
        "data.db.exec" -> result.obj.obj("write")?.let { w -> "${w.str("store")}: ${w.str("change")}" } +
            (result.obj.obj("exec")?.long("invalidated")?.takeIf { it > 0 }?.let { "\nRoom told: $it invalidation ${if (it == 1L) "tracker re-checks its" else "trackers re-check their"} tables." } ?: "")
        "data.prefs.read" -> io.yamsergey.dta.present.data.DataText.prefs(decode(io.yamsergey.dta.protocol.data.PrefsContent.serializer(), result))
        "data.prefs.set", "data.prefs.remove", "data.proto.write", "data.files.write", "data.files.mkdir", "data.files.delete" -> result.obj.let { w ->
            "${w.str("store")}: ${w.str("change")}" + if (w.str("access") == "file") " (written to the file; the app reads it on next open)" else ""
        }
        "data.proto.read" -> result.obj.let { p ->
            PRETTY.encodeToString(JsonElement.serializer(), p["decoded"] ?: JsonNull) + "\n(${p.long("sizeBytes")} bytes; ${p.str("decodeNote") ?: ""})"
        }
        "data.files.list" -> io.yamsergey.dta.present.data.DataText.files(decode(io.yamsergey.dta.protocol.data.FileListing.serializer(), result))
        "data.files.read" -> result.obj.let { f ->
            f.str("text") ?: f.str("output")?.let { "Saved ${f.long("sizeBytes")} bytes to $it (sha256 ${f.str("sha256")})." } ?: "${f.long("sizeBytes")} bytes, binary (base64): ${f.str("base64")}"
        }
        "runtime.activities" -> io.yamsergey.dta.present.data.RuntimeText.activities(decode(io.yamsergey.dta.protocol.data.RuntimeActivities.serializer(), result))
        "runtime.navigation" -> io.yamsergey.dta.present.data.RuntimeText.navigation(decode(io.yamsergey.dta.protocol.data.NavigationState.serializer(), result))
        "runtime.viewmodels" -> io.yamsergey.dta.present.data.RuntimeText.viewModels(decode(io.yamsergey.dta.protocol.data.ViewModelList.serializer(), result))
        "runtime.memory" -> io.yamsergey.dta.present.data.RuntimeText.memory(decode(io.yamsergey.dta.protocol.data.MemoryState.serializer(), result))
        "runtime.threads" -> io.yamsergey.dta.present.data.RuntimeText.threads(decode(io.yamsergey.dta.protocol.data.ThreadList.serializer(), result))
        "runtime.di" -> io.yamsergey.dta.present.data.RuntimeText.di(decode(io.yamsergey.dta.protocol.data.DiReport.serializer(), result))
        "runtime.functions" -> io.yamsergey.dta.present.data.RuntimeText.functions(decode(io.yamsergey.dta.protocol.data.FunctionList.serializer(), result))
        "runtime.navigate", "runtime.back", "runtime.vm.set", "runtime.saved.set" -> result.obj.let { w -> "${w.str("change")}." }
        "runtime.invoke" -> result.obj.let { r -> r.str("error")?.let { "${r.str("id")} threw $it" } ?: "${r.str("id")}: ${r["result"]?.let { PRETTY.encodeToString(JsonElement.serializer(), it) } ?: "done"}" }
        "archive.list" -> archiveList(result.obj)
        "archive.delete" -> archiveDelete(result.obj)
        "daemon.status" -> status(result.obj)
        "daemon.stop" -> "The DTA daemon is stopping."
        else -> PRETTY.encodeToString(JsonElement.serializer(), result)
    }

    /** Feature 020: where a pushed message went and how it reached the app. */
    private fun wsPush(r: JsonObject): String {
        val how = if (r.str("delivery") == "dta-thread") "from DTA's thread after ${r.long("waitedMs")} ms (no frame arrived)"
        else "on the library's reading thread after ${r.long("waitedMs")} ms"
        return "Pushed to ${r.str("connectionId")} as entry ${r.str("entryId")}, delivered $how." +
            (r.str("listenerError")?.let { "\nThe app's listener threw: $it" } ?: "")
    }

    /** One live entry line for `network watch` (same columns as the list). */
    fun watchLine(event: JsonObject): String {
        Statements.forCleared(event)?.let { return style.mark(it) }
        val entry = event.obj("event")?.obj("entry") ?: return ""
        val line = entryRow(entry, absolute = true)
        val dropped = event["degraded"]?.let { "\n" + style.mark(StatementKind.DEGRADED, "events dropped: this terminal fell behind") }
        return line + (dropped ?: "")
    }

    // --- Timeline and state (features 015/016)

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, result: JsonElement): T =
        io.yamsergey.dta.protocol.rpc.DtaJson.decodeFromJsonElement(serializer, result)

    private fun timelineEvents(result: JsonElement): String {
        val page = decode(io.yamsergey.dta.protocol.timeline.TimelinePage.serializer(), result)
        val off = page.sources.filter { it.state != io.yamsergey.dta.protocol.ui.SourceState.ON }
            .map { style.mark(StatementKind.DEGRADED, "${it.name} ${it.state.name.lowercase()}: ${it.reason ?: "no reason given"}") }
        val byId = page.events.associateBy { it.id }
        val lines = page.events.map { timelineLine(it, byId::get) }
        val body = lines.ifEmpty { listOf("No events.") }
        val more = if (page.more) listOf(style.paint("more: --since ${page.next}", Token.INK_MUTED)) else emptyList()
        return (off + body + more + style.paint("next: ${page.next}", Token.INK_MUTED)).joinToString("\n")
    }

    /** One live event line for `timeline watch`. */
    fun timelineEventLine(event: JsonObject): String {
        val e = event["event"] ?: return ""
        return timelineLine(decode(io.yamsergey.dta.protocol.timeline.TimelineEvent.serializer(), e)) { null }
    }

    private fun timelineLine(e: io.yamsergey.dta.protocol.timeline.TimelineEvent, lookup: (String) -> io.yamsergey.dta.protocol.timeline.TimelineEvent?): String {
        val text = TimelineEventText.line(e, lookup)
        val shown = when {
            e.source == io.yamsergey.dta.protocol.timeline.Sources.TIMELINE -> style.paint(text, Token.CAVEAT)
            e.kind == io.yamsergey.dta.protocol.timeline.Kinds.ui("window-settled") -> style.paint(text, Token.INK_MUTED)
            else -> text
        }
        return style.paint(TIME.format(Instant.ofEpochMilli(e.wallMs)), Token.INK_MUTED) + "  " + style.paint(e.id.padEnd(16), Token.INK_MUTED) + shown
    }

    private fun statePath(p: io.yamsergey.dta.protocol.timeline.StatePath): String {
        if (!p.found) return "No observed path from ${p.from} to ${p.to}."
        if (p.steps.isEmpty()) return "Already there."
        return p.steps.joinToString("\n") { t -> "${t.from} → ${t.to}  ×${t.count}  trigger ${t.triggers.firstOrNull() ?: "—"}${if (t.noKnownCause) " (no known cause)" else ""}" }
    }

    // --- Devices and apps

    /** The apps running when `apps watch` starts, one line each. */
    fun appsSnapshot(result: JsonElement): String = result.obj.arr("apps").flatMap { list ->
        val device = list.obj.str("device")
        list.obj.arr("apps").map { a -> "$device  ${a.obj.str("packageName")}  running" + if (a.obj.bool("foreground")) " (foreground)" else "" }
    }.joinToString("\n")

    /** One `app.connection` event: device, app, state and its reason. */
    fun appEventLine(event: JsonObject): String {
        val state = event.str("state") ?: "?"
        val shown = when (state) {
            "lost", "refused" -> style.paint(state, Token.CAVEAT)
            "ended" -> style.paint(state, Token.INK_MUTED)
            else -> state
        }
        return "${event.str("device")}  ${event.str("app")}  $shown" + (event.str("reason")?.let { ": $it" } ?: "")
    }

    private fun devices(list: JsonArray): String {
        if (list.isEmpty()) return "No devices connected."
        return list.joinToString("\n") { d ->
            val o = d.obj
            listOf(o.str("serial"), o.str("state"), o.str("model")?.let { style.paint(it, Token.INK_MUTED) }).filterNotNull().joinToString("  ")
        }
    }

    private fun apps(o: JsonObject): String {
        val apps = o.arr("apps")
        val lines = apps.map { a ->
            val name = a.obj.str("packageName")!!
            if (a.obj.bool("foreground")) "$name  ${style.paint("foreground", Token.INK_MUTED)}" else name
        }
        val foreground = o.str("foreground")
        val tail = if (foreground != null && apps.none { it.obj.str("packageName") == foreground }) {
            listOf(style.paint("foreground: $foreground (no DTA inside)", Token.INK_MUTED))
        } else {
            emptyList()
        }
        return (lines.ifEmpty { listOf("No running app on ${o.str("device")} has DTA inside.") } + tail).joinToString("\n")
    }

    private fun capability(o: JsonObject): String = buildString {
        val gaps = Statements.forCapability(o).associateBy { it.subject }
        fun capLine(name: String, status: String, reason: String?): String {
            val state = gaps[name]?.let { style.mark(it.kind, it.word) } ?: status
            return listOfNotNull(name.padEnd(16), state, reason?.let { style.paint(it, Token.INK_MUTED) }).joinToString("  ")
        }
        appendLine("${o.str("packageName")}  sidekick ${o.str("sidekickVersion")}" + (o.str("sidekickBuild")?.let { " (build $it)" } ?: ""))
        o.arr("capabilities").forEach { c ->
            val cap = c.obj
            appendLine(capLine(cap.str("name")!!, cap.str("status")!!, cap.str("reason")))
            cap.arr("libraries").forEach { l ->
                val lib = l.obj
                appendLine("  " + capLine(lib.str("name")!!, lib.str("status")!!, lib.str("reason")))
                // Feature 019: the events a partly captured library misses, at a glance; the raw detail stays in --json.
                if (lib.str("status") == "degraded") {
                    lib.arr("notCaptured").map { it.toString().trim('"') }.takeIf { it.isNotEmpty() }?.let {
                        appendLine("    " + style.paint("not captured: ${it.joinToString(", ")}", Token.INK_MUTED))
                    }
                }
            }
            cap.obj("tooling")?.let { t ->
                val origin = if (t.str("source") == "dta") "added by DTA injection" + (t.str("version")?.let { " ($it)" } ?: "") else "declared by the project"
                val state = if (t["present"]?.toString() == "true") "present" else style.mark(StatementKind.DEGRADED, "not usable")
                appendLine("  " + listOf("tooling data".padEnd(16), state, style.paint(origin, Token.INK_MUTED)).joinToString("  "))
            }
        }
    }.trimEnd()

    // --- Layout

    private fun layout(o: JsonObject): String = buildString {
        val screen = o.obj("screen")
        appendLine(
            "Screen ${screen?.long("widthPx")}×${screen?.long("heightPx")} px" +
                (screen?.str("activity")?.let { "  $it" } ?: "") +
                "  source ${o.str("source")}" + (o.obj("detail")?.let { "  detail ${it.str("delivered")}" } ?: ""),
        )
        o.obj("screenImage")?.let { appendLine(screenLine(it)) }
        o["screenWindows"]?.let { list ->
            val foreign = (list as JsonArray).map { it.obj }.filter { it["app"]?.jsonPrimitive?.content != "true" }
            if (foreign.isNotEmpty()) appendLine(style.paint("Not the app: " + foreign.joinToString(", ") { "${it.str("name")} (${it.str("kind")})" }, Token.INK_MUTED))
        }
        Statements.forCapture(o).forEach { appendLine(style.mark(it)) }
        val matches = o["matches"]
        if (matches is JsonArray) {
            appendLine("${matches.size} match${if (matches.size == 1) "" else "es"}")
            matches.forEach { m ->
                val ancestors = m.obj.arr("ancestors").joinToString(" › ") { it.obj.str("label") ?: "" }
                if (ancestors.isNotEmpty()) appendLine(style.paint(ancestors, Token.INK_MUTED))
                appendLine("  " + nodeLine(m.obj.obj("node")!!))
            }
        } else {
            o.arr("windows").forEach { w ->
                appendLine("window ${w.obj.str("type")}${w.obj.str("title")?.let { " \"$it\"" } ?: ""}")
                tree(w.obj.obj("root")!!, 1)
            }
        }
    }.trimEnd()

    /** Feature 014: where the image went and how the bounds line up with it. */
    private fun screenLine(image: JsonObject): String {
        val state = image.str("state")
        if (state != "present") return "Screen: not saved — $state: ${image.str("reason") ?: "no reason given"}"
        val where = image.obj("file")?.str("path") ?: "inline"
        val alignment = image.obj("alignment")?.let { a ->
            when (a.str("kind")) {
                "offset" -> "offset by ${a.long("dx") ?: 0},${a.long("dy") ?: 0}"
                "scaled" -> "scaled ×${a["scale"]?.jsonPrimitive?.content}"
                else -> a.str("kind")
            }
        } ?: "alignment unknown"
        return "Screen: $where — ${image.long("widthPx")}×${image.long("heightPx")}, $alignment"
    }

    private fun StringBuilder.tree(node: JsonObject, depth: Int) {
        appendLine("  ".repeat(depth) + nodeLine(node))
        node.arr("children").forEach { tree(it.obj, depth + 1) }
    }

    private fun nodeLine(n: JsonObject): String {
        val row = NodeRow.of(n)
        val tag = style.paint(row.kind.tag, when (row.kind) {
            NodeKind.VIEW -> Token.NODE_VIEW
            NodeKind.COMPOSE -> Token.NODE_COMPOSE
            NodeKind.FALLBACK -> Token.CAVEAT
        })
        return listOfNotNull(
            tag, row.name, row.ids.joinToString(" ").ifEmpty { null }?.let { style.paint(it, Token.INK_MUTED) }, row.text,
            row.interactions.joinToString(",").ifEmpty { null }?.let { style.paint(it, Token.INK_MUTED) },
            row.source?.let { style.paint(it, Token.INK_MUTED) }, "disabled".takeIf { row.disabled },
        ).joinToString(" ") + markers(n["degraded"]).joinToString("") { " $it" }
    }

    // --- Network

    private fun networkList(o: JsonObject): String = buildString {
        val entries = o.arr("entries")
        val archived = o.obj("archived")
        entries.forEach { e ->
            val mark = archived?.obj(e.obj.str("id") ?: "")?.let { "  " + style.mark(Statements.forArchived(it)) }.orEmpty()
            appendLine(entryRow(e.obj, absolute = false) + mark)
        }
        if (entries.isEmpty()) appendLine("No entries.")
        append(style.paint("${entries.size} of ${o.long("total")} shown", Token.INK_MUTED))
        o.str("next")?.let { append(style.paint("  next page: --before $it", Token.INK_MUTED)) }
        Statements.forNetworkList(o).forEach { append("\n" + style.mark(it)) }
    }

    // --- Links (feature 012)

    private fun linksList(o: JsonObject): String = buildString {
        val entries = o.arr("entries")
        if (entries.isEmpty()) appendLine("No links have reached the app.")
        val archived = o.obj("archived")
        entries.forEach { e ->
            val l = e.obj
            val mark = archived?.obj(l.str("id") ?: "")?.let { "  " + style.mark(Statements.forArchived(it)) }.orEmpty()
            appendLine(listOf(style.paint(l.str("id") ?: "", Token.INK_MUTED), l.str("uri") ?: "", style.paint(l.str("delivery") ?: "", Token.ACCENT),
                (l.str("activity") ?: "").substringAfterLast('.')).joinToString("  ") + mark)
        }
        append(style.paint("${entries.size} of ${o.long("total")} shown", Token.INK_MUTED))
        o.str("next")?.let { append(style.paint("  next page: --before $it", Token.INK_MUTED)) }
    }

    private fun linkEntry(l: JsonObject): String = buildString {
        appendLine(l.str("uri"))
        fun row(name: String, value: String?) { value?.takeIf { it.isNotEmpty() }?.let { appendLine(name.padEnd(12) + it) } }
        row("delivery", l.str("delivery"))
        row("activity", l.str("activity"))
        row("referrer", l.str("referrer") ?: "unknown")
        row("action", l.str("action"))
        row("categories", l.arr("categories").joinToString(", ") { it.jsonContent })
        row("flags", l.str("flags"))
        l.arr("query").takeIf { it.isNotEmpty() }?.let { q -> appendLine("query"); q.forEach { appendLine("  ${it.obj.str("name")} = ${it.obj.str("value")}") } }
        l.arr("extras").takeIf { it.isNotEmpty() }?.let { x ->
            appendLine("extras")
            x.forEach { e ->
                val ex = e.obj
                appendLine(ex.str("notRead")?.let { "  ${ex.str("key")}: " + style.mark(StatementKind.DEGRADED, "not read: $it") }
                    ?: "  ${ex.str("key")}${ex.str("type")?.let { " ($it)" } ?: ""} = ${ex.str("value")}")
            }
        }
    }.trimEnd()

    private fun linkOpen(o: JsonObject): String {
        val activity = o.str("activity")
        val main = when (o.str("outcome")) {
            "received" -> "Received by ${activity ?: o.str("packageName")}" + (o.str("recordId")?.let { " — link $it." } ?: ".")
            "other-app" -> "Opened by another app: ${o.str("packageName")}" + (activity?.let { " ($it)" } ?: "") + "."
            "chooser" -> "The system showed a chooser: several apps can open it and none is the default."
            else -> "Nothing on the device can open it."
        }
        val extra = listOfNotNull(
            o.str("reason")?.takeIf { o.str("outcome") != "received" || o.str("recordId") == null }?.let { if (o.str("outcome") == "received") it else "Why: $it" },
            o.obj("appLinks")?.let { h -> "App Links: ${h.str("host")}: ${h.str("state")}" + (h.str("reason")?.let { " ($it)" } ?: "") },
        )
        return (listOf(main) + extra).joinToString("\n")
    }

    private fun verification(o: JsonObject): String {
        if (!o.bool("available")) return o.str("reason") ?: "unavailable"
        val hosts = o.arr("hosts")
        if (hosts.isEmpty()) return o.str("reason") ?: "No App Links hosts."
        return buildString {
            hosts.forEach { h ->
                val host = h.obj
                val off = if (host["enabled"]?.toString() == "false") " · turned off by the user" else ""
                appendLine("${host.str("host")}  ${host.str("state")}" + (host.str("reason")?.let { " — $it" } ?: "") + off)
            }
            if (o["linkHandlingAllowed"]?.toString() == "false") append(style.mark(StatementKind.DEGRADED, "the user turned off opening supported links for this app"))
        }.trimEnd()
    }

    // --- Schemas from the project (feature 013)

    private fun schemaSync(o: JsonObject): String = buildString {
        val set = o.obj("set")
        if (set == null) {
            appendLine(style.mark(StatementKind.FAILED, "nothing synced"))
        } else {
            val from = set.obj("origin")?.str("path") ?: "the project"
            appendLine("${set.arr("files").size} files from $from as ${set.str("name")}" + if (o.bool("replaced")) " (replaced)" else "")
            appendLine("  types: " + set.arr("types").joinToString(", ") { it.jsonContent })
        }
        o.arr("failed").forEach { f -> appendLine(style.mark(StatementKind.DEGRADED, "not compiled") + "  ${f.obj.str("file")}: ${f.obj.str("reason")}") }
        o.long("mappings")?.takeIf { it > 0 }?.let { appendLine("$it mappings derived from the code") }
        o.arr("rejectedMappings").forEach { appendLine(style.mark(StatementKind.DEGRADED, "mapping not added") + "  ${it.jsonContent}") }
        o.arr("notes").forEach { appendLine(style.mark(StatementKind.DEGRADED, it.jsonContent)) }
        o.arr("unmappedTypes").takeIf { it.isNotEmpty() }?.let { types ->
            appendLine(style.paint("not mapped yet: ${types.joinToString(", ") { it.jsonContent }}", Token.INK_MUTED))
            append(style.paint("map the endpoints that use them: dta network mapping add --url-pattern '*/path' --part response --schema ${set?.str("name") ?: "<set>"} --type <type>", Token.INK_MUTED))
        }
    }.trimEnd()

    // --- Archive (feature 011)

    private fun archiveList(o: JsonObject): String {
        val devices = o.arr("devices")
        if (devices.isEmpty()) return "Nothing archived yet."
        return buildString {
            devices.forEach { d ->
                val dev = d.obj
                appendLine(listOfNotNull(dev.str("model"), dev.str("serial")).joinToString("  ").ifEmpty { dev.str("key") ?: "" })
                dev.arr("apps").forEach { a ->
                    appendLine("  " + a.obj.str("packageName"))
                    a.obj.arr("installations").forEach { i ->
                        val inst = i.obj
                        val version = listOfNotNull(inst.str("versionName"), inst.long("versionCode")?.let { "($it)" }).joinToString(" ").ifEmpty { "unknown version" }
                        appendLine("    $version  install ${inst.long("id")}")
                        inst.arr("epochs").forEach { e ->
                            val ep = e.obj
                            val closed = ep.long("closedMs")
                            val state = if (closed == null) style.paint("live", Token.ACCENT) else ep.str("reason")?.replace('-', ' ') ?: "archived"
                            val span = "${time(ep.long("openedMs"))} → ${closed?.let { time(it) } ?: "now"}"
                            val never = ep.long("neverReceived")?.takeIf { it > 0 }?.let { "  " + style.mark(StatementKind.DEGRADED, "$it never reached this computer") }.orEmpty()
                            appendLine("      ${ep.str("id")}  $span  $state  ${ep.long("entries")} entries  ${bytes(ep.long("bytes") ?: 0)}$never")
                        }
                    }
                }
            }
            append(style.paint("${bytes(o.long("bytes") ?: 0)} on disk; query with --archive, delete with dta archive delete", Token.INK_MUTED))
        }
    }

    private fun archiveDelete(o: JsonObject): String {
        val r = o.obj("removed") ?: JsonObject(emptyMap())
        val what = "${r.long("epochs")} epochs, ${r.long("entries")} entries and ${r.long("bodiesFreed")} bodies (${bytes(r.long("bytesFreed") ?: 0)})"
        return if (o.bool("confirmed")) "Removed $what." else "Would remove $what. Run again with --confirm to delete them."
    }

    private fun time(ms: Long?): String = ms?.let {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(it))
    } ?: "?"

    /** One entry as a line; the row model is dta-present's, the same the plugin's list shows. */
    private fun entryRow(e: JsonObject, absolute: Boolean, withPayload: Boolean = false): String {
        val row = EntryRows.of(e)
        val kind = if (row.kind == EntryKind.HTTP) Token.KIND_HTTP else Token.KIND_WS
        // The URL column is the URL; a message's payload shows only under its connection.
        val name = listOfNotNull(
            row.host?.let { style.paint(it, Token.INK_MUTED) + row.path } ?: row.path,
            // Feature 038: what the message is, before its text.
            row.envelope?.takeIf { withPayload }?.label()?.takeIf { it.isNotEmpty() }?.let { "[$it]" },
            row.preview?.takeIf { withPayload }?.let { style.paint(it, Token.INK_MUTED) },
        ).filter { it.isNotEmpty() }.joinToString("  ")
        val outcome = row.outcome.statement?.let { style.mark(it.kind, it.word) } ?: when (row.outcome.token) {
            "status-client" -> style.paint(row.outcome.text, Token.STATUS_CLIENT)
            "status-server" -> style.paint(row.outcome.text, Token.STATUS_SERVER)
            else -> row.outcome.text
        }
        val marks = row.marks.shown.map { style.mark(it.kind, it.word) } + listOfNotNull(row.marks.moreLabel)
        return listOf(
            style.paint(row.id, Token.INK_MUTED), row.time, style.paint(if (style.unicode) row.kind.glyph else row.kind.ascii, kind),
            style.paint(row.verb, kind), name, outcome, style.paint(row.timing, Token.INK_MUTED), marks.joinToString(" "),
        ).filter { it.isNotEmpty() }.joinToString("  ") + if (absolute) "" else ""
    }

    private fun networkEntry(o: JsonObject): String = buildString {
        val e = o.obj("entry")!!
        appendLine(entryRow(e, absolute = true))
        val statements = Statements.forEntryDetail(o)
        statements.filter { it.kind == StatementKind.FAILED || it.kind == StatementKind.SCRIPTED }.forEach { appendLine(style.mark(it)) }
        e.str("library")?.let { appendLine(style.paint("library $it  run ${e.str("runId")}", Token.INK_MUTED)) }
        headers("Request headers", e.arr("requestHeaders"))
        e.obj("requestBody")?.let { appendLine(bodyLine("Request body", it)) }
        headers("Response headers", e.arr("responseHeaders"))
        e.obj("responseBody")?.let { appendLine(bodyLine("Response body", it)) }
        e.obj("payload")?.let { appendLine(bodyLine("Payload", it)) }
        e.obj("initiator")?.let { i ->
            appendLine(section("Initiator") + "  thread ${i.str("thread")}  ${i.str("site")}")
            val first = i.long("firstAppFrame")?.toInt()
            i.arr("frames").forEachIndexed { index, f ->
                val frame = "  " + io.yamsergey.dta.present.network.FrameText.of(f.obj.str("className"), f.obj.str("method"), f.obj.str("file"), f.obj.long("line")?.toInt())
                appendLine(if (index == first) style.paint(frame, Token.STRONG) else style.paint(frame, Token.INK_MUTED))
            }
        }
        o.arr("messages").takeIf { it.isNotEmpty() }?.let { messages ->
            appendLine(section("Messages"))
            messages.forEach { appendLine("  " + entryRow(it.obj, absolute = true, withPayload = true)) }
        }
        statements.filter { it.kind == StatementKind.REDACTED || it.kind == StatementKind.DEGRADED }.forEach { appendLine(style.mark(it)) }
    }.trimEnd()

    private fun StringBuilder.headers(title: String, list: JsonArray) {
        if (list.isEmpty()) return
        appendLine(section(title))
        list.forEach { h -> appendLine("  ${h.obj.str("name")}: ${h.obj.str("value")}") }
    }

    private fun bodyLine(title: String, b: JsonObject): String {
        val facts = listOfNotNull(b.str("contentType"), b.str("encoding"), b.long("storedBytes")?.let(::bytes))
        val marks = Statements.forBody(JsonObject(mapOf("body" to b))).map { style.mark(it) }
        val preview = b.str("preview")?.let { "\n  " + it.replace("\n", "\n  ") } ?: ""
        return section(title) + "  " + style.paint(facts.joinToString(" · "), Token.INK_MUTED) + marks.joinToString("") { "  $it" } + preview
    }

    private fun body(o: JsonObject): String = buildString {
        val info = o.obj("body")
        appendLine(bodyLine("Body (${info?.str("part")})", info ?: JsonObject(emptyMap())).lineSequence().first())
        // Completeness and binary are on the header line; the rest follow it.
        Statements.forBody(o).filter { it.kind != StatementKind.INCOMPLETE && it.kind != StatementKind.BINARY }.forEach { appendLine(style.mark(it)) }
        o.str("file")?.let { appendLine("Wrote ${bytes(o.long("bytes") ?: 0)} to $it  sha256 ${o.str("sha256")}") }
        o.str("text")?.let { append(it) }
    }.trimEnd()

    private fun scriptStatus(o: JsonObject): String = buildString {
        if (!o.bool("active")) append("No network script active.") else {
            append("Network script ${o.str("id")} active" + (o.long("installedAtMs")?.let { " since ${TIME.format(Instant.ofEpochMilli(it))}" } ?: ""))
            o.arr("handlers").takeIf { it.isNotEmpty() }?.let { append("\nhandlers: ${it.joinToString(", ") { h -> h.jsonContent }}") }
            append(style.paint("\ninvocations ${o.long("invocations") ?: 0}  changes ${o.long("changes") ?: 0}  errors ${o.long("errors") ?: 0}", Token.INK_MUTED))
            if (o.bool("persisted", default = true).not()) append("\n" + style.mark(StatementKind.DEGRADED, "not persisted: lost on app restart"))
        }
        o.str("unavailable")?.let { append("\n" + style.mark(StatementKind.DEGRADED, it)) }
    }

    private fun scriptLogs(o: JsonObject): String = buildString {
        o.arr("lines").forEach { l ->
            val line = l.obj
            val text = "${TIME.format(Instant.ofEpochMilli(line.long("timeMs") ?: 0))}  ${line.str("handler") ?: "-"}  ${line.str("entryId") ?: ""}  ${line.str("message")}"
            appendLine(if (line.str("level") == "error") style.paint(text, Token.DANGER) else text)
        }
        o.long("skipped")?.takeIf { it > 0 }?.let { appendLine(style.mark(StatementKind.EVICTED, "$it lines lost to the log buffer")) }
        append(style.paint("next: --since ${o.long("next")}", Token.INK_MUTED))
    }

    private fun schemaSet(o: JsonObject): String =
        "${o.str("name")}  ${style.paint("${o.str("source")} · ${o.arr("files").size} files", Token.INK_MUTED)}\n" +
            o.arr("types").joinToString("\n") { "  ${it.jsonContent}" }

    private fun schemaRemoved(o: JsonObject): String {
        val broken = o.arr("brokenMappings")
        return "Removed schema set ${o.str("removed")}." + if (broken.isEmpty()) "" else "\n" +
            style.mark(StatementKind.DEGRADED, "now broken: ${broken.joinToString(", ") { it.obj.str("id") ?: "" }}")
    }

    private fun mappingRow(m: JsonObject): String = listOfNotNull(
        style.paint(m.str("id") ?: "", Token.INK_MUTED), m.str("urlPattern"), m.str("part"), m.str("method"), m.str("direction"),
        "→ ${m.str("type")}", style.paint("(${m.str("schema")})", Token.INK_MUTED),
        if (m.bool("builtin")) style.paint("built-in", Token.INK_MUTED) else null,
        m.str("broken")?.let { style.mark(StatementKind.DEGRADED, "broken: $it") },
    ).joinToString("  ")

    // --- Daemon

    private fun status(o: JsonObject): String = buildString {
        val identity = o.obj("identity")
        appendLine("DTA daemon ${identity?.str("version")} (build ${identity?.str("build")})  pid ${o.long("pid")}  up ${duration(o.long("uptimeMs") ?: 0)}")
        appendLine(style.paint("home ${o.str("home")}", Token.INK_MUTED))
        val adb = o.obj("adb")
        appendLine(if (adb?.bool("available") == true) "adb ${adb.str("path")}" else style.mark(StatementKind.DEGRADED, "adb unavailable: ${adb?.str("reason")}"))
        val clients = o.arr("clients")
        appendLine("clients ${clients.size}" + clients.joinToString("") { c -> "\n  ${c.obj.str("kind")} ${c.obj.str("version")}" })
        val devices = o.arr("devices")
        appendLine("devices ${devices.size}" + devices.joinToString("") { d -> "\n  ${d.obj.str("serial")} ${d.obj.str("state")}${d.obj.str("model")?.let { " $it" } ?: ""}" })
        val connections = o.arr("connections")
        append("app connections ${connections.size}" + connections.joinToString("") { c -> "\n  ${c.obj.str("device")} ${c.obj.str("app")}" })
    }

    // --- Shared

    /** Degradation markers (`[{capability, reason}]`) as caveat marks. */
    private fun markers(element: JsonElement?): List<String> = Statements.markers(element).map { style.mark(it) }

    private fun section(title: String) = style.paint(title, Token.STRONG)

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** How an exchange ended: shown in the status column, not among the row's marks. */
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        fun bytes(n: Long): String = io.yamsergey.dta.present.Units.bytes(n)

        fun duration(ms: Long): String {
            val s = ms / 1000
            return when {
                s >= 3600 -> "${s / 3600}h ${s % 3600 / 60}m"
                s >= 60 -> "${s / 60}m ${s % 60}s"
                else -> "${s}s"
            }
        }
    }
}

private val JsonElement.obj: JsonObject get() = this as? JsonObject ?: JsonObject(emptyMap())
private val JsonElement.arr: JsonArray get() = this as? JsonArray ?: JsonArray(emptyList())
private val JsonElement.jsonContent: String get() = (this as? JsonPrimitive)?.content ?: toString()
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.arr(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(name: String, default: Boolean = false): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: default
