package io.yamsergey.dta.protocol.network

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Feature 072 (R3, R4): the key the fingerprints of masked values are made under. A fingerprint is
 * HMAC-SHA256 of the credential's UTF-8 bytes, cut to 12 hex characters (48 bits): equal values give
 * equal fingerprints on this computer, and a short secret cannot be found by hashing guesses
 * without the key. The daemon creates the real key in the DTA home; tests pass a fixed one.
 */
class FingerprintKey(bytes: ByteArray) {
    private val spec = SecretKeySpec(bytes.copyOf().also { require(it.size >= 16) { "a fingerprint key has at least 16 bytes" } }, "HmacSHA256")

    /** One [Mac] per thread: [Mac] is not thread safe, creating one costs more than the HMAC itself. */
    private val mac = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256").also { it.init(spec) } }

    /** 12 lowercase hex characters. */
    fun of(value: String): String {
        val digest = mac.get().doFinal(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(FINGERPRINT_CHARS)
        for (i in 0 until FINGERPRINT_CHARS / 2) {
            val b = digest[i].toInt() and 0xff
            out.append(HEX[b ushr 4]).append(HEX[b and 15])
        }
        return out.toString()
    }

    companion object {
        const val FINGERPRINT_CHARS = 12
        private const val HEX = "0123456789abcdef"
    }
}
