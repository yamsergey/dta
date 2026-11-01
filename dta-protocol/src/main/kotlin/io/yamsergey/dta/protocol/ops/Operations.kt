package io.yamsergey.dta.protocol.ops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The ONE list of what DTA can do (feature 008, research R6). The daemon's
 * dispatcher, the `dta` CLI commands and the `dta mcp` tools are all derived
 * from it, so the three cannot drift (constitution VI, FR-022/023).
 */
enum class ParamType { STRING, INT, LONG, BOOLEAN, ENUM, STRING_LIST, ENUM_LIST, STRING_MAP }

/** How the device and app of an operation are chosen (FR-010). */
enum class Scope {
    /** Neither. */
    NONE,

    /** A device; a single connected one is chosen when omitted. */
    DEVICE,

    /** A device and an app with DTA inside; the foreground app is chosen when omitted. */
    APP,

    /** Layout: like [APP], but without DTA in the app the system fallback captures the screen. */
    APP_OR_FALLBACK,
}

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
    /** The CLI takes it as a positional argument instead of an option. */
    val positional: Boolean = false,
    /** A path on this machine: the CLI makes it absolute before sending (the daemon's cwd differs). */
    val path: Boolean = false,
    /** Short CLI alias (`-o`). */
    val cliShort: String? = null,
    /** The CLI option's name instead of the one derived from [name] (`--screen` for `screenOutput`). */
    val cliName: String? = null,
    /** Not on the command line: its value would not print (an inline image). Agents and the plugin still get it. */
    val cliHidden: Boolean = false,
)

data class OperationSpec(
    val name: String,
    val description: String,
    val scope: Scope,
    val params: List<ParamSpec> = emptyList(),
    /** Streams events until cancelled (CLI only). */
    val streaming: Boolean = false,
    /** Not offered to agents. */
    val cliOnly: Boolean = false,
    private val cliPathOverride: List<String>? = null,
) {
    val toolName: String get() = name.replace('.', '_')
    val cliPath: List<String> get() = cliPathOverride ?: name.split('.')

    fun param(name: String): ParamSpec? = params.firstOrNull { it.name == name }

    /** JSON Schema of the tool input: the params plus `device`/`app` per [scope]. */
    fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            if (scope != Scope.NONE) {
                putJsonObject("device") {
                    put("type", "string")
                    put("description", "Device serial. Omit when one device is connected.")
                }
            }
            if (scope == Scope.APP || scope == Scope.APP_OR_FALLBACK) {
                putJsonObject("app") {
                    put("type", "string")
                    put("description", "Package name. Omit to use the foreground app.")
                }
            }
            params.forEach { p -> put(p.name, p.schema()) }
        }
        val required = params.filter { it.required }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it.name) } }
        put("additionalProperties", false)
    }

    private fun ParamSpec.schema(): JsonObject = buildJsonObject {
        when (type) {
            ParamType.STRING -> put("type", "string")
            ParamType.INT, ParamType.LONG -> put("type", "integer")
            ParamType.BOOLEAN -> put("type", "boolean")
            ParamType.ENUM -> {
                put("type", "string")
                putJsonArray("enum") { enumValues.forEach { add(it) } }
            }
            ParamType.STRING_LIST -> {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            ParamType.ENUM_LIST -> {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "string")
                    putJsonArray("enum") { enumValues.forEach { add(it) } }
                }
            }
            ParamType.STRING_MAP -> {
                put("type", "object")
                putJsonObject("additionalProperties") { put("type", "string") }
            }
        }
        put("description", description)
    }
}

@Serializable
enum class DeviceRule {
    @SerialName("named") NAMED,
    @SerialName("only-device") ONLY_DEVICE,
}

@Serializable
enum class AppRule {
    @SerialName("named") NAMED,
    @SerialName("foreground") FOREGROUND,
}

/** How the device and app of a result were chosen (data-model). Present whenever either was involved. */
@Serializable
data class SelectionStatement(
    val device: String,
    val deviceRule: DeviceRule,
    val app: String? = null,
    val appRule: AppRule? = null,
    val note: String? = null,
)

/** Envelope of every operation result, identical in CLI `--json` and MCP output. */
@Serializable
data class Selected(
    val selection: SelectionStatement? = null,
    val result: JsonElement,
)

