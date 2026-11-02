package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.InjectionSource
import io.yamsergey.dta.protocol.network.WsPushResult
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.sidekick.capability.Reasons
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * `network_ws_push` (feature 020, contract §2): pushes a client's message into an open WebSocket
 * connection and answers once it was delivered.
 *
 * @param unavailable why hooks are off, or null
 */
class WsPushMethods(private val injector: () -> WsInjector?, private val unavailable: () -> String?) {

    fun install(registry: MethodRegistry) {
        registry.register("network_ws_push") { ctx -> DtaJson.encodeToJsonElement(WsPushResult.serializer(), push(ctx.params)) }
    }

    // java.util.Base64 is API 26; it is reached only with hooks on, which need API 28 (checked first).
    @android.annotation.SuppressLint("NewApi")
    fun push(params: JsonObject?): WsPushResult {
        val text = params.string("text")
        val base64 = params.string("base64")
        if ((text == null) == (base64 == null)) throw InvalidParamsException("give exactly one of text or base64")
        unavailable()?.let { throw IllegalStateException(Reasons.offBecauseHooks("pushes cannot be delivered", it)) }
        val bytes = base64?.let {
            try {
                Base64.getDecoder().decode(it)
            } catch (e: IllegalArgumentException) {
                throw InvalidParamsException("base64 is not valid base64: ${e.message}")
            }
        }
        val waitMs = (params?.get("waitMs") as? JsonPrimitive)?.longOrNull ?: DEFAULT_WAIT_MS
        if (waitMs < 0) throw InvalidParamsException("waitMs must be 0 or more")
        val injector = injector() ?: throw IllegalStateException("pushes cannot be delivered: DTA's network capture is not running in this app")
        val future = try {
            injector.push(params.string("connection"), PushMessage(text, bytes), PushSource(InjectionSource.CLIENT), waitMs)
        } catch (e: PushException) {
            throw IllegalStateException(e.message)
        }
        return when (val outcome = future.get(waitMs + DELIVERY_GRACE_MS, TimeUnit.MILLISECONDS)) {
            is PushOutcome.Delivered -> WsPushResult(outcome.entryId, outcome.connectionId, outcome.delivery, outcome.waitedMs, outcome.listenerError)
            is PushOutcome.Failed -> throw IllegalStateException(outcome.reason)
        }
    }

    private fun JsonObject?.string(name: String): String? = (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val DEFAULT_WAIT_MS = 1000L

        /** Time for the delivery itself after the wait: the app's listener runs in it. */
        const val DELIVERY_GRACE_MS = 30_000L
    }
}
