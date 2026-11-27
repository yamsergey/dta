package io.yamsergey.dta.protocol.data

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/** D-132: every non-nullable field of a heap answer is in its JSON, also when it holds its default (false, empty). */
@OptIn(ExperimentalSerializationApi::class)
class HeapAnswerFieldsTest {
    private fun missing(d: SerialDescriptor, v: JsonElement, path: String, out: MutableList<String>) {
        when (d.kind) {
            StructureKind.CLASS -> {
                val o = v as? JsonObject ?: return
                for (i in 0 until d.elementsCount) {
                    val name = d.getElementName(i)
                    val e = d.getElementDescriptor(i)
                    val x = o[name]
                    if (x == null) { if (!e.isNullable) out += "$path.$name"; continue }
                    missing(e, x, "$path.$name", out)
                }
            }
            StructureKind.LIST -> (v as? JsonArray)?.forEachIndexed { n, item -> missing(d.getElementDescriptor(0), item, "$path[$n]", out) }
            else -> Unit
        }
    }

    private fun <T> check(s: KSerializer<T>, value: T) {
        val json = DtaJson.encodeToJsonElement(s, value)
        val out = mutableListOf<String>()
        missing(s.descriptor, json, s.descriptor.serialName.substringAfterLast('.'), out)
        assertTrue(out.isEmpty(), "missing from the JSON: $out")
    }

    private val row = HeapClassRow("A", null, HeapOrigin.APP, 1, 10)
    private val totals = HeapTotals(1, 10)
    private val summary = HeapSnapshotSummary("s1", null, "r", 0, 5, false, totals, 1, listOf(row))
    private val instance = HeapInstance(1, "A", 8)
    private val field = HeapField("f", "A", "int")
    private val comparison = HeapComparison("s1", "s2", "r", listOf(HeapComparisonRow("A", null, HeapOrigin.APP, 1, 2, 10, 20, HeapChange.GREW)),
        HeapFilter(), HeapLeftOut(), 0, 1, 10)

    @Test fun snapshotAnswer() = check(HeapSnapshotAnswer.serializer(), HeapSnapshotAnswer(summary, 5))
    @Test fun snapshot() = check(HeapSnapshot.serializer(), HeapSnapshot("s1", null, "r", 0, 5, false, listOf(row), totals))
    @Test fun list() = check(HeapListAnswer.serializer(), HeapListAnswer(snapshots = listOf(HeapSnapshotInfo("s1", null, 0, 5, 1, 10))))
    @Test fun emptyList() = check(HeapListAnswer.serializer(), HeapListAnswer())
    @Test fun compare() = check(HeapCompareAnswer.serializer(), HeapCompareAnswer(comparison, "none"))
    @Test fun instances() = check(HeapInstancesAnswer.serializer(), HeapInstancesAnswer("A", 1, 0, listOf(instance), "live"))
    @Test fun fields() = check(HeapFieldsAnswer.serializer(), HeapFieldsAnswer(HeapFields(1, "A", listOf(field)), false, "n"))
    @Test fun run() = check(HeapRunInfo.serializer(), HeapRunInfo("r"))
    @Test fun walk() = check(HeapWalk.serializer(), HeapWalk("r"))
}
