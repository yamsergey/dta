package io.yamsergey.dta.studio.schemas

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 013 T010 (research R4): mappings from Retrofit declarations, nothing guessed. */
class RetrofitMappingsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The libraries an app would have: Retrofit's annotations and protobuf's message base class.
        listOf("GET", "POST", "PUT").forEach { verb ->
            myFixture.addFileToProject("retrofit2/http/$verb.java", "package retrofit2.http; public @interface $verb { String value() default \"\"; }")
        }
        myFixture.addFileToProject("retrofit2/http/Body.java", "package retrofit2.http; public @interface Body {}")
        myFixture.addFileToProject("retrofit2/http/Path.java", "package retrofit2.http; public @interface Path { String value(); }")
        myFixture.addFileToProject("retrofit2/Call.java", "package retrofit2; public interface Call<T> {}")
        myFixture.addFileToProject("com/google/protobuf/GeneratedMessageV3.java", "package com.google.protobuf; public abstract class GeneratedMessageV3 {}")
        listOf("CartUpdate", "CartState", "Item").forEach { name ->
            myFixture.addFileToProject("shop/v1/$name.java", "package shop.v1; public final class $name extends com.google.protobuf.GeneratedMessageV3 {}")
        }
        myFixture.addFileToProject("shop/json/Profile.java", "package shop.json; public final class Profile {}")
        myFixture.addFileToProject(
            "shop/api/CartApi.java",
            """
            package shop.api;
            import retrofit2.Call;
            import retrofit2.http.*;
            import shop.v1.*;
            public interface CartApi {
                @POST("cart/update")
                Call<CartState> update(@Body CartUpdate update);

                @GET("/items/{id}")
                Call<Item> item(@Path("id") String id);

                @GET("profile")
                Call<shop.json.Profile> profile();

                void notRetrofit(CartUpdate update);
            }
            """.trimIndent(),
        )
    }

    private val types = setOf("shop.v1.CartUpdate", "shop.v1.CartState", "shop.v1.Item", "other.v1.Item")

    fun `test request and response bodies of protobuf endpoints are mapped, with their location`() {
        val found = RetrofitMappings.derive(project, types - "other.v1.Item")
        val mappings = found.mappings.sortedBy { it.type }
        assertEquals(listOf("shop.v1.CartState", "shop.v1.CartUpdate", "shop.v1.Item"), mappings.map { it.type })
        val update = mappings.single { it.type == "shop.v1.CartUpdate" }
        assertEquals("*/cart/update", update.urlPattern)
        assertEquals("POST", update.method)
        assertEquals("request", update.part.wire)
        assertEquals("response", mappings.single { it.type == "shop.v1.CartState" }.part.wire)
        assertEquals("*/items/*", mappings.single { it.type == "shop.v1.Item" }.urlPattern)
        assertTrue(update.location!!.startsWith("CartApi.java:"))
    }

    fun `test a JSON endpoint is not mapped, and an ambiguous type is stated, not guessed`() {
        val found = RetrofitMappings.derive(project, types)
        assertTrue(found.mappings.none { it.type.endsWith("Profile") })
        assertTrue(found.mappings.none { it.type.endsWith("Item") })
        val item = found.unmapped.single { "Item" in it }
        assertTrue(item, "shop.v1.Item" in item && "other.v1.Item" in item)
    }
}
