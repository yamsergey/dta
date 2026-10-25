package io.yamsergey.dta.sidekick.network.okhttp

import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.InterventionRecord
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.script.ActiveScript
import io.yamsergey.dta.sidekick.network.script.BodySource
import io.yamsergey.dta.sidekick.network.script.Effect
import io.yamsergey.dta.sidekick.network.script.InterventionDiff
import io.yamsergey.dta.sidekick.network.script.RequestChange
import io.yamsergey.dta.sidekick.network.script.ResponseChange
import io.yamsergey.dta.sidekick.network.script.ScriptRequest
import io.yamsergey.dta.sidekick.network.script.ScriptResponse
import io.yamsergey.dta.sidekick.network.script.ScriptResult
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import io.yamsergey.dta.sidekick.network.script.ThrottleInputStream
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import okio.source
import java.io.IOException

/**
 * Our one application-level OkHttp interceptor, placed first:
 * - runs the network script (feature 005) on the request **before the connection** — so a
 *   mock or a drop needs no server, and a delay delays the whole call — and on the final
 *   response, deciding what the app receives;
 * - records calls that fail before reaching the network layer (refused, DNS, TLS), which
 *   [DtaInterceptor] cannot see.
 *
 * The wire view stays [DtaInterceptor]'s; this interceptor adds what the script did and,
 * when it changed something, the app-side request/response. Script failures never reach
 * the app: the original traffic passes (FR-021). With no script, it only watches failures.
 */
