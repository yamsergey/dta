package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.network.proto.DerivedMappingWire
import io.yamsergey.dta.daemon.network.proto.OriginKind
import io.yamsergey.dta.daemon.network.proto.ProtoDiscovery
import io.yamsergey.dta.daemon.network.proto.SchemaOrigin
import io.yamsergey.dta.daemon.network.proto.SyncResult
import io.yamsergey.dta.daemon.store.DeleteTarget
import io.yamsergey.dta.daemon.store.LiveEpochException
import io.yamsergey.dta.protocol.network.ArchiveDeletion
import io.yamsergey.dta.protocol.network.ArchiveListing
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.daemon.AdbUnavailableException
import io.yamsergey.dta.daemon.connection.ConnectException
import io.yamsergey.dta.daemon.connection.ConnectionLostException
import io.yamsergey.dta.daemon.connection.RequestTimeoutException
import io.yamsergey.dta.daemon.connection.SidekickRpcException
import io.yamsergey.dta.daemon.network.BinaryBodyException
import io.yamsergey.dta.daemon.network.BodyNotDecodedException
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.MappingPart
import io.yamsergey.dta.daemon.network.proto.SchemaException
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.OperationSpec
import io.yamsergey.dta.protocol.ops.ParamType
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Runs catalog operations against the library (feature 008): validates params against the
 * catalog, resolves device and app through the [Selector], calls [ops], wraps the result in
 * [Selected], and turns library failures into [ClientException]s that name the next step.
 */
