package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.AppRule
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.Scope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectorTest {
    private val pixel = DeviceInfo("emulator-5554", "online", "Pixel 8")
    private val phone = DeviceInfo("R5CT", "online", "Galaxy")
    private val offline = DeviceInfo("OFF1", "offline")

    private fun selector(devices: List<DeviceInfo>, foreground: String? = "com.example.shop", withDta: List<String> = listOf("com.example.shop")) =
        Selector(devices = { devices }, apps = { serial -> AppList(serial, foreground, withDta.map { AppInfo(it, it == foreground) }) })

    @Test
    fun `no scope selects nothing`() = assertNull(selector(emptyList()).resolve(Scope.NONE, null, null).statement)

    @Test
    fun `no device`() {
        val e = assertFailsWith<ClientException> { selector(listOf(offline)).resolve(Scope.DEVICE, null, null) }
        assertEquals(ClientErrorCodes.NO_DEVICE, e.code)
    }

    @Test
    fun `the only online device is chosen and stated`() {
        val r = selector(listOf(pixel, offline)).resolve(Scope.DEVICE, null, null)
        assertEquals("emulator-5554", r.serial)
        assertEquals(DeviceRule.ONLY_DEVICE, r.statement!!.deviceRule)
    }

    @Test
    fun `several devices need a choice and are listed`() {
        val e = assertFailsWith<ClientException> { selector(listOf(pixel, phone)).resolve(Scope.DEVICE, null, null) }
        assertEquals(ClientErrorCodes.SEVERAL_DEVICES, e.code)
        assertEquals(listOf("emulator-5554", "R5CT"), e.data!!["devices"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("--device" in e.message!! || "device" in e.message!!)
    }

    @Test
    fun `a named device must be online`() {
        assertEquals(DeviceRule.NAMED, selector(listOf(pixel, phone)).resolve(Scope.DEVICE, "R5CT", null).statement!!.deviceRule)
        assertEquals(ClientErrorCodes.NOT_FOUND, assertFailsWith<ClientException> { selector(listOf(pixel)).resolve(Scope.DEVICE, "nope", null) }.code)
        assertEquals(ClientErrorCodes.NOT_AVAILABLE, assertFailsWith<ClientException> { selector(listOf(pixel, offline)).resolve(Scope.DEVICE, "OFF1", null) }.code)
    }

    @Test
    fun `omitted app means the foreground app when it has DTA`() {
        val r = selector(listOf(pixel)).resolve(Scope.APP, null, null)
        assertEquals("com.example.shop", r.pkg)
        assertEquals(AppRule.FOREGROUND, r.statement!!.appRule)
    }

    @Test
    fun `foreground app without DTA is refused with the apps that have it`() {
        val e = assertFailsWith<ClientException> {
            selector(listOf(pixel), foreground = "com.android.settings", withDta = listOf("com.example.shop")).resolve(Scope.APP, null, null)
        }
        assertEquals(ClientErrorCodes.NO_APP, e.code)
        assertEquals("com.android.settings", e.data!!["foreground"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.example.shop"), e.data!!["appsWithDta"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a named app is used verbatim`() {
        val r = selector(listOf(pixel)).resolve(Scope.APP, null, "com.other")
        assertEquals("com.other", r.pkg)
        assertEquals(AppRule.NAMED, r.statement!!.appRule)
    }

    @Test
    fun `layout without DTA in the foreground app falls back and says so`() {
        val r = selector(listOf(pixel), foreground = "com.android.settings", withDta = emptyList()).resolve(Scope.APP_OR_FALLBACK, null, null)
        assertNull(r.pkg)
        assertNull(r.statement!!.app)
        assertTrue(r.statement!!.note!!.contains("com.android.settings"))
    }
}