/** Error codes of the client interface (contract daemon-protocol). */
object ClientErrorCodes {
    const val NO_DEVICE = -32010
    const val SEVERAL_DEVICES = -32011
    const val NO_APP = -32012
    const val NOT_FOUND = -32013
    const val NOT_AVAILABLE = -32014
}

object Catalog {
    private val NETWORK_FILTERS = listOf(
        ParamSpec("url", ParamType.STRING, "Case-insensitive URL substring; WebSocket messages match by their connection's URL."),
        ParamSpec("method", ParamType.STRING, "HTTP method, case-insensitive."),
        ParamSpec("status", ParamType.STRING, "Exact code (200), class (4xx), failed or pending."),
        ParamSpec("kinds", ParamType.ENUM_LIST, "Entry kinds to include.", enumValues = listOf("http", "ws-connection", "ws-message")),
        ParamSpec("intervened", ParamType.BOOLEAN, "true: only entries the network script touched; false: only untouched ones."),
    )

    private val UI_KINDS = ParamSpec(
        "kinds", ParamType.ENUM_LIST, "Event kinds to include.",
        enumValues = listOf("window", "toast", "element", "screen-window", "gap", "process"),
    )

    private val PART = ParamSpec(
        "part", ParamType.ENUM,
        "Which body: request, response or message; app-* is what the app saw when a script changed it. Default response (message for WebSocket messages).",
        enumValues = listOf("request", "response", "message", "app-request", "app-response", "app-message"),
    )

