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

    fun `test while the IDE indexes, a click is refused, not reported as missing`() {
        var missing = false
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {
            SourceNavigator(project).open(Source("Screen.kt", 3), "com.example.shop", javax.swing.JLabel()) { missing = true }
        }
        assertFalse("indexing is not 'not in this project'", missing)
    }

    fun `test a file not in the project is reported missing`() {
        var missing = false
        SourceNavigator(project).open(Source("Nowhere.kt", 1), "com.example.shop", javax.swing.JLabel()) { missing = true }
        com.intellij.testFramework.PlatformTestUtil.waitWhileBusy { !missing }
        assertTrue(missing)
    }

    fun `test 054 - a source with a package is found under that package only`() {
        myFixture.addFileToProject("lib/src/com/example/other/Screen.kt", "package com.example.other\n")
        myFixture.addFileToProject("app/src/com/example/shop/Screen.kt", "package com.example.shop\n")
        val found = SourceNavigator(project).candidates(Source("Screen.kt", 3, packageName = "com.example.other"), "com.example.shop").map { it.path }
        assertEquals(1, found.size)
        assertTrue(found.single(), found.single().endsWith("com/example/other/Screen.kt"))
    }

    fun `test 054 - an app file outside its package directory is still found by name`() {
        myFixture.addFileToProject("app/src/flat/Screen.kt", "package com.example.shop\n")
        val found = SourceNavigator(project).candidates(Source("Screen.kt", 3, packageName = "com.example.shop"), "com.example.shop")
        assertEquals(1, found.size)
    }

    fun `test 054 - a library source not in the project is the class to open`() {
        assertEquals("androidx.compose.material3.TextKt", SourceNavigator.facadeClass(Source("Text.kt", 70, "androidx.compose.material3", library = true)))
        assertNull(SourceNavigator.facadeClass(Source("Text.kt", 70)))
    }
}
