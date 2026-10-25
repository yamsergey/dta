package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors
import com.google.protobuf.InvalidProtocolBufferException

/** Compiled schemas (`protoc --include_imports --descriptor_set_out`) → descriptors (R4). */
object DescriptorSetReader {

    fun read(bytes: ByteArray): List<Descriptors.FileDescriptor> {
        val set = try {
            FileDescriptorSet.parseFrom(bytes)
        } catch (e: InvalidProtocolBufferException) {
            throw SchemaException("not a descriptor set: ${e.message}")
        }
        if (set.fileCount == 0 || set.unknownFields.asMap().isNotEmpty() || set.fileList.any { !it.hasName() }) {
            throw SchemaException("not a descriptor set: no named files in it")
        }
        val protos = set.fileList.associateBy { it.name }
        val missing = set.fileList.flatMap { file ->
            file.dependencyList.filter { it !in protos && it !in ProtoSourceCompiler.WELL_KNOWN }.map { "$it (imported by ${file.name})" }
        }
        if (missing.isNotEmpty()) {
            throw SchemaException("missing imports: ${missing.joinToString(", ")} — build the set with protoc --include_imports")
        }
        return ProtoSourceCompiler.link(protos)
    }
}
