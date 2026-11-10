package io.yamsergey.dta.studio.settings

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.studio.inject.InjectionSettings

/** Feature 056: Timeline off by default; Agents and Protobuf are pages under DTA's settings. */
class DtaSettingsTest : BasePlatformTestCase() {
    fun `test timeline is off by default and its flow follows the switch`() {
        val settings = InjectionSettings.get()
        val before = settings.timeline
        try {
            settings.loadState(InjectionSettings.Options())
            assertFalse(settings.timeline)
            settings.timeline = true
            assertTrue(settings.timelineFlow.value)
        } finally {
            settings.timeline = before
        }
    }

    fun `test the agents and protobuf pages sit under DTA`() {
        val app = Configurable.APPLICATION_CONFIGURABLE.extensionList.filter { it.parentId == "io.yamsergey.dta.settings" }.map { it.id }
        val proj = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.parentId == "io.yamsergey.dta.settings" }.map { it.id }
        assertEquals(listOf("io.yamsergey.dta.settings.agents"), app)
        assertEquals(listOf("io.yamsergey.dta.settings.protobuf"), proj)
    }
}
