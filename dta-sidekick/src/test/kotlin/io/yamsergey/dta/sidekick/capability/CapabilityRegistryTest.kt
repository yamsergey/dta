package io.yamsergey.dta.sidekick.capability

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityRegistryTest {

    @Test
    fun `statuses are evaluated at snapshot time, not registration time`() {
        val registry = CapabilityRegistry()
        var healthy = true
        registry.register("network") {
            if (healthy) {
                CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)
            } else {
                CapabilityStatus("network", CapabilityStatus.Status.DEGRADED, "hooks detached")
            }
        }

        assertEquals(CapabilityStatus.Status.AVAILABLE, registry.snapshot().single().status)
        healthy = false
        assertEquals(CapabilityStatus.Status.DEGRADED, registry.snapshot().single().status)
    }

    @Test
    fun `throwing supplier degrades that capability with the captured reason and spares the rest`() {
        val registry = CapabilityRegistry()
        registry.register("channel") { CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE) }
        registry.register("layout") { error("reflection target moved in this Compose version") }

        val statuses = registry.snapshot().associateBy { it.name }
        assertEquals(CapabilityStatus.Status.AVAILABLE, statuses["channel"]!!.status)
        val layout = statuses["layout"]!!
        assertEquals(CapabilityStatus.Status.DEGRADED, layout.status)
        assertTrue("reflection target moved" in layout.reason!!)
    }

    @Test
    fun `identity report carries protocol version, package, sidekick version and all capabilities`() {
        val registry = CapabilityRegistry()
        registry.register("channel") { CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE) }

        val identity = registry.buildIdentity(
            packageName = "com.example.fixture",
            sidekickVersion = "0.1.0",
        )
        assertEquals(PROTOCOL_VERSION, identity.protocolVersion)
        assertEquals("com.example.fixture", identity.packageName)
        assertEquals("0.1.0", identity.sidekickVersion)
        assertEquals(listOf("channel"), identity.capabilities.map { it.name })
    }

    @Test
    fun `registering the same capability twice keeps the latest supplier`() {
        val registry = CapabilityRegistry()
        registry.register("channel") { CapabilityStatus("channel", CapabilityStatus.Status.UNAVAILABLE, "old") }
        registry.register("channel") { CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE) }

        assertEquals(CapabilityStatus.Status.AVAILABLE, registry.snapshot().single().status)
    }
}
