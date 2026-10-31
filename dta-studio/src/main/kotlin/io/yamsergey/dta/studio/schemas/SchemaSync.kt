package io.yamsergey.dta.studio.schemas

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.yamsergey.dta.studio.ContextController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The last sync: the daemon's answer (set, failures, unmapped types) and what derivation could not map. */
data class SchemaSyncState(val result: JsonObject? = null, val unmappedEndpoints: List<String> = emptyList(), val running: Boolean = false, val error: String? = null)

/**
 * Keeps the app's schemas in step with the project (feature 013 US1/US2): the project's `.proto`
 * files (its own and its dependencies') and the mappings its Retrofit declarations imply are
 * synced as one origin when the app is known, after indexing, and 2 s after a `.proto` file
 * changes; "Sync now" does it on demand.
 */
class SchemaSync(private val project: Project, private val context: ContextController, private val scope: CoroutineScope, parent: Disposable) {
    private val mutable = MutableStateFlow(SchemaSyncState())
    val state: StateFlow<SchemaSyncState> = mutable.asStateFlow()
    private var pending: Job? = null

    init {
        scope.launch {
            context.state.map { it.device to it.app }.distinctUntilChanged().collect { (device, app) -> if (device != null && app != null) request(0) }
        }
        project.messageBus.connect(parent).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.path.endsWith(".proto") }) request(DEBOUNCE_MS)
                }
            },
        )
    }

    fun syncNow() = request(0)

    private fun request(delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            DumbService.getInstance(project).runWhenSmart { scope.launch { run() } }
        }
    }

    private suspend fun run() {
        val client = context.client ?: return
        val s = context.state.value
        val device = s.device ?: return
        val app = s.app ?: return
        mutable.value = mutable.value.copy(running = true, error = null)
        try {
            val files = ProjectProtos.collect(project)
            if (files.isEmpty()) {
                mutable.value = SchemaSyncState(result = buildJsonObject { put("notes", buildJsonArray { add(JsonPrimitive("no .proto files in the project or its dependencies")) }) })
                return
            }
            fun params(mappings: List<DerivedMapping>?) = buildJsonObject {
                put("device", device)
                put("app", app)
                put("sources", JsonObject(files.mapValues { JsonPrimitive(it.value) }))
                project.basePath?.let { put("origin", it) }
                mappings?.let { put("mappings", json(it)) }
            }
            // The schemas first: derivation matches message names against what compiled.
            val first = client.call("network.schema.sync", params(null)).result.jsonObject
            val types = ((first["set"] as? JsonObject)?.get("types") as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()
            val derivation = RetrofitMappings.derive(project, types)
            val result = if (derivation.mappings.isEmpty()) first else client.call("network.schema.sync", params(derivation.mappings)).result.jsonObject
            mutable.value = SchemaSyncState(result, derivation.unmapped)
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(running = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun json(mappings: List<DerivedMapping>): String = buildJsonArray {
        mappings.forEach { m ->
            add(buildJsonObject {
                put("urlPattern", m.urlPattern)
                put("part", m.part.wire)
                put("type", m.type)
                put("method", m.method)
                m.location?.let { put("location", it) }
            })
        }
    }.toString()

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
