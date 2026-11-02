package io.yamsergey.dta.sidekick.capability

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.AppInstallation
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION

/**
 * Named on-device capabilities with live status suppliers. Statuses are read
 * at snapshot time so reports reflect *now*, and a throwing supplier degrades
 * only its own capability — visibly, with the captured reason (constitution III).
 */
class CapabilityRegistry {

    private val suppliers = LinkedHashMap<String, () -> CapabilityStatus>()

    @Synchronized
    fun register(name: String, statusSupplier: () -> CapabilityStatus) {
        suppliers[name] = statusSupplier
    }

    @Synchronized
    fun snapshot(): List<CapabilityStatus> = suppliers.map { (name, supplier) ->
        try {
            supplier()
        } catch (e: Exception) {
            CapabilityStatus(
                name = name,
                status = CapabilityStatus.Status.DEGRADED,
                reason = Reasons.STATUS_UNREADABLE,
                detail = "${e.javaClass.name}: ${e.message?.lineSequence()?.firstOrNull()?.trim()}",
            )
        }
    }

    fun buildIdentity(packageName: String, sidekickVersion: String, app: AppInstallation? = null): IdentityReport =
        IdentityReport(
            protocolVersion = PROTOCOL_VERSION,
            sidekickVersion = sidekickVersion,
            packageName = packageName,
            capabilities = snapshot(),
            app = app,
        )
}
