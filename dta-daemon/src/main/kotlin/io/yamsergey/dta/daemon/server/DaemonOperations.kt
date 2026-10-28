package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.DtaDaemon
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.rpc.IdentityReport
import kotlinx.coroutines.flow.Flow

/**
 * The library operations the daemon process serves (feature 008). [DtaDaemon] is the
 * production implementation; tests substitute a fake. Every catalog operation maps to
 * one of these (parity test).
 */
interface DaemonOperations {
    fun devices(): List<DeviceInfo>
    fun apps(serial: String): AppList
    suspend fun capabilityReport(serial: String, pkg: String): IdentityReport
    suspend fun layout(serial: String, pkg: String?, filters: LayoutFilter?, detail: DetailLevel, frameworkFrames: Boolean): LayoutCapture
    suspend fun network(serial: String, pkg: String, query: NetworkQuery): NetworkPage
    suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean): NetworkEntryDetail
    suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean, allowUnredacted: Boolean): BodyResult
    suspend fun networkClear(serial: String, pkg: String): ClearResult
    fun networkEvents(serial: String, pkg: String): Flow<NetworkEntryEvent>
    suspend fun networkScriptSet(serial: String, pkg: String, source: String): ScriptStatus
    suspend fun networkScriptClear(serial: String, pkg: String): Boolean
    suspend fun networkScriptStatus(serial: String, pkg: String): ScriptStatus
    suspend fun networkScriptLogs(serial: String, pkg: String, since: Long): ScriptLogPage
    fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput): SchemaSet
    fun networkSchemas(pkg: String): List<SchemaSet>
    fun networkSchemaRemove(pkg: String, name: String): List<BodyMapping>
    fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec): BodyMapping
    fun networkMappings(pkg: String): List<BodyMapping>
    fun networkMappingRemove(pkg: String, id: String): Boolean
}

/** Adapts [DtaDaemon] (whose methods carry defaults) to [DaemonOperations]. */
class LibraryOperations(private val daemon: DtaDaemon) : DaemonOperations {
    override fun devices() = daemon.devices()
    override fun apps(serial: String) = daemon.apps(serial)
    override suspend fun capabilityReport(serial: String, pkg: String) = daemon.capabilityReport(serial, pkg)
    override suspend fun layout(serial: String, pkg: String?, filters: LayoutFilter?, detail: DetailLevel, frameworkFrames: Boolean) =
        daemon.layout(serial, pkg, filters, detail, frameworkFrames)
    override suspend fun network(serial: String, pkg: String, query: NetworkQuery) = daemon.network(serial, pkg, query)
    override suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean) = daemon.networkEntry(serial, pkg, id, redact)
    override suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean, allowUnredacted: Boolean) =
        daemon.networkBody(serial, pkg, id, part, output, redact, allowUnredacted)
    override suspend fun networkClear(serial: String, pkg: String) = daemon.networkClear(serial, pkg)
    override fun networkEvents(serial: String, pkg: String) = daemon.networkEvents(serial, pkg)
    override suspend fun networkScriptSet(serial: String, pkg: String, source: String) = daemon.networkScriptSet(serial, pkg, source)
    override suspend fun networkScriptClear(serial: String, pkg: String) = daemon.networkScriptClear(serial, pkg)
    override suspend fun networkScriptStatus(serial: String, pkg: String) = daemon.networkScriptStatus(serial, pkg)
    override suspend fun networkScriptLogs(serial: String, pkg: String, since: Long) = daemon.networkScriptLogs(serial, pkg, since)
    override fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput) = daemon.networkSchemaAdd(pkg, name, input)
    override fun networkSchemas(pkg: String) = daemon.networkSchemas(pkg)
    override fun networkSchemaRemove(pkg: String, name: String) = daemon.networkSchemaRemove(pkg, name)
    override fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec) = daemon.networkMappingAdd(pkg, mapping)
    override fun networkMappings(pkg: String) = daemon.networkMappings(pkg)
    override fun networkMappingRemove(pkg: String, id: String) = daemon.networkMappingRemove(pkg, id)
}
