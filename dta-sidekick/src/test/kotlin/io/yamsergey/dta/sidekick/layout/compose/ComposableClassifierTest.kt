package io.yamsergey.dta.sidekick.layout.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the feature-002 user/framework decisions before they are shared by
 * labeling and call chains (feature 003, constitution VI). */
class ComposableClassifierTest {

    @Test
    fun `node-emitting primitives are not meaningful labels`() {
        for (name in listOf(
            "ReusableComposeNode", "ComposeNode", "Layout", "LayoutNode",
            "SubcomposeLayout", "ReusableContentHost", "ReusableContent",
        )) {
            assertFalse(ComposableClassifier.isMeaningful(name), name)
        }
        assertFalse(ComposableClassifier.isMeaningful(null))
        assertTrue(ComposableClassifier.isMeaningful("Row"))
        assertTrue(ComposableClassifier.isMeaningful("SubmitButton"))
    }

    @Test
    fun `known framework files are library and app files are not`() {
        for (file in listOf("Text.kt", "Button.kt", "Box.kt", "Row.kt", "Column.kt", "Wrapper.android.kt")) {
            assertTrue(ComposableClassifier.isLibrary(file), file)
        }
        assertFalse(ComposableClassifier.isLibrary("ComposeScreens.kt"))
        assertFalse(ComposableClassifier.isLibrary("LoginScreen.kt"))
    }

    @Test
    fun `a missing source file is treated as library`() {
        assertTrue(ComposableClassifier.isLibrary(null))
    }

    @Test
    fun `basic text composables are normalized to their public names`() {
        assertEquals("Text", ComposableClassifier.normalize("BasicText"))
        assertEquals("TextField", ComposableClassifier.normalize("BasicTextField"))
        assertEquals("Row", ComposableClassifier.normalize("Row"))
    }

    // ---- feature 003: package-hash classification (AS inspector prior art) ----

    @Test
    fun `package hash follows the SourceLocation formula`() {
        assertEquals("androidx.compose.foundation.lazy.layout".fold(0) { h, c -> h * 31 + c.code }.let { kotlin.math.abs(it) },
            ComposableClassifier.packageHash("androidx.compose.foundation.lazy.layout"))
    }

    @Test
    fun `framework packages are library whatever the file is called`() {
        val lazyLayout = ComposableClassifier.packageHash("androidx.compose.foundation.lazy.layout")
        val viewinterop = ComposableClassifier.packageHash("androidx.compose.ui.viewinterop")
        assertTrue(ComposableClassifier.isLibrary("LazySaveableStateHolder.kt", lazyLayout))
        assertTrue(ComposableClassifier.isLibrary("AndroidView.android.kt", viewinterop))
    }

    @Test
    fun `app packages are user even for files named like framework files`() {
        val app = ComposableClassifier.packageHash("io.yamsergey.dta.fixture.channel")
        assertFalse(ComposableClassifier.isLibrary("ContainersScreen.kt", app))
        assertFalse(ComposableClassifier.isLibrary("Button.kt", app))
    }

    @Test
    fun `unknown package hash falls back to the file list`() {
        assertTrue(ComposableClassifier.isLibrary("Box.kt", -1))
        assertFalse(ComposableClassifier.isLibrary("ContainersScreen.kt", -1))
    }
}
