package io.yamsergey.dta.studio.layout

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.present.layout.Source

/** T036: a node's source resolved through the project's file index. */
class SourceNavigatorTest : BasePlatformTestCase() {

    fun `test two files of one name - the one under the app's package comes first`() {
        myFixture.addFileToProject("lib/src/com/example/other/Screen.kt", "package com.example.other\n")
        myFixture.addFileToProject("app/src/com/example/shop/Screen.kt", "package com.example.shop\n")
        val found = SourceNavigator(project).candidates(Source("Screen.kt", 3), "com.example.shop").map { it.path }
        assertEquals(2, found.size)
        assertTrue(found.first(), found.first().endsWith("com/example/shop/Screen.kt"))
        assertTrue(SourceNavigator.preferred(found, "com.example.shop"))
        assertFalse("without a package both are equal candidates", SourceNavigator.preferred(found, null))
    }

    fun `test a file not in the project has no candidates`() {
        assertEmpty(SourceNavigator(project).candidates(Source("Nowhere.kt", 1), "com.example.shop"))
    }
}
