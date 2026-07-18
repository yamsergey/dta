package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/** Connect-path failure with an actionable message (discovery contract §5). */
open class ConnectException(message: String) : Exception(message)

/** Protocol versions differ; message names both and the remedy (SC-002). */
class VersionIncompatibleException(message: String) : ConnectException(message)

/** The link died; in-flight requests fail with this (FR-009). */
class ConnectionLostException(message: String) : Exception(message)

/** A request exceeded its timeout while the link stayed up (spec edge case:
 * suspended app). The connection itself remains usable. */
class RequestTimeoutException(message: String) : Exception(message)

/** Sidekick answered with a structured error (contract §7). */
class SidekickRpcException(val code: Int, message: String, val details: JsonObject? = null) :
    Exception(message)

/** A verified duplex link to one app's sidekick. */
interface AppConnection {
    val hello: HelloResult
    val events: SharedFlow<Event>
    val isOpen: Boolean

    suspend fun request(method: String, params: JsonObject? = null, timeout: Duration? = null): JsonElement

    suspend fun close()
}
