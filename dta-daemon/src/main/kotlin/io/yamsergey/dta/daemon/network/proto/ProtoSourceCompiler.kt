package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.AnyProto
import com.google.protobuf.ApiProto
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.DurationProto
import com.google.protobuf.EmptyProto
import com.google.protobuf.FieldMaskProto
import com.google.protobuf.SourceContextProto
import com.google.protobuf.StructProto
import com.google.protobuf.TimestampProto
import com.google.protobuf.TypeProto
import com.google.protobuf.WrappersProto
import com.squareup.wire.Syntax
import com.squareup.wire.schema.Field
import com.squareup.wire.schema.Location
import com.squareup.wire.schema.internal.parser.EnumElement
import com.squareup.wire.schema.internal.parser.FieldElement
import com.squareup.wire.schema.internal.parser.MessageElement
import com.squareup.wire.schema.internal.parser.ProtoFileElement
import com.squareup.wire.schema.internal.parser.ProtoParser

/**
 * `.proto` sources → protobuf-java descriptors without `protoc` (R4): Square Wire's
 * parser reads the files, this turns them into `FileDescriptorProto`s, and protobuf-java
 * links them (type resolution by protobuf scoping, validation) — the same engine as
 * descriptor sets. Unsupported constructs (`extend`, groups) are rejected with their
 * location; `google/protobuf/…` imports resolve to the bundled well-known types.
 */
object ProtoSourceCompiler {

    fun compile(files: Map<String, String>): List<Descriptors.FileDescriptor> {
        if (files.isEmpty()) throw SchemaException("no .proto files given")
        val parsed = files.mapValues { (path, source) ->
            try {
                ProtoParser.parse(Location.get(path), source)
            } catch (e: Exception) {
                throw SchemaException(e.message ?: "cannot parse $path")
            }
        }
        val missing = parsed.flatMap { (path, file) -> imports(file).filter { it !in parsed && it !in WELL_KNOWN }.map { "$it (imported by $path)" } }
        if (missing.isNotEmpty()) throw SchemaException("missing imports: ${missing.joinToString(", ")}")
        val protos = parsed.mapValues { (path, file) -> fileProto(path, file) }
        return link(protos)
    }

    /** Builds files in dependency order; shared with the descriptor-set reader. */
    fun link(protos: Map<String, FileDescriptorProto>): List<Descriptors.FileDescriptor> {
        val built = LinkedHashMap<String, Descriptors.FileDescriptor>()
        val visiting = mutableSetOf<String>()
        fun build(name: String): Descriptors.FileDescriptor {
            built[name]?.let { return it }
            WELL_KNOWN[name]?.let { return it }
            val proto = protos[name] ?: throw SchemaException("missing import: $name")
            if (!visiting.add(name)) throw SchemaException("import cycle through $name")
            val deps = proto.dependencyList.map { build(it) }.toTypedArray()
            val file = try {
                Descriptors.FileDescriptor.buildFrom(proto, deps)
            } catch (e: Descriptors.DescriptorValidationException) {
                throw SchemaException("$name: ${e.message}")
            }
            visiting.remove(name)
            built[name] = file
            return file
        }
        protos.keys.forEach { build(it) }
        return built.values.toList()
    }

    private fun imports(file: ProtoFileElement) = file.imports + file.publicImports + file.weakImports

    private fun fileProto(path: String, file: ProtoFileElement): FileDescriptorProto {
        file.extendDeclarations.firstOrNull()?.let { unsupported("extend", it.location) }
        val proto3 = file.syntax == Syntax.PROTO_3
        return FileDescriptorProto.newBuilder().setName(path).apply {
            file.packageName?.let { setPackage(it) }
            setSyntax(if (proto3) "proto3" else "proto2")
            imports(file).forEach { addDependency(it) }
            file.publicImports.forEach { addPublicDependency(dependencyList.indexOf(it)) }
            for (type in file.types) {
                when (type) {
                    is MessageElement -> addMessageType(message(type, proto3))
                    is EnumElement -> addEnumType(enum(type))
                    else -> unsupported(type.javaClass.simpleName, type.location)
                }
            }
        }.build()
    }

    private fun message(element: MessageElement, proto3: Boolean): DescriptorProto {
        element.groups.firstOrNull()?.let { unsupported("group", it.location) }
        element.oneOfs.flatMap { it.groups }.firstOrNull()?.let { unsupported("group", it.location) }
        element.extendDeclarations.firstOrNull()?.let { unsupported("extend", it.location) }
        val builder = DescriptorProto.newBuilder().setName(element.name)
        element.oneOfs.forEach { builder.addOneofDecl(DescriptorProtos.OneofDescriptorProto.newBuilder().setName(it.name)) }
        val declared = element.fields.map { it to null as Int? } +
            element.oneOfs.flatMapIndexed { i, oneOf -> oneOf.fields.map { it to i } }
        val synthetic = mutableListOf<String>()
        for ((field, oneOf) in declared.sortedWith(compareBy({ it.first.location.line }, { it.first.location.column }))) {
            builder.addField(field(field, oneOf, proto3, builder, synthetic))
        }
        // proto3 `optional` = a synthetic oneof each, declared after every real one.
        synthetic.forEach { builder.addOneofDecl(DescriptorProtos.OneofDescriptorProto.newBuilder().setName(it)) }
        for (nested in element.nestedTypes) {
            when (nested) {
                is MessageElement -> builder.addNestedType(message(nested, proto3))
                is EnumElement -> builder.addEnumType(enum(nested))
                else -> unsupported(nested.javaClass.simpleName, nested.location)
            }
        }
        return builder.build()
    }

