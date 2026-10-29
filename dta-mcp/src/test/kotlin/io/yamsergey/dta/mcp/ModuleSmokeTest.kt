package io.yamsergey.dta.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSmokeTest {
    @Test
    fun `module builds and runs tests`() = assertEquals("mcp", Module.NAME)
}
