package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Permanent protocol-conformance surface (contract §5): `echo`, `emit_event`,
 * `blob`. Consumed by the E2E harness and the deviceless suites — diagnostic,
 * not dead code (constitution VI).
 */
object DiagnosticMethods {

    /** 256 KiB — keeps single frames small while proving multi-frame reassembly. */
    const val BLOB_CHUNK_BYTES = 256 * 1024

    fun install(registry: MethodRegistry) {
        registry.register("echo") { ctx -> ctx.params }

        registry.register("emit_event") { ctx ->
            val name = ctx.params?.get("name")?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: throw InvalidParamsException("emit_event requires a string 'name'")
            val count = ctx.params["count"]?.jsonPrimitive?.int ?: 1
            if (count < 1) throw InvalidParamsException("count must be >= 1")
            repeat(count) { i ->
                ctx.sendEvent(name, buildJsonObject { put("n", i + 1) })
            }
            buildJsonObject { put("emitted", count) }
        }

        registry.register("blob") { ctx ->
            val bytes = ctx.params?.get("bytes")?.jsonPrimitive?.int
            if (bytes == null || bytes < 1) {
                throw InvalidParamsException("blob requires a positive integer 'bytes'")
            }
            var offset = 0
            while (offset < bytes) {
                val chunkSize = minOf(BLOB_CHUNK_BYTES, bytes - offset)
                val chunk = ByteArray(chunkSize)
                for (i in 0 until chunkSize) chunk[i] = ((offset + i) % 251).toByte()
                ctx.attachBinary(chunk)
                offset += chunkSize
            }
            null
        }
    }
}
