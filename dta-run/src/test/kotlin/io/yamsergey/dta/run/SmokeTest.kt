package io.yamsergey.dta.run

import io.yamsergey.dta.inject.InjectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun `the module sees the injection config`() {
        assertEquals("io.github.yamsergey:dta-sidekick:1.0.0", InjectionConfig("1.0.0").coordinate)
    }
}
