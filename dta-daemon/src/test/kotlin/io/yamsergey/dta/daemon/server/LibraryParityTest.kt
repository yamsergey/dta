package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.DtaDaemon
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FR-023, daemon column: every public operation of the library is served by the daemon
 * process (through [DaemonOperations] and the dispatcher). The CLI and MCP columns live in
 * dta-cli's ParityTest.
 */
class LibraryParityTest {
    /** Library members that are plumbing, not user operations. */
    private val notOperations = setOf(
        "startupSweep", "getDiscovery", "getConnections", "shutdown", "adbInfo", "openConnections", "foregroundPackage",
    )

    @Test
    fun `every public library operation is a daemon operation`() {
        val library = DtaDaemon::class.java.methods
            .filter { it.declaringClass == DtaDaemon::class.java && Modifier.isPublic(it.modifiers) && !it.name.contains('$') }
            .map { it.name }.toSet() - notOperations
        val served = DaemonOperations::class.java.methods.map { it.name }.toSet()
        assertEquals(emptySet(), library - served, "library operations the daemon does not serve")
        assertEquals(emptySet(), served - library, "daemon operations the library does not have")
    }
}
