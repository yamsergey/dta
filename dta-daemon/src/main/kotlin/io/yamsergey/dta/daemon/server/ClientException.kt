package io.yamsergey.dta.daemon.server

import kotlinx.serialization.json.JsonObject

/** An operation failure a client can act on: one of [io.yamsergey.dta.protocol.ops.ClientErrorCodes], a message naming the next step, data. */
class ClientException(val code: Int, message: String, val data: JsonObject? = null) : Exception(message)
