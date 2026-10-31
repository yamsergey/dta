package io.yamsergey.dta.daemon.network.proto

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 013 T003: `.proto` files under a folder, keyed as their imports name them. */
class ProtoDiscoveryTest {
    @TempDir
    lateinit var dir: File

    private fun file(path: String, text: String = "syntax = \"proto3\";") = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun `files under src-set-proto are keyed from that root, so imports across modules resolve`() {
        file("core/src/main/proto/shop/v1/item.proto", """syntax = "proto3"; package shop.v1; message Item { string name = 1; }""")
        file("app/src/main/proto/shop/v1/cart.proto", """syntax = "proto3"; package shop.v1; import "shop/v1/item.proto"; import "google/protobuf/timestamp.proto"; message Cart { repeated Item items = 1; google.protobuf.Timestamp at = 2; }""")
        file("schemas/other.proto")
        val found = ProtoDiscovery.discover(dir)
        assertEquals(setOf("shop/v1/item.proto", "shop/v1/cart.proto", "schemas/other.proto"), found.files.keys)
        assertTrue(ProtoSourceCompiler.compile(found.files).any { it.findMessageTypeByName("Cart") != null }, "imports and well-known types resolve")
    }

    @Test
    fun `build outputs and hidden folders are skipped`() {
        file("app/build/generated/x.proto")
        file(".gradle/y.proto")
        file("node_modules/z.proto")
        file("app/src/main/proto/a.proto")
        assertEquals(setOf("a.proto"), ProtoDiscovery.discover(dir).files.keys)
    }

    @Test
    fun `the bound is stated, and a folder without protos says so`() {
        repeat(5) { file("p/f$it.proto") }
        val bounded = ProtoDiscovery.discover(dir, maxFiles = 3)
        assertEquals(3, bounded.files.size)
        assertTrue(bounded.notes.any { "3" in it && "bound" in it }, "${bounded.notes}")
        val empty = File(dir, "empty").apply { mkdirs() }
        assertTrue(ProtoDiscovery.discover(empty).notes.any { "no .proto" in it })
    }

    @Test
    fun `ten thousand files are walked quickly (SC-005)`() {
        repeat(10_000) { File(dir, "src/x/f$it.kt").apply { parentFile.mkdirs(); writeText("") } }
        repeat(20) { file("lib/src/main/proto/p$it.proto") }
        val start = System.nanoTime()
        val found = ProtoDiscovery.discover(dir)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(20, found.files.size)
        assertTrue(ms < 3_000, "took $ms ms")
    }
}
