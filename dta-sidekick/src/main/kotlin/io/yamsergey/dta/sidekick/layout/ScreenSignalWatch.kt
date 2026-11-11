package io.yamsergey.dta.sidekick.layout

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import io.yamsergey.dta.protocol.layout.LayoutChanged
import io.yamsergey.dta.protocol.layout.LeaseAnswer
import io.yamsergey.dta.protocol.layout.ScreenSignal
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.sidekick.layout.compose.ComposePresence
import io.yamsergey.dta.shim.ShimReflect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feature 057: the lease and the coalescing of "the screen changed", pure (no Android). Marks
 * are cheap (one atomic OR) so main-thread listeners only call [mark]; [tick] runs elsewhere.
 */
class ScreenSignalCore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLeaseMs: Long = ScreenSignal.MAX_LEASE_MS,
    private val minGapMs: Long = ScreenSignal.MIN_GAP_MS,
) {
    enum class Reason(val bit: Int, val label: String) { LAYOUT(1, "layout"), WINDOWS(2, "windows"), COMPOSE(4, "compose") }

    @Volatile var until = 0L
        private set
    private val dirty = AtomicInteger(0)
    @Volatile private var lastSent = Long.MIN_VALUE / 2

    val leased: Boolean get() = clock() < until

    /** Arms or renews until now + [ms] (capped); 0 stops. True when this armed a watch that was off. */
    @Synchronized
    fun lease(ms: Long): Boolean {
        val was = leased
        until = if (ms <= 0) 0 else clock() + ms.coerceAtMost(maxLeaseMs)
        if (!was) dirty.set(0)
        return !was && leased
    }

    fun mark(reason: Reason) {
        if (until != 0L) dirty.getAndUpdate { it or reason.bit }
    }

    /** The notice to send now, if anything changed and the last one is old enough. */
    fun tick(): LayoutChanged? {
        if (!leased) { dirty.set(0); return null }
        val now = clock()
        if (now - lastSent < minGapMs) return null
        val bits = dirty.getAndSet(0)
        if (bits == 0) return null
        lastSent = now
        return LayoutChanged(now, Reason.entries.filter { bits and it.bit != 0 }.map { it.label })
    }
}

/**
 * Feature 057: the app method [ScreenSignal.METHOD] and what it runs while leased: a
 * global-layout listener on each window root (roots compared every 500 ms on the main thread),
 * a Compose apply observer when Compose is present, and a ticker that publishes
 * [ScreenSignal.APP_EVENT]. When the lease ends every listener is removed.
 */
class ScreenSignalWatch(
    private val publish: (String, JsonElement?) -> Unit,
    private val presence: ComposePresence = ComposePresence(),
    private val core: ScreenSignalCore = ScreenSignalCore(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val roots = HashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()
    private var compose: Any? = null
    @Volatile private var ticker: Thread? = null

    fun install(registry: MethodRegistry) {
        registry.register(ScreenSignal.METHOD) { ctx ->
            val ms = ctx.params?.get("leaseMs")?.jsonPrimitive?.longOrNull ?: 10_000L
            if (core.lease(ms)) start()
            DtaJson.encodeToJsonElement(LeaseAnswer.serializer(), LeaseAnswer(core.leased, core.until, listOfNotNull("views", "compose".takeIf { presence.composePresent })))
        }
    }

    @Synchronized
    private fun start() {
        if (ticker?.isAlive == true) return
        ticker = Thread({ run() }, "dta-screen-signal").apply { isDaemon = true; start() }
    }

    private fun run() {
        main.post { guard { attachCompose(); scanRoots() } }
        var n = 0
        while (core.leased) {
            try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { break }
            if (++n % 2 == 0) main.post { guard { scanRoots() } }
            core.tick()?.let { publish(ScreenSignal.APP_EVENT, DtaJson.encodeToJsonElement(LayoutChanged.serializer(), it)) }
        }
        main.post { guard { detach() } }
    }

    /** Main thread: new roots get a listener (a window appeared), gone ones are forgotten. */
    private fun scanRoots() {
        if (!core.leased) return
        val now = currentRoots().toSet()
        var changed = false
        (roots.keys - now).forEach { v -> roots.remove(v)?.let { l -> runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(l) } }; changed = true }
        (now - roots.keys).forEach { v ->
            val l = ViewTreeObserver.OnGlobalLayoutListener { core.mark(ScreenSignalCore.Reason.LAYOUT) }
            v.viewTreeObserver.addOnGlobalLayoutListener(l)
            roots[v] = l
            changed = true
        }
        if (changed) core.mark(ScreenSignalCore.Reason.WINDOWS)
    }

    private fun detach() {
        roots.forEach { (v, l) -> runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(l) } }
        roots.clear()
        compose?.let { handle -> runCatching { handle.javaClass.getMethod("dispose").invoke(handle) } }
        compose = null
    }

    /** `Snapshot.registerApplyObserver`: called after state writes are applied; it only marks. */
    private fun attachCompose() {
        if (compose != null || !presence.composePresent) return
        compose = runCatching {
            androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { _, _ -> core.mark(ScreenSignalCore.Reason.COMPOSE) }
        }.onFailure { Log.w(TAG, "no Compose apply observer: ${it.message}") }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentRoots(): List<View> = runCatching {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val global = ShimReflect.call(wmg, null, "getInstance") ?: return emptyList()
        (ShimReflect.field(global, "mViews") as? List<View>)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "screen signal: ${t.javaClass.simpleName}: ${t.message}") }
    }

    private companion object {
        const val TAG = "DtaScreenSignal"
        const val TICK_MS = 250L
    }
}
