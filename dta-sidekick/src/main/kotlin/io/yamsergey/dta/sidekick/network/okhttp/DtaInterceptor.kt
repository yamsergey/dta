package io.yamsergey.dta.sidekick.network.okhttp

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.BodyRecorder
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.TeeInputStream
import io.yamsergey.dta.sidekick.network.TeeOutputStream
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.io.OutputStream

/**
 * Network interceptor injected into every OkHttpClient (Android Studio's model). Sees each
 * request as it goes on the wire, and each redirect as its own exchange. Only
 * `chain.proceed` is outside the safety net: any capture failure (including an API the
 * app's OkHttp version lacks) leaves the app's request and response untouched.
 *
 * Request bodies are written a second time into the tee, as Android Studio does;
 * one-shot and duplex bodies cannot be written twice and are stated as omitted.
 * Response bodies are teed as the app reads them — never pre-buffered (FR-018).
 */
internal class DtaInterceptor(
    private val recorder: NetworkRecorder,
    private val contexts: CallContexts,
    private val initiators: Initiators,
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val id = try {
            startExchange(request, initiators.forCall(chain.call(), InitiatorSite.CALL)).also { contexts.entryCreated(chain.call(), it) }
        } catch (_: Throwable) {
            null
        }
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            id?.let { recorder.httpFailed(it, e) }
            throw e
        } catch (e: RuntimeException) {
            id?.let { recorder.httpFailed(it, e) }
            throw e
        }
        if (id == null) return response
        return try {
            trackResponse(id, request, response)
        } catch (_: Throwable) {
            response
        }
    }

    private fun startExchange(request: Request, initiator: Initiator): String {
        val id = recorder.httpStarted(
            library = NetworkLibraries.OKHTTP3,
            method = request.method,
            url = request.url.toString(),
            headers = headers(request.headers),
            initiator = initiator,
        )
        val body = request.body ?: return id
        val recorderForBody = recorder.httpRequestBody(id, body.contentType()?.toString(), body.contentLength(), request.header("Content-Encoding"))
        when {
            runCatching { body.isDuplex() }.getOrDefault(false) ->
                omit(recorderForBody, "duplex request body: streamed concurrently with the response, cannot be observed without altering it")
            runCatching { body.isOneShot() }.getOrDefault(false) ->
                omit(recorderForBody, "one-shot request body: it can be written only once, by the app's own call")
            else -> {
                val sink = TeeOutputStream(NullOutputStream, recorderForBody).sink().buffer()
                try {
                    body.writeTo(sink)
                } finally {
                    sink.close()
                }
            }
        }
        return id
    }

    private fun omit(body: BodyRecorder, reason: String) = body.omit(reason)

    private fun trackResponse(id: String, request: Request, response: Response): Response {
        recorder.httpResponse(id, response.code, headers(response.headers), response.protocol.toString())
        val body = response.body
        if (body == null || !promisesBody(request, response)) {
            recorder.httpComplete(id)
            return response
        }
        val bodyRecorder = recorder.httpResponseBody(
            id,
            contentType = body.contentType()?.toString(),
            encoding = response.header("Content-Encoding"),
            declaredBytes = body.contentLength(),
        )
        val teed = TeeInputStream(body.source().inputStream(), bodyRecorder).source().buffer()
        @Suppress("DEPRECATION")
        val wrapped = ResponseBody.create(body.contentType(), body.contentLength(), teed)
        return response.newBuilder().body(wrapped).build()
    }

    /** OkHttp's own rule (HttpHeaders.promisesBody): HEAD, 1xx, 204 and 304 carry no body. */
    private fun promisesBody(request: Request, response: Response): Boolean {
        if (request.method == "HEAD") return false
        val code = response.code
        if ((code < 100 || code >= 200) && code != 204 && code != 304) return true
        val length = response.header("Content-Length")?.toLongOrNull() ?: -1
        return length > 0 || "chunked".equals(response.header("Transfer-Encoding"), ignoreCase = true)
    }

    private fun headers(headers: Headers): List<Header> = (0 until headers.size).map { Header(headers.name(it), headers.value(it)) }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
