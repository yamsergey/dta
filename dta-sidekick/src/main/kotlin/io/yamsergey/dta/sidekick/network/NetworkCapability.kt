package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.LibraryStatus
import io.yamsergey.dta.protocol.network.NetworkBounds
import io.yamsergey.dta.protocol.network.ScriptSummary
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.network.store.StoreBounds

/** What capture knows about one supported library. */
data class LibraryState(val name: String, val present: Boolean, val installFailure: String?)

/**
 * The `network` capability status (contract §1): unavailable when hooks cannot attach,
 * degraded when a library the app uses could not be hooked; absent libraries are listed
 * but do not degrade. Pure, so every reason is tested without a device.
 */
object NetworkCapability {
    const val NAME = "network"
    const val ABSENT = "library not present in app"

    fun status(attachFailure: String?, libraries: List<LibraryState>, bounds: StoreBounds, script: ScriptSummary? = null): CapabilityStatus {
        val statuses = libraries.map { lib ->
            when {
                !lib.present -> LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, ABSENT)
                attachFailure != null -> LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, "hooks unavailable: $attachFailure")
                lib.installFailure != null -> LibraryStatus(lib.name, CapabilityStatus.Status.UNAVAILABLE, lib.installFailure)
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
