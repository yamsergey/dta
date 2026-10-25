package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.ScriptLogLevel
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkScriptMethodsTest {

    @TempDir
    lateinit var dir: File

    private var unavailable: String? = null
    private val runtime = ScriptRuntime(clock = { 100 })
    private val methods by lazy { NetworkScriptMethods(runtime, ScriptStore(dir)) { unavailable } }

    private fun params(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun set(source: String) = methods.set(JsonObject(mapOf("source" to kotlinx.serialization.json.JsonPrimitive(source))))

    @Test
    fun `a valid script becomes active and persisted`() {
        val status = set("function onRequest(r) {}")
        assertEquals(true, status.active)
        assertEquals(true, status.persisted)
        assertEquals("function onRequest(r) {}", ScriptStore(dir).load()!!.source)
        assertEquals(100, ScriptStore(dir).load()!!.installedAtMs)
    }

    @Test
    fun `an invalid script is rejected with its location and the previous one stays`() {
        val first = set("function onRequest(r) {}").id
        val e = assertFailsWith<InvalidParamsException> { set("function onRequest(r) {\n  return r.;\n}") }
        assertTrue("does not compile" in e.message!! && "line 2" in e.message!!, e.message)
        assertEquals(first, methods.status().id)
        assertEquals("function onRequest(r) {}", ScriptStore(dir).load()!!.source)
    }

    @Test
    fun `a script without handlers or without source is invalid`() {
        assertFailsWith<InvalidParamsException> { set("var x = 1;") }
        assertFailsWith<InvalidParamsException> { methods.set(null) }
        assertFailsWith<InvalidParamsException> { set("x".repeat(NetworkScriptMethods.MAX_SOURCE_CHARS + 1)) }
    }

    @Test
    fun `intervention unavailable is an error with the reason`() {
        unavailable = "requires API 28+ (device is API 26)"
        val e = assertFailsWith<IllegalStateException> { set("function onRequest(r) {}") }
        assertTrue("API 28" in e.message!!)
    }

    @Test
    fun `clear deactivates and forgets the persisted script`() {
        set("function onRequest(r) {}")
        assertEquals(true, methods.clear().cleared)
        assertNull(ScriptStore(dir).load())
        assertEquals(false, methods.status().active)
        assertEquals(false, methods.clear().cleared)
    }

    @Test
    fun `status shows the source and logs page by position`() {
        set("function onRequest(r) {}")
        assertEquals("function onRequest(r) {}", methods.status().source)
        repeat(3) { runtime.log(ScriptLogLevel.LOG, null, null, "l$it") }
        assertEquals(listOf("l1", "l2"), methods.logs(params("""{"since":1}""")).lines.map { it.message })
        assertEquals(3, methods.logs(null).lines.size)
    }

    @Test
    fun `a script that cannot persist says so`() {
        val unusable = NetworkScriptMethods(runtime, ScriptStore(File(dir, "f").apply { writeText("x") })) { null }
        val status = unusable.set(JsonObject(mapOf("source" to kotlinx.serialization.json.JsonPrimitive("function onRequest(r) {}"))))
        assertEquals(false, status.persisted)
        assertEquals(listOf(DegradationCodes.NETWORK_SCRIPT_NOT_PERSISTED), runtime.degradations())
    }
}
