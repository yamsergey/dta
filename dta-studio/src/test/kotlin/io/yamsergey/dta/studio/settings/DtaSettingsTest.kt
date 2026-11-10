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

    fun `test the General page turns the timeline on`() {
        val settings = InjectionSettings.get()
        val before = settings.timeline
        try {
            settings.timeline = false
            val page = io.yamsergey.dta.studio.inject.InjectionConfigurable()
            val root = page.createComponent()!!
            fun boxes(c: java.awt.Component): List<javax.swing.JCheckBox> =
                (if (c is javax.swing.JCheckBox) listOf(c) else emptyList()) + ((c as? java.awt.Container)?.components?.flatMap(::boxes) ?: emptyList())
            val box = boxes(root).single { it.text.startsWith("Timeline") }
            assertFalse(box.isSelected)
            box.isSelected = true
            assertTrue(page.isModified)
            page.apply()
            assertTrue(settings.timeline)
            assertTrue(settings.timelineFlow.value)
            page.disposeUIResources()
        } finally {
            settings.timeline = before
        }
    }
}
