package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.ClientKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-114 (071 FR-008): the dispatcher leaves a cancelled call to the server (CANCELLED); a superseded view keeps its own code. */
class DispatcherCancelTest {
    private val fake = FakeOperations()
    private val dispatcher = OperationDispatcher(fake, Selector(fake::devices, fake::apps))

    @AfterTest
    fun tearDown() { fake.searches.close() }

    private fun params(q: String) = JsonObject(mapOf("q" to JsonPrimitive(q)))

    @Test
    fun `a cancelled call stays a cancellation for the server to answer CANCELLED, not an INTERNAL error`() = runBlocking<Unit> {
        val call = CoroutineScope(Dispatchers.IO).async { dispatcher.dispatch("network.list", params("wait-forever"), ClientKind.CLI) }
        delay(300)
        call.cancel()
        val failure = runCatching { call.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException, "a cancellation, was $failure")
        assertFalse(failure is ClientException, "not turned into an error answer")
        assertEquals(1, fake.cancelledCalls.get())
    }

    @Test
    fun `a superseded view still answers SUPERSEDED`() = runBlocking<Unit> {
        val e = assertFailsWith<ClientException> { dispatcher.dispatch("network.list", params("superseded"), ClientKind.IDE) }
        assertEquals(ClientErrorCodes.SUPERSEDED, e.code)
    }
}