    private fun field(element: FieldElement, oneOf: Int?, proto3: Boolean, message: DescriptorProto.Builder, synthetic: MutableList<String>): FieldDescriptorProto {
        val builder = FieldDescriptorProto.newBuilder().setName(element.name).setNumber(element.tag)
        element.jsonName?.let { builder.setJsonName(it) }
        builder.label = when (element.label) {
            Field.Label.REPEATED -> FieldDescriptorProto.Label.LABEL_REPEATED
            Field.Label.REQUIRED -> FieldDescriptorProto.Label.LABEL_REQUIRED
            else -> FieldDescriptorProto.Label.LABEL_OPTIONAL
        }
        if (oneOf != null) builder.oneofIndex = oneOf
        if (proto3 && element.label == Field.Label.OPTIONAL && oneOf == null) {
            builder.proto3Optional = true
            builder.oneofIndex = message.oneofDeclCount + synthetic.size
            synthetic += "_${element.name}"
        }
        if (!proto3) element.defaultValue?.let { builder.setDefaultValue(it) }
        val map = MAP.matchEntire(element.type)
        if (map != null) {
            val entry = element.name.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Entry"
            message.addNestedType(
                DescriptorProto.newBuilder().setName(entry)
                    .setOptions(DescriptorProtos.MessageOptions.newBuilder().setMapEntry(true))
                    .addField(typed(FieldDescriptorProto.newBuilder().setName("key").setNumber(1).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL), map.groupValues[1]))
                    .addField(typed(FieldDescriptorProto.newBuilder().setName("value").setNumber(2).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL), map.groupValues[2])),
            )
            builder.label = FieldDescriptorProto.Label.LABEL_REPEATED
            builder.typeName = entry
            return builder.build()
        }
        return typed(builder, element.type).build()
    }

    /** Scalars by keyword; anything else is a message or enum name that protobuf-java resolves by scope. */
    private fun typed(builder: FieldDescriptorProto.Builder, type: String): FieldDescriptorProto.Builder {
        val scalar = SCALARS[type]
        return if (scalar != null) builder.setType(scalar) else builder.setTypeName(type)
    }

    private fun enum(element: EnumElement): EnumDescriptorProto {
        val builder = EnumDescriptorProto.newBuilder().setName(element.name)
        if (element.options.any { it.name == "allow_alias" && it.value.toString() == "true" }) {
            builder.setOptions(DescriptorProtos.EnumOptions.newBuilder().setAllowAlias(true))
        }
        element.constants.forEach { builder.addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName(it.name).setNumber(it.tag)) }
        return builder.build()
    }

    private fun unsupported(construct: String, location: Location): Nothing =
        throw SchemaException("$construct is not supported ($location)")

    private val MAP = Regex("""map\s*<\s*([\w.]+)\s*,\s*([\w.]+)\s*>""")

    private val SCALARS = mapOf(
        "double" to FieldDescriptorProto.Type.TYPE_DOUBLE, "float" to FieldDescriptorProto.Type.TYPE_FLOAT,
        "int64" to FieldDescriptorProto.Type.TYPE_INT64, "uint64" to FieldDescriptorProto.Type.TYPE_UINT64,
        "int32" to FieldDescriptorProto.Type.TYPE_INT32, "fixed64" to FieldDescriptorProto.Type.TYPE_FIXED64,
        "fixed32" to FieldDescriptorProto.Type.TYPE_FIXED32, "bool" to FieldDescriptorProto.Type.TYPE_BOOL,
        "string" to FieldDescriptorProto.Type.TYPE_STRING, "bytes" to FieldDescriptorProto.Type.TYPE_BYTES,
        "uint32" to FieldDescriptorProto.Type.TYPE_UINT32, "sfixed32" to FieldDescriptorProto.Type.TYPE_SFIXED32,
        "sfixed64" to FieldDescriptorProto.Type.TYPE_SFIXED64, "sint32" to FieldDescriptorProto.Type.TYPE_SINT32,
        "sint64" to FieldDescriptorProto.Type.TYPE_SINT64,
    )

    /** `google/protobuf/…` files bundled with protobuf-java. */
    val WELL_KNOWN: Map<String, Descriptors.FileDescriptor> = listOf(
        AnyProto.getDescriptor(), ApiProto.getDescriptor(), DescriptorProtos.getDescriptor(), DurationProto.getDescriptor(),
        EmptyProto.getDescriptor(), FieldMaskProto.getDescriptor(), SourceContextProto.getDescriptor(), StructProto.getDescriptor(),
        TimestampProto.getDescriptor(), TypeProto.getDescriptor(), WrappersProto.getDescriptor(),
    ).associateBy { it.name }

}
