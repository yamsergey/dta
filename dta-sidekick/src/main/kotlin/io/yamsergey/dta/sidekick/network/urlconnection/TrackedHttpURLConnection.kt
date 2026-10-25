package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.security.Permission

/**
 * Delegating HttpURLConnection returned in place of the platform's (Android Studio's
 * model). Every call forwards to [delegate]; the tracker observes connect, the request
 * body, the response and the body streams.
 */
internal class TrackedHttpURLConnection(
    private val delegate: HttpURLConnection,
    recorder: NetworkRecorder,
    initiator: Initiator,
) : HttpURLConnection(delegate.url) {

    private val tracker = UrlConnectionTracker(delegate, recorder, initiator)

    private inline fun <T> responding(block: () -> T): T {
        tracker.start()
        val value = try {
            block()
        } catch (e: IOException) {
            tracker.failed(e)
            throw e
        }
        tracker.response()
        return value
    }

    override fun connect() {
        tracker.start()
        try {
            delegate.connect()
        } catch (e: IOException) {
            tracker.failed(e)
            throw e
        }
    }

    override fun disconnect() {
        tracker.finish()
        delegate.disconnect()
    }

    override fun usingProxy(): Boolean = delegate.usingProxy()

    override fun getOutputStream(): OutputStream {
        tracker.start()
        val stream = try {
            delegate.outputStream
        } catch (e: IOException) {
            tracker.failed(e)
            throw e
        }
        return tracker.requestBody(stream)
    }

    override fun getInputStream(): InputStream {
        tracker.start()
        val stream = try {
            delegate.inputStream
        } catch (e: IOException) {
            // 4xx/5xx: the platform throws here although a response arrived; the
            // app reads getErrorStream() next.
            runCatching { delegate.responseCode }.onSuccess { tracker.response() }
            tracker.failed(e)
            throw e
        }
        tracker.response()
        return tracker.responseBody(stream)!!
    }

    override fun getErrorStream(): InputStream? {
        val stream = delegate.errorStream ?: return null
        tracker.response()
        return tracker.responseBody(stream)
    }

    override fun getResponseCode(): Int = responding { delegate.responseCode }
    override fun getResponseMessage(): String? = responding { delegate.responseMessage }
    override fun getHeaderField(name: String?): String? = responding { delegate.getHeaderField(name) }
    override fun getHeaderField(n: Int): String? = responding { delegate.getHeaderField(n) }
    override fun getHeaderFieldKey(n: Int): String? = responding { delegate.getHeaderFieldKey(n) }
    override fun getHeaderFields(): MutableMap<String, MutableList<String>> = responding { delegate.headerFields }
    override fun getHeaderFieldInt(name: String?, default: Int): Int = responding { delegate.getHeaderFieldInt(name, default) }
    override fun getHeaderFieldLong(name: String?, default: Long): Long = responding { delegate.getHeaderFieldLong(name, default) }
    override fun getHeaderFieldDate(name: String?, default: Long): Long = responding { delegate.getHeaderFieldDate(name, default) }
    override fun getContentType(): String? = responding { delegate.contentType }
    override fun getContentLength(): Int = responding { delegate.contentLength }
    override fun getContentLengthLong(): Long = responding { delegate.contentLengthLong }
    override fun getContentEncoding(): String? = responding { delegate.contentEncoding }
    override fun getDate(): Long = responding { delegate.date }
    override fun getExpiration(): Long = responding { delegate.expiration }
    override fun getLastModified(): Long = responding { delegate.lastModified }
    override fun getContent(): Any? = responding { delegate.content }
    override fun getContent(classes: Array<out Class<*>>?): Any? = responding { delegate.getContent(classes) }

    override fun getPermission(): Permission? = delegate.permission
    override fun getRequestMethod(): String = delegate.requestMethod
    override fun setRequestMethod(method: String?) = delegate.setRequestMethod(method)
    override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects
    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        delegate.instanceFollowRedirects = followRedirects
    }
    override fun setChunkedStreamingMode(chunklen: Int) = delegate.setChunkedStreamingMode(chunklen)
    override fun setFixedLengthStreamingMode(contentLength: Int) = delegate.setFixedLengthStreamingMode(contentLength)
    override fun setFixedLengthStreamingMode(contentLength: Long) = delegate.setFixedLengthStreamingMode(contentLength)

    override fun getURL(): java.net.URL = delegate.url
    override fun setConnectTimeout(timeout: Int) {
        delegate.connectTimeout = timeout
    }
    override fun getConnectTimeout(): Int = delegate.connectTimeout
    override fun setReadTimeout(timeout: Int) {
        delegate.readTimeout = timeout
    }
    override fun getReadTimeout(): Int = delegate.readTimeout
    override fun setDoInput(doinput: Boolean) {
        delegate.doInput = doinput
    }
    override fun getDoInput(): Boolean = delegate.doInput
    override fun setDoOutput(dooutput: Boolean) {
        delegate.doOutput = dooutput
    }
    override fun getDoOutput(): Boolean = delegate.doOutput
    override fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        delegate.allowUserInteraction = allowuserinteraction
    }
    override fun getAllowUserInteraction(): Boolean = delegate.allowUserInteraction
    override fun setUseCaches(usecaches: Boolean) {
        delegate.useCaches = usecaches
    }
    override fun getUseCaches(): Boolean = delegate.useCaches
    override fun setIfModifiedSince(ifmodifiedsince: Long) {
        delegate.ifModifiedSince = ifmodifiedsince
    }
    override fun getIfModifiedSince(): Long = delegate.ifModifiedSince
    override fun getDefaultUseCaches(): Boolean = delegate.defaultUseCaches
    override fun setDefaultUseCaches(defaultusecaches: Boolean) {
        delegate.defaultUseCaches = defaultusecaches
    }
    override fun setRequestProperty(key: String?, value: String?) = delegate.setRequestProperty(key, value)
    override fun addRequestProperty(key: String?, value: String?) = delegate.addRequestProperty(key, value)
    override fun getRequestProperty(key: String?): String? = delegate.getRequestProperty(key)
    override fun getRequestProperties(): MutableMap<String, MutableList<String>> = delegate.requestProperties
    override fun toString(): String = delegate.toString()
}
