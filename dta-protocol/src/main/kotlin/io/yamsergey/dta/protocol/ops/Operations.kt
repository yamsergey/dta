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

private val STORE = ParamSpec("store", ParamType.STRING, "The store as data list shows it: db:<file>, prefs:<name>, datastore:<file>.", required = true, positional = true)
private val PASSPHRASE = ParamSpec("passphrase", ParamType.STRING, "SQLCipher passphrase, only for an encrypted database the app has not opened.")

object Catalog {
    /** Feature 040: the query line, shared by network.list and network.search. */
    private val QUERY_LINE_HELP =
        "Query line: key:value words filter (method:, status:, host:, url:, kind:, has:intervention, body:, run:, since:5m, until:; " +
        "bare GET/POST… and http/ws too; -key:value leaves matches out); other words and \"quoted phrases\" are searched, fuzzily " +
        "(case and separators ignored, one typo allowed in words of 5+ letters, numbers exact), in URLs, headers, bodies and message payloads."

    private val NETWORK_FILTERS = listOf(
        ParamSpec("url", ParamType.STRING, "Case-insensitive URL substring; WebSocket messages match by their connection's URL."),
        ParamSpec("method", ParamType.STRING, "HTTP method, case-insensitive."),
        ParamSpec("status", ParamType.STRING, "Exact code (200), class (4xx), failed or pending."),
        ParamSpec("kinds", ParamType.ENUM_LIST, "Entry kinds to include.", enumValues = listOf("http", "ws-connection", "ws-message")),
        ParamSpec("intervened", ParamType.BOOLEAN, "true: only entries the network script touched; false: only untouched ones."),
    )

    private val TIMELINE_SOURCES = ParamSpec(
        "sources", ParamType.STRING_LIST,
        "Sources to include: input, ui, screen, network, nav, app (gaps and process ends always come).",
    )

    private val TIMELINE_KINDS = ParamSpec(
        "kinds", ParamType.STRING_LIST, "Kinds to include, e.g. ui/toast, network/request, app/cart.removed.",
    )