internal class ScriptInterceptor(
    private val recorder: NetworkRecorder,
    private val contexts: CallContexts,
    private val initiators: Initiators,
    private val scripts: ScriptRuntime,
    private val bodyBound: Long,
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val active = scripts.current()
        val original = chain.request()
        var request = original
        var requestResult: ScriptResult<RequestChange>? = null

        if (active != null && ScriptHandler.REQUEST in active.script.handlers) {
            val result = active.script.onRequest(scriptRequest(original))
            requestResult = result
            val record = InterventionDiff.record(active.id, ScriptHandler.REQUEST, result)
            when (val effect = result.effect) {
                Effect.Drop -> {
                    val reason = "dropped by DTA network script ${active.id}"
                    val id = recorder.httpDropped(NetworkLibraries.OKHTTP3, original.method, original.url.toString(), headers(original.headers),
                        initiators.forCall(chain.call(), InitiatorSite.CALL), reason, record!!)
                    scripts.account(ScriptHandler.REQUEST, result, id)
                    throw IOException(reason)
                }
                is Effect.Mock -> {
                    val r = effect.response
                    val id = recorder.httpMocked(NetworkLibraries.OKHTTP3, original.method, original.url.toString(), headers(original.headers),
                        initiators.forCall(chain.call(), InitiatorSite.CALL), r.status, r.headers, r.body, record!!)
                    scripts.account(ScriptHandler.REQUEST, result, id)
                    return mockResponse(original, r.status, r.headers, r.body)
                }
                is Effect.Change -> request = applyRequest(original, effect.change) ?: run {
                    requestResult = result.copy(effect = Effect.None, error = "onRequest: the change could not be applied to the request")
                    original
                }
                Effect.None -> Unit
            }
        }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            failedBeforeNetwork(chain, request, e)
            finish(active, chain, original, request, requestResult, null, null)
            throw e
        }

        var delivered = response
        var responseResult: ScriptResult<ResponseChange>? = null
        if (active != null && ScriptHandler.RESPONSE in active.script.handlers) {
            val result = active.script.onResponse(scriptResponse(response), scriptRequest(request))
            responseResult = result
            when (val effect = result.effect) {
                Effect.Drop -> {
                    response.close()
                    finish(active, chain, original, request, requestResult, result, null)
                    throw IOException("response dropped by DTA network script ${active.id}")
                }
                is Effect.Change -> delivered = applyResponse(response, effect.change) ?: run {
                    responseResult = result.copy(effect = Effect.None, error = "onResponse: the change could not be applied to the response")
                    response
                }
                else -> Unit
            }
            result.throttleBytesPerSecond?.takeIf { responseResult?.error == null }?.let { rate -> delivered = throttled(delivered, rate) }
        }
        finish(active, chain, original, request, requestResult, responseResult, delivered.takeIf { it !== response })
        return delivered
    }

    /** Attaches what the script did to every network entry of the call (FR-016/017). */
    private fun finish(
        active: ActiveScript?,
        chain: Interceptor.Chain,
        original: Request,
        sent: Request,
        requestResult: ScriptResult<RequestChange>?,
        responseResult: ScriptResult<ResponseChange>?,
        changedResponse: Response?,
    ) {
        if (active == null) return
        try {
            val ids = contexts.entries(chain.call())
            requestResult?.let { scripts.account(ScriptHandler.REQUEST, it, ids.firstOrNull()) }
            responseResult?.let { scripts.account(ScriptHandler.RESPONSE, it, ids.lastOrNull()) }
            val record: InterventionRecord = InterventionDiff.merge(
                requestResult?.let { InterventionDiff.record(active.id, ScriptHandler.REQUEST, it) },
                responseResult?.let { InterventionDiff.record(active.id, ScriptHandler.RESPONSE, it) },
            ) ?: return
            for (id in ids) recordOn(id, record)
            if (sent !== original) {
                for (id in ids) {
                    recorder.httpAppRequest(id, original.url.toString(), original.method, headers(original.headers),
                        readRequestBody(original), original.body?.contentType()?.toString())
                }
            }
            if (changedResponse != null) {
                ids.lastOrNull()?.let { id ->
                    recorder.httpAppResponse(id, changedResponse.code, headers(changedResponse.headers),
                        changedResponse.peekBody(bodyBound).bytes(), changedResponse.body?.contentType()?.toString())
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun recordOn(id: String, record: InterventionRecord) = recorder.recordIntervention(id, record)

    private fun failedBeforeNetwork(chain: Interceptor.Chain, request: Request, error: IOException) {
        try {
            if (contexts.entries(chain.call()).isNotEmpty()) return
            val id = recorder.httpStarted(NetworkLibraries.OKHTTP3, request.method, request.url.toString(), headers(request.headers),
                initiators.forCall(chain.call(), InitiatorSite.CALL))
            recorder.httpFailed(id, error)
            contexts.entryCreated(chain.call(), id)
        } catch (_: Throwable) {
        }
    }

    // ---- views and rebuilding ---------------------------------------------------------

    private fun scriptRequest(request: Request): ScriptRequest {
        val body = request.body
        val source = body?.let {
            val type = it.contentType()?.toString()
            when {
                runCatching { it.isDuplex() }.getOrDefault(false) -> BodySource(type, false, "duplex request body", null)
                runCatching { it.isOneShot() }.getOrDefault(false) -> BodySource(type, false, "one-shot request body", null)
                else -> BodySource(type, type != null && BodyCodec.isBinary(type, ByteArray(0))) { readRequestBody(request) ?: ByteArray(0) }
            }
        }
        return ScriptRequest(request.url.toString(), request.method, headers(request.headers), NetworkLibraries.OKHTTP3, null, source)
    }

    private fun scriptResponse(response: Response): ScriptResponse {
        val type = response.body?.contentType()?.toString()
        val source = response.body?.let {
            BodySource(type, type != null && BodyCodec.isBinary(type, ByteArray(0))) { decoded(response) }
        }
        return ScriptResponse(response.code, headers(response.headers), source)
    }

    /** The response body as the app would read it (bounded peek; the app's stream is untouched). */
    private fun decoded(response: Response): ByteArray = response.peekBody(bodyBound).bytes()

    private fun readRequestBody(request: Request): ByteArray? {
        val body = request.body ?: return null
        if (runCatching { body.isDuplex() || body.isOneShot() }.getOrDefault(false)) return null
        return Buffer().also { body.writeTo(it) }.readByteArray()
    }

    private fun applyRequest(original: Request, change: RequestChange): Request? = try {
        val builder = original.newBuilder()
        change.url?.let { builder.url(it) }
        change.headers?.let { builder.headers(headersOf(it)) }
        if (change.method != null || change.body != null) {
            val method = change.method ?: original.method
            @Suppress("DEPRECATION")
            val body = change.body?.let {
                val type = (change.headers ?: headers(original.headers)).firstOrNull { h -> h.name.equals("Content-Type", true) }?.value
                    ?: original.body?.contentType()?.toString()
                RequestBody.create(type?.toMediaTypeOrNull(), it)
            } ?: original.body
            builder.method(method, if (method == "GET" || method == "HEAD") null else body)
        }
        builder.build()
    } catch (_: Throwable) {
        null
    }

    private fun applyResponse(response: Response, change: ResponseChange): Response? = try {
        val builder = response.newBuilder()
        change.status?.let { builder.code(it) }
        change.headers?.let { builder.headers(headersOf(it)) }
        change.body?.let { bytes ->
            val type = (change.headers ?: headers(response.headers)).firstOrNull { it.name.equals("Content-Type", true) }?.value
            response.body?.close()
            @Suppress("DEPRECATION")
            builder.body(ResponseBody.create(type?.toMediaTypeOrNull(), bytes))
            builder.removeHeader("Content-Length")
            builder.removeHeader("Content-Encoding")
        }
        builder.build()
    } catch (_: Throwable) {
        null
    }

    private fun throttled(response: Response, bytesPerSecond: Long): Response = try {
        val body = response.body ?: return response
        val slow = ThrottleInputStream(body.source().inputStream(), bytesPerSecond).source().buffer()
        @Suppress("DEPRECATION")
        response.newBuilder().body(ResponseBody.create(body.contentType(), body.contentLength(), slow)).build()
    } catch (_: Throwable) {
        response
    }

    private fun mockResponse(request: Request, status: Int, headers: List<Header>, body: ByteArray): Response {
        val type = headers.firstOrNull { it.name.equals("Content-Type", true) }?.value
        @Suppress("DEPRECATION")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Scripted by DTA")
            .headers(headersOf(headers))
            .body(ResponseBody.create(type?.toMediaTypeOrNull(), body))
            .build()
    }

    private fun headers(headers: Headers): List<Header> = (0 until headers.size).map { Header(headers.name(it), headers.value(it)) }

    private fun headersOf(list: List<Header>): Headers = Headers.Builder().apply { list.forEach { add(it.name, it.value) } }.build()
}
