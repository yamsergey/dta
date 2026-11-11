package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.store.DeleteTarget
import io.yamsergey.dta.protocol.network.ArchiveDeletion
import io.yamsergey.dta.protocol.network.ArchiveListing
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
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
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
    /** App connection state changes on [serial], or every device when null (feature 009). */
    fun appEvents(serial: String?): Flow<AppConnectionEvent>
    suspend fun capabilityReport(serial: String, pkg: String): IdentityReport
    suspend fun layout(serial: String, pkg: String?, filters: LayoutFilter?, detail: DetailLevel, frameworkFrames: Boolean, screen: io.yamsergey.dta.daemon.layout.ScreenRequest?): LayoutCapture
    suspend fun network(serial: String, pkg: String, query: NetworkQuery): NetworkPage
    suspend fun networkSearch(serial: String, pkg: String, query: NetworkQuery): io.yamsergey.dta.protocol.network.SearchPage
    suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean): NetworkEntryDetail
    suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean, allowUnredacted: Boolean): BodyResult
    suspend fun networkClear(serial: String, pkg: String): ClearResult
    /** Live entry events; [onSubscribed] runs once the app's event stream is subscribed. */
    fun networkEvents(serial: String, pkg: String, onSubscribed: suspend () -> Unit): Flow<NetworkEntryEvent>
    suspend fun networkScriptSet(serial: String, pkg: String, source: String): ScriptStatus
    suspend fun networkScriptClear(serial: String, pkg: String): Boolean
    suspend fun networkScriptStatus(serial: String, pkg: String): ScriptStatus
    suspend fun networkScriptLogs(serial: String, pkg: String, since: Long): ScriptLogPage
    suspend fun networkWsPush(serial: String, pkg: String, connection: String?, text: String?, base64: String?, waitMs: Long?): io.yamsergey.dta.protocol.network.WsPushResult
    fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput): SchemaSet
    fun networkSchemas(pkg: String): List<SchemaSet>
    fun networkSchemaRemove(pkg: String, name: String): List<BodyMapping>
    fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec): BodyMapping
    fun networkMappings(pkg: String): List<BodyMapping>
    fun networkMappingRemove(pkg: String, id: String): Boolean
    fun networkFormatAdd(pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec): io.yamsergey.dta.protocol.network.FormatRule
    fun networkFormats(pkg: String): List<io.yamsergey.dta.protocol.network.FormatRule>
    fun networkFormatRemove(pkg: String, id: String): Boolean
    suspend fun networkFormatTest(serial: String, pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec, connection: String?): io.yamsergey.dta.protocol.network.FormatTestResult
    /** The app's saved network filters (feature 043). */
    fun networkFilters(pkg: String): io.yamsergey.dta.protocol.network.SavedFilters
    fun networkFiltersAdd(pkg: String, words: String): io.yamsergey.dta.protocol.network.SavedFilters
    fun networkFiltersRemove(pkg: String, word: String): Boolean
    /** Opens a URI on the device and says who received it (feature 012). */
    suspend fun linksOpen(serial: String, uri: String, pkg: String?, only: Boolean): io.yamsergey.dta.protocol.network.LinkOpenResult
    /** The app's App Links state (feature 012). */
    fun linksVerification(serial: String, pkg: String): io.yamsergey.dta.protocol.network.AppLinksState
    /** Replaces one origin's schema set and derived mappings (feature 013). */
    fun networkSchemaSync(pkg: String, origin: io.yamsergey.dta.daemon.network.proto.SchemaOrigin, files: Map<String, String>, mappings: List<io.yamsergey.dta.daemon.network.proto.DerivedMapping>, notes: List<String>): io.yamsergey.dta.daemon.network.proto.SyncResult
    /** The app's timeline (features 015/016). */
    suspend fun timelineEvents(serial: String, pkg: String, since: String?, sinceMs: Long?, sources: Set<String>, kinds: Set<String>, limit: Int): io.yamsergey.dta.protocol.timeline.TimelinePage
    fun timelineWatch(serial: String, pkg: String, history: Boolean, sources: Set<String>, kinds: Set<String>): Flow<io.yamsergey.dta.protocol.timeline.TimelineEvent>

    /** Feature 057: the app's screen-change signal, leased while collected. */
    fun layoutSignal(serial: String, pkg: String): Flow<io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update>
    suspend fun timelineCauses(serial: String, pkg: String, event: String, direction: io.yamsergey.dta.protocol.timeline.ChainDirection, depth: Int, inferred: Boolean): io.yamsergey.dta.protocol.timeline.CauseChain
    suspend fun timelineSequence(serial: String, pkg: String, limit: Int, q: String?, sinceMs: Long?, flow: String?): io.yamsergey.dta.protocol.timeline.SequencePage
    /** Named flows over the sequence (feature 049). */
    suspend fun timelineFlowStart(serial: String, pkg: String, name: String?): io.yamsergey.dta.protocol.timeline.FlowStarted
    suspend fun timelineFlowEnd(serial: String, pkg: String): io.yamsergey.dta.protocol.timeline.Flow
    suspend fun timelineFlowList(serial: String, pkg: String): io.yamsergey.dta.protocol.timeline.FlowList
    fun timelineFlowRename(serial: String, pkg: String, flow: String, name: String): io.yamsergey.dta.protocol.timeline.Flow
    fun timelineFlowRemove(serial: String, pkg: String, flow: String): io.yamsergey.dta.protocol.timeline.Flow
    suspend fun stateCurrent(serial: String, pkg: String): io.yamsergey.dta.protocol.timeline.CurrentState
    /** Feature 056: read or set whether the app records its timeline. */
    suspend fun timelineEnabled(serial: String, pkg: String, on: Boolean?): io.yamsergey.dta.protocol.timeline.TimelineSwitch
    /** Feature 060: browser capture. */
    suspend fun webCapture(serial: String, pkg: String, webview: Boolean?, customTabs: Boolean?): io.yamsergey.dta.protocol.web.WebSwitch
    suspend fun webStatus(serial: String, pkg: String): io.yamsergey.dta.protocol.web.WebStatus
    suspend fun webReload(serial: String, pkg: String, page: String): Boolean
    suspend fun webDevtools(serial: String, pkg: String): io.yamsergey.dta.protocol.web.WebDevtools
    suspend fun stateGraph(serial: String, pkg: String, facets: List<String>?, minHoldMs: Long, inferred: Boolean): io.yamsergey.dta.protocol.timeline.StateGraph
    suspend fun statePath(serial: String, pkg: String, from: String, to: String?, facets: List<String>?): io.yamsergey.dta.protocol.timeline.StatePath
    /** The host's network archive (feature 011). */
    fun archiveList(): ArchiveListing
    fun archiveDelete(target: DeleteTarget, confirm: Boolean): ArchiveDeletion
    /** The app's stores (feature 027). */
    fun data(serial: String, pkg: String): io.yamsergey.dta.daemon.data.DataService
}

