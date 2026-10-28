package io.yamsergey.dta.protocol.ops

import io.yamsergey.dta.protocol.ops.VersionIdentity.Comparison.CLIENT_NEWER
import io.yamsergey.dta.protocol.ops.VersionIdentity.Comparison.CLIENT_OLDER
import io.yamsergey.dta.protocol.ops.VersionIdentity.Comparison.SAME
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionIdentityTest {
    private fun v(version: String, build: String = "2026-10-28T10:00:00Z", protocol: Int = 1) = VersionIdentity(version, build, protocol)

    @Test
    fun `equal identities are the same`() = assertEquals(SAME, VersionIdentity.compare(v("0.1.0"), v("0.1.0")))

    @Test
    fun `semantic version decides first`() {
        assertEquals(CLIENT_NEWER, VersionIdentity.compare(client = v("0.2.0"), daemon = v("0.1.9")))
        assertEquals(CLIENT_OLDER, VersionIdentity.compare(client = v("0.1.9"), daemon = v("0.10.0")))
        assertEquals(CLIENT_NEWER, VersionIdentity.compare(client = v("1.0.0"), daemon = v("1.0.0-SNAPSHOT.9")))
    }

    @Test
    fun `snapshot numbers order development builds`() {
        assertEquals(CLIENT_NEWER, VersionIdentity.compare(client = v("0.9.37-SNAPSHOT.12"), daemon = v("0.9.37-SNAPSHOT.9")))
        assertEquals(CLIENT_OLDER, VersionIdentity.compare(client = v("0.9.37-SNAPSHOT.9"), daemon = v("0.9.37-SNAPSHOT.12")))
    }

    @Test
    fun `build time orders two builds of the same version`() {
        assertEquals(CLIENT_NEWER, VersionIdentity.compare(client = v("0.1.0", "2026-10-28T11:00:00Z"), daemon = v("0.1.0", "2026-10-28T10:00:00Z")))
        assertEquals(CLIENT_OLDER, VersionIdentity.compare(client = v("0.1.0", "2026-10-28T09:00:00Z"), daemon = v("0.1.0", "2026-10-28T10:00:00Z")))
    }

    @Test
    fun `protocol version outranks everything`() {
        assertEquals(CLIENT_NEWER, VersionIdentity.compare(client = v("0.1.0", protocol = 2), daemon = v("0.9.0", protocol = 1)))
        assertEquals(CLIENT_OLDER, VersionIdentity.compare(client = v("0.9.0", protocol = 1), daemon = v("0.1.0", protocol = 2)))
    }

    @Test
    fun `display names version and build`() = assertEquals("0.1.0 (build 2026-10-28T10:00:00Z)", v("0.1.0").display)
}
