package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowStoreTest {
    private val dir: File = Files.createTempDirectory("flows").toFile()
    private val store = FlowStore(dir)
    private val d = "emulator-5554"
    private val a = "com.example.app"

    @Test
    fun `a start after the newest row, named or Flow N`() {
        val named = store.start(d, a, "Buy ACME", newestMs = 1_000).flow
        assertEquals("f1", named.id)
        assertEquals("Buy ACME", named.name)
        assertEquals(1_001, named.startMs)
        assertTrue(named.recording)
        store.end(d, a, newestMs = 1_100)
        assertEquals("Flow 2", store.start(d, a, null, newestMs = 2_000).flow.name)
        store.end(d, a, newestMs = 2_100)
        assertEquals("Flow 3", store.start(d, a, "   ", newestMs = 3_000).flow.name)
    }

    @Test
    fun `with nothing recorded it starts from the beginning`() {
        assertEquals(0, store.start(d, a, null, newestMs = null).flow.startMs)
    }

    @Test
    fun `a second start ends the first where it starts, and says so`() {
        store.start(d, a, "One", newestMs = 1_000)
        val second = store.start(d, a, "Two", newestMs = 2_000)
        assertEquals("One", second.ended?.name)
        assertEquals(2_001, second.ended?.endMs)
        assertEquals(2_001, second.flow.startMs)
        assertEquals(listOf("Two"), store.list(d, a).filter { it.recording }.map { it.name })
    }

    @Test
    fun `an end is after the newest row, never before the start, and needs a recording flow`() {
        store.start(d, a, "One", newestMs = 1_000)
        assertEquals(1_501, store.end(d, a, newestMs = 1_500).endMs)
        assertFailsWith<InvalidParamsException> { store.end(d, a, newestMs = 1_600) }
        store.start(d, a, "Two", newestMs = 2_000)
        assertEquals(2_001, store.end(d, a, newestMs = null).endMs)
    }

    @Test
    fun `names are bounded, also on rename`() {
        val long = "x".repeat(81)
        assertFailsWith<InvalidParamsException> { store.start(d, a, long, newestMs = 1) }
        val f = store.start(d, a, "x".repeat(80), newestMs = 1).flow
        assertFailsWith<InvalidParamsException> { store.rename(d, a, f.id, long) }
        assertFailsWith<InvalidParamsException> { store.rename(d, a, f.id, "  ") }
        assertEquals("New", store.rename(d, a, f.id, " New ").name)
    }

    @Test
    fun `found by id or a unique name, several are refused with their ids, unknown is unknown`() {
        store.start(d, a, "Same", newestMs = 1)
        store.start(d, a, "Same", newestMs = 2)
        store.start(d, a, "Other", newestMs = 3)
        assertEquals("f1", store.resolve(d, a, "f1").id)
        assertEquals("f3", store.resolve(d, a, "Other").id)
        val many = assertFailsWith<InvalidParamsException> { store.resolve(d, a, "Same") }
        assertTrue("f1" in many.message!! && "f2" in many.message!!, many.message)
        assertFailsWith<FlowStore.UnknownFlow> { store.resolve(d, a, "nope") }
    }

    @Test
    fun `a removed flow goes, and its id is not reused`() {
        store.start(d, a, "One", newestMs = 1)
        store.remove(d, a, "f1")
        assertTrue(store.list(d, a).isEmpty())
        assertEquals("f2", store.start(d, a, null, newestMs = 2).flow.id)
        assertFailsWith<FlowStore.UnknownFlow> { store.remove(d, a, "f1") }
    }

    @Test
    fun `flows survive a daemon restart, a recording one still records`() {
        store.start(d, a, "One", newestMs = 1)
        store.end(d, a, newestMs = 2)
        store.start(d, a, "Two", newestMs = 3)
        val again = FlowStore(dir)
        assertEquals(listOf("One", "Two"), again.list(d, a).map { it.name })
        assertTrue(again.list(d, a).last().recording)
        assertEquals("f3", again.start(d, a, null, newestMs = 4).flow.id)
    }

    @Test
    fun `flows are per device and app, also for a device named with a port`() {
        store.start("10.0.0.2:5555", a, "One", newestMs = 1)
        assertTrue(store.list(d, a).isEmpty())
        assertTrue(store.list("10.0.0.2:5555", "com.example.other").isEmpty())
        assertEquals("One", FlowStore(dir).list("10.0.0.2:5555", a).single().name)
    }

    @Test
    fun `a damaged file is kept aside and said so`() {
        File(dir, "flows/$d").mkdirs()
        File(dir, "flows/$d/$a.json").writeText("{ not json")
        val fresh = FlowStore(dir)
        assertTrue(fresh.list(d, a).isEmpty())
        assertNotNull(fresh.problem(d, a))
        assertTrue(File(dir, "flows/$d/$a.json.bad").isFile)
        fresh.start(d, a, "One", newestMs = 1)
        assertNull(fresh.problem(d, a))
    }
}
