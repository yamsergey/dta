package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.protocol.network.ScriptLogLevel
import io.yamsergey.dta.protocol.network.ScriptLogLine
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** The installed script. Adapters capture it at the start of a call (FR-015). */
class ActiveScript internal constructor(
    val id: String,
    val source: String,
    val installedAtMs: Long,
    val script: CompiledScript,
) {
    /** False when the script will not survive a restart (storage unavailable). */
    @Volatile
    var persisted: Boolean = false
        internal set
}

/**
 * Owner of the one network script of this process (constitution II: one lifecycle):
 * install/replace, clear, counters and the script log. With no script, [current] is a
 * single volatile read — the whole cost of intervention on the network path (FR-024).
 */
class ScriptRuntime(
    private val clock: () -> Long = System::currentTimeMillis,
    private val logCapacity: Int = 1000,
) {
    @Volatile
    private var active: ActiveScript? = null

    /** Why a script cannot apply (hooks unavailable, persisted script no longer compiles). */
    @Volatile
    var unavailable: String? = null

    private val invocations = AtomicLong()
    private val changes = AtomicLong()
    private val errors = AtomicLong()

    private val lines = ArrayDeque<ScriptLogLine>()
    private var nextSeq = 0L

    fun current(): ActiveScript? = active

    /** Compiles and activates [source]; an invalid script throws and leaves the current one. */
    @Synchronized
    fun install(source: String, installedAtMs: Long = clock()): ScriptStatus {
        val compiled = ScriptEngine.compile(source) { level, handler, message -> log(level, handler, null, message) }
        active = ActiveScript(idOf(source), source, installedAtMs, compiled)
        unavailable = null
        invocations.set(0)
        changes.set(0)
        errors.set(0)
        return status(withSource = false)
    }

    @Synchronized
    fun clear(): Boolean {
        val had = active != null
        active = null
        unavailable = null
        return had
    }

    fun markPersisted(persisted: Boolean) {
        active?.persisted = persisted
    }

    /** Network degradation codes that apply because of the script (contract §5). */
    fun degradations(): List<String> = buildList {
        if (unavailable != null) add(io.yamsergey.dta.protocol.layout.DegradationCodes.NETWORK_SCRIPT_UNAVAILABLE)
        active?.let { if (!it.persisted) add(io.yamsergey.dta.protocol.layout.DegradationCodes.NETWORK_SCRIPT_NOT_PERSISTED) }
    }

    /** Counts one handler call; errors go to the log with the entry they concern. */
    fun account(handler: ScriptHandler, result: ScriptResult<*>, entryId: String?) {
        invocations.incrementAndGet()
        if (result.error != null) {
            errors.incrementAndGet()
            log(ScriptLogLevel.ERROR, handler, entryId, result.error)
        } else if (result.effect != Effect.None || result.delayMs > 0 || result.throttleBytesPerSecond != null) {
            changes.incrementAndGet()
        }
    }

    fun log(level: ScriptLogLevel, handler: ScriptHandler?, entryId: String?, message: String) {
        synchronized(lines) {
            lines.addLast(ScriptLogLine(nextSeq++, clock(), level, handler, entryId, message))
            while (lines.size > logCapacity) lines.removeFirst()
        }
    }

    /** Lines with `seq >= since`; [ScriptLogPage.skipped] counts those the ring already dropped. */
    fun logs(since: Long): ScriptLogPage = synchronized(lines) {
        val first = lines.firstOrNull()?.seq ?: nextSeq
        ScriptLogPage(
            lines = lines.filter { it.seq >= since },
            next = nextSeq,
            skipped = (first - since).coerceAtLeast(0),
        )
    }

    fun status(withSource: Boolean = true): ScriptStatus {
        val script = active ?: return ScriptStatus(active = false, unavailable = unavailable)
        return ScriptStatus(
            active = true,
            id = script.id,
            installedAtMs = script.installedAtMs,
            persisted = script.persisted,
            unavailable = unavailable,
            source = if (withSource) script.source else null,
            handlers = ScriptHandler.entries.filter { it in script.script.handlers },
            invocations = invocations.get(),
            changes = changes.get(),
            errors = errors.get(),
        )
    }

    companion object {
        fun idOf(source: String): String =
            MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(12)
    }
}
