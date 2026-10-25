package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.LibraryStatus
import io.yamsergey.dta.protocol.network.NetworkBounds
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.protocol.network.ScriptSummary
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.network.store.StoreBounds

/**
 * What capture knows about one library. [unsupported] marks a library present in the app
 * whose traffic capture does not cover by design (gRPC). It is stated in the library list
 * but does not degrade the capability: presence alone (often a transitive dependency)
 * does not mean any traffic went uncaptured.
 */
data class LibraryState(val name: String, val present: Boolean, val installFailure: String?, val unsupported: String? = null)

/**
 * The `network` capability status (contract §1): unavailable when hooks cannot attach,
 * degraded when a library the app uses could not be hooked; absent and uncovered (gRPC) libraries are listed
 * but do not degrade. Pure, so every reason is tested without a device.
 */
object NetworkCapability {
    const val NAME = "network"
    const val ABSENT = "library not present in app"
    const val GRPC_UNSUPPORTED = "present in app; gRPC uses its own transport, which network capture does not cover"

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

    fun status(attachFailure: String?, libraries: List<LibraryState>, bounds: StoreBounds, script: ScriptSummary? = null): CapabilityStatus {
        val statuses = libraries.map { lib ->
            when {
                !lib.present -> LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, ABSENT)
                attachFailure != null -> LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, "hooks unavailable: $attachFailure")
                lib.installFailure != null -> LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, lib.installFailure)
                lib.unsupported != null -> LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, lib.unsupported)
                else -> LibraryStatus(lib.name, CapabilityStatus.Status.AVAILABLE)
            }
        }
        val failed = libraries.filter { it.present && it.installFailure != null }
        val problems = failed.map { "${it.name} not captured: ${it.installFailure}" } +
            listOfNotNull(script?.unavailable?.let { "network script ${script.id} not applied: $it" })
        val (status, reason) = when {
            attachFailure != null -> CapabilityStatus.Status.UNAVAILABLE to "hooks unavailable: $attachFailure"
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
        )
    }
}
