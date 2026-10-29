package io.yamsergey.dta.present.daemon

import io.yamsergey.dta.present.StatementKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonViewTest {
    private fun canned(name: String): JsonObject = Json.parseToJsonElement(javaClass.getResource("/canned/$name.json")!!.readText()).jsonObject

    @Test
    fun `status shows version, adb, clients, devices and app connections`() {
        val view = DaemonView.from(canned("daemon-status"), capability = null)
        assertEquals("0.10.0 (build 2026-10-29T10:00:00Z)", view.version)
        assertEquals("pid 4242 · up 1m 5s · /Users/dev/.dta", view.process)
        assertEquals("/sdk/platform-tools/adb", view.adb)
        assertNull(view.adbProblem)
        assertEquals(listOf("ide 0.10.0", "cli 0.10.0"), view.clients)
        assertEquals(listOf("emulator-5554 online Pixel 8"), view.devices)
        assertEquals(listOf("emulator-5554 com.example.shop"), view.connections)
        assertEquals(emptyList(), view.capabilities)
    }

    @Test
    fun `an unavailable adb is a degraded statement with the reason`() {
        val status = JsonObject(canned("daemon-status") + ("adb" to Json.parseToJsonElement("""{"available":false,"reason":"adb not found (looked in DTA_ADB, ANDROID_HOME)"}""")))
        val view = DaemonView.from(status, capability = null)
        assertEquals(StatementKind.DEGRADED, view.adbProblem!!.kind)
        assertTrue("adb not found" in view.adbProblem!!.detail)
    }

    @Test
    fun `capabilities list every capability and library, gaps with status and reason`() {
        val view = DaemonView.from(canned("daemon-status"), canned("capability"))
        assertEquals("sidekick 0.10.0 in com.example.shop", view.sidekick)
        val rows = view.capabilities.associateBy { it.name }
        assertEquals(listOf("layout", "network", "okhttp3", "grpc", "java-websocket"), view.capabilities.map { it.name })
        assertNull(rows.getValue("okhttp3").statement)
        assertEquals(1, rows.getValue("okhttp3").depth)
        assertEquals(StatementKind.NOT_CAPTURED, rows.getValue("grpc").statement!!.kind)
        assertEquals(StatementKind.DEGRADED, rows.getValue("java-websocket").statement!!.kind)
        assertEquals("hook failed: NoSuchMethodError", rows.getValue("java-websocket").reason)
    }
}
