package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.Permission
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * HTTPS flavor: must be an HttpsURLConnection (apps cast to it). HTTP behavior forwards
 * to a [TrackedHttpURLConnection] around the same delegate; TLS calls go to the delegate.
 */
internal class TrackedHttpsURLConnection(
    private val delegate: HttpsURLConnection,
    recorder: NetworkRecorder,
    initiator: Initiator,
    scripts: ScriptRuntime,
    openRaw: (java.net.URL) -> java.net.HttpURLConnection,
) : HttpsURLConnection(delegate.url) {

    private val http = TrackedHttpURLConnection(delegate, recorder, initiator, scripts, openRaw)

    override fun getCipherSuite(): String? = delegate.cipherSuite
    override fun getLocalCertificates(): Array<Certificate>? = delegate.localCertificates
    override fun getServerCertificates(): Array<Certificate>? = delegate.serverCertificates
    override fun getPeerPrincipal(): Principal? = delegate.peerPrincipal
    override fun getLocalPrincipal(): Principal? = delegate.localPrincipal
    override fun setHostnameVerifier(v: HostnameVerifier?) {
        delegate.hostnameVerifier = v
    }
    override fun getHostnameVerifier(): HostnameVerifier? = delegate.hostnameVerifier
    override fun setSSLSocketFactory(sf: SSLSocketFactory?) {
        delegate.sslSocketFactory = sf
    }
    override fun getSSLSocketFactory(): SSLSocketFactory? = delegate.sslSocketFactory

    override fun connect() = http.connect()
    override fun disconnect() = http.disconnect()
    override fun usingProxy(): Boolean = http.usingProxy()
    override fun getOutputStream(): OutputStream = http.outputStream
    override fun getInputStream(): InputStream = http.inputStream
    override fun getErrorStream(): InputStream? = http.errorStream
    override fun getResponseCode(): Int = http.responseCode
    override fun getResponseMessage(): String? = http.responseMessage
    override fun getHeaderField(name: String?): String? = http.getHeaderField(name)
    override fun getHeaderField(n: Int): String? = http.getHeaderField(n)
    override fun getHeaderFieldKey(n: Int): String? = http.getHeaderFieldKey(n)
    override fun getHeaderFields(): MutableMap<String, MutableList<String>> = http.headerFields
    override fun getHeaderFieldInt(name: String?, default: Int): Int = http.getHeaderFieldInt(name, default)
    override fun getHeaderFieldLong(name: String?, default: Long): Long = http.getHeaderFieldLong(name, default)
    override fun getHeaderFieldDate(name: String?, default: Long): Long = http.getHeaderFieldDate(name, default)
    override fun getContentType(): String? = http.contentType
    override fun getContentLength(): Int = http.contentLength
    override fun getContentLengthLong(): Long = http.contentLengthLong
    override fun getContentEncoding(): String? = http.contentEncoding
    override fun getDate(): Long = http.date
    override fun getExpiration(): Long = http.expiration
    override fun getLastModified(): Long = http.lastModified
    override fun getContent(): Any? = http.content
    override fun getContent(classes: Array<out Class<*>>?): Any? = http.getContent(classes)
    override fun getPermission(): Permission? = http.permission
    override fun getRequestMethod(): String = http.requestMethod
    override fun setRequestMethod(method: String?) = http.setRequestMethod(method)
    override fun getInstanceFollowRedirects(): Boolean = http.instanceFollowRedirects
    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
        http.instanceFollowRedirects = followRedirects
    }
    override fun setChunkedStreamingMode(chunklen: Int) = http.setChunkedStreamingMode(chunklen)
    override fun setFixedLengthStreamingMode(contentLength: Int) = http.setFixedLengthStreamingMode(contentLength)
    override fun setFixedLengthStreamingMode(contentLength: Long) = http.setFixedLengthStreamingMode(contentLength)
    override fun getURL(): URL = http.url
    override fun setConnectTimeout(timeout: Int) {
        http.connectTimeout = timeout
    }
    override fun getConnectTimeout(): Int = http.connectTimeout
    override fun setReadTimeout(timeout: Int) {
        http.readTimeout = timeout
    }
    override fun getReadTimeout(): Int = http.readTimeout
    override fun setDoInput(doinput: Boolean) {
        http.doInput = doinput
    }
    override fun getDoInput(): Boolean = http.doInput
    override fun setDoOutput(dooutput: Boolean) {
        http.doOutput = dooutput
    }
    override fun getDoOutput(): Boolean = http.doOutput
    override fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        http.allowUserInteraction = allowuserinteraction
    }
    override fun getAllowUserInteraction(): Boolean = http.allowUserInteraction
    override fun setUseCaches(usecaches: Boolean) {
        http.useCaches = usecaches
    }
    override fun getUseCaches(): Boolean = http.useCaches
    override fun setIfModifiedSince(ifmodifiedsince: Long) {
        http.ifModifiedSince = ifmodifiedsince
    }
    override fun getIfModifiedSince(): Long = http.ifModifiedSince
    override fun getDefaultUseCaches(): Boolean = http.defaultUseCaches
    override fun setDefaultUseCaches(defaultusecaches: Boolean) {
        http.defaultUseCaches = defaultusecaches
    }
    override fun setRequestProperty(key: String?, value: String?) = http.setRequestProperty(key, value)
    override fun addRequestProperty(key: String?, value: String?) = http.addRequestProperty(key, value)
    override fun getRequestProperty(key: String?): String? = http.getRequestProperty(key)
    override fun getRequestProperties(): MutableMap<String, MutableList<String>> = http.requestProperties
    override fun toString(): String = http.toString()
}
