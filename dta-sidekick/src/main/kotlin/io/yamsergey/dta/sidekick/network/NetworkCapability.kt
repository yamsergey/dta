package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.LibraryStatus
import io.yamsergey.dta.protocol.network.NetworkBounds
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.protocol.network.ScriptSummary
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.capability.Reasons
import io.yamsergey.dta.sidekick.network.store.StoreBounds

/**
 * What capture knows about one library. [unsupported] marks a library present in the app
 * whose traffic capture does not cover by design (gRPC). It is stated in the library list
 * but does not degrade the capability: presence alone (often a transitive dependency)
 * does not mean any traffic went uncaptured.
 */
data class LibraryState(
    val name: String,
    val present: Boolean,
    /** The whole library could not be set up (plain text); [installFailureDetail] is the raw cause. */
    val installFailure: String?,
    val unsupported: String? = null,
    /** Per-event hook results (feature 019); the status is folded from them by [LibraryCoverage]. */
    val events: List<EventResult> = emptyList(),
    val installFailureDetail: String? = null,
    /** An [unsupported] library whose presence does mean traffic goes uncaptured (the Ktor client on CIO): it degrades. */
    val degrades: Boolean = false,
)

/**
 * The `network` capability status (contract §1): unavailable when hooks cannot attach,
 * degraded when a library the app uses is not fully captured (per event, feature 019); absent and uncovered (gRPC) libraries are listed
 * but do not degrade. Pure, so every reason is tested without a device.
 */
object NetworkCapability {
    const val NAME = "network"
    const val ABSENT = LibraryCoverage.ABSENT
    const val GRPC_UNSUPPORTED = "the app includes gRPC, which uses its own transport that network capture does not cover"

    /** gRPC is only detected, never hooked (feature 006, FR-019). */
    fun probeGrpc(loader: ClassLoader): LibraryState {
        val present = try {
            loader.loadClass("io.grpc.ManagedChannel")
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
        return LibraryState(NetworkLibraries.GRPC, present, installFailure = null, unsupported = if (present) GRPC_UNSUPPORTED else null)
    }

    /** Ktor client engines by the class each adds, and whether capture sees their traffic (through OkHttp / URLConnection). */
    private val KTOR_ENGINES = linkedMapOf(
        "okhttp" to ("io.ktor.client.engine.okhttp.OkHttp" to true),
        "android" to ("io.ktor.client.engine.android.Android" to true),
        "cio" to ("io.ktor.client.engine.cio.CIO" to false),
        "java" to ("io.ktor.client.engine.java.Java" to false),
    )

    /** The Ktor client and its engines in the app (feature 030): detected, never hooked itself. */
    fun probeKtor(loader: ClassLoader): LibraryState {
        val present = runCatching { loader.loadClass("io.ktor.client.HttpClient") }.isSuccess
        if (!present) return ktor(emptySet())
        return ktor(KTOR_ENGINES.filterValues { (cls, _) -> runCatching { loader.loadClass(cls) }.isSuccess }.keys)
    }

    /** What the Ktor client with [engines] means for capture. Pure. */
    fun ktor(engines: Set<String>): LibraryState {
        if (engines.isEmpty()) return LibraryState(NetworkLibraries.KTOR, present = false, installFailure = null)
        val blind = engines.filter { KTOR_ENGINES[it]?.second == false }
        if (blind.isEmpty()) return LibraryState(NetworkLibraries.KTOR, present = true, installFailure = null)
        val names = blind.joinToString(" and ") { if (it == "cio") "CIO" else it.replaceFirstChar(Char::uppercase) }
        return LibraryState(
            NetworkLibraries.KTOR, present = true, installFailure = null, degrades = true,
            unsupported = "the app includes the Ktor client with the $names engine${if (blind.size > 1) "s" else ""}, which open${if (blind.size > 1) "" else "s"} " +
                "${if (blind.size > 1) "their" else "its"} own connections: HTTP requests and WebSockets sent through it are not captured " +
                "(Ktor's OkHttp and Android engines are; switch the debug build to one of them to see this traffic)",
        )
    }

    fun status(
        attachFailure: String?,
        libraries: List<LibraryState>,
        bounds: StoreBounds,
        script: ScriptSummary? = null,
        attachDetail: String? = null,
    ): CapabilityStatus {
        val statuses = libraries.map { lib ->
            when {
                lib.present && lib.unsupported == null && attachFailure != null ->
                    LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, Reasons.partOffBecauseHooks(Reasons.LIBRARY_NOT_CAPTURED), notCaptured = listOf(LibraryCoverage.ALL_TRAFFIC))
                else -> LibraryCoverage.of(lib)
            }
        }
        // Absent and uncovered-by-design (gRPC) libraries are listed but never degrade.
        val uncovered = libraries.filter { !it.present || (it.unsupported != null && !it.degrades) }.map { it.name }.toSet()
        val problems = statuses.filter { it.name !in uncovered && it.status != CapabilityStatus.Status.AVAILABLE }
            .map { "${it.name}: ${it.reason}" } +
            listOfNotNull(script?.unavailable?.let { Reasons.scriptNotApplied(script.id, it) })
        val (status, reason) = when {
            attachFailure != null -> CapabilityStatus.Status.UNAVAILABLE to Reasons.offBecauseHooks(Reasons.NO_TRAFFIC, attachFailure)
            problems.isNotEmpty() -> CapabilityStatus.Status.DEGRADED to problems.joinToString("; ")
            else -> CapabilityStatus.Status.AVAILABLE to null
        }
        return CapabilityStatus(
            name = NAME,
            status = status,
            reason = reason,
            libraries = statuses,
            bounds = NetworkBounds(historyBytes = bounds.historyBytes, bodyBytes = bounds.bodyBytes),
            script = script,
            detail = attachDetail.takeIf { attachFailure != null },
        )
    }
}
