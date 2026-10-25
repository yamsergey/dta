package io.yamsergey.dta.sidekick.layout.compose

/**
 * The single user-vs-framework decision for composables, shared by labeling
 * and call chains (constitution VI). Pure: names and file names in, verdicts
 * out.
 */
object ComposableClassifier {

    /** A composable can label a node or appear in a chain unless it is a
     * node-emitting / layout primitive. */
    fun isMeaningful(rawName: String?): Boolean = rawName != null && rawName !in SKIP_NAMES

    /** A composable's source file is a framework file when it lives in a known
     * Compose/AndroidX file — user composables live elsewhere (the app). */
    fun isLibrary(sourceFile: String?): Boolean = sourceFile == null || sourceFile in LIBRARY_FILES

    fun normalize(name: String): String = when (name) {
        "BasicText" -> "Text"
        "BasicTextField" -> "TextField"
        else -> name
    }

    // Node-emitting / layout primitives that must not become a node's label.
    private val SKIP_NAMES = setOf(
        "ReusableComposeNode", "ComposeNode", "Layout", "LayoutNode",
        "SubcomposeLayout", "ReusableContentHost", "ReusableContent",
    )

    // Common Compose/Material/Foundation source files — composables from
    // these are framework internals, not the developer's own code.
    private val LIBRARY_FILES = setOf(
        "Text.kt", "BasicText.kt", "BasicTextField.kt", "TextField.kt",
        "Surface.kt", "Box.kt", "Row.kt", "Column.kt", "Layout.kt",
        "Button.kt", "Card.kt", "Scaffold.kt", "AppBar.kt", "Icon.kt",
        "Image.kt", "Spacer.kt", "SelectionContainer.kt", "CoreText.kt",
        "AndroidView.kt", "ComposeView.android.kt", "Wrapper.android.kt",
        "SnackbarHost.kt", "ProvideTextStyle.kt", "TextStyle.kt",
    )
}
