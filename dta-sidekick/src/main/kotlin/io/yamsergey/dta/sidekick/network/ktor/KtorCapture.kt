package io.yamsergey.dta.sidekick.network.ktor

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.EntryHooks
import io.yamsergey.dta.sidekick.network.HookInstaller
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkCapability
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import java.util.Collections

/**
 * Feature 034: the Ktor client, whatever its engine. Ktor builds each call in
 * `HttpClientCall(client, requestData, responseData)`, which sets its response through the final
 * `setResponse(HttpResponse)` once the response head has arrived (`this` is initialised there,
 * unlike in the constructor): request line, headers and body content, status, headers, timing.
 * Ktor then saves the body (Ktor 3's SaveBody, Ktor 2's `save()`) in a `SavedHttpCall`, whose
 * `setResponse` carries the bytes. A 101 answer is a WebSocket: the CIO engine's frames go out
 * through `Serializer.enqueue(Frame)` and come in through `Frame.Companion.byType(…)`.
 * Calls made on the OkHttp or Android engines are already captured underneath: not recorded twice.
 */
internal object KtorCapture {
    private const val CALL = "io.ktor.client.call.HttpClientCall"
    private const val SERIALIZER = "io.ktor.websocket.Serializer"
    private const val FRAME_COMPANION = "io.ktor.websocket.Frame\$Companion"

    const val EVENT_EXCHANGES = "requests and responses"
    const val EVENT_BODIES = "response bodies"
    const val EVENT_SENT = "sent WebSocket frames"
    const val EVENT_RECEIVED = "received WebSocket frames"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators, installer: HookInstaller = HookInstaller.Device): LibraryState {
        val callClass = EntryHooks.load(CALL, loader) ?: return NetworkCapability.ktor(emptySet())
        val logic = KtorRecorder(recorder) { initiators.capture(InitiatorSite.CONNECT) }
        val call = EntryHooks(callClass, installer)
        call.on(EVENT_EXCHANGES, "setResponse", "(Lio/ktor/client/statement/HttpResponse;)V") { self, params -> logic.onSetResponse(self, params[0]) }
        val hooks = mutableListOf(call)
        EntryHooks.load(SERIALIZER, loader)?.let { cls ->
            hooks += EntryHooks(cls, installer).apply { on(EVENT_SENT, "enqueue", "(Lio/ktor/websocket/Frame;)V") { _, p -> p[0]?.let(logic::onFrameSent) } }
        }
        EntryHooks.load(FRAME_COMPANION, loader)?.let { cls ->
            hooks += EntryHooks(cls, installer).apply {
                on(EVENT_RECEIVED, "byType", "(ZLio/ktor/websocket/FrameType;[BZZZ)Lio/ktor/websocket/Frame;") { _, p -> logic.onFrameReceived(p[1], p[2] as? ByteArray) }
            }
        }
        return EntryHooks.state(NetworkLibraries.KTOR, *hooks.toTypedArray())
    }
}

