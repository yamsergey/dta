package io.yamsergey.dta.studio.schemas

import kotlin.test.Test
import kotlin.test.assertEquals

/** 013 T008 (research R3): a .proto file's key is the path its imports use. */
class ProtoKeysTest {
    @Test
    fun `source roots, jars and other files`() {
        assertEquals("shop/v1/cart.proto", ProtoKeys.of("/work/shop/app/src/main/proto/shop/v1/cart.proto", "/work/shop"))
        assertEquals("google/type/money.proto", ProtoKeys.of("/home/.gradle/caches/x/proto-google-common.jar!/google/type/money.proto", "/work/shop"))
        assertEquals("schemas/other.proto", ProtoKeys.of("/work/shop/schemas/other.proto", "/work/shop"))
        assertEquals("other.proto", ProtoKeys.of("/elsewhere/other.proto", "/work/shop"))
    }
}
