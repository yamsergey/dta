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
)

/**
 * The `network` capability status (contract §1): unavailable when hooks cannot attach,
 * degraded when a library the app uses is not fully captured (per event, feature 019); absent and uncovered (gRPC) libraries are listed
 * but do not degrade. Pure, so every reason is tested without a device.
 */
object NetworkCapability {
    const val NAME = "network"
    const val ABSENT = LibraryCoverage.ABSENT
    const val GRPC_UNSUPPORTED = "gRPC calls are not captured: the app includes gRPC, which uses its own transport that network capture does not cover"

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
        val uncovered = libraries.filter { !it.present || it.unsupported != null }.map { it.name }.toSet()
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
