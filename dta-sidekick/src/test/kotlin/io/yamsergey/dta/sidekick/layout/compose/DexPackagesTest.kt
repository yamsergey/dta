package io.yamsergey.dta.sidekick.layout.compose

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Feature 054: the packages an app ships, read from its dex type table. */
class DexPackagesTest {

    /** A dex holding only what the reader looks at: header, string_ids, type_ids, string data. */
    private fun dex(vararg descriptors: String): ByteArray {
        val strings = descriptors.toList()
        val headerSize = 0x70
        val stringIdsOff = headerSize
        val typeIdsOff = stringIdsOff + 4 * strings.size
        var dataOff = typeIdsOff + 4 * strings.size
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val at = dataOff + data.size()
            val bytes = s.toByteArray(Charsets.UTF_8)
            data.write(s.length) // uleb128, short strings only
            data.write(bytes); data.write(0)
            at
        }
        val total = dataOff + data.size()
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put("dex\n035\u0000".toByteArray(Charsets.ISO_8859_1))
        b.putInt(32, total); b.putInt(36, headerSize); b.putInt(40, 0x12345678)
        b.putInt(56, strings.size); b.putInt(60, stringIdsOff)
        b.putInt(64, strings.size); b.putInt(68, typeIdsOff)
        offsets.forEachIndexed { i, off -> b.putInt(stringIdsOff + 4 * i, off) }
        strings.indices.forEach { i -> b.putInt(typeIdsOff + 4 * i, i) }
        b.position(dataOff); b.put(data.toByteArray())
        return b.array()
    }

    @Test
    fun `packages come from class descriptors`() {
        val p = DexPackages.packagesOf(dex(
            "Landroidx/compose/material3/internal/ProvideContentColorTextStyleKt;",
            "Lio/example/shop/ui/CartKt;", "Lio/example/shop/ui/CartKt\$Row\$1;",
            "[Ljava/lang/String;", "I", "LTopLevel;",
        ))
        assertEquals(setOf("androidx.compose.material3.internal", "io.example.shop.ui"), p)
    }

    @Test
    fun `every dex of every apk is read`() {
        val apk = File.createTempFile("app", ".apk").apply { deleteOnExit() }
        ZipOutputStream(apk.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex")); z.write(dex("La/b/C;")); z.closeEntry()
            z.putNextEntry(ZipEntry("classes2.dex")); z.write(dex("Lc/d/E;")); z.closeEntry()
            z.putNextEntry(ZipEntry("res/raw/notdex.dex")); z.write(dex("Lx/y/Z;")); z.closeEntry()
        }
        assertEquals(setOf("a.b", "c.d"), DexPackages.read(listOf(apk)))
    }

    @Test
    fun `a broken dex is an error, not an empty answer`() {
        assertFailsWith<IllegalStateException> { DexPackages.packagesOf(ByteArray(10)) }
        val bad = dex("La/b/C;").also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(60, 1_000_000) }
        assertFailsWith<IllegalStateException> { DexPackages.packagesOf(bad) }
    }

    @Test
    fun `the index finds packages by Compose's hash`() {
        val index = PackageIndex(setOf("androidx.compose.material3.internal", "io.example.shop.ui"))
        assertEquals(listOf("androidx.compose.material3.internal"), index.packagesOf(ComposableClassifier.packageHash("androidx.compose.material3.internal")))
        assertTrue(index.packagesOf(12345).isEmpty())
        assertEquals(2, index.size)
    }

    @Test
    fun `the holder answers once built, and says why when it cannot`() {
        val ready = PackageIndexHolder { setOf("a.b") }.also { it.start() }
        assertEquals(1, (ready.await(2_000) as PackageIndexHolder.State.Ready).index.size)
        val failed = PackageIndexHolder { error("no apk") }.also { it.start() }
        assertTrue((failed.await(2_000) as PackageIndexHolder.State.Failed).reason.contains("no apk"))
        val slow = PackageIndexHolder { Thread.sleep(2_000); emptySet() }.also { it.start() }
        assertEquals(PackageIndexHolder.State.NotReady, slow.await(50))
        assertEquals(PackageIndexHolder.State.NotReady, PackageIndexHolder { emptySet() }.await(10), "never started")
    }
}