class OperationDispatcher(
    private val ops: DaemonOperations,
    private val selector: Selector,
    /** Names DTA in exported files (HAR creator). */
    private val version: String = "dev",
) {
    /** What one handler sees: resolved target and validated params. */
    private class Call(val serial: String?, val pkg: String?, val params: JsonObject) {
        val device: String get() = serial!!
        val app: String get() = pkg!!

        fun string(name: String): String? = (params[name] as? JsonPrimitive)?.content
        fun bool(name: String): Boolean = (params[name] as? JsonPrimitive)?.booleanOrNull ?: false
        fun long(name: String): Long? = (params[name] as? JsonPrimitive)?.longOrNull
        fun strings(name: String): List<String>? = (params[name] as? JsonArray)?.map { (it as JsonPrimitive).content }
        fun required(name: String): String = string(name) ?: throw InvalidParamsException("missing required parameter '$name'")

        /** The operation's own params, for a sidekick method that takes them as they are (027). */
        fun forward(): JsonObject = JsonObject(params.filterKeys { it != "device" && it != "app" })
    }

    private val handlers: Map<String, suspend (Call) -> JsonElement> = mapOf(
        "devices.list" to { _ -> encode(ListSerializer(DeviceInfo.serializer()), ops.devices()) },
        "apps.list" to { c -> encode(AppList.serializer(), ops.apps(c.device)) },
        "capability.report" to { c -> encode(IdentityReport.serializer(), ops.capabilityReport(c.device, c.app)) },
        "layout.tree" to { c -> encode(LayoutCapture.serializer(), layout(c)) },
        "network.list" to { c -> encode(NetworkPage.serializer(), ops.network(c.device, c.app, query(c.params))) },
        "network.entry" to { c -> encode(NetworkEntryDetail.serializer(), ops.networkEntry(c.device, c.app, c.required("id"), c.bool("redact"))) },
        "network.body" to { c -> encode(BodyResult.serializer(), body(c)) },
        "network.clear" to { c -> encode(ClearResult.serializer(), ops.networkClear(c.device, c.app)) },
        "network.export" to { c -> encode(io.yamsergey.dta.protocol.network.NetworkExportResult.serializer(), export(c)) },
        "network.script.set" to { c -> encode(ScriptStatus.serializer(), ops.networkScriptSet(c.device, c.app, scriptSource(c))) },
        "network.script.clear" to { c -> buildJsonObject { put("cleared", ops.networkScriptClear(c.device, c.app)) } },
        "network.script.status" to { c -> encode(ScriptStatus.serializer(), ops.networkScriptStatus(c.device, c.app)) },
        "network.script.logs" to { c -> encode(ScriptLogPage.serializer(), ops.networkScriptLogs(c.device, c.app, c.long("since") ?: 0)) },
        "network.ws.push" to { c ->
            encode(io.yamsergey.dta.protocol.network.WsPushResult.serializer(), ops.networkWsPush(c.device, c.app, c.string("connection"), c.string("text"), c.string("base64"), c.long("waitMs")))
        },
        "network.schema.add" to { c -> encode(SchemaSet.serializer(), ops.networkSchemaAdd(c.app, c.required("name"), schemaInput(c))) },
        "network.schema.sync" to { c -> encode(SyncResult.serializer(), schemaSync(c)) },
        "network.schema.list" to { c -> encode(ListSerializer(SchemaSet.serializer()), ops.networkSchemas(c.app)) },
        "network.schema.remove" to { c ->
            val name = c.required("name")
            if (ops.networkSchemas(c.app).none { it.name == name }) notFound("schema set", name)
            buildJsonObject { put("removed", name); put("brokenMappings", encode(ListSerializer(BodyMapping.serializer()), ops.networkSchemaRemove(c.app, name))) }
        },
        "network.mapping.add" to { c -> encode(BodyMapping.serializer(), ops.networkMappingAdd(c.app, mapping(c))) },
        "timeline.events" to { c ->
            encode(
                io.yamsergey.dta.protocol.timeline.TimelinePage.serializer(),
                ops.timelineEvents(
                    c.device, c.app, c.string("since"), c.long("sinceMs"),
                    c.strings("sources").orEmpty().toSet(), c.strings("kinds").orEmpty().toSet(), c.long("limit")?.toInt() ?: 100,
                ),
            )
        },
        "timeline.causes" to { c ->
            val direction = when (c.string("direction")) {
                null, "causes" -> io.yamsergey.dta.protocol.timeline.ChainDirection.CAUSES
                "effects" -> io.yamsergey.dta.protocol.timeline.ChainDirection.EFFECTS
                else -> invalid("direction must be causes or effects")
            }
            try {
                encode(
                    io.yamsergey.dta.protocol.timeline.CauseChain.serializer(),
                    ops.timelineCauses(c.device, c.app, c.required("event"), direction, c.long("depth")?.toInt() ?: 8, c.bool("inferred")),
                )
            } catch (e: io.yamsergey.dta.daemon.timeline.CauseGraph.UnknownEvent) {
                notFound("event", e.id)
            }
        },
        "state.current" to { c -> encode(io.yamsergey.dta.protocol.timeline.CurrentState.serializer(), ops.stateCurrent(c.device, c.app)) },
        "state.graph" to { c ->
            encode(io.yamsergey.dta.protocol.timeline.StateGraph.serializer(), ops.stateGraph(c.device, c.app, c.strings("facets"), c.long("minHoldMs") ?: 0, c.bool("inferred")))
        },
        "state.path" to { c ->
            encode(io.yamsergey.dta.protocol.timeline.StatePath.serializer(), ops.statePath(c.device, c.app, c.required("from"), c.string("to"), c.strings("facets")))
        },
        "links.list" to { c -> encode(NetworkPage.serializer(), ops.network(c.device, c.app, linksQuery(c.params))) },
        "links.entry" to { c -> encode(NetworkEntryDetail.serializer(), ops.networkEntry(c.device, c.app, c.required("id"), c.bool("redact"))) },
        "links.open" to { c ->
            try {
                encode(io.yamsergey.dta.protocol.network.LinkOpenResult.serializer(), ops.linksOpen(c.device, c.required("uri"), c.string("package"), c.bool("only")))
            } catch (e: IllegalArgumentException) {
                invalid(e.message ?: "invalid URI")
            }
        },
        "links.verification" to { c -> encode(io.yamsergey.dta.protocol.network.AppLinksState.serializer(), ops.linksVerification(c.device, c.app)) },
        "archive.list" to { _ -> encode(ArchiveListing.serializer(), ops.archiveList()) },
        "archive.delete" to { c ->
            val targets = listOfNotNull(
                c.string("epoch")?.let { DeleteTarget.Epoch(it) },
                c.long("installation")?.let { DeleteTarget.Installation(it) },
                c.string("packageName")?.let { DeleteTarget.Package(it) },
                c.long("closedBeforeMs")?.let { DeleteTarget.ClosedBefore(it) },
            )
            if (targets.size != 1) invalid("name exactly one of epoch, installation, packageName or closedBeforeMs")
            try {
                encode(ArchiveDeletion.serializer(), ops.archiveDelete(targets.single(), c.bool("confirm")))
            } catch (e: LiveEpochException) {
                invalid(e.message ?: "the live history is not deleted here")
            }
        },
        // Feature 027: the app's stores.
        "data.list" to { c -> encode(io.yamsergey.dta.protocol.data.StoreList.serializer(), ops.data(c.device, c.app).list()) },
        "data.db.schema" to { c -> encode(io.yamsergey.dta.protocol.data.DbSchema.serializer(), ops.data(c.device, c.app).schema(c.forward())) },
        "data.db.query" to { c -> encode(io.yamsergey.dta.protocol.data.QueryPage.serializer(), ops.data(c.device, c.app).query(c.forward())) },
        "data.db.exec" to { c -> encode(io.yamsergey.dta.daemon.data.DbExecOutcome.serializer(), ops.data(c.device, c.app).exec(c.forward())) },
        "data.prefs.read" to { c -> encode(io.yamsergey.dta.protocol.data.PrefsContent.serializer(), ops.data(c.device, c.app).prefsRead(c.forward())) },
        "data.prefs.set" to { c -> encode(io.yamsergey.dta.protocol.data.DataWriteResult.serializer(), ops.data(c.device, c.app).prefsSet(c.forward())) },
        "data.prefs.remove" to { c -> encode(io.yamsergey.dta.protocol.data.DataWriteResult.serializer(), ops.data(c.device, c.app).prefsRemove(c.forward())) },
        "data.proto.read" to { c -> encode(io.yamsergey.dta.protocol.data.ProtoContent.serializer(), ops.data(c.device, c.app).protoRead(c.required("store"), c.string("type"))) },
        "data.proto.write" to { c ->
            encode(io.yamsergey.dta.protocol.data.DataWriteResult.serializer(), ops.data(c.device, c.app).protoWrite(c.required("store"), c.required("type"), c.required("json")))
        },
        "data.files.list" to { c -> encode(io.yamsergey.dta.protocol.data.FileListing.serializer(), ops.data(c.device, c.app).filesList(c.forward())) },
        "data.files.read" to { c -> encode(io.yamsergey.dta.protocol.data.FileContent.serializer(), ops.data(c.device, c.app).filesRead(c.required("path"), c.string("output"))) },
        "data.files.write" to { c ->
            encode(io.yamsergey.dta.protocol.data.DataWriteResult.serializer(), ops.data(c.device, c.app).filesWrite(c.required("path"), c.string("text"), c.string("base64"), c.string("input")))
        },
        "data.files.mkdir" to { c -> encode(io.yamsergey.dta.protocol.data.DataWriteResult.serializer(), ops.data(c.device, c.app).mkdir(c.required("path"))) },
        "data.files.delete" to { c -> encode(io.yamsergey.dta.protocol.data.DataWriteResult.serializer(), ops.data(c.device, c.app).delete(c.required("path"))) },
        // Feature 028: the running process.
        "runtime.activities" to { c -> rt(c, "runtime_activities", io.yamsergey.dta.protocol.data.RuntimeActivities.serializer()) },
        "runtime.navigation" to { c -> rt(c, "runtime_navigation", io.yamsergey.dta.protocol.data.NavigationState.serializer()) },
        "runtime.navigate" to { c ->
            if (c.string("route") == null && c.string("uri") == null) invalid("give a route or uri")
            rt(c, "runtime_navigate", io.yamsergey.dta.protocol.data.DataWriteResult.serializer())
        },
        "runtime.back" to { c -> rt(c, "runtime_back", io.yamsergey.dta.protocol.data.DataWriteResult.serializer()) },
        "runtime.viewmodels" to { c -> rt(c, "runtime_viewmodels", io.yamsergey.dta.protocol.data.ViewModelList.serializer()) },
        "runtime.vm.set" to { c -> rt(c, "runtime_vm_set", io.yamsergey.dta.protocol.data.DataWriteResult.serializer()) },
        "runtime.saved.set" to { c -> rt(c, "runtime_saved_set", io.yamsergey.dta.protocol.data.DataWriteResult.serializer()) },
        "runtime.memory" to { c -> rt(c, "runtime_memory", io.yamsergey.dta.protocol.data.MemoryState.serializer()) },
        "runtime.threads" to { c -> rt(c, "runtime_threads", io.yamsergey.dta.protocol.data.ThreadList.serializer()) },
        "runtime.di" to { c -> rt(c, "runtime_di", io.yamsergey.dta.protocol.data.DiReport.serializer()) },
        "runtime.functions" to { c -> rt(c, "runtime_functions", io.yamsergey.dta.protocol.data.FunctionList.serializer()) },
        "runtime.invoke" to { c -> rt(c, "runtime_invoke", io.yamsergey.dta.protocol.data.InvokeResult.serializer()) },
        "network.mapping.list" to { c -> encode(ListSerializer(BodyMapping.serializer()), ops.networkMappings(c.app)) },
        "network.mapping.remove" to { c ->
            val id = c.required("id")
            if (!ops.networkMappingRemove(c.app, id)) notFound("mapping", id)
            buildJsonObject { put("removed", id) }
        },
    )

    /** Operation names this dispatcher serves (the server owns the rest: status, stop, watch). */
    val handled: Set<String> get() = handlers.keys

    suspend fun dispatch(method: String, params: JsonObject?): Selected {
        val spec = Catalog.find(method)
        val handler = handlers[method]
        if (spec == null || handler == null) {
            throw ClientException(ErrorCodes.METHOD_NOT_FOUND, "Unknown operation '$method'")
        }
        val all = params ?: JsonObject(emptyMap())
        val device = (all["device"] as? JsonPrimitive)?.content
        val app = (all["app"] as? JsonPrimitive)?.content
        val own = JsonObject(all - "device" - "app")
        try {
            validate(spec, own)
        } catch (e: InvalidParamsException) {
            throw translate(e, app)
        }
        val resolution = selector.resolve(spec.scope, device, app)
        val result = try {
            handler(Call(resolution.serial, resolution.pkg, own))
        } catch (e: Exception) {
            throw translate(e, resolution.pkg)
        }
        return Selected(resolution.statement, result)
    }

    // --- Params

    private fun validate(spec: OperationSpec, params: JsonObject) {
        params.forEach { (name, value) ->
            val p = spec.param(name) ?: invalid(
                "unknown parameter '$name' for ${spec.name}; valid: ${(spec.params.map { it.name } + scopeParams(spec)).joinToString(", ")}",
            )
            val ok = when (p.type) {
                ParamType.STRING -> value is JsonPrimitive && value.isString
                ParamType.INT, ParamType.LONG -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                ParamType.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                ParamType.ENUM -> value is JsonPrimitive && value.isString && value.content in p.enumValues
                ParamType.STRING_LIST -> value is JsonArray && value.all { it is JsonPrimitive && it.isString }
                ParamType.ENUM_LIST -> value is JsonArray && value.all { it is JsonPrimitive && it.isString && it.content in p.enumValues }
                ParamType.STRING_MAP -> value is JsonObject && value.values.all { it is JsonPrimitive && it.isString }
            }
            if (!ok) {
                val expected = when (p.type) {
                    ParamType.ENUM, ParamType.ENUM_LIST -> "one of ${p.enumValues.joinToString(", ")}"
                    else -> p.type.name.lowercase().replace('_', ' ')
                }
                invalid("parameter '$name' must be $expected, was $value")
            }
        }
        spec.params.filter { it.required && it.name !in params }.forEach { invalid("missing required parameter '${it.name}'") }
    }

    private fun scopeParams(spec: OperationSpec) = when (spec.scope) {
        io.yamsergey.dta.protocol.ops.Scope.NONE -> emptyList()
        io.yamsergey.dta.protocol.ops.Scope.DEVICE -> listOf("device")
        else -> listOf("device", "app")
    }

    /** `links.list` is the app's history filtered to links (feature 012 R1). */
    private fun linksQuery(params: JsonObject): NetworkQuery {
        val renamed = JsonObject(params.mapKeys { (k, _) -> if (k == "uri") "url" else k } + ("kinds" to kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("link")) }))
        return query(renamed)
    }

    private fun query(params: JsonObject): NetworkQuery {
        val filterKeys = NetworkFilter.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
        val archive = (params["archive"] as? JsonPrimitive)?.content?.let { text ->
            try {
                DtaJson.encodeToJsonElement(ArchiveScope.serializer(), ArchiveScope.parse(text))
            } catch (e: IllegalArgumentException) {
                invalid(e.message ?: "invalid archive scope '$text'")
            }
        }
        val raw = buildJsonObject {
            put("filter", JsonObject(params.filterKeys { it in filterKeys }))
            params.filterKeys { it !in filterKeys && it != "archive" }.forEach { (k, v) -> put(k, v) }
            archive?.let { put("archive", it) }
        }
        return NetworkQuery.parse(raw)
    }

    private suspend fun layout(c: Call): LayoutCapture {
        val filter = LayoutFilter(c.string("text"), c.string("type"), c.string("resourceId"), c.long("rootId"))
        val detail = c.string("detail")?.let { d -> DetailLevel.entries.first { it.name.lowercase() == d } } ?: DetailLevel.SEMANTIC
        val screen = if (c.bool("screen") || c.string("screenOutput") != null) io.yamsergey.dta.daemon.layout.ScreenRequest(c.string("screenOutput")) else null
        return ops.layout(c.device, c.pkg, filter.takeUnless { it.isEmpty }, detail, c.bool("frameworkFrames"), screen)
    }

    /** Feature 026: the list's selection as one HAR file; bodies through `network.body`'s path, redaction alike. */
    private suspend fun export(c: Call): io.yamsergey.dta.protocol.network.NetworkExportResult {
        val output = java.io.File(c.required("output"))
        val redact = c.bool("redact")
        val params = JsonObject(c.params.filterKeys { it != "output" && it != "format" })
        return io.yamsergey.dta.daemon.network.NetworkExport(
            page = { q -> ops.network(c.device, c.app, q) },
            entry = { id -> ops.networkEntry(c.device, c.app, id, redact) },
            body = { id, part, file ->
                ops.networkBody(c.device, c.app, id, part, BodyOutput.File(file.path), redact, allowUnredacted = false)
                check(file.exists()) { "no bytes stored" }
            },
            version = version,
        ).export(query(params), output)
    }

    private suspend fun body(c: Call): BodyResult {
        val id = c.required("id")
        val part = c.string("part")?.let { wire -> BodyPart.entries.first { DtaJson.encodeToString(BodyPart.serializer(), it).trim('"') == wire } }
            ?: if (ops.networkEntry(c.device, c.app, id, false).entry is WsMessageEntry) BodyPart.MESSAGE else BodyPart.RESPONSE
        val output = c.string("output")?.let { BodyOutput.File(it, decoded = !c.bool("raw"), structured = c.bool("structured")) } ?: BodyOutput.Text
        return ops.networkBody(c.device, c.app, id, part, output, c.bool("redact"), c.bool("allowUnredacted"))
    }

    /** A folder the daemon reads, or sources (and derived mappings) a client read (feature 013, research R1). */
    private fun schemaSync(c: Call): SyncResult {
        val folder = c.string("folder")
        val inline = (c.params["sources"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }
        if ((folder == null) == (inline == null)) invalid("give the project folder, or sources a client read itself")
        val derived = c.string("mappings")?.let { text ->
            try {
                DtaJson.decodeFromString(ListSerializer(DerivedMappingWire.serializer()), text).map { it.toDerived() }
            } catch (e: Exception) {
                invalid("mappings is not a JSON list of derived mappings: ${e.message}")
            }
        }.orEmpty()
        if (folder != null) {
            val dir = File(folder)
            if (!dir.isDirectory) notFound("folder", folder)
            val found = ProtoDiscovery.discover(dir)
            return ops.networkSchemaSync(c.app, SchemaOrigin(OriginKind.FOLDER, dir.canonicalPath, "cli"), found.files, derived, found.notes)
        }
        return ops.networkSchemaSync(c.app, SchemaOrigin(OriginKind.PROJECT, c.string("origin"), "ide"), inline!!, derived, emptyList())
    }

    private fun scriptSource(c: Call): String {
        c.string("source")?.let { return it }
        val path = c.string("path") ?: invalid("give the script as 'source' (text) or 'path' (a file)")
        val file = File(path)
        if (!file.isFile) notFound("script file", path)
        return file.readText()
    }

    private fun schemaInput(c: Call): SchemaInput {
        val inline = (c.params["sources"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
        val paths = c.strings("paths").orEmpty().map(::File)
        paths.firstOrNull { !it.exists() }?.let { notFound("schema path", it.path) }
        if (paths.size == 1 && paths[0].isFile && !paths[0].name.endsWith(".proto")) {
            return SchemaInput.DescriptorSet(paths[0].readBytes())
        }
        val files = buildMap {
            putAll(inline)
            paths.forEach { path ->
                if (path.isDirectory) {
                    path.walkTopDown().filter { it.isFile && it.name.endsWith(".proto") }
                        .forEach { put(it.relativeTo(path).invariantSeparatorsPath, it.readText()) }
                } else {
                    put(path.name, path.readText())
                }
            }
        }
        if (files.isEmpty()) invalid("give .proto files or directories, or one descriptor-set file, in 'paths' (or sources inline)")
        return SchemaInput.ProtoSources(files)
    }

    private fun mapping(c: Call) = BodyMappingSpec(
        urlPattern = c.required("urlPattern"),
        part = MappingPart.entries.first { it.name.lowercase() == c.required("part") },
        schema = c.required("schema"),
        type = c.required("type"),
        method = c.string("method"),
        direction = c.string("direction")?.let { d -> Direction.entries.first { it.name.lowercase() == d } },
    )

    // --- Errors

    private fun translate(e: Exception, pkg: String?): Exception = when (e) {
        is ClientException -> e
        is InvalidParamsException -> ClientException(ErrorCodes.INVALID_PARAMS, e.message ?: "invalid params")
        is SchemaException -> ClientException(ErrorCodes.INVALID_PARAMS, e.message ?: "invalid schema")
        is SerializationException -> ClientException(ErrorCodes.INVALID_PARAMS, e.message?.lineSequence()?.first() ?: "invalid params")
        is SidekickRpcException -> when {
            e.message?.startsWith("unknown entry id") == true -> ClientException(ClientErrorCodes.NOT_FOUND, e.message!!)
            e.code == ErrorCodes.INVALID_PARAMS -> ClientException(ErrorCodes.INVALID_PARAMS, e.message ?: "invalid params")
            else -> ClientException(e.code, e.message ?: "app error", e.details)
        }
        is BodyNotDecodedException -> ClientException(
            ClientErrorCodes.NOT_AVAILABLE,
            "${e.message}; fetch the raw bytes with output (and raw) instead",
            unavailable("decoding", e.decoding.reason ?: "not decoded"),
        )
        is BinaryBodyException -> ClientException(ClientErrorCodes.NOT_AVAILABLE, e.message ?: "binary body", unavailable("text", "binary body"))
        is io.yamsergey.dta.daemon.adb.AppNotRunningException -> ClientException(
            ClientErrorCodes.NOT_AVAILABLE,
            "${e.message}; DTA works only in apps built with DTA inside.",
            unavailable("connection", e.message ?: "app not running"),
        )
        is ConnectException -> ClientException(
            ClientErrorCodes.NOT_AVAILABLE,
            "Cannot reach DTA inside ${pkg ?: "the app"}: ${e.message}. Is the app running and built with DTA?",
            unavailable("connection", e.message ?: "unreachable"),
        )
        is ConnectionLostException, is RequestTimeoutException ->
            ClientException(ClientErrorCodes.NOT_AVAILABLE, e.message ?: "app connection lost", unavailable("connection", e.message ?: "lost"))
        is AdbUnavailableException -> ClientException(ClientErrorCodes.NOT_AVAILABLE, e.message ?: "adb not available", unavailable("adb", e.message ?: "not found"))
        else -> ClientException(ErrorCodes.INTERNAL, "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull() ?: "internal error"}")
    }

    private fun unavailable(capability: String, reason: String) = buildJsonObject { put("capability", capability); put("reason", reason) }

    private fun notFound(what: String, id: String): Nothing =
        throw ClientException(ClientErrorCodes.NOT_FOUND, "No $what '$id'", buildJsonObject { put("what", what); put("id", id) })

    private fun invalid(message: String): Nothing = throw InvalidParamsException(message)

    private suspend fun <T> rt(c: Call, method: String, s: KSerializer<T>): JsonElement = encode(s, ops.data(c.device, c.app).runtime(method, s, c.forward()))

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = DtaJson.encodeToJsonElement(serializer, value)
}
