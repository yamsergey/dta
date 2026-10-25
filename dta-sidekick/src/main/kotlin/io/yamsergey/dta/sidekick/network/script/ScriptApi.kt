package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.protocol.network.ScriptLogLevel

/** Receives `log(...)` output of a script. */
typealias ScriptLogSink = (level: ScriptLogLevel, handler: ScriptHandler?, message: String) -> Unit

/** A script that does not compile, or defines no handler; [line]/[column] are 1-based (0 = unknown). */
class ScriptCompileException(message: String, val line: Int, val column: Int) : Exception(message)

/**
 * A body offered to the script lazily (FR-012): [reader] runs only when the script asks.
 * [unreadable] says why it cannot be read (one-shot request body); it can still be replaced.
 */
class BodySource(
    val contentType: String?,
    val binary: Boolean,
    unreadable: String? = null,
    private val reader: (() -> ByteArray)?,
) {
    val unreadable: String? = unreadable ?: if (reader == null) "body cannot be read" else null
    private var cached: ByteArray? = null

    constructor(contentType: String?, binary: Boolean, reader: (() -> ByteArray)?) : this(contentType, binary, null, reader)

    fun bytes(): ByteArray = cached ?: run {
        val read = reader ?: throw IllegalStateException("${unreadable ?: "body cannot be read"}; it can still be replaced")
        read().also { cached = it }
    }
}

data class ScriptRequest(
    val url: String,
    val method: String,
    val headers: List<Header>,
    val library: String,
    val id: String?,
    val body: BodySource?,
)

data class ScriptResponse(val status: Int, val headers: List<Header>, val body: BodySource?)

data class ScriptMessage(val text: String?, val bytes: ByteArray?, val binary: Boolean) {
    companion object {
        fun text(text: String) = ScriptMessage(text, null, false)
        fun binary(bytes: ByteArray) = ScriptMessage(null, bytes, true)
    }
}

data class ScriptConnection(val url: String, val library: String, val id: String?)

/** Only the fields the script changed are non-null. */
data class RequestChange(val url: String? = null, val method: String? = null, val headers: List<Header>? = null, val body: ByteArray? = null)

data class ResponseChange(val status: Int? = null, val headers: List<Header>? = null, val body: ByteArray? = null)

data class MessageChange(val text: String? = null, val bytes: ByteArray? = null)

/** What `respond({...})` asked to answer with. */
data class ScriptedResponse(val status: Int, val headers: List<Header>, val body: ByteArray)

/** What a handler decided. */
sealed interface Effect<out T> {
    data object None : Effect<Nothing>
    data class Change<T>(val change: T) : Effect<T>
    data class Mock(val response: ScriptedResponse) : Effect<Nothing>
    data object Drop : Effect<Nothing>
}

/**
 * The outcome of one handler call. A failed call is [Effect.None] with [error]: the
 * traffic passes unchanged (FR-021).
 */
data class ScriptResult<out T>(
    val effect: Effect<T>,
    val delayMs: Long = 0,
    val scriptMs: Long = 0,
    val throttleBytesPerSecond: Long? = null,
    val error: String? = null,
)
