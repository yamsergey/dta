package io.yamsergey.dta.present

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class TokensTest {

    @Test
    fun `every token a statement names exists with a light and a dark value`() {
        StatementKind.entries.forEach { kind ->
            val token = assertNotNull(Tokens[kind.token], "${kind.name} names ${kind.token}")
            assertNotEquals(token.light, token.dark, token.name)
        }
    }

    @Test
    fun `semantic tokens carry the design system's values`() {
        assertEquals(Rgb(0x0c6a72) to Rgb(0x5ec6cf), Tokens["kind-http"]!!.let { it.light to it.dark })
        assertEquals(Rgb(0x855000) to Rgb(0xf0b34d), Tokens["caveat"]!!.let { it.light to it.dark })
        assertEquals("#9c1f69", Tokens["intervened"]!!.light.hex)
        listOf("kind-ws", "node-view", "node-compose", "status-client", "inferred", "caveat-tint", "inferred-tint", "intervened-tint", "danger-tint", "redacted")
            .forEach { assertNotNull(Tokens[it], it) }
    }

    @Test
    fun `danger is an alias of status-server`() {
        assertEquals(Tokens["status-server"]!!.light, Tokens["danger"]!!.light)
        assertEquals(Tokens["status-server"]!!.dark, Tokens["danger"]!!.dark)
    }
}
