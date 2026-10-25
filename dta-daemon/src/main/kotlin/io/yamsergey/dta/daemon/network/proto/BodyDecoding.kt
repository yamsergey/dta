package io.yamsergey.dta.daemon.network.proto

import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.DecodingInfo
import io.yamsergey.dta.protocol.network.DecodingOutcome
import io.yamsergey.dta.protocol.network.Direction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which body of an exchange a rule applies to. */
@Serializable
enum class MappingPart {
    @SerialName("request") REQUEST,
    @SerialName("response") RESPONSE,
    @SerialName("message") MESSAGE,
}

/** What rules match a body against: the exchange's URL and method, the part, the message direction. */
data class BodyTarget(
    val url: String,
    val method: String?,
    val part: MappingPart,
    val direction: Direction?,
    val contentType: String?,
)

fun interface DecodingRule {
    fun select(target: BodyTarget): Selection?
}

/**
 * The one decoding path shared by text output, file output and redaction (FR-021):
 * the first rule that selects a type wins; Content-Encoding is undone first.
 */
class BodyDecoding(private val rules: List<DecodingRule>) {

    fun decode(target: BodyTarget, wire: ByteArray, info: BodyInfo): Decoded {
        val selection = rules.firstNotNullOfOrNull { it.select(target) }
            ?: return Decoded(null, DecodingInfo(DecodingOutcome.NOT_APPLICABLE))
        val body = BodyCodec.decode(wire, info.encoding)
        if (!body.decoded) {
            return Decoded(
                null,
                DecodingInfo(
                    DecodingOutcome.NOT_DECODED, format = selection.format, schema = selection.schema,
                    type = selection.type, rule = selection.rule, reason = "${info.encoding} encoding not decoded",
                ),
            )
        }
        return ProtoDecoder.decode(body.bytes, info.completeness, selection)
    }

    companion object {
        val NONE = BodyDecoding(emptyList())
    }
}
