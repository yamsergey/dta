package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.connection.ConnectException
import io.yamsergey.dta.daemon.connection.SidekickRpcException
import io.yamsergey.dta.daemon.network.BodyNotDecodedException
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.MappingPart
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.daemon.network.proto.SchemaSource
import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingInfo
import io.yamsergey.dta.protocol.network.DecodingOutcome
import io.yamsergey.dta.protocol.network.EntryKind
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import io.yamsergey.dta.protocol.rpc.IdentityReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeOperations : DaemonOperations {
    val calls = mutableListOf<String>()
    var lastQuery: NetworkQuery? = null
    var lastLayout: Triple<String?, LayoutFilter?, DetailLevel>? = null
    var lastBody: Pair<BodyPart, BodyOutput>? = null
    var lastSchemaInput: SchemaInput? = null
    var lastMapping: BodyMappingSpec? = null
    var bodyFailure: Exception? = null
    var entryFailure: Exception? = null
    var entryKind: EntryKind = EntryKind.HTTP

    var deviceList = listOf(DeviceInfo("emulator-5554", "online", "Pixel 8"))
    val appEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<io.yamsergey.dta.protocol.ops.AppConnectionEvent>(extraBufferCapacity = 100)
    override fun devices() = deviceList
    /** Every device's events, unfiltered: the server must filter by what the client watches. */
    override fun appEvents(serial: String?): Flow<io.yamsergey.dta.protocol.ops.AppConnectionEvent> = appEventFlow
    override fun apps(serial: String) = AppList(serial, "com.example.shop", listOf(AppInfo("com.example.shop", true)))
    override suspend fun capabilityReport(serial: String, pkg: String) = IdentityReport(1, "0.1.0", pkg, emptyList()).also { calls += "capability" }
    override suspend fun layout(serial: String, pkg: String?, filters: LayoutFilter?, detail: DetailLevel, frameworkFrames: Boolean): LayoutCapture {
        lastLayout = Triple(pkg, filters, detail)
        return LayoutCapture(Screen(1080, 2400, 2.6f), CaptureSource.SIDEKICK, emptyList())
    }
    override suspend fun network(serial: String, pkg: String, query: NetworkQuery): NetworkPage {
        lastQuery = query
        return NetworkPage(emptyList(), total = 0, coverage = io.yamsergey.dta.protocol.network.Coverage(searched = 0, stored = 0, persisted = true))
    }
    override suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean): NetworkEntryDetail {
        entryFailure?.let { throw it }
        val entry = if (entryKind == EntryKind.WS_MESSAGE) {
            WsMessageEntry(id, "r1-1", 1, "okhttp3-ws", connectionId = "r1-1:1", direction = Direction.IN, opcode = MessageOpcode.TEXT, payload = BodyInfo(BodyPart.MESSAGE, 2, completeness = Completeness.COMPLETE))
        } else {
            HttpEntry(id, "r1-1", 1, "okhttp3", method = "GET", url = "https://api.example.com/", state = HttpState.COMPLETE, status = 200)
        }
        return NetworkEntryDetail(entry)
    }
    override suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean, allowUnredacted: Boolean): BodyResult {
        bodyFailure?.let { throw it }
        lastBody = part to output
        return BodyResult(BodyInfo(part, 2, binary = false, completeness = Completeness.COMPLETE), text = "{}", bytes = 2)
    }
    override suspend fun networkClear(serial: String, pkg: String) = ClearResult(3, 1)
    val events = kotlinx.coroutines.flow.MutableSharedFlow<NetworkEntryEvent>(extraBufferCapacity = 10_000)
    override fun networkEvents(serial: String, pkg: String): Flow<NetworkEntryEvent> = events
    override suspend fun networkScriptSet(serial: String, pkg: String, source: String) = ScriptStatus(active = true).also { calls += "script:$source" }
    override suspend fun networkScriptClear(serial: String, pkg: String) = true
    override suspend fun networkScriptStatus(serial: String, pkg: String) = ScriptStatus(active = false)
    override suspend fun networkScriptLogs(serial: String, pkg: String, since: Long) = ScriptLogPage(emptyList(), next = since)
    override fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput): SchemaSet {
        lastSchemaInput = input
        return SchemaSet(name, SchemaSource.PROTO_SOURCES, emptyList(), emptyList(), 0)
    }
    override fun networkSchemas(pkg: String) = emptyList<SchemaSet>()
    override fun networkSchemaRemove(pkg: String, name: String) = emptyList<BodyMapping>()
    override fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec): BodyMapping {
        lastMapping = mapping
        return BodyMapping("m1", mapping.urlPattern, mapping.part, mapping.schema, mapping.type)
    }
    override fun networkMappings(pkg: String) = emptyList<BodyMapping>()
    override fun networkMappingRemove(pkg: String, id: String) = id == "m1"
    override fun networkSchemaSync(pkg: String, origin: io.yamsergey.dta.daemon.network.proto.SchemaOrigin, files: Map<String, String>, mappings: List<io.yamsergey.dta.daemon.network.proto.DerivedMapping>, notes: List<String>) =
        io.yamsergey.dta.daemon.network.proto.SyncResult(notes = notes + "synced ${files.size} files")
    override suspend fun linksOpen(serial: String, uri: String, pkg: String?, only: Boolean) =
        io.yamsergey.dta.protocol.network.LinkOpenResult(io.yamsergey.dta.protocol.network.LinkOpenOutcome.NOT_HANDLED, reason = "fake")
    override fun linksVerification(serial: String, pkg: String) = io.yamsergey.dta.protocol.network.AppLinksState(available = true)
    override fun archiveList() = io.yamsergey.dta.protocol.network.ArchiveListing(emptyList(), 0)
    override fun archiveDelete(target: io.yamsergey.dta.daemon.store.DeleteTarget, confirm: Boolean) =
        io.yamsergey.dta.protocol.network.ArchiveDeletion(confirm, io.yamsergey.dta.protocol.network.ArchiveDeleted(0, 0, 0, 0))
}

