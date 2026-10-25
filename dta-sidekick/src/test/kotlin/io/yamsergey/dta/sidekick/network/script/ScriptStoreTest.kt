package io.yamsergey.dta.sidekick.network.script

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a saved script loads back with its install time`() {
        val store = ScriptStore(dir)
        assertTrue(store.save("function onRequest(r) {}", installedAtMs = 42))
        val loaded = ScriptStore(dir).load()!!
        assertEquals("function onRequest(r) {}", loaded.source)
        assertEquals(42, loaded.installedAtMs)
    }

    @Test
    fun `nothing saved loads nothing`() {
        assertNull(ScriptStore(dir).load())
    }

    @Test
    fun `clear deletes the script`() {
        val store = ScriptStore(dir)
        store.save("function onRequest(r) {}", 1)
        store.clear()
        assertNull(store.load())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("script") })
    }

    @Test
    fun `an unusable location cannot persist`() {
        val notADir = File(dir, "file").apply { writeText("x") }
        val store = ScriptStore(notADir)
        assertFalse(store.save("function onRequest(r) {}", 1))
        assertNull(store.load())
    }

    @Test
    fun `no location at all cannot persist`() {
        assertFalse(ScriptStore(null).save("function onRequest(r) {}", 1))
    }
}
