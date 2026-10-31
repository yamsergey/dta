package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** 013 T005: what a schema sync did, in the terminal. */
class SchemaSyncFormatTest {
    @Test
    fun `the set, failures, unmapped types and the next step`() {
        val text = Formatters(Style.PLAIN).format(
            "network.schema.sync",
            Json.parseToJsonElement(
                """{"set":{"name":"folder-ab12","source":"proto-sources","files":["shop/v1/cart.proto","shop/v1/item.proto"],"types":["shop.v1.Cart","shop.v1.Item"],"addedAtMs":1,
                    "origin":{"kind":"folder","path":"/work/shop","client":"cli","syncedMs":1}},
                   "replaced":true,"failed":[{"file":"shop/v1/bad.proto","reason":"Syntax error"}],"mappings":0,
                   "unmappedTypes":["shop.v1.Cart","shop.v1.Item"],"notes":[]}""",
            ),
        )
        assertTrue("2 files from /work/shop as folder-ab12 (replaced)" in text, text)
        assertTrue("shop/v1/bad.proto: Syntax error" in text, text)
        assertTrue("not mapped yet: shop.v1.Cart, shop.v1.Item" in text, text)
        assertTrue("dta network mapping add" in text, text)
    }
}