/** Adapts [DtaDaemon] (whose methods carry defaults) to [DaemonOperations]. */
class LibraryOperations(private val daemon: DtaDaemon) : DaemonOperations {
    override suspend fun timelineEvents(serial: String, pkg: String, since: String?, sinceMs: Long?, sources: Set<String>, kinds: Set<String>, limit: Int) =
        daemon.timelineEvents(serial, pkg, since, sinceMs, sources, kinds, limit)
    override fun timelineWatch(serial: String, pkg: String, history: Boolean, sources: Set<String>, kinds: Set<String>) = daemon.timelineWatch(serial, pkg, history, sources, kinds)
    override fun layoutSignal(serial: String, pkg: String) = daemon.layoutSignal(serial, pkg)
    override suspend fun timelineCauses(serial: String, pkg: String, event: String, direction: io.yamsergey.dta.protocol.timeline.ChainDirection, depth: Int, inferred: Boolean) =
        daemon.timelineCauses(serial, pkg, event, direction, depth, inferred)
    override suspend fun timelineSequence(serial: String, pkg: String, limit: Int, q: String?, sinceMs: Long?, flow: String?) = daemon.timelineSequence(serial, pkg, limit, q, sinceMs, flow)
    override suspend fun timelineFlowStart(serial: String, pkg: String, name: String?) = daemon.timelineFlowStart(serial, pkg, name)
    override suspend fun timelineFlowEnd(serial: String, pkg: String) = daemon.timelineFlowEnd(serial, pkg)
    override suspend fun timelineFlowList(serial: String, pkg: String) = daemon.timelineFlowList(serial, pkg)
    override fun timelineFlowRename(serial: String, pkg: String, flow: String, name: String) = daemon.timelineFlowRename(serial, pkg, flow, name)
    override fun timelineFlowRemove(serial: String, pkg: String, flow: String) = daemon.timelineFlowRemove(serial, pkg, flow)
    override suspend fun stateCurrent(serial: String, pkg: String) = daemon.stateCurrent(serial, pkg)
    override suspend fun timelineEnabled(serial: String, pkg: String, on: Boolean?) = daemon.timelineEnabled(serial, pkg, on)
    override suspend fun webCapture(serial: String, pkg: String, webview: Boolean?, customTabs: Boolean?) = daemon.webCapture(serial, pkg, webview, customTabs)
    override suspend fun webStatus(serial: String, pkg: String) = daemon.webStatus(serial, pkg)
    override suspend fun webReload(serial: String, pkg: String, page: String) = daemon.webReload(serial, pkg, page)
    override suspend fun webDevtools(serial: String, pkg: String) = daemon.webDevtools(serial, pkg)
    override suspend fun stateGraph(serial: String, pkg: String, facets: List<String>?, minHoldMs: Long, inferred: Boolean) = daemon.stateGraph(serial, pkg, facets, minHoldMs, inferred)
    override suspend fun statePath(serial: String, pkg: String, from: String, to: String?, facets: List<String>?) = daemon.statePath(serial, pkg, from, to, facets)
    override fun archiveList() = daemon.archiveList()
    override suspend fun linksOpen(serial: String, uri: String, pkg: String?, only: Boolean) = daemon.linksOpen(serial, uri, pkg)
    override fun linksVerification(serial: String, pkg: String) = daemon.linksVerification(serial, pkg)
    override fun networkSchemaSync(pkg: String, origin: io.yamsergey.dta.daemon.network.proto.SchemaOrigin, files: Map<String, String>, mappings: List<io.yamsergey.dta.daemon.network.proto.DerivedMapping>, notes: List<String>) =
        daemon.networkSchemaSync(pkg, origin, files, mappings, notes)
    override fun archiveDelete(target: DeleteTarget, confirm: Boolean) = daemon.archiveDelete(target, confirm)
    override fun data(serial: String, pkg: String) = daemon.data(serial, pkg)
    override fun devices() = daemon.devices()
    override fun apps(serial: String) = daemon.apps(serial)
    override fun appEvents(serial: String?) = daemon.appEvents(serial)
    override suspend fun capabilityReport(serial: String, pkg: String) = daemon.capabilityReport(serial, pkg)
    override suspend fun layout(serial: String, pkg: String?, filters: LayoutFilter?, detail: DetailLevel, frameworkFrames: Boolean, screen: io.yamsergey.dta.daemon.layout.ScreenRequest?) =
        daemon.layout(serial, pkg, filters, detail, frameworkFrames, screen)
    override suspend fun network(serial: String, pkg: String, query: NetworkQuery) = daemon.network(serial, pkg, query)
    override suspend fun networkSearch(serial: String, pkg: String, query: NetworkQuery) = daemon.networkSearch(serial, pkg, query)
    override suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean) = daemon.networkEntry(serial, pkg, id, redact)
    override suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean, allowUnredacted: Boolean) =
        daemon.networkBody(serial, pkg, id, part, output, redact, allowUnredacted)
    override suspend fun networkClear(serial: String, pkg: String) = daemon.networkClear(serial, pkg)
    override fun networkEvents(serial: String, pkg: String, onSubscribed: suspend () -> Unit) = daemon.networkEvents(serial, pkg, onSubscribed)
    override suspend fun networkScriptSet(serial: String, pkg: String, source: String) = daemon.networkScriptSet(serial, pkg, source)
    override suspend fun networkScriptClear(serial: String, pkg: String) = daemon.networkScriptClear(serial, pkg)
    override suspend fun networkScriptStatus(serial: String, pkg: String) = daemon.networkScriptStatus(serial, pkg)
    override suspend fun networkScriptLogs(serial: String, pkg: String, since: Long) = daemon.networkScriptLogs(serial, pkg, since)
    override suspend fun networkWsPush(serial: String, pkg: String, connection: String?, text: String?, base64: String?, waitMs: Long?) =
        daemon.networkWsPush(serial, pkg, connection, text, base64, waitMs)
    override fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput) = daemon.networkSchemaAdd(pkg, name, input)
    override fun networkSchemas(pkg: String) = daemon.networkSchemas(pkg)
    override fun networkSchemaRemove(pkg: String, name: String) = daemon.networkSchemaRemove(pkg, name)
    override fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec) = daemon.networkMappingAdd(pkg, mapping)
    override fun networkMappings(pkg: String) = daemon.networkMappings(pkg)
    override fun networkMappingRemove(pkg: String, id: String) = daemon.networkMappingRemove(pkg, id)
    override fun networkFormatAdd(pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec) = daemon.networkFormatAdd(pkg, spec)
    override fun networkFormats(pkg: String) = daemon.networkFormats(pkg)
    override fun networkFormatRemove(pkg: String, id: String) = daemon.networkFormatRemove(pkg, id)
    override fun networkFilters(pkg: String) = daemon.networkFilters(pkg)
    override fun networkFiltersAdd(pkg: String, words: String) = daemon.networkFiltersAdd(pkg, words)
    override fun networkFiltersRemove(pkg: String, word: String) = daemon.networkFiltersRemove(pkg, word)
    override suspend fun networkFormatTest(serial: String, pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec, connection: String?) =
        daemon.networkFormatTest(serial, pkg, spec, connection)
}
