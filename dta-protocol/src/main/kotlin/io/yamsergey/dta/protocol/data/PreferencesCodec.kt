package io.yamsergey.dta.protocol.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Feature 027 (research R4): a Preferences DataStore file (`*.preferences_pb`) — the protobuf
 * `PreferenceMap { map<string, Value> preferences = 1 }`, `Value` a oneof of boolean(1),
 * float(2), integer(3), long(4), string(5), string_set(6: repeated strings = 1), double(7),
 * bytes(8). Written by hand: the sidekick may not depend on protobuf (constitution I).
 */
object PreferencesCodec {
    fun decode(bytes: ByteArray): List<PrefEntry> = try {
        val r = Reader(bytes)
        val out = mutableListOf<PrefEntry>()
        while (!r.done) {
            val (field, wire) = r.tag()
            if (field == 1 && wire == 2) out += entry(Reader(r.bytes())) else r.skip(wire)
        }
        out.sortedBy { it.key }
    } catch (e: IndexOutOfBoundsException) {
        throw IllegalArgumentException("not a Preferences DataStore file (the protobuf ends early)")
    } catch (e: IllegalStateException) {
        throw IllegalArgumentException("not a Preferences DataStore file (${e.message})")
    }

    fun encode(entries: List<PrefEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries.sortedBy { it.key }) {
            val value = ByteArrayOutputStream().apply { value(e) }.toByteArray()
            val entry = ByteArrayOutputStream().apply {
                bytesField(1, e.key.toByteArray())
                bytesField(2, value)
            }.toByteArray()
            out.bytesField(1, entry)
        }
        return out.toByteArray()
    }

    private fun entry(r: Reader): PrefEntry {
        var key = ""
        var value: PrefEntry? = null
        while (!r.done) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> key = String(r.bytes())
                field == 2 && wire == 2 -> value = value(Reader(r.bytes()))
                else -> r.skip(wire)
            }
        }
        val v = value ?: PrefEntry("", PrefType.STRING, JsonPrimitive(""))
        return v.copy(key = key)
    }

    private fun value(r: Reader): PrefEntry {
        var result: PrefEntry? = null
        while (!r.done) {
            val (field, wire) = r.tag()
            result = when (field) {
                1 -> PrefEntry("", PrefType.BOOLEAN, JsonPrimitive(r.varint() != 0L))
                2 -> PrefEntry("", PrefType.FLOAT, JsonPrimitive(java.lang.Float.intBitsToFloat(r.fixed32())))
                3 -> PrefEntry("", PrefType.INT, JsonPrimitive(r.varint().toInt()))
                4 -> PrefEntry("", PrefType.LONG, JsonPrimitive(r.varint()))
                5 -> PrefEntry("", PrefType.STRING, JsonPrimitive(String(r.bytes())))
                6 -> {
                    val set = Reader(r.bytes())
                    val strings = mutableListOf<JsonPrimitive>()
                    while (!set.done) {
                        val (f, w) = set.tag()
                        if (f == 1 && w == 2) strings += JsonPrimitive(String(set.bytes())) else set.skip(w)
                    }
                    PrefEntry("", PrefType.STRING_SET, JsonArray(strings))
                }
                7 -> PrefEntry("", PrefType.DOUBLE, JsonPrimitive(java.lang.Double.longBitsToDouble(r.fixed64())))
                8 -> PrefEntry("", PrefType.BYTES, JsonPrimitive(Base64.getEncoder().encodeToString(r.bytes())))
                else -> { r.skip(wire); result }
            }
        }
        return result ?: throw IllegalStateException("a value without a known type")
    }

    private fun ByteArrayOutputStream.value(e: PrefEntry) {
        val v = e.value.asPrefValueOrNull(e.type) ?: throw IllegalArgumentException("${e.key}: the value is not ${e.type.wireName}")
        when (e.type) {
            PrefType.BOOLEAN -> { tag(1, 0); varint(if (v as Boolean) 1 else 0) }
            PrefType.FLOAT -> { tag(2, 5); fixed32(java.lang.Float.floatToIntBits(v as Float)) }
            PrefType.INT -> { tag(3, 0); varint((v as Int).toLong()) }
            PrefType.LONG -> { tag(4, 0); varint(v as Long) }
            PrefType.STRING -> bytesField(5, (v as String).toByteArray())
            PrefType.STRING_SET -> {
                @Suppress("UNCHECKED_CAST")
                val set = ByteArrayOutputStream().apply { (v as Set<String>).forEach { bytesField(1, it.toByteArray()) } }.toByteArray()
                bytesField(6, set)
            }
            PrefType.DOUBLE -> { tag(7, 1); fixed64(java.lang.Double.doubleToLongBits(v as Double)) }
            PrefType.BYTES -> bytesField(8, v as ByteArray)
        }
    }

    private fun ByteArrayOutputStream.tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.fixed32(v: Int) = repeat(4) { write((v ushr (8 * it)) and 0xff) }
    private fun ByteArrayOutputStream.fixed64(v: Long) = repeat(8) { write(((v ushr (8 * it)) and 0xff).toInt()) }

    private fun ByteArrayOutputStream.bytesField(field: Int, bytes: ByteArray) {
        tag(field, 2)
        varint(bytes.size.toLong())
        write(bytes)
    }

    private class Reader(private val b: ByteArray) {
        private var i = 0
        val done get() = i >= b.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b[i++].toInt()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                check(shift < 64) { "a varint longer than 10 bytes" }
            }
        }

        fun tag(): Pair<Int, Int> = varint().toInt().let { (it ushr 3) to (it and 7) }

        fun bytes(): ByteArray {
            val n = varint().toInt()
            check(n >= 0 && i + n <= b.size) { "a length past the end" }
            return b.copyOfRange(i, i + n).also { i += n }
        }

        fun fixed32(): Int = (0 until 4).fold(0) { acc, k -> acc or ((b[i++].toInt() and 0xff) shl (8 * k)) }
        fun fixed64(): Long = (0 until 8).fold(0L) { acc, k -> acc or ((b[i++].toLong() and 0xff) shl (8 * k)) }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> i += 8
                2 -> bytes()
                5 -> i += 4
                else -> throw IllegalStateException("wire type $wire")
            }
            check(i <= b.size) { "a field past the end" }
        }
    }
}
