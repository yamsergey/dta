package io.yamsergey.dta.studio.source

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Initiator frames resolved by class name, as the IDE's own stack navigation does. */
class FrameNavigatorTest : BasePlatformTestCase() {

    private fun project() {
        myFixture.addFileToProject("app/src/com/example/shop/Cart.java", """
            package com.example.shop;
            public class Cart {
                public void checkout() {
                    new Thread(new Runnable() { public void run() {} });
                }
                public static class Line {
                    void price() {}
                }
            }
        """.trimIndent())
        // Another file of the same name in another package: a lookup by file name would be ambiguous.
        myFixture.addFileToProject("lib/src/com/example/other/Cart.java", "package com.example.other;\npublic class Cart {}\n")
    }

    private fun resolve(cls: String, method: String, file: String?, line: Int?) =
        FrameNavigator(project).resolve(cls, method, file, line)

    fun `test a class of the project opens at the frame's line`() {
        project()
        val at = resolve("com.example.shop.Cart", "checkout", "Cart.java", 3)!!
        assertTrue(at.file.path, at.file.path.endsWith("com/example/shop/Cart.java"))
        assertEquals(3, at.line)
    }

    fun `test nested and anonymous classes open in their top-level file`() {
        project()
        assertTrue(resolve("com.example.shop.Cart\$Line", "price", "Cart.java", 7)!!.file.path.endsWith("com/example/shop/Cart.java"))
        val anonymous = resolve("com.example.shop.Cart\$1", "run", "Cart.java", 4)!!
        assertTrue(anonymous.file.path.endsWith("com/example/shop/Cart.java"))
        assertEquals(4, anonymous.line)
    }

    fun `test a frame without a line opens at its method`() {
        project()
        val at = resolve("com.example.shop.Cart", "checkout", null, null)!!
        assertNull(at.line)
        val text = myFixture.psiManager.findFile(at.file)!!.text
        assertTrue(text.substring(at.offset!!).startsWith("public void checkout") || text.substring(at.offset).startsWith("checkout"))
    }

    fun `test a class in neither the project nor its libraries is not found`() {
        project()
        assertNull(resolve("com.example.gone.Nowhere", "x", "Nowhere.kt", 1))
        assertNull(resolve("com.example.shop.Cart\$\$ExternalSyntheticLambda0", "run", "D8\$\$SyntheticClass", null))
    }

    fun `test opening runs off the UI thread and reports a frame found nowhere`() {
        project()
        var missing = false
        var opened = false
        FrameNavigator(project).open("com.example.gone.Nowhere", "x", "Nowhere.kt", 1, onMissing = { missing = true }, onOpened = { opened = true })
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("not reported", { missing }, 10)
        assertFalse(opened)
        FrameNavigator(project).open("com.example.shop.Cart", "checkout", "Cart.java", 3, onMissing = { missing = false }, onOpened = { opened = true })
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("not opened", { opened }, 10)
        assertTrue(com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.single().path.endsWith("com/example/shop/Cart.java"))
    }
}
