package io.yamsergey.dta.client

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.VersionIdentity

class VersionInfoAndHomeTest {

    @Test
    fun `version info is read from properties`() {
        val props = Properties().apply {
            setProperty("version", "0.2.0")
            setProperty("build", "2026-10-28T10:00:00Z")
            setProperty("sha", "abc1234")
        }
        val info = VersionInfo.from(props)
        assertEquals("0.2.0", info.version)
        assertEquals("2026-10-28T10:00:00Z", info.build)
        assertEquals("abc1234", info.sha)
        assertNull(info.missingReason)
    }

    @Test
    fun `the version resource ships in dta-client, so the CLI and the plugin read the same one`() {
        val info = VersionInfo.current()
        assertNull(info.missingReason, info.missingReason)
        assertEquals(System.getProperty("dta.expectedVersion"), info.version)
        java.time.Instant.parse(info.build)
    }

    @Test
    fun `missing properties give a dev version with the reason stated`() {
        val info = VersionInfo.from(null)
        assertEquals("dev", info.version)
        assertNotNull(info.missingReason)
    }

    @Test
    fun `DTA_HOME wins over the system property and the user home`() {
        assertEquals(File("/tmp/a"), DtaHome.resolve(env = mapOf("DTA_HOME" to "/tmp/a"), property = "/tmp/b", userHome = "/u").root)
        assertEquals(File("/tmp/b"), DtaHome.resolve(env = emptyMap(), property = "/tmp/b", userHome = "/u").root)
        val home = DtaHome.resolve(env = emptyMap(), property = null, userHome = "/u")
        assertEquals(File("/u/.dta"), home.root)
        assertEquals(File("/u/.dta/daemon"), home.daemonDir)
    }

    @Test
    fun `a version conflict tells the plugin's clients to update the plugin`() {
        val daemon = VersionIdentity("0.3.0", "2026-10-30T10:00:00Z", 1)
        val client = VersionIdentity("0.2.0", "2026-10-28T10:00:00Z", 1)
        for (kind in listOf(ClientKind.IDE, ClientKind.MCP_HTTP)) {
            assertTrue("Update the DTA plugin" in VersionConflictException(daemon, client, kind).message!!, "$kind")
        }
        assertTrue("Upgrade dta" in VersionConflictException(daemon, client, ClientKind.MCP).message!!)
    }
}
