package io.yamsergey.dta.present

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSmokeTest {
    @Test
    fun `module builds and runs tests`() = assertEquals("present", Module.NAME)
}
