package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sets come from descriptors protobuf-java already trusts, not from our own compiler (R9). */
class DescriptorSetReaderTest {

    /** Every file a descriptor depends on, dependencies first — what `protoc --include_imports` writes. */
    private fun closure(file: Descriptors.FileDescriptor, out: LinkedHashMap<String, FileDescriptorProto> = LinkedHashMap()): LinkedHashMap<String, FileDescriptorProto> {
        file.dependencies.forEach { closure(it, out) }
        out.putIfAbsent(file.name, file.toProto())
        return out
    }

    private val otlpSet = FileDescriptorSet.newBuilder().addAllFile(closure(ExportTraceServiceRequest.getDescriptor().file).values).build()

    @Test
    fun `an OTLP set reads back with all its types`() {
        val files = DescriptorSetReader.read(otlpSet.toByteArray())
        val types = files.flatMap { it.messageTypes }.map { it.fullName }
        assertTrue("opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest" in types, "$types")
        assertTrue("opentelemetry.proto.trace.v1.Span" in types, "$types")
    }

    @Test
    fun `a hand-built set reads back`() {
        val proto = FileDescriptorProto.newBuilder().setName("h.proto").setPackage("h").setSyntax("proto3")
            .addMessageType(DescriptorProto.newBuilder().setName("M").addField(FieldDescriptorProto.newBuilder().setName("x").setNumber(1).setType(FieldDescriptorProto.Type.TYPE_INT32)))
            .build()
        val files = DescriptorSetReader.read(FileDescriptorSet.newBuilder().addFile(proto).build().toByteArray())
        assertEquals("h.M", files.single().messageTypes.single().fullName)
    }

    @Test
    fun `a set without its imports lists them`() {
        val withoutDeps = FileDescriptorSet.newBuilder().addFile(ExportTraceServiceRequest.getDescriptor().file.toProto()).build()
        val e = assertFailsWith<SchemaException> { DescriptorSetReader.read(withoutDeps.toByteArray()) }
        assertTrue("opentelemetry/proto/trace/v1/trace.proto" in e.message!!, e.message)
        assertTrue("--include_imports" in e.message!!, e.message)
    }

    @Test
    fun `bytes that are not a descriptor set are rejected`() {
        for (bytes in listOf(ByteArray(64) { (it * 37 + 11).toByte() }, ByteArray(0), "hello".toByteArray())) {
            val e = assertFailsWith<SchemaException> { DescriptorSetReader.read(bytes) }
            assertTrue("not a descriptor set" in e.message!!, e.message)
        }
    }
}
