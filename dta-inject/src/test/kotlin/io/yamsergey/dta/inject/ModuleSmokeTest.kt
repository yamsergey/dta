package io.yamsergey.dta.inject

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSmokeTest {
    @Test
    fun `module builds and runs tests`() = assertEquals("inject", Module.NAME)
}
