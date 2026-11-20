package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stored format has a version; each version says how the one before it becomes it (2026-11-20). */
class VersionedDatabaseTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "x.db")

    private fun version(c: Connection) = c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
    private fun rows(c: Connection, table: String) = c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getInt(1) } }
    private fun columns(c: Connection, table: String) =
        c.createStatement().use { st -> st.executeQuery("PRAGMA table_info($table)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toList() } }

    private val v1 = StoreSchema(1, listOf("CREATE TABLE t(a TEXT)"), mapOf(1 to Migration.StartOver("before versions")))

    private fun open(schema: StoreSchema, startedOver: MutableList<String> = mutableListOf()) = VersionedDatabase.open(file, schema) { startedOver += it }

    @Test
    fun `a new store is made at the current version, without any migration`() {
        val startedOver = mutableListOf<String>()
        open(v1, startedOver).use { c ->
            assertEquals(1, version(c))
            assertEquals(listOf("a"), columns(c, "t"))
        }
        assertEquals(emptyList(), startedOver)
    }

    @Test
    fun `a store at the current version is opened as it is`() {
        open(v1).use { c -> c.createStatement().use { it.execute("INSERT INTO t VALUES ('kept')") } }
        open(v1).use { c -> assertEquals(1, rows(c, "t")) }
    }

    @Test
    fun `a store from before versions is made anew by the rule of version 1`() {
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE old(x)"); it.execute("INSERT INTO old VALUES (1)") }
        }
        val startedOver = mutableListOf<String>()
        open(v1, startedOver).use { c ->
            assertEquals(1, version(c))
            assertEquals(0, rows(c, "t"))
            assertTrue(c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='old'").use { it.next(); it.getInt(1) } } == 0)
        }
        assertEquals(listOf("before versions"), startedOver)
    }

    @Test
    fun `steps move a store one version at a time and keep its data`() {
        open(v1).use { c -> c.createStatement().use { it.execute("INSERT INTO t VALUES ('kept')") } }
        val v3 = StoreSchema(
            3, listOf("CREATE TABLE t(a TEXT, b TEXT, c TEXT)"),
            mapOf(1 to Migration.StartOver("before versions"), 2 to Migration.Steps(listOf("ALTER TABLE t ADD COLUMN b TEXT")), 3 to Migration.Steps(listOf("ALTER TABLE t ADD COLUMN c TEXT"))),
        )
        open(v3).use { c ->
            assertEquals(3, version(c))
            assertEquals(listOf("a", "b", "c"), columns(c, "t"))
            assertEquals(1, rows(c, "t"))
        }
    }

    @Test
    fun `a start over on the way makes the store anew at the current version`() {
        open(v1).use { c -> c.createStatement().use { it.execute("INSERT INTO t VALUES ('gone')") } }
        val v2 = StoreSchema(2, listOf("CREATE TABLE u(z TEXT)"), mapOf(1 to Migration.StartOver("before versions"), 2 to Migration.StartOver("u replaces t")))
        val startedOver = mutableListOf<String>()
        open(v2, startedOver).use { c ->
            assertEquals(2, version(c))
            assertEquals(0, rows(c, "u"))
            assertEquals(emptyList(), columns(c, "t"))
        }
        assertEquals(listOf("u replaces t"), startedOver)
    }

    @Test
    fun `a failed step leaves the store at the version before it`() {
        open(v1).use { }
        val bad = StoreSchema(2, listOf("CREATE TABLE t(a TEXT, b TEXT)"), mapOf(1 to Migration.StartOver("before versions"), 2 to Migration.Steps(listOf("ALTER TABLE t ADD COLUMN b TEXT", "NOT SQL"))))
        assertFailsWith<java.sql.SQLException> { open(bad) }
        open(v1).use { c -> assertEquals(listOf("a"), columns(c, "t")) }
    }

    @Test
    fun `a store written by a newer version is refused and left as it is`() {
        open(StoreSchema(2, listOf("CREATE TABLE t(a TEXT)"), mapOf(1 to Migration.StartOver("x"), 2 to Migration.Steps(emptyList())))).use { c ->
            c.createStatement().use { it.execute("INSERT INTO t VALUES ('newer')") }
        }
        val e = assertFailsWith<NewerStoreException> { open(v1) }
        assertTrue("version 2" in e.message!!, e.message)
        open(StoreSchema(2, listOf("CREATE TABLE t(a TEXT)"), mapOf(1 to Migration.StartOver("x"), 2 to Migration.Steps(emptyList())))).use { c -> assertEquals(1, rows(c, "t")) }
    }

    @Test
    fun `a version without a rule from the one before is a mistake found at once`() {
        open(v1).use { }
        assertFailsWith<IllegalStateException> { open(StoreSchema(2, listOf("CREATE TABLE t(a TEXT)"), mapOf(1 to Migration.StartOver("x")))) }
    }

    @Test
    fun `the host archive from before versions is cleared with its bodies`() {
        val root = File(dir, "store")
        HostStore(root).use { s ->
            val e = s.openEpoch(DeviceIdentity("dev1"), "io.example", InstallationInfo(), "j1", 1)
            s.putEntries(e.id, listOf(HttpEntry("r1-1:1", "r1-1", 1, "okhttp3", method = "GET", url = "https://x", state = HttpState.COMPLETE)))
            s.putBody(e.id, "r1-1:1", BodyPart.RESPONSE, java.io.ByteArrayInputStream(ByteArray(10)))
            s.partialFile(e.id, "r1-1:2", BodyPart.RESPONSE).writeBytes(ByteArray(10))
        }
        DriverManager.getConnection("jdbc:sqlite:${File(root, "dta.db").path}").use { c -> c.createStatement().use { it.execute("PRAGMA user_version=0") } }
        HostStore(root).use { s ->
            assertNull(s.liveEpoch("dev1", "io.example"))
            assertEquals(emptyList(), s.listing().devices)
            assertTrue(File(root, "bodies").walk().none { it.isFile }, "bodies cleared")
            assertTrue(File(root, "partial").walk().none { it.isFile }, "bodies being copied cleared")
            assertTrue(s.startedOver != null)
        }
    }

    private fun oldHostArchive(root: File) {
        HostStore(root).use { s ->
            val e = s.openEpoch(DeviceIdentity("dev1"), "io.example", InstallationInfo(), "j1", 1)
            s.putBody(e.id, "r1-1:1", BodyPart.RESPONSE, java.io.ByteArrayInputStream(ByteArray(10)))
            s.partialFile(e.id, "r1-1:2", BodyPart.RESPONSE).writeBytes(ByteArray(10))
        }
        DriverManager.getConnection("jdbc:sqlite:${File(root, "dta.db").path}").use { c -> c.createStatement().use { it.execute("PRAGMA user_version=0") } }
    }

    @Test
    fun `clearing the host archive does not wait for its body files to be removed`() {
        val root = File(dir, "store")
        oldHostArchive(root)
        val held = mutableListOf<File>()
        val remover = DirRemover { held += it }
        HostStore(root, FileBodyStore(File(root, "bodies"), remover), remover).use { s ->
            assertTrue(File(root, "bodies").walk().none { it.isFile }, "no body is served from the old archive")
            assertTrue(File(root, "partial").walk().none { it.isFile })
            assertEquals(2, held.size, "both left to be removed: $held")
            assertTrue(held.all { it.exists() && it.parentFile == root })
            val e = s.openEpoch(DeviceIdentity("dev1"), "io.example", InstallationInfo(), "j2", 2)
            s.putBody(e.id, "r1-1:1", BodyPart.RESPONSE, java.io.ByteArrayInputStream(ByteArray(10)))
            assertEquals(10, s.bodyRef(e.id, "r1-1:1", BodyPart.RESPONSE)?.sha256?.let(s::openBody)?.use { it.readBytes().size }, "new bodies are stored meanwhile")
        }
        HostStore(root, remover = DirRemover { it.deleteRecursively() }).use { }
        assertEquals(emptyList(), root.list()!!.filter { DirRemover.REMOVING in it }, "a removal cut short is finished at the next start")
    }

    @Test
    fun `body files are removed in the background`() {
        val root = File(dir, "store")
        oldHostArchive(root)
        HostStore(root).use { }
        val until = System.currentTimeMillis() + 5_000
        while (root.list()!!.any { DirRemover.REMOVING in it } && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(emptyList(), root.list()!!.filter { DirRemover.REMOVING in it })
    }
}
