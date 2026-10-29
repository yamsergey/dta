package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonGoneException
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonServerTest {
    private val identity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1)
    private val home = Files.createTempDirectory("dta-server").toFile()
    private val state = StateFile(java.io.File(home, "daemon"))
    private val fake = FakeOperations()
    private val stopped = AtomicReference<String?>()
    private val server: DaemonServer = DaemonServer(
        identity, state, OperationDispatcher(fake, Selector(fake::devices, fake::apps)), fake, Selector(fake::devices, fake::apps),
        status = { clients -> DaemonStatus(identity, 1, 5, home.path, AdbInfo("/fake/adb", true), clients, fake.devices(), emptyList()) },
        onStop = { reason -> stopped.set(reason); serverStop(reason) },
        subscriberBuffer = 4,
    ).also { it.start() }

    private fun serverStop(reason: String) = server.stop(reason)

    @AfterTest
    fun tearDown() = server.stop(null)

    private fun connect(kind: ClientKind = ClientKind.CLI, id: VersionIdentity = identity) =
        runBlocking { DaemonClient.connect(server.port, state.token()!!, kind, id) }

    @Test
    fun `state file and owner-only token`() {
        val s = assertNotNull(state.read())
        assertEquals(server.port, s.port)
        assertEquals("0.1.0", s.version)
        val perms = Files.getPosixFilePermissions(state.tokenFile.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `upgrade without the token is refused`() {
        val e = assertFailsWith<CompletionException> {
            HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer wrong")
                .buildAsync(URI("ws://127.0.0.1:${server.port}/"), object : WebSocket.Listener {}).join()
        }
        assertTrue("401" in e.toString() || "Unauthorized" in e.toString() || "handshake" in e.toString().lowercase(), e.toString())
    }

    @Test
    fun `hello returns the daemon identity and calls carry the selection`() = runBlocking<Unit> {
        val client = connect()
        assertEquals(identity, client.daemon.identity)
        val selected = client.call("network.clear")
        assertEquals("emulator-5554", selected.selection!!.device)
        assertEquals(3, selected.result.jsonObject["clearedEntries"]!!.jsonPrimitive.content.toInt())
        val e = assertFailsWith<DaemonCallException> { client.call("network.teleport") }
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, e.code)
        client.close()
    }

    @Test
    fun `status lists the connected clients`() = runBlocking<Unit> {
        val cli = connect(ClientKind.CLI)
        val mcp = connect(ClientKind.MCP)
        val status = cli.call("daemon.status").result.jsonObject
        assertEquals(setOf("cli", "mcp"), status["clients"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }.toSet())
        cli.close(); mcp.close()
    }

    private fun entry(n: Int) = NetworkEntryEvent(
        HttpEntry("r1-1:$n", "r1-1", n.toLong(), "okhttp3", method = "GET", url = "https://api.example.com/$n", state = HttpState.COMPLETE, status = 200),
        EntryChange.CREATED,
    )

    @Test
    fun `each subscriber gets the events, filtered`() = runBlocking<Unit> {
        val a = connect()
        val b = connect()
        val fromA = async { withTimeout(5_000) { a.networkEvents(buildJsonObject { put("url", "/2") }).take(2).toList() } }
        val fromB = async { withTimeout(5_000) { b.networkEvents(JsonObject(emptyMap())).take(3).toList() } }
        delay(300)
        (1..2).forEach { fake.events.emit(entry(it)) }
        val first = fromA.await()
        assertTrue(first[0].containsKey("selection"))
        assertEquals("r1-1:2", first[1]["event"]!!.jsonObject["entry"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("r1-1:1", "r1-1:2"), fromB.await().drop(1).map { it["event"]!!.jsonObject["entry"]!!.jsonObject["id"]!!.jsonPrimitive.content })
        a.close(); b.close()
    }

    @Test
    fun `stop tells clients why and removes the state file`() = runBlocking<Unit> {
        val watcher = connect()
        val stopper = connect()
        val events = async {
            runCatching { withTimeout(5_000) { watcher.networkEvents(JsonObject(emptyMap())).collect { } } }.exceptionOrNull()
        }
        delay(300)
        stopper.call("daemon.stop")
        val error = events.await()
        assertTrue(error is DaemonGoneException, "$error")
        assertTrue("stopped" in error.message!!, error.message)
        withTimeout(3_000) { while (state.stateFile.exists()) delay(50) }
        assertNotNull(stopped.get())
    }

    @Test
    fun `only a newer client may replace the daemon`() = runBlocking<Unit> {
        val older = connect(id = identity.copy(build = "2026-10-27T10:00:00Z"))
        val e = assertFailsWith<DaemonCallException> { older.request("daemon.replace", DaemonClient.replaceParams(older.daemon.identity.copy(build = "2026-10-27T10:00:00Z"))) }
        assertEquals(ErrorCodes.VERSION_INCOMPATIBLE, e.code)
        assertNull(stopped.get())

        val bystander = connect()
        val gone = async { runCatching { withTimeout(5_000) { bystander.allEvents.first { it.name == "daemon.replaced" } } }.getOrNull() }
        delay(200)
        val newer = identity.copy(build = "2026-10-29T10:00:00Z")
        val replacer = connect(id = newer)
        replacer.request("daemon.replace", DaemonClient.replaceParams(newer))
        assertNotNull(gone.await())
        withTimeout(3_000) { while (stopped.get() == null) delay(50) }
        assertTrue("replaced" in stopped.get()!!)
        assertFalse(state.stateFile.exists())
    }

    @Test
    fun `one client may send from many coroutines at once`() = runBlocking<Unit> {
        val client = connect()
        val results = (1..50).map { async(kotlinx.coroutines.Dispatchers.IO) { client.call("devices.list") } }.map { it.await() }
        assertEquals(50, results.size)
        client.close()
    }
}
