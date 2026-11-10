package io.yamsergey.dta.sidekick.layout.compose

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Feature 054: the packages the app ships, read from the type tables of its dex files
 * (header, string_ids, type_ids; no class data). Public paths only, no hidden API.
 */
object DexPackages {
    private val DEX_ENTRY = Regex("classes\\d*\\.dex")

    /** The packages of every `classes*.dex` in [apks] (base and split APKs). */
    fun read(apks: List<File>): Set<String> {
        val out = HashSet<String>()
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filter { DEX_ENTRY.matches(it.name) }.forEach { e ->
                    out += packagesOf(zip.getInputStream(e).use { it.readBytes() })
                }
            }
        }
        return out
    }

    /** Packages of the class descriptors (`La/b/C;`) in one dex; throws on a malformed dex. */
    fun packagesOf(dex: ByteArray): Set<String> {
        check(dex.size >= 0x70 && dex[0] == 'd'.code.toByte() && dex[1] == 'e'.code.toByte() && dex[2] == 'x'.code.toByte()) { "not a dex file" }
        val b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsOff = b.getInt(0x3C)
        val typeCount = b.getInt(0x40)
        val typeIdsOff = b.getInt(0x44)
        val stringCount = b.getInt(0x38)
        fun inside(off: Int, len: Long) = off >= 0 && off + len <= dex.size
        check(inside(typeIdsOff, 4L * typeCount) && inside(stringIdsOff, 4L * stringCount)) { "dex tables outside the file" }
        val out = HashSet<String>()
        for (i in 0 until typeCount) {
            val stringIdx = b.getInt(typeIdsOff + 4 * i)
            check(stringIdx in 0 until stringCount) { "dex type $i names no string" }
            var at = b.getInt(stringIdsOff + 4 * stringIdx)
            check(at in 0 until dex.size) { "dex string $stringIdx outside the file" }
            while (dex[at].toInt() and 0x80 != 0) at++ // uleb128 length
            at++
            if (at >= dex.size || dex[at] != 'L'.code.toByte()) continue
            var end = at
            var lastSlash = -1
            while (end < dex.size && dex[end] != ';'.code.toByte() && dex[end] != 0.toByte()) {
                if (dex[end] == '/'.code.toByte()) lastSlash = end
                end++
            }
            if (lastSlash < 0) continue // default package
            out += String(dex, at + 1, lastSlash - at - 1, Charsets.UTF_8).replace('/', '.')
        }
        return out
    }
}

/** The packages a Compose package hash may name. */
interface PackageIndexLookup {
    fun packagesOf(hash: Int): List<String>
}

/** Packages by Compose's package hash; a hash may name several packages. */
class PackageIndex(packages: Set<String>) : PackageIndexLookup {
    private val byHash: Map<Int, List<String>> = packages.groupBy(ComposableClassifier::packageHash)
    val size: Int = packages.size

    override fun packagesOf(hash: Int): List<String> = byHash[hash].orEmpty()
}

/**
 * Builds the [PackageIndex] once, on its own low-priority thread; readers wait for it with a
 * time limit and never on the main thread (constitution I).
 */
class PackageIndexHolder(private val packages: () -> Set<String>) {
    sealed interface State {
        data object NotReady : State
        data class Ready(val index: PackageIndex, val tookMs: Long) : State
        data class Failed(val reason: String) : State
    }

    @Volatile private var state: State = State.NotReady
    private val done = java.util.concurrent.CountDownLatch(1)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Starts the build once; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val t0 = System.nanoTime()
            state = try {
                State.Ready(PackageIndex(packages()), (System.nanoTime() - t0) / 1_000_000)
            } catch (t: Throwable) {
                State.Failed(t.message ?: t.javaClass.simpleName)
            }
            done.countDown()
        }, "dta-package-index").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** The state after waiting at most [timeoutMs] for the build (NotReady if not started or not done). */
    fun await(timeoutMs: Long): State {
        if (started.get()) done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return state
    }

    companion object {
        @Volatile private var app: PackageIndexHolder? = null

        /** The app's holder (base and split APKs of [context]), started on first use. */
        fun of(context: android.content.Context): PackageIndexHolder = app ?: synchronized(this) {
            app ?: PackageIndexHolder {
                val info = context.applicationInfo
                DexPackages.read((listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()).map(::File))
            }.also { app = it }
        }.also { it.start() }
    }
}
