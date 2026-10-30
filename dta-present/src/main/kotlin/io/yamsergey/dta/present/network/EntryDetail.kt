package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.Units
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** Which side of a scripted entry is shown: what went over the wire, or what the app saw. */
enum class Side { WIRE, APP }

data class Field(val label: String, val value: String)

/** A body to open in the body viewer: its part (`response`, `app-response`, …) and summary. */
data class BodyRef(val part: String, val info: JsonObject)

data class Section(val title: String, val headers: List<Pair<String, String>>, val body: BodyRef?)

/** One stack frame as a JVM stack prints it: `(File.kt:12)`, `(File.kt)`, or `(Unknown Source)`. */
object FrameText {
    fun of(className: String?, method: String?, file: String?, line: Int?): String =
        "$className.$method(${file?.let { f -> line?.let { "$f:$it" } ?: f } ?: "Unknown Source"})"
}

/** A frame of the initiator: its printed [text] and where it points, for navigation. */
data class FrameLine(
    val text: String,
    val app: Boolean,
    val first: Boolean,
    val className: String = "",
    val method: String = "",
    val file: String? = null,
    val line: Int? = null,
)

data class InitiatorView(val header: String, val frames: List<FrameLine>, val missing: String?, val truncated: Int?)

data class InterventionView(val summary: String, val hasAppSide: Boolean, val mocked: Boolean, val dropped: Boolean, val error: String?)

/**
 * One entry in full (information.md "Network entry"): summary, script intervention with the
 * wire/app switch, request, response, initiator with the first app frame marked, and a
 * connection's messages.
 */
data class EntryDetail(
    val summary: List<Field>,
    val statements: List<Statement>,
    val intervention: InterventionView?,
    val side: Side,
    val request: Section?,
    val response: Section?,
    val initiator: InitiatorView?,
    val messages: List<EntryRow>,
    val messagesMore: String?,
    val sections: List<String>,
) {
    companion object {
        /** From a `network.entry` result. */
        fun of(detail: JsonObject, side: Side = Side.WIRE, zone: ZoneId = ZoneId.systemDefault()): EntryDetail {
            val e = detail.obj("entry") ?: JsonObject(emptyMap())
            val type = e.str("type")
            val app = side == Side.APP
            val summary = buildList {
                when (type) {
                    "http" -> {
                        add(Field("Request", "${e.str("method")} ${e.str("url")}"))
                        e.long("status")?.let { add(Field("Status", "$it")) }
                        e.str("failure")?.let { add(Field("Failure", it)) }
                    }
                    "ws-connection" -> {
                        add(Field("Connection", e.str("url") ?: ""))
                        add(Field("State", listOfNotNull(e.str("state"), e.long("closeCode")?.let { "code $it" }, e.str("closeReason")).joinToString(" · ")))
                        e.str("failure")?.let { add(Field("Failure", it)) }
                    }
                    else -> add(Field("Message", "${e.str("direction")} ${e.str("opcode")} on ${e.str("connectionId")}"))
                }
                add(Field("Started", EntryRows.of(e, zone).time))
                e.long("durationMs")?.let { add(Field("Duration", Units.millis(it))) }
                add(Field("Library", listOfNotNull(e.str("library"), e.str("protocol")).joinToString(" · ")))
            }
            val i = e.obj("intervention")
            val hasAppSide = e["appRequest"] != null || e["appResponse"] != null || e["appPayload"] != null
            val intervention = i?.let {
                val changed = it.arr("changed").joinToString(", ") { c -> c.text }
                InterventionView(
                    summary = listOfNotNull(changed.takeIf { c -> c.isNotEmpty() }?.let { c -> "changed $c" }, it.long("delayMs")?.let { d -> "delayed $d ms" },
                        if (it.bool("mocked")) "answered by the script" else null, if (it.bool("dropped")) "dropped" else null).joinToString(" · ").ifEmpty { "ran, changed nothing" },
                    hasAppSide = hasAppSide, mocked = it.bool("mocked"), dropped = it.bool("dropped"), error = it.str("error"),
                )
            }
            val appRequest = e.obj("appRequest").takeIf { app }
            val appResponse = e.obj("appResponse").takeIf { app }
            val request = when (type) {
                "http" -> section("Request", appRequest?.arr("headers") ?: e.arr("requestHeaders"), (appRequest?.obj("body") ?: e.obj("requestBody")))
                "ws-connection" -> section("Handshake", e.arr("handshakeHeaders"), null)
                else -> section("Message", kotlinx.serialization.json.JsonArray(emptyList()), (e.obj("appPayload").takeIf { app } ?: e.obj("payload")))
            }
            val response = if (type == "http") section("Response", appResponse?.arr("headers") ?: e.arr("responseHeaders"), appResponse?.obj("body") ?: e.obj("responseBody")) else null
            val initiator = e.obj("initiator")?.let { init ->
                val first = init.long("firstAppFrame")?.toInt()
                InitiatorView(
                    header = "${init.str("thread")} · ${init.str("site")}",
                    frames = init.arr("frames").mapIndexed { index, f ->
                        val frame = f.o
                        FrameLine(
                            FrameText.of(frame.str("className"), frame.str("method"), frame.str("file"), frame.long("line")?.toInt()), frame.bool("app"), index == first,
                            className = frame.str("className") ?: "", method = frame.str("method") ?: "", file = frame.str("file"), line = frame.long("line")?.toInt(),
                        )
                    },
                    missing = init.str("missing"),
                    truncated = init.long("truncatedFrames")?.toInt(),
                )
            }
            val messages = detail.arr("messages").map { EntryRows.of(it.o, zone) }
            return EntryDetail(
                summary = summary,
                statements = Statements.forEntryDetail(detail),
                intervention = intervention,
                side = side,
                request = request,
                response = response,
                initiator = initiator,
                messages = messages,
                messagesMore = detail.long("messagesTruncated")?.takeIf { it > 0 }?.let { "$it more message${if (it == 1L) "" else "s"} not shown" },
                sections = listOfNotNull(
                    "Summary", intervention?.let { "Script" }, request?.title, response?.title, initiator?.let { "Initiator" },
                    if (type == "ws-connection") "Messages" else null,
                ),
            )
        }

        private fun section(title: String, headers: kotlinx.serialization.json.JsonArray, body: JsonObject?): Section? {
            if (headers.isEmpty() && body == null) return null
            return Section(title, headers.map { it.o.str("name").orEmpty() to it.o.str("value").orEmpty() }, body?.let { BodyRef(it.str("part") ?: "", it) })
        }
    }
}