    private val STATE_FACETS = ParamSpec(
        "facets", ParamType.STRING_LIST,
        "Facets that make a state, e.g. app.cart,nav.destination. Default: every available facet except nav.args and ui.windows.",
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
                ParamSpec("q", ParamType.STRING, "$QUERY_LINE_HELP Search words leave only the entries holding every word; the page's hits say where.", cliShort = "-q", cliName = "query"),
                ParamSpec("allRows", ParamType.BOOLEAN, "With search words: list every entry the filters keep, hits marked, instead of only the matches.", cliName = "all-rows"),
                ParamSpec("ignoreSavedFilters", ParamType.BOOLEAN, "Do not apply the app's saved filters to this call (they stay saved); by default they apply and the result says what they hid.", cliName = "no-saved-filters"),
            ),
        ),
        OperationSpec(
            "network.search",
            "Find where words occur in captured traffic: the entries holding every search word of the query, in list order, each with its hits — the place (url, request/response header, request/response body, payload, envelope), the matched span and a short excerpt. Use it to find where a value appeared without reading every body.",
            Scope.APP,
            listOf(
                ParamSpec("q", ParamType.STRING, "$QUERY_LINE_HELP At least one search word is needed.", required = true, positional = true),
                ParamSpec("limit", ParamType.INT, "Entries per page, 1..1000 (default 100)."),
                ParamSpec("order", ParamType.ENUM, "newest-first (default) or oldest-first.", enumValues = listOf("newest-first", "oldest-first")),
                ParamSpec("before", ParamType.STRING, "Cursor: the next value of a previous page."),
                ParamSpec("redact", ParamType.BOOLEAN, "Search URLs, headers and bodies as redacted: masked secrets are not found."),
                ParamSpec("archive", ParamType.STRING, "Search the archived history: all, version:<name>, <fromMs>..<toMs>, or an epoch id."),
                ParamSpec("ignoreSavedFilters", ParamType.BOOLEAN, "Do not apply the app's saved filters to this call (they stay saved); by default they apply and the result says what they hid.", cliName = "no-saved-filters"),
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
            "network.export",
            "Write the entries network.list selects (all pages, same filters) to a HAR 1.2 file that browsers' developer tools and proxies open: headers, timings, bodies (binary as base64), WebSocket connections with their messages. The result counts entries, messages and bodies, and bodies left out (each says why in the file).",
            Scope.APP,
            listOf(ParamSpec("output", ParamType.STRING, "The .har file to write.", required = true, path = true, cliShort = "-o")) + NETWORK_FILTERS + listOf(
                ParamSpec("sinceMs", ParamType.LONG, "Only entries at or after this epoch millisecond."),
                ParamSpec("untilMs", ParamType.LONG, "Only entries at or before this epoch millisecond."),
                ParamSpec("bodyContains", ParamType.STRING, "Case-sensitive text inside a body or payload."),
                ParamSpec("runs", ParamType.ENUM, "current: only the running process; all (default): earlier runs too.", enumValues = listOf("current", "all")),
                ParamSpec("redact", ParamType.BOOLEAN, "Mask secrets in URLs, headers and bodies; bodies that cannot be redacted are left out."),
                ParamSpec("archive", ParamType.STRING, "Archived history instead of the live one (as network.list)."),
                ParamSpec("q", ParamType.STRING, "Query line; its filters select what is written (search words hide nothing). See network list.", cliShort = "-q", cliName = "query"),
                ParamSpec("ignoreSavedFilters", ParamType.BOOLEAN, "Do not apply the app's saved filters to this call (they stay saved); by default they apply and the result says what they hid.", cliName = "no-saved-filters"),
            ),
        ),
        OperationSpec(
            "network.watch", "Follow new network entries as they happen, until interrupted. With history=current, the running process's entries come first.", Scope.APP,
            NETWORK_FILTERS + ParamSpec("history", ParamType.ENUM, "current: first the entries of the app's running process (e.g. its start-up traffic), then new ones; none (default): only new ones.", enumValues = listOf("current", "none")) +
                ParamSpec("q", ParamType.STRING, "Query line; its filters apply (search words hide nothing). See network list.", cliShort = "-q", cliName = "query") +
                ParamSpec("ignoreSavedFilters", ParamType.BOOLEAN, "Do not apply the app's saved filters to this call (they stay saved); by default they apply and the result says what they hid.", cliName = "no-saved-filters"),
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
            "network.ws.push",
            "Push a message into an open WebSocket connection of the app, as if the server sent it: the app's listener receives it through the library. " +
                "It is delivered on the library's reading thread when a frame arrives (DTA pings the server where the library allows), otherwise from DTA's thread after waitMs; " +
                "the history shows it as an incoming message marked injected. Blocks until delivered.",
            Scope.APP,
            listOf(
                ParamSpec("connection", ParamType.STRING, "WebSocket connection id (network_list, type ws-connection). Optional when the app has exactly one open connection."),
                ParamSpec("text", ParamType.STRING, "Text message.", positional = true),
                ParamSpec("base64", ParamType.STRING, "Binary message, base64. Give text or base64."),
                ParamSpec("waitMs", ParamType.LONG, "How long to wait for a frame on the library's reading thread before delivering from DTA's thread (default 1000; 0 delivers now)."),
            ),
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
            "network.format.add",
            "Say how the app's own WebSocket protocol is built, for the sockets a URL glob matches: a regex with named groups (kind, id, topic, payload) and/or field paths into JSON. " +
                "From then on network_list and network_entry give each matching text message an envelope (format = the rule's name, kind, id, topic, payload), before DTA's built-in formats. " +
                "Try it first with network.format.test.",
            Scope.APP,
            RULE_PARAMS,
        ),
        OperationSpec(
            "network.format.test",
            "Try a format rule on a connection's latest text messages without keeping it: how many it reads, a few envelopes it read, texts it did not read.",
            Scope.APP,
            RULE_PARAMS + ParamSpec("connection", ParamType.STRING, "ws-connection id from network_list; default the newest connection whose URL urlPattern matches."),
        ),
        OperationSpec("network.format.list", "List the app's WebSocket message format rules.", Scope.APP),
        OperationSpec(
            "network.format.remove", "Remove a format rule; its messages return to DTA's built-in formats.", Scope.APP,
            listOf(ParamSpec("id", ParamType.STRING, "Rule id from network.format.list.", required = true, positional = true)),
        ),
        OperationSpec(
            "network.filters.list",
            "The app's saved network filters and the ones it had before. Saved filters apply to every network list, search and export unless the call says ignoreSavedFilters; results say what they hid.",
            Scope.APP,
        ),
        OperationSpec(
            "network.filters.add",
            "Save filters for this app: words of the query language (-host:telemetry.example.test status:2xx). They survive restarts and apply to every client. A saved filter of the same key as a new one is replaced. run:, since: and until: belong to one session and are refused.",
            Scope.APP,
            listOf(ParamSpec("words", ParamType.STRING, "The filter words, separated by spaces.", required = true, positional = true)),
        ),
        OperationSpec(
            "network.filters.remove",
            "Stop applying a saved filter; it stays among the app's recent ones.",
            Scope.APP,
            listOf(ParamSpec("word", ParamType.STRING, "The filter word as network.filters.list gives it.", required = true, positional = true)),
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
            "timeline.events",
            "Everything the app did, in order, from every source: clicks and back, windows, dialogs and toasts, elements that came and went, network requests, navigation, screen changes and the app's own events. Each event lists its known causes. Pass since (the next cursor of a previous read) to get only what happened after it.",
            Scope.APP,
            listOf(
                ParamSpec("since", ParamType.STRING, "Cursor: the next value of a previous read; end: only the cursor after everything kept. Omit to start from the oldest kept event."),
                ParamSpec("sinceMs", ParamType.LONG, "Only events at or after this epoch millisecond (device clock)."),
                TIMELINE_SOURCES,
                TIMELINE_KINDS,
                ParamSpec("limit", ParamType.INT, "Page size, 1..500 (default 100)."),
            ),
        ),
        OperationSpec(
            "timeline.watch", "Follow the app's timeline as it happens, until interrupted. With history=current, the kept events come first.", Scope.APP,
            listOf(
                TIMELINE_SOURCES,
                TIMELINE_KINDS,
                ParamSpec("history", ParamType.ENUM, "current: first the kept events, then new ones; none (default): only new ones.", enumValues = listOf("current", "none")),
            ),
            streaming = true, cliOnly = true,
        ),
        OperationSpec(
            "timeline.causes",
            "Why an event happened, and what it led to. Each link says how it is known: exact (from recorded data, e.g. a request issued while a click was handled), declared (stated by the app), or inferred (from timing and matching data, only with inferred=true). Events without a known cause say so, with how many inferred candidates exist.",
            Scope.APP,
            listOf(
                ParamSpec("event", ParamType.STRING, "An event id from timeline events.", required = true, positional = true),
                ParamSpec("direction", ParamType.ENUM, "causes (default): back to the roots; effects: forward to what followed from it.", enumValues = listOf("causes", "effects")),
                ParamSpec("depth", ParamType.INT, "How many links to follow, 1..32 (default 8)."),
                ParamSpec("inferred", ParamType.BOOLEAN, "Also follow inferred links (timing and matching data); they are marked inferred."),
            ),
        ),
        OperationSpec(
            "state.current",
            "What the app is in now: every facet from every source that is on (the screen's structure, open windows, navigation, the app's own facets), with its source and when it changed, and each source's status.",
            Scope.APP, cliPathOverride = listOf("state"),
        ),
        OperationSpec(
            "state.graph",
            "The states the app went through in this run and the transitions between them, grouped by the facets you choose (fewer facets give coarser states). Each transition names the events that triggered it and their causes; transitions without a known cause are marked.",
            Scope.APP,
            listOf(
                STATE_FACETS,
                ParamSpec("minHoldMs", ParamType.LONG, "Merge states held for less than this into the next one (default 0)."),
                ParamSpec("inferred", ParamType.BOOLEAN, "Give inferred causes to transitions that have no known cause; they are marked inferred."),
            ),
            cliPathOverride = listOf("state", "graph"),
        ),
        OperationSpec(
            "state.path",
            "The shortest path the app was seen to take from one state to another (to the current state by default), with the events on each step.",
            Scope.APP,
            listOf(
                ParamSpec("from", ParamType.STRING, "A state id or label from state graph.", required = true),
                ParamSpec("to", ParamType.STRING, "A state id or label; the current state when omitted."),
                STATE_FACETS,
            ),
            cliPathOverride = listOf("state", "path"),
        ),
        // Feature 027: the app's stores — databases, preferences, DataStore, files.
        OperationSpec(
            "data.list",
            "The app's databases (SQLite, Room, SQLCipher), SharedPreferences (plain and encrypted), DataStore files and file roots, each with how DTA reaches it: live (through the app's own open object: no key needed, the app sees writes at once), file (DTA opens it), or unreadable (why, and what to do).",
            Scope.APP,
        ),
        OperationSpec(
            "data.db.schema",
            "A database's tables, views, indexes and triggers with columns, primary keys and row counts.",
            Scope.APP,
            listOf(STORE, PASSPHRASE),
        ),
        OperationSpec(
            "data.db.query",
            "Run a query (SELECT, PRAGMA, WITH …) on a database; rows come in pages with the total. Blobs show size and their first bytes unless blobs=full.",
            Scope.APP,
            listOf(
                STORE,
                ParamSpec("sql", ParamType.STRING, "The query.", required = true, positional = true),
                ParamSpec("args", ParamType.STRING_LIST, "Values for ? placeholders, in order (bound as text; SQLite converts by column affinity)."),
                ParamSpec("offset", ParamType.INT, "First row of the page (default 0)."),
                ParamSpec("limit", ParamType.INT, "Rows per page (default 100)."),
                ParamSpec("blobs", ParamType.ENUM, "prefix (default): size and first 32 bytes as hex; full: whole blobs as base64.", enumValues = listOf("prefix", "full")),
                PASSPHRASE,
            ),
        ),
        OperationSpec(
            "data.db.exec",
            "Run a statement that changes a database (INSERT, UPDATE, DELETE, CREATE, …); the result says how many rows changed. Through the app's open Room database, Room's observers are told so the app's screens follow.",
            Scope.APP,
            listOf(
                STORE,
                ParamSpec("sql", ParamType.STRING, "The statement.", required = true, positional = true),
                ParamSpec("args", ParamType.STRING_LIST, "Values for ? placeholders, in order (bound as text)."),
                PASSPHRASE,
            ),
        ),
        OperationSpec(
            "data.prefs.read",
            "All keys of a SharedPreferences file (encrypted ones decrypted) or a Preferences DataStore, with types and values.",
            Scope.APP,
            listOf(STORE),
        ),
        OperationSpec(
            "data.prefs.set",
            "Set one key of a SharedPreferences file or Preferences DataStore to a typed value, through the app's own instance when it has one (its listeners and flows fire).",
            Scope.APP,
            listOf(
                STORE,
                ParamSpec("key", ParamType.STRING, "The key.", required = true, positional = true),
                ParamSpec("value", ParamType.STRING, "The value as text (stringSet: a JSON array of strings; bytes: base64).", required = true, positional = true),
                ParamSpec("type", ParamType.ENUM, "The value's type; by default the key's current type, else string.", enumValues = listOf("string", "int", "long", "float", "boolean", "stringSet", "double", "bytes")),
            ),
        ),
        OperationSpec(
            "data.prefs.remove",
            "Remove keys from a SharedPreferences file or Preferences DataStore.",
            Scope.APP,
            listOf(STORE, ParamSpec("keys", ParamType.STRING_LIST, "The keys.", required = true, positional = true)),
        ),
        OperationSpec(
            "data.proto.read",
            "A Proto DataStore's message, decoded by field number, or by a message type of the app's protobuf schemas (network schema add/sync).",
            Scope.APP,
            listOf(STORE, ParamSpec("type", ParamType.STRING, "Message type (full name) to decode with.")),
        ),
        OperationSpec(
            "data.proto.write",
            "Replace a Proto DataStore's message with JSON encoded by a message type of the app's protobuf schemas, through the app's own DataStore when it has one.",
            Scope.APP,
            listOf(
                STORE,
                ParamSpec("type", ParamType.STRING, "Message type (full name).", required = true),
                ParamSpec("json", ParamType.STRING, "The message as JSON (proto3 JSON names).", required = true, positional = true),
            ),
        ),
        OperationSpec(
            "data.files.list",
            "One folder of the app's files: names, sizes, modified times; folders with their entry counts. Paths are data:/… (the app's private folder) or external:/… (its app-specific external folder).",
            Scope.APP,
            listOf(
                ParamSpec("path", ParamType.STRING, "The folder (default data:/).", positional = true),
                ParamSpec("offset", ParamType.INT, "First entry (default 0)."),
                ParamSpec("limit", ParamType.INT, "Entries per page (default 500)."),
            ),
        ),
        OperationSpec(
            "data.files.read",
            "A file of the app: text inline when short, otherwise written to output on this machine.",
            Scope.APP,
            listOf(
                ParamSpec("path", ParamType.STRING, "The file (data:/… or external:/…).", required = true, positional = true),
                ParamSpec("output", ParamType.STRING, "Write the file here; the result gives size and sha256.", path = true, cliShort = "-o"),
            ),
        ),
        OperationSpec(
            "data.files.write",
            "Write a file of the app (created or replaced, folders made as needed) from text, base64 or a file on this machine.",
            Scope.APP,
            listOf(
                ParamSpec("path", ParamType.STRING, "The file (data:/… or external:/…).", required = true, positional = true),
                ParamSpec("text", ParamType.STRING, "Text content (UTF-8)."),
                ParamSpec("base64", ParamType.STRING, "Binary content, base64."),
                ParamSpec("input", ParamType.STRING, "A file on this machine to copy.", path = true, cliShort = "-i"),
            ),
        ),
        OperationSpec(
            "data.files.mkdir",
            "Create a folder of the app (and its parents).",
            Scope.APP,
            listOf(ParamSpec("path", ParamType.STRING, "The folder (data:/… or external:/…).", required = true, positional = true)),
        ),
        OperationSpec(
            "data.files.delete",
            "Delete a file or a folder with everything in it.",
            Scope.APP,
            listOf(ParamSpec("path", ParamType.STRING, "The file or folder (data:/… or external:/…).", required = true, positional = true)),
        ),
        // Feature 028: what the running process holds.
        OperationSpec("runtime.activities", "The app's activities: class, lifecycle state, task, focus, intent, and their fragments with state, tag and arguments.", Scope.APP),
        OperationSpec(
            "runtime.navigation",
            "Every navigation host of the app: AndroidX Navigation controllers (Compose and fragment NavHost) with their back stacks (route, arguments, lifecycle) and graphs (destinations, routes, arguments, deep links, start), and Navigation 3 back stacks.",
            Scope.APP,
        ),
        OperationSpec(
            "runtime.navigate",
            "Navigate a NavController to a route (product/42) or a deep link URI. With one controller it is chosen; otherwise give host from runtime.navigation.",
            Scope.APP,
            listOf(
                ParamSpec("route", ParamType.STRING, "The route to navigate to.", positional = true),
                ParamSpec("uri", ParamType.STRING, "A deep link to navigate to instead of a route."),
                ParamSpec("host", ParamType.STRING, "The host id from runtime.navigation."),
            ),
        ),
        OperationSpec(
            "runtime.back",
            "Go back one entry on a navigation host (popBackStack, or the top key off a Navigation 3 stack).",
            Scope.APP,
            listOf(ParamSpec("host", ParamType.STRING, "The host id from runtime.navigation (optional with one host).")),
        ),
        OperationSpec(
            "runtime.viewmodels",
            "The app's live ViewModels: class, key, owner, and their properties (StateFlow, LiveData, Compose State, fields) with values as JSON, and SavedStateHandle entries; editable ones are marked.",
            Scope.APP,
            listOf(ParamSpec("filter", ParamType.STRING, "Only ViewModels whose class name contains this.", positional = true)),
        ),
        OperationSpec(
            "runtime.vm.set",
            "Set a ViewModel property to a JSON value: a MutableStateFlow, MutableLiveData, MutableState or var field, or a field inside a data-class value (path state.coupon), rebuilt with copy. The app's observers see it.",
            Scope.APP,
            listOf(
                ParamSpec("viewModel", ParamType.STRING, "The ViewModel id from runtime.viewmodels.", required = true, positional = true),
                ParamSpec("path", ParamType.STRING, "The property path (as runtime.viewmodels lists it).", required = true, positional = true),
                ParamSpec("value", ParamType.STRING, "The value as JSON (\"text\", 3, true, null, {…}).", required = true, positional = true),
            ),
        ),
        OperationSpec(
            "runtime.saved.set",
            "Set a key of a ViewModel's SavedStateHandle to a JSON value.",
            Scope.APP,
            listOf(
                ParamSpec("viewModel", ParamType.STRING, "The ViewModel id from runtime.viewmodels.", required = true, positional = true),
                ParamSpec("key", ParamType.STRING, "The key.", required = true, positional = true),
                ParamSpec("value", ParamType.STRING, "The value as JSON.", required = true, positional = true),
            ),
        ),
        OperationSpec(
            "runtime.memory",
            "The app's memory: Java heap used and max, native heap, PSS, garbage collections.",
            Scope.APP,
            listOf(ParamSpec("gc", ParamType.BOOLEAN, "Run a garbage collection first.")),
        ),
        OperationSpec(
            "runtime.threads",
            "The app's threads: name, state, daemon, priority, group, and stacks.",
            Scope.APP,
            listOf(ParamSpec("stacks", ParamType.BOOLEAN, "Include each thread's stack (default true)."), ParamSpec("filter", ParamType.STRING, "Only threads whose name contains this.")),
        ),
        OperationSpec(
            "runtime.di",
            "The app's dependency-injection graphs — Hilt components (and what Hilt injected into live activities and ViewModels), Metro graphs, Dagger components, Koin — each binding's type, kind (singleton, factory, scoped, instance, injected, assisted), qualifier, the runtime class of its instance and whether it was created yet. Nothing is created by reading.",
            Scope.APP,
            listOf(ParamSpec("filter", ParamType.STRING, "Only graphs or bindings whose framework, graph name or type contains this.", positional = true)),
        ),
        OperationSpec(
            "runtime.functions",
            "Functions the app registered with dta-state (DtaState.function) — invocable — and AndroidX AppFunctions declared in the APK.",
            Scope.APP,
        ),
        OperationSpec(
            "runtime.invoke",
            "Invoke a function the app registered with dta-state, with string arguments; returns its result.",
            Scope.APP,
            listOf(
                ParamSpec("id", ParamType.STRING, "The function id from runtime.functions.", required = true, positional = true),
                ParamSpec("args", ParamType.STRING_MAP, "Arguments by name."),
            ),
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

/** Feature 039: what a WebSocket message format rule takes (network.format.add and network.format.test). */
private val RULE_PARAMS = listOf(
                ParamSpec("urlPattern", ParamType.STRING, "Glob over the socket's URL; * is any run of characters (wss://api.example.test/live*).", required = true),
                ParamSpec("name", ParamType.STRING, "Shown as the messages' format (default custom)."),
                ParamSpec("pattern", ParamType.STRING, "Regex over the whole message with named groups kind, id, topic, payload, e.g. ^(?<id>\\d+)\\|(?<kind>\\w+)\\|(?<payload>.*)$ for 42|ORDER_UPDATE|{...}."),
                ParamSpec("kind", ParamType.STRING, "Field path of the kind in the JSON payload (or the whole message without a pattern), e.g. h.op."),
                ParamSpec("idPath", ParamType.STRING, "Field path of the request/message id, e.g. h.rid; a reply with the same id is paired with its request."),
                ParamSpec("topic", ParamType.STRING, "Field path of the topic or channel, e.g. h.ch[0]."),
                ParamSpec("payload", ParamType.STRING, "Field path of the payload, e.g. b."),
                ParamSpec("payloadFormat", ParamType.ENUM, "json (default) or text.", enumValues = listOf("json", "text")),
                ParamSpec("direction", ParamType.ENUM, "Only messages sent (out) or received (in).", enumValues = listOf("out", "in")),
            )