/** What the Ktor hooks record (pure reflection on Ktor's objects, so tested on a real Ktor client). */
internal class KtorRecorder(private val recorder: NetworkRecorder, private val initiator: () -> io.yamsergey.dta.protocol.network.Initiator?) {
    /**
     * One exchange by its method, URL and Ktor's request time: Ktor sets the response again on
     * calls plugins wrap, and the saved call carries the same request time — the key finds the
     * entry for both, so each exchange is recorded once and gets its body.
     */
    private val exchanges = Collections.synchronizedMap(object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    })

    private fun key(request: Any, response: Any): String? {
        val method = (get(request, "method")?.let { get(it, "value") } as? String) ?: return null
        val url = get(request, "url")?.toString() ?: return null
        val time = get(response, "requestTime")?.let { get(it, "timestamp") } ?: return null
        return "$method $url $time"
    }
    /** Open WebSocket connections, newest last: frames go to the newest (the CIO writer and parser do not name theirs). */
    private val sockets = Collections.synchronizedList(mutableListOf<String>())

    fun onSetResponse(call: Any, response: Any?) {
        response ?: return
        if (call.javaClass.name == "io.ktor.client.call.SavedHttpCall") return onSaved(call, response)
        if (underneath(call)) return
        val request = runCatching { call.javaClass.getMethod("getRequest").invoke(call) }.getOrNull() ?: return
        val method = (get(request, "method")?.let { get(it, "value") } as? String) ?: "GET"
        val url = get(request, "url")?.toString() ?: return
        val status = (get(response, "status")?.let { get(it, "value") } as? Int) ?: 0
        val key = key(request, response)
        if (key != null && exchanges.containsKey(key)) return
        val requestHeaders = headers(get(request, "headers"))
        val responseHeaders = headers(get(response, "headers"))
        if (status == 101) {
            val id = recorder.wsStarted(NetworkLibraries.KTOR, url, requestHeaders, initiator())
            recorder.wsHandshake(id, responseHeaders)
            recorder.wsOpened(id)
            sockets += id
            key?.let { exchanges[it] = id }
            return
        }
        val started = (get(response, "requestTime")?.let { get(it, "timestamp") } as? Long)
        val id = recorder.httpStarted(NetworkLibraries.KTOR, method, url, requestHeaders, initiator(), started)
        requestBody(get(request, "content"))?.let { (bytes, type) ->
            recorder.httpRequestBody(id, type, bytes.size.toLong()).apply { accept(bytes, 0, bytes.size); finish(endOfStream = true) }
        }
        recorder.httpResponse(id, status, responseHeaders, get(response, "version")?.toString())
        key?.let { exchanges[it] = id }
        // The head has arrived: complete now; the saved body, when Ktor reads it whole, is added after.
        recorder.httpComplete(id)
    }

    private fun onSaved(saved: Any, response: Any) {
        val savedRequest = runCatching { saved.javaClass.getMethod("getRequest").invoke(saved) }.getOrNull() ?: return
        val original = get(savedRequest, "\$\$delegate_0") ?: savedRequest
        val id = key(original, response)?.let { exchanges[it] } ?: return
        // Ktor 3 keeps the bytes in the saved response; Ktor 2 in the saved call (its response wraps them in a channel).
        val bytes = (get(response, "body") as? ByteArray) ?: (get(saved, "responseBody") as? ByteArray) ?: return
        val type = headers(get(response, "headers")).firstOrNull { it.name.equals("Content-Type", true) }?.value
        recorder.httpResponseBody(id, type, null, bytes.size.toLong()).apply { accept(bytes, 0, bytes.size); finish(endOfStream = true) }
    }

    fun onFrameSent(frame: Any) {
        val type = get(frame, "frameType") ?: return
        frame(Direction.OUT, type, get(frame, "data") as? ByteArray)
    }

    fun onFrameReceived(type: Any?, data: ByteArray?) { type?.let { frame(Direction.IN, it, data) } }

    private fun frame(direction: Direction, type: Any, data: ByteArray?) {
        val id = sockets.lastOrNull() ?: return
        val bytes = data ?: ByteArray(0)
        when ((type as? Enum<*>)?.name) {
            "TEXT" -> recorder.wsMessage(id, direction, MessageOpcode.TEXT, bytes, if (direction == Direction.OUT) initiator() else null)
            "BINARY" -> recorder.wsMessage(id, direction, MessageOpcode.BINARY, bytes, if (direction == Direction.OUT) initiator() else null)
            "CLOSE" -> {
                val code = if (bytes.size >= 2) ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff) else null
                val reason = if (bytes.size > 2) String(bytes, 2, bytes.size - 2) else null
                recorder.wsClosed(id, code, reason)
                sockets.remove(id)
            }
            else -> Unit // ping / pong: the connection's own keep-alive
        }
    }

    /** Calls on the OkHttp and Android engines are captured by those stacks already. */
    private fun underneath(call: Any): Boolean {
        val engine = runCatching { get(call.javaClass.getMethod("getClient").invoke(call)!!, "engine") }.getOrNull() ?: return false
        val name = engine.javaClass.name
        return name.startsWith("io.ktor.client.engine.okhttp.") || name.startsWith("io.ktor.client.engine.android.")
    }

    /** A request body Ktor holds whole (bytes or text); a streamed one is not read. */
    private fun requestBody(content: Any?): Pair<ByteArray, String?>? {
        content ?: return null
        val type = get(content, "contentType")?.toString()
        val bytes = when {
            hasMethod(content, "bytes") -> runCatching { content.javaClass.getMethod("bytes").invoke(content) as ByteArray }.getOrNull()
            get(content, "text") is String -> (get(content, "text") as String).toByteArray()
            else -> null
        } ?: return null
        return bytes to type
    }

    private fun headers(h: Any?): List<Header> {
        h ?: return emptyList()
        val entries = runCatching { h.javaClass.getMethod("entries").invoke(h) as Set<*> }.getOrNull() ?: return emptyList()
        return entries.flatMap { e -> val m = e as Map.Entry<*, *>; (m.value as List<*>).map { Header(m.key.toString(), it.toString()) } }
    }

    private fun hasMethod(o: Any, name: String) = o.javaClass.methods.any { it.name == name && it.parameterCount == 0 }

    /** A property through its getter (Kotlin's, public or internal-mangled), else the field. */
    private fun get(o: Any, name: String): Any? {
        val getter = "get" + name.replaceFirstChar(Char::uppercase)
        o.javaClass.methods.firstOrNull { it.parameterCount == 0 && (it.name == getter || it.name.startsWith("$getter-")) }?.let { m -> return runCatching { m.invoke(o) }.getOrNull() }
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let { f -> return runCatching { f.isAccessible = true; f.get(o) }.getOrNull() }
            c = c.superclass
        }
        return null
    }
}
