package io.yamsergey.dta.protocol.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a body was turned into structured data — stated on every body result (feature 006,
 * FR-022), so a decoding is never claimed that did not happen.
 */
@Serializable
data class DecodingInfo(
    val outcome: DecodingOutcome,
    /** `otlp-traces`, `otlp-metrics`, `otlp-logs` or `protobuf`. */
    val format: String? = null,
    /** Schema set name; `otlp` for the built-in one. */
    val schema: String? = null,
    /** Fully qualified message type. */
    val type: String? = null,
    /** The rule that chose the type: `builtin:otlp-traces` or a mapping id. */
    val rule: String? = null,
    /** Unknown fields with their paths, implausible values. */
    val warnings: List<String> = emptyList(),
    /** Why the body was not decoded. */
    val reason: String? = null,
)

@Serializable
enum class DecodingOutcome {
    @SerialName("decoded") DECODED,
    @SerialName("decoded-with-warnings") DECODED_WITH_WARNINGS,
    @SerialName("not-decoded") NOT_DECODED,
    /** No rule matched: plain text, or binary with no schema. */
    @SerialName("not-applicable") NOT_APPLICABLE,
}