    val all: List<OperationSpec> = listOf(
        OperationSpec(
            "devices.list", "List connected Android devices with their state (online, offline, unauthorized).",
            Scope.NONE, cliPathOverride = listOf("devices"),
        ),
        OperationSpec(
            "apps.list", "List apps on the device that have DTA inside and are running, and which app is in the foreground.",
            Scope.DEVICE, cliPathOverride = listOf("apps"),
        ),
        OperationSpec(
            "apps.watch",
            "Follow apps with DTA connecting, losing their link, being refused or ending, until interrupted. Every device when none is given; the app option narrows it to one app.",
            Scope.DEVICE, streaming = true, cliOnly = true, cliPathOverride = listOf("apps", "watch"),
        ),
        OperationSpec(
            "capability.report", "What DTA can see in the app: layout, network and script capabilities and each network library, with the reason when unavailable.",
            Scope.APP, cliPathOverride = listOf("capability"),
        ),
        OperationSpec(
            "layout.tree",
            "Capture the screen as a tree of windows and nodes (Views and Compose in one tree). Without DTA in the app the system fallback captures it and the result says so. Filters return matching nodes with their ancestors.",
            Scope.APP_OR_FALLBACK,
            listOf(
                ParamSpec("detail", ParamType.ENUM, "Compose detail: semantic (default), layout (adds containers) or composition (adds call chains).", enumValues = listOf("semantic", "layout", "composition")),
                ParamSpec("frameworkFrames", ParamType.BOOLEAN, "Include framework composables in call chains (composition detail)."),
                ParamSpec("text", ParamType.STRING, "Match nodes whose text, content description or hint contains this (case-insensitive)."),
                ParamSpec("type", ParamType.STRING, "Match by simple class name (View) or composable name (Compose)."),
                ParamSpec("resourceId", ParamType.STRING, "Match by full resource id (android:id/content)."),
                ParamSpec("rootId", ParamType.LONG, "Only search the subtree of this node id."),
                ParamSpec("screen", ParamType.BOOLEAN, "Also take the screen image and the windows on screen, with statements when the picture may not match the tree (secure window, lock screen, a change during the capture).", cliHidden = true),
                ParamSpec("screenOutput", ParamType.STRING, "Also take the screen and the windows on screen, writing the image (PNG) to this file; the result gives path, size and sha256.", path = true, cliName = "screen"),
            ),
            cliPathOverride = listOf("layout"),
        ),
        OperationSpec(
            "network.list",
            "List captured network traffic: HTTP exchanges, WebSocket connections and messages in one chronological list, with filters. Use sinceMs to fetch only what happened after a point in time.",
            Scope.APP,
            NETWORK_FILTERS + listOf(
                ParamSpec("sinceMs", ParamType.LONG, "Only entries at or after this epoch millisecond."),
                ParamSpec("untilMs", ParamType.LONG, "Only entries at or before this epoch millisecond."),
                ParamSpec("bodyContains", ParamType.STRING, "Case-sensitive text inside a body or payload; also searched inside binary bodies."),
                ParamSpec("runs", ParamType.ENUM, "current: only the running process; all (default): earlier runs too.", enumValues = listOf("current", "all")),
                ParamSpec("limit", ParamType.INT, "Page size, 1..1000 (default 100)."),
                ParamSpec("order", ParamType.ENUM, "newest-first (default) or oldest-first.", enumValues = listOf("newest-first", "oldest-first")),
                ParamSpec("before", ParamType.STRING, "Cursor: the next value of a previous page."),
                ParamSpec("redact", ParamType.BOOLEAN, "Mask secrets (tokens, cookies, passwords) in URLs and headers."),
                ParamSpec(
                    "archive", ParamType.STRING,
                    "Archived history instead of the live one (what the device cleared, or earlier installs): all, version:<name>, <fromMs>..<toMs>, or an epoch id from archive list. Entries come marked with their epoch.",
                ),
            ),
        ),
        OperationSpec(
            "network.entry", "One entry in full: request and response headers, timing, initiator stack, script intervention, body summaries; for a WebSocket connection its messages.",
            Scope.APP,
            listOf(
                ParamSpec("id", ParamType.STRING, "Entry id from network.list.", required = true, positional = true),
                ParamSpec("redact", ParamType.BOOLEAN, "Mask secrets in URLs and headers."),
            ),
        ),
        OperationSpec(
            "network.body",
            "One body as text (protobuf decoded to JSON where a schema applies) or written to a file. Binary bodies that do not decode need output.",
            Scope.APP,
            listOf(
                ParamSpec("id", ParamType.STRING, "Entry id from network.list.", required = true, positional = true),
                PART,
                ParamSpec("output", ParamType.STRING, "Write the body to this file instead of returning text; the result gives path, size and sha256.", path = true, cliShort = "-o"),
                ParamSpec("raw", ParamType.BOOLEAN, "With output: keep the bytes as sent on the wire (compressed), instead of decoded."),
                ParamSpec("structured", ParamType.BOOLEAN, "With output: write the decoded JSON of a protobuf body instead of its bytes."),
                ParamSpec("redact", ParamType.BOOLEAN, "Mask secrets inside the body (decoded protobuf included); bodies that cannot be redacted are withheld."),
                ParamSpec("allowUnredacted", ParamType.BOOLEAN, "With redact: return a body that cannot be redacted anyway, saying so."),
            ),
        ),
        OperationSpec("network.clear", "Delete the app's captured network history.", Scope.APP),
        OperationSpec(
            "network.watch", "Follow new network entries as they happen, until interrupted. With history=current, the running process's entries come first.", Scope.APP,
            NETWORK_FILTERS + ParamSpec("history", ParamType.ENUM, "current: first the entries of the app's running process (e.g. its start-up traffic), then new ones; none (default): only new ones.", enumValues = listOf("current", "none")),
            streaming = true, cliOnly = true,
        ),
        OperationSpec(
            "network.script.set",
            "Install the app's network script (JavaScript with request/response/ws handlers that can delay, rewrite, answer or drop traffic, and say what they did with description(text), a short line stored on the entry). It persists until cleared. Give source or path.",
            Scope.APP,
            listOf(
                ParamSpec("source", ParamType.STRING, "Script source text."),
                ParamSpec("path", ParamType.STRING, "Script file on this machine.", positional = true, path = true),
            ),
        ),
        OperationSpec("network.script.clear", "Remove the app's network script; traffic flows untouched again.", Scope.APP),
        OperationSpec("network.script.status", "Whether a network script is active, its handlers and its last error.", Scope.APP),
        OperationSpec(
            "network.script.logs", "Log lines the network script wrote.", Scope.APP,
            listOf(ParamSpec("since", ParamType.LONG, "Only lines after this sequence number (the next value of a previous call).")),
        ),
        OperationSpec(
            "network.schema.add",
            "Add (or replace) a protobuf schema set for the app, from .proto sources (no protoc needed) or a compiled descriptor set, so its binary bodies decode to JSON. Give paths or sources.",
            Scope.APP,
            listOf(
                ParamSpec("name", ParamType.STRING, "Name of the schema set.", required = true, positional = true),
                ParamSpec("paths", ParamType.STRING_LIST, ".proto files, directories of them, or one descriptor-set file on this machine.", positional = true, path = true),
                ParamSpec("sources", ParamType.STRING_MAP, ".proto sources by relative path (path → text), for imports to resolve."),
            ),
        ),
        OperationSpec(
            "network.schema.sync",
            "Take the app's protobuf schemas from a project: every .proto file under the folder (src/*/proto roots resolve imports across modules), as one set replacing what this folder gave before. Then add mappings from the code with network.mapping.add (which endpoint sends or returns which message type). Reports files that do not compile and message types nothing maps yet.",
            Scope.APP,
            listOf(
                ParamSpec("folder", ParamType.STRING, "The project folder on this machine, usually the repository root.", positional = true, path = true),
                ParamSpec("sources", ParamType.STRING_MAP, ".proto sources by import path (path → text), when a client read them itself (the IDE: dependencies' protos)."),
                ParamSpec("origin", ParamType.STRING, "With sources: the project they came from (its path)."),
                ParamSpec("mappings", ParamType.STRING, "With sources: derived mappings as JSON (the IDE: from Retrofit declarations), each with its code location."),
            ),
        ),
        OperationSpec("network.schema.list", "List the app's protobuf schema sets and their message types.", Scope.APP),
        OperationSpec(
            "network.schema.remove", "Remove a schema set; the result lists mappings that used it and are now broken.", Scope.APP,
            listOf(ParamSpec("name", ParamType.STRING, "Name of the schema set.", required = true, positional = true)),
        ),
        OperationSpec(
            "network.mapping.add",
            "Map a protobuf message type to traffic: which bodies (URL glob, part, optional method or direction) decode with which type.",
            Scope.APP,
            listOf(
                ParamSpec("urlPattern", ParamType.STRING, "Glob over the full URL; * is any run of characters.", required = true),
                ParamSpec("part", ParamType.ENUM, "request, response or message.", required = true, enumValues = listOf("request", "response", "message")),
                ParamSpec("schema", ParamType.STRING, "Schema set name.", required = true),
                ParamSpec("type", ParamType.STRING, "Fully qualified message type.", required = true),
                ParamSpec("method", ParamType.STRING, "HTTP method (request and response parts only)."),
                ParamSpec("direction", ParamType.ENUM, "Message direction (message part only).", enumValues = listOf("out", "in")),
            ),
        ),
        OperationSpec("network.mapping.list", "List the app's mappings (with the reason when broken), then the built-in OTLP rules.", Scope.APP),
        OperationSpec(
            "network.mapping.remove", "Remove a mapping; its bodies return as raw bytes.", Scope.APP,
            listOf(ParamSpec("id", ParamType.STRING, "Mapping id from network.mapping.list.", required = true, positional = true)),
        ),
        OperationSpec(
            "links.list",
            "Links (deep links, App Links, any intent with a URI) that reached an activity of the app: the URI, how it was delivered (cold-start, created, new-intent, from-recents), the activity, who sent it, the extras. Newest first, from the same history as the network traffic (runs, archive).",
            Scope.APP,
            listOf(
                ParamSpec("uri", ParamType.STRING, "Case-insensitive substring of the URI."),
                ParamSpec("activity", ParamType.STRING, "Case-insensitive substring of the receiving activity's class."),
                ParamSpec("delivery", ParamType.ENUM, "How it reached the activity.", enumValues = listOf("cold-start", "created", "new-intent", "from-recents")),
                ParamSpec("runs", ParamType.ENUM, "current: only the running process; all (default): earlier runs too.", enumValues = listOf("current", "all")),
                ParamSpec("sinceMs", ParamType.LONG, "Only links at or after this epoch millisecond."),
                ParamSpec("limit", ParamType.INT, "Page size, 1..1000 (default 100)."),
                ParamSpec("before", ParamType.STRING, "Cursor: the next value of a previous page."),
                ParamSpec("archive", ParamType.STRING, "Archived history instead of the live one: all, version:<name>, <fromMs>..<toMs>, or an epoch id."),
                ParamSpec("redact", ParamType.BOOLEAN, "Mask secrets in URIs, query parameters and extras."),
            ),
            cliPathOverride = listOf("links", "list"),
        ),
        OperationSpec(
            "links.entry",
            "One link that reached the app, in full: URI parts, action, categories, flags, every extra (or why it could not be read), the activity, delivery and referrer.",
            Scope.APP,
            listOf(
                ParamSpec("id", ParamType.STRING, "The link's id from links list.", required = true, positional = true),
                ParamSpec("redact", ParamType.BOOLEAN, "Mask secrets in the URI, query parameters and extras."),
            ),
            cliPathOverride = listOf("links", "entry"),
        ),
        OperationSpec(
            "links.open",
            "Open a URI on the device (ACTION_VIEW) and say who received it: the app (with the link's record), another app, the system's chooser, or nothing, with why. For an https link the app did not get, its App Links state explains it.",
            Scope.DEVICE,
            listOf(
                ParamSpec("uri", ParamType.STRING, "The URI to open, e.g. myapp://item/42 or https://example.com/item/42.", required = true, positional = true),
                ParamSpec("package", ParamType.STRING, "The app it is meant for: its record is awaited, and its App Links state explains an https link it did not get."),
                ParamSpec("only", ParamType.BOOLEAN, "Only that app may open it (skips the chooser and App Links verification)."),
            ),
            cliPathOverride = listOf("links", "open"),
        ),
        OperationSpec(
            "links.verification",
            "The app's App Links: every host it declares for automatic verification, whether Android verified it (and why not), and whether the user allowed the app to open it. Why an https link opens in the browser instead of the app.",
            Scope.APP,
            cliPathOverride = listOf("links", "verification"),
        ),
        OperationSpec(
            "ui.events",
            "The app's journal of short-lived UI: windows that appeared and closed (dialogs, popups, bottom sheets), toasts, elements that came and went inside a window, and windows that are not the app's (permission prompts, system dialogs). Pass since (the next cursor of a previous read) to get only what happened after it.",
            Scope.APP,
            listOf(
                ParamSpec("since", ParamType.STRING, "Cursor: the next value of a previous read. Omit to start from the oldest kept event."),
                ParamSpec("sinceMs", ParamType.LONG, "Only events at or after this epoch millisecond (device clock)."),
                UI_KINDS,
                ParamSpec("limit", ParamType.INT, "Page size, 1..500 (default 100)."),
            ),
        ),
        OperationSpec(
            "ui.watch", "Follow the app's UI journal as it happens, until interrupted. With history=current, the kept events come first.", Scope.APP,
            listOf(
                UI_KINDS,
                ParamSpec("history", ParamType.ENUM, "current: first the kept events, then new ones; none (default): only new ones.", enumValues = listOf("current", "none")),
            ),
            streaming = true, cliOnly = true,
        ),
        OperationSpec(
            "archive.list",
            "The network history this computer keeps after devices forgot it (cleared, data wiped, reinstalled): devices › apps › installs › epochs, with dates, why each epoch closed, entry counts and size on disk.",
            Scope.NONE,
        ),
        OperationSpec(
            "archive.delete",
            "Delete archived network history: one epoch, one install, an app's whole archive, or everything closed before a time. Without confirm it only says what it would remove. The live history is never deleted here: clearing it on the device archives it.",
            Scope.NONE,
            listOf(
                ParamSpec("epoch", ParamType.STRING, "An epoch id from archive list."),
                ParamSpec("installation", ParamType.LONG, "An install id from archive list."),
                ParamSpec("packageName", ParamType.STRING, "An app's whole archive, on every device."),
                ParamSpec("closedBeforeMs", ParamType.LONG, "Every epoch closed before this epoch millisecond."),
                ParamSpec("confirm", ParamType.BOOLEAN, "Delete for real; without it the answer only says what would go."),
            ),
        ),
        OperationSpec(
            "daemon.status", "The DTA service: version, uptime, adb, connected clients, devices and open app connections.", Scope.NONE,
        ),
        OperationSpec("daemon.stop", "Stop the DTA service (it starts again with the next command).", Scope.NONE, cliOnly = true),
    )

    private val byName = all.associateBy { it.name }

    operator fun get(name: String): OperationSpec = byName[name] ?: error("no operation $name")

    fun find(name: String): OperationSpec? = byName[name]

    val agentTools: List<OperationSpec> get() = all.filterNot { it.cliOnly }
}
