package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SC-007 fault-injection, end to end deviceless: every disabled capability
 * surfaces in the identity report with a reason, and responses assembled from
 * degraded paths carry the marker — through the REAL wire, both directions.
 * (Supplier-failure capture itself is covered by CapabilityRegistryTest in
 * the sidekick module; this proves the visibility across the channel.)
 */
class DegradationVisibilityTest {

    @Test
    fun `degraded and unavailable capabilities reach the daemon with reasons`() = runBlocking {
        val registry = MethodRegistry().apply {
            register("identity") {
                DtaJson.encodeToJsonElement(
                    IdentityReport.serializer(),
                    IdentityReport(
                        protocolVersion = PROTOCOL_VERSION,
                        sidekickVersion = "0.1.0",
                        packageName = "com.example.fixture",
                        capabilities = listOf(
                            CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE),
                            CapabilityStatus(
                                "layout",
                                CapabilityStatus.Status.DEGRADED,
                                "reflection target moved: LayoutNode children accessor absent",
                            ),
                            CapabilityStatus(
                                "network",
                                CapabilityStatus.Status.UNAVAILABLE,
                                "shim not attached (API < 28)",
                            ),
                        ),
                    ),
                )
            }
        }

        TestWsServer(registry).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            val identity = DtaJson.decodeFromJsonElement(
                IdentityReport.serializer(),
                connection.request("identity"),
            )

            val byName = identity.capabilities.associateBy { it.name }
            assertEquals(3, byName.size, "zero silent omissions: every capability reports")
            assertEquals(CapabilityStatus.Status.AVAILABLE, byName["channel"]!!.status)
            assertEquals(CapabilityStatus.Status.DEGRADED, byName["layout"]!!.status)
            assertTrue("reflection target moved" in byName["layout"]!!.reason!!)
            assertEquals(CapabilityStatus.Status.UNAVAILABLE, byName["network"]!!.status)
            assertTrue("shim not attached" in byName["network"]!!.reason!!)
            connection.close()
        }
    }

    @Test
    fun `degraded markers travel the wire to requestFull`() = runBlocking {
        val registry = MethodRegistry().apply {
            register("layout_tree") { ctx ->
                ctx.markDegraded("compose", "slot table empty — composition predates inspection enable")
                buildJsonObject { }
            }
        }

        TestWsServer(registry).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            val full = connection.requestFull("layout_tree")

            val marker = full.degraded!!.single()
            assertEquals("compose", marker.capability)
            assertTrue("slot table empty" in marker.reason)
            connection.close()
        }
    }
}
