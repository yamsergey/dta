package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.timeline.Flow
import io.yamsergey.dta.protocol.timeline.FlowStarted
import io.yamsergey.dta.protocol.timeline.FlowSources
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Feature 049: each device's and app's flows, in `<dataDir>/flows/<device>/<package>.json`, written
 * before a change returns. Boundaries are told from the newest recorded row, on the device's clock;
 * the computer's clock is never used.
 */
class FlowStore(private val dataDir: File) {
    class UnknownFlow(val ref: String) : Exception("no flow '$ref'")

    @Serializable
    private data class Stored(val next: Int = 1, val flows: List<Flow> = emptyList(), val problem: String? = null)

    private val apps = HashMap<Pair<String, String>, Stored>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    @Synchronized
    fun list(serial: String, pkg: String): List<Flow> = state(serial, pkg).flows

    @Synchronized
    fun problem(serial: String, pkg: String): String? = state(serial, pkg).problem

    /** Opens a flow after [newestMs], the newest row recorded (null: none); one recording ends there. */
    @Synchronized
    fun start(serial: String, pkg: String, name: String?, newestMs: Long?): FlowStarted {
        val given = name?.trim()?.takeIf { it.isNotEmpty() }?.also(::checkLength)
        val s = state(serial, pkg)
        val at = after(newestMs)
        val open = s.flows.firstOrNull { it.source == FlowSources.MANUAL && it.recording }
        val ended = open?.copy(endMs = maxOf(at, open.startMs))
        val flow = Flow(id = "f${s.next}", name = given ?: "Flow ${s.next}", startMs = at)
        save(serial, pkg, Stored(s.next + 1, s.flows.map { if (it.id == ended?.id) ended else it } + flow))
        return FlowStarted(flow, ended)
    }

    @Synchronized
    fun end(serial: String, pkg: String, newestMs: Long?): Flow {
        val s = state(serial, pkg)
        val open = s.flows.firstOrNull { it.source == FlowSources.MANUAL && it.recording }
            ?: throw InvalidParamsException("no flow is recording")
        val ended = open.copy(endMs = maxOf(after(newestMs), open.startMs))
        save(serial, pkg, s.copy(flows = s.flows.map { if (it.id == ended.id) ended else it }, problem = null))
        return ended
    }

    @Synchronized
    fun rename(serial: String, pkg: String, ref: String, name: String): Flow {
        val given = name.trim()
        if (given.isEmpty()) throw InvalidParamsException("a flow's name cannot be blank")
        checkLength(given)
        val s = state(serial, pkg)
        val renamed = resolve(serial, pkg, ref).copy(name = given)
        save(serial, pkg, s.copy(flows = s.flows.map { if (it.id == renamed.id) renamed else it }, problem = null))
        return renamed
    }

    /** The flow goes; what is recorded stays. */
    @Synchronized
    fun remove(serial: String, pkg: String, ref: String): Flow {
        val s = state(serial, pkg)
        val flow = resolve(serial, pkg, ref)
        save(serial, pkg, s.copy(flows = s.flows.filter { it.id != flow.id }, problem = null))
        return flow
    }

    /** By id, or by a name only one flow has. */
    @Synchronized
    fun resolve(serial: String, pkg: String, ref: String): Flow {
        val flows = state(serial, pkg).flows
        flows.firstOrNull { it.id == ref }?.let { return it }
        val named = flows.filter { it.name == ref }
        return when (named.size) {
            0 -> throw UnknownFlow(ref)
            1 -> named.single()
            else -> throw InvalidParamsException("${named.size} flows are called '$ref': use an id: ${named.joinToString(", ") { it.id }}")
        }
    }

    private fun after(newestMs: Long?) = newestMs?.plus(1) ?: 0

    private fun checkLength(name: String) {
        if (name.length > Flow.MAX_NAME) throw InvalidParamsException("a flow's name is at most ${Flow.MAX_NAME} characters, this one has ${name.length}")
    }

    private fun state(serial: String, pkg: String) = apps.getOrPut(serial to pkg) { load(serial, pkg) }

    private fun load(serial: String, pkg: String): Stored {
        val f = file(serial, pkg)
        if (!f.isFile) return Stored()
        return runCatching { json.decodeFromString(Stored.serializer(), f.readText()) }.getOrElse { e ->
            runCatching { Files.move(f.toPath(), File(f.path + ".bad").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            Stored(problem = "flows could not be read (${e.message?.lineSequence()?.first()}); kept as ${f.name}.bad, starting with none")
        }
    }

    private fun save(serial: String, pkg: String, s: Stored) {
        apps[serial to pkg] = s
        val f = file(serial, pkg).apply { parentFile.mkdirs() }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), s))
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun file(serial: String, pkg: String) = File(File(File(dataDir, "flows"), safe(serial)), "${safe(pkg)}.json")

    /** A device's name may hold a port; neither name may climb out of the folder. */
    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.all { c -> c == '.' }) "_" else it }
}
