package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.script.ActiveScript
import io.yamsergey.dta.sidekick.network.script.BodySource
import io.yamsergey.dta.sidekick.network.script.Effect
import io.yamsergey.dta.sidekick.network.script.InterventionDiff
import io.yamsergey.dta.sidekick.network.script.ScriptRequest
import io.yamsergey.dta.sidekick.network.script.ScriptResponse
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import io.yamsergey.dta.sidekick.network.script.ThrottleInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.Permission

/**
 * Delegating HttpURLConnection returned in place of the platform's (Android Studio's
 * model). Every call forwards to the delegate; the tracker observes connect, the request
 * body, the response and the body streams.
 *
 * Feature 005: the network script decides at **commit** (the first call that needs the
 * network). While a script defines `onRequest`, the request body is buffered so the
 * script can read and replace it; a changed URL, method or headers opens a fresh
 * delegate. Mocked and script-changed responses are served by this wrapper.
 */
internal class TrackedHttpURLConnection(
    delegate: HttpURLConnection,
    private val recorder: NetworkRecorder,
    initiator: Initiator,
    private val scripts: ScriptRuntime,
    private val openRaw: (URL) -> HttpURLConnection,
) : HttpURLConnection(delegate.url) {

    @Volatile
    private var target: HttpURLConnection = delegate
    private val tracker = UrlConnectionTracker({ target }, recorder, initiator)
    private val script: ActiveScript? by lazy { scripts.current() }

    private var committed = false
    private var failure: IOException? = null
    private var buffered: ByteArrayOutputStream? = null
    private var served: Served? = null
    private var responseScripted = false

    /** A response this wrapper serves instead of the delegate's (mock, or changed by the script). */
    private class Served(val status: Int, val message: String?, val headers: List<Header>, val body: InputStream?) {
        fun header(name: String?): String? = headers.lastOrNull { it.name.equals(name, ignoreCase = true) }?.value
        fun fields(): MutableMap<String, MutableList<String>> =
            LinkedHashMap<String, MutableList<String>>().also { map ->
                headers.forEach { map.getOrPut(it.name) { mutableListOf() } += it.value }
            }
    }

    private val requestScripted get() = script?.let { ScriptHandler.REQUEST in it.script.handlers } == true
    private val responseScriptedHandler get() = script?.let { ScriptHandler.RESPONSE in it.script.handlers } == true

    // ---- commit: the script decides about the request ----------------------------------

    @Synchronized
    private fun commit() {
        if (!committed) {
            committed = true
            try {
                runRequestScript()
            } catch (e: IOException) {
                failure = e
            } catch (_: Throwable) {
                tracker.start()
            }
        }
        failure?.let { throw it }
    }

    private fun requestHeaders(connection: HttpURLConnection): List<Header> = try {
        connection.requestProperties.flatMap { (name, values) -> if (name == null) emptyList() else values.map { Header(name, it) } }
    } catch (_: IllegalStateException) {
        emptyList()
    }

    private fun runRequestScript() {
        val active = script
        if (active == null || !requestScripted) {
            tracker.start()
            writeBuffered(buffered?.toByteArray())
            return
        }
        val original = target
        val originalHeaders = requestHeaders(original)
        val originalBody = buffered?.toByteArray()
        val contentType = original.getRequestProperty("Content-Type")
        val result = active.script.onRequest(
            ScriptRequest(
                original.url.toString(), original.requestMethod, originalHeaders, NetworkLibraries.URLCONNECTION, null,
                originalBody?.let { b -> BodySource(contentType, contentType != null && BodyCodec.isBinary(contentType, b.copyOf(minOf(b.size, 512)))) { b } },
            ),
        )
        val record = InterventionDiff.record(active.id, ScriptHandler.REQUEST, result)
        when (val effect = result.effect) {
            Effect.Drop -> {
                val reason = "dropped by DTA network script ${active.id}"
                val id = recorder.httpDropped(NetworkLibraries.URLCONNECTION, original.requestMethod, original.url.toString(), originalHeaders, null, reason, record!!)
                scripts.account(ScriptHandler.REQUEST, result, id)
                throw IOException(reason)
            }
            is Effect.Mock -> {
                val r = effect.response
                val id = recorder.httpMocked(NetworkLibraries.URLCONNECTION, original.requestMethod, original.url.toString(), originalHeaders, null,
                    r.status, r.headers, r.body, record!!)
                scripts.account(ScriptHandler.REQUEST, result, id)
                served = Served(r.status, "Scripted by DTA", r.headers, ByteArrayInputStream(r.body))
                return
            }
            is Effect.Change -> {
                val change = effect.change
                if (change.url != null || change.method != null || change.headers != null) {
                    target = reopen(original, change.url, change.method ?: original.requestMethod, change.headers ?: originalHeaders)
                }
                tracker.start()
                writeBuffered(change.body ?: originalBody)
                tracker.id?.let { id ->
                    recorder.httpAppRequest(id, original.url.toString(), original.requestMethod, originalHeaders, originalBody, contentType)
                }
            }
            Effect.None -> {
                tracker.start()
                writeBuffered(originalBody)
            }
        }
        tracker.id?.let { id -> record?.let { recorder.recordIntervention(id, it) } }
        scripts.account(ScriptHandler.REQUEST, result, tracker.id)
    }

    private fun reopen(original: HttpURLConnection, url: String?, method: String, headers: List<Header>): HttpURLConnection {
        val fresh = openRaw(URL(url ?: original.url.toString()))
        fresh.requestMethod = method
        fresh.connectTimeout = original.connectTimeout
        fresh.readTimeout = original.readTimeout
        fresh.doInput = original.doInput
        fresh.doOutput = original.doOutput
        fresh.useCaches = original.useCaches
        fresh.instanceFollowRedirects = original.instanceFollowRedirects
        fresh.allowUserInteraction = original.allowUserInteraction
        fresh.ifModifiedSince = original.ifModifiedSince
        headers.forEach { fresh.addRequestProperty(it.name, it.value) }
        return fresh
    }

    private fun writeBuffered(body: ByteArray?) {
        if (body == null) return
        target.doOutput = true
        tracker.requestBody(target.outputStream).use { it.write(body) }
    }

    // ---- response: the script decides what the app receives ----------------------------

    @Synchronized
    private fun respond() {
        commit()
        if (served != null || responseScripted) {
            if (served == null) tracker.response()
            return
        }
        responseScripted = true
        tracker.response()
        val active = script ?: return
        if (!responseScriptedHandler) return
        try {
            val code = target.responseCode
            val headers = target.headerFields.flatMap { (name, values) -> if (name == null) emptyList() else values.map { Header(name, it) } }
            val contentType = target.contentType
            var consumed: ByteArray? = null
            val result = active.script.onResponse(
                ScriptResponse(code, headers, BodySource(contentType, contentType != null && BodyCodec.isBinary(contentType, ByteArray(0))) {
                    rawBody()?.use { it.readBytes() }?.also { consumed = it } ?: ByteArray(0)
                }),
                ScriptRequest(target.url.toString(), target.requestMethod, emptyList(), NetworkLibraries.URLCONNECTION, tracker.id, null),
            )
            val record = InterventionDiff.record(active.id, ScriptHandler.RESPONSE, result)
            when (val effect = result.effect) {
                Effect.Drop -> failure = IOException("response dropped by DTA network script ${active.id}")
                is Effect.Change -> {
                    val c = effect.change
                    val body = c.body ?: consumed ?: rawBody()?.use { it.readBytes() } ?: ByteArray(0)
                    served = Served(c.status ?: code, target.responseMessage, c.headers ?: headers, ByteArrayInputStream(body))
                    tracker.id?.let { recorder.httpAppResponse(it, c.status ?: code, c.headers ?: headers, body, contentType) }
                }
                else -> consumed?.let { served = Served(code, target.responseMessage, headers, ByteArrayInputStream(it)) }
            }
            result.throttleBytesPerSecond?.let { rate ->
                val current = served
                served = if (current != null) {
                    Served(current.status, current.message, current.headers, current.body?.let { ThrottleInputStream(it, rate) })
                } else {
                    Served(code, target.responseMessage, headers, rawBody()?.let { ThrottleInputStream(it, rate) })
                }
            }
            tracker.id?.let { id -> record?.let { recorder.recordIntervention(id, it) } }
            scripts.account(ScriptHandler.RESPONSE, result, tracker.id)
        } catch (_: IOException) {
            // The delegate failed: the app sees the same failure on its own call below.
        }
        failure?.let { throw it }
    }

    /** The delegate's body stream, teed for capture (error stream for 4xx/5xx). */
    private fun rawBody(): InputStream? = try {
        tracker.responseBody(target.inputStream)
    } catch (_: IOException) {
        target.errorStream?.let { tracker.responseBody(it) }
    }

    private inline fun <T> responding(block: () -> T): T {
        try {
            respond()
        } catch (e: IOException) {
            tracker.failed(e)
            throw e
        }
        val value = try {
            block()
        } catch (e: IOException) {
            tracker.failed(e)
            throw e
        }
        tracker.response()
        return value
    }

    // ---- HttpURLConnection -------------------------------------------------------------

    override fun connect() {
        if (requestScripted && !committed && target.doOutput) return // body still to come; commit later
        commit()
        if (served != null) return
        try {
            target.connect()
        } catch (e: IOException) {
            tracker.failed(e)
            throw e
        }
    }

    override fun disconnect() {
        tracker.finish()
        target.disconnect()
    }

    override fun usingProxy(): Boolean = target.usingProxy()

    override fun getOutputStream(): OutputStream {
        if (requestScripted && !committed) {
            return buffered ?: ByteArrayOutputStream().also { buffered = it }
        }
        commit()
        if (served != null) return ByteArrayOutputStream()
        val stream = try {
            target.outputStream
        } catch (e: IOException) {
            tracker.failed(e)
            throw e
        }
        return tracker.requestBody(stream)
    }

    override fun getInputStream(): InputStream {
        respond()
        served?.let { s ->
            if (s.status >= 400) throw java.io.FileNotFoundException(url.toString())
            return s.body ?: ByteArrayInputStream(ByteArray(0))
        }
        val stream = try {
            target.inputStream
        } catch (e: IOException) {
            // 4xx/5xx: the platform throws here although a response arrived; the
            // app reads getErrorStream() next.
            runCatching { target.responseCode }.onSuccess { tracker.response() }
            tracker.failed(e)
            throw e
        }
        tracker.response()
        return tracker.responseBody(stream)!!
    }

    override fun getErrorStream(): InputStream? {
        served?.let { s -> return if (s.status >= 400) s.body else null }
        val stream = target.errorStream ?: return null
        tracker.response()
        return tracker.responseBody(stream)
    }

    override fun getResponseCode(): Int = responding { served?.status ?: target.responseCode }
    override fun getResponseMessage(): String? = responding { served?.message ?: target.responseMessage }
    override fun getHeaderField(name: String?): String? = responding { served?.header(name) ?: if (served != null) null else target.getHeaderField(name) }
    override fun getHeaderField(n: Int): String? = responding {
        served?.let { s -> if (n == 0) "HTTP/1.1 ${s.status} ${s.message ?: ""}" else s.headers.getOrNull(n - 1)?.value } ?: target.getHeaderField(n)
    }
    override fun getHeaderFieldKey(n: Int): String? = responding {
        served?.let { s -> if (n == 0) null else s.headers.getOrNull(n - 1)?.name } ?: target.getHeaderFieldKey(n)
    }
    override fun getHeaderFields(): MutableMap<String, MutableList<String>> = responding { served?.fields() ?: target.headerFields }
    override fun getHeaderFieldInt(name: String?, default: Int): Int = responding {
        served?.let { s -> s.header(name)?.toIntOrNull() ?: default } ?: target.getHeaderFieldInt(name, default)
    }
    override fun getHeaderFieldLong(name: String?, default: Long): Long = responding {
        served?.let { s -> s.header(name)?.toLongOrNull() ?: default } ?: target.getHeaderFieldLong(name, default)
    }
    override fun getHeaderFieldDate(name: String?, default: Long): Long = responding {
        if (served != null) default else target.getHeaderFieldDate(name, default)
    }
    override fun getContentType(): String? = responding { if (served != null) served?.header("Content-Type") else target.contentType }
    override fun getContentLength(): Int = responding { if (served != null) -1 else target.contentLength }
    override fun getContentLengthLong(): Long = responding { if (served != null) -1 else target.contentLengthLong }
    override fun getContentEncoding(): String? = responding { if (served != null) served?.header("Content-Encoding") else target.contentEncoding }
    override fun getDate(): Long = responding { if (served != null) 0 else target.date }
    override fun getExpiration(): Long = responding { if (served != null) 0 else target.expiration }
    override fun getLastModified(): Long = responding { if (served != null) 0 else target.lastModified }
    override fun getContent(): Any? = responding { target.content }
    override fun getContent(classes: Array<out Class<*>>?): Any? = responding { target.getContent(classes) }

    override fun getPermission(): Permission? = target.permission
    override fun getRequestMethod(): String = target.requestMethod
    override fun setRequestMethod(method: String?) = target.setRequestMethod(method)
    override fun getInstanceFollowRedirects(): Boolean = target.instanceFollowRedirects
    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        target.instanceFollowRedirects = followRedirects
    }
    override fun setChunkedStreamingMode(chunklen: Int) = target.setChunkedStreamingMode(chunklen)
    override fun setFixedLengthStreamingMode(contentLength: Int) = target.setFixedLengthStreamingMode(contentLength)
    override fun setFixedLengthStreamingMode(contentLength: Long) = target.setFixedLengthStreamingMode(contentLength)

    override fun getURL(): URL = target.url
    override fun setConnectTimeout(timeout: Int) {
        target.connectTimeout = timeout
    }
    override fun getConnectTimeout(): Int = target.connectTimeout
    override fun setReadTimeout(timeout: Int) {
        target.readTimeout = timeout
    }
    override fun getReadTimeout(): Int = target.readTimeout
    override fun setDoInput(doinput: Boolean) {
        target.doInput = doinput
    }
    override fun getDoInput(): Boolean = target.doInput
    override fun setDoOutput(dooutput: Boolean) {
        target.doOutput = dooutput
    }
    override fun getDoOutput(): Boolean = target.doOutput
    override fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        target.allowUserInteraction = allowuserinteraction
    }
    override fun getAllowUserInteraction(): Boolean = target.allowUserInteraction
    override fun setUseCaches(usecaches: Boolean) {
        target.useCaches = usecaches
    }
    override fun getUseCaches(): Boolean = target.useCaches
    override fun setIfModifiedSince(ifmodifiedsince: Long) {
        target.ifModifiedSince = ifmodifiedsince
    }
    override fun getIfModifiedSince(): Long = target.ifModifiedSince
    override fun getDefaultUseCaches(): Boolean = target.defaultUseCaches
    override fun setDefaultUseCaches(defaultusecaches: Boolean) {
        target.defaultUseCaches = defaultusecaches
    }
    override fun setRequestProperty(key: String?, value: String?) = target.setRequestProperty(key, value)
    override fun addRequestProperty(key: String?, value: String?) = target.addRequestProperty(key, value)
    override fun getRequestProperty(key: String?): String? = target.getRequestProperty(key)
    override fun getRequestProperties(): MutableMap<String, MutableList<String>> = target.requestProperties
    override fun toString(): String = target.toString()
}
