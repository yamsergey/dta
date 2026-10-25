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

    /**
     * A frame is framework when its call site's package is a known framework
     * package. Without package information ([packageHash] -1) it falls back to
     * known framework file names; a missing source file is framework.
     */
    fun isLibrary(sourceFile: String?, packageHash: Int = -1): Boolean = when {
        sourceFile == null -> true
        packageHash != -1 -> packageHash in FRAMEWORK_HASHES
        else -> sourceFile in LIBRARY_FILES
    }

    /** SourceLocation.packageHash of a dotted package name. */
    fun packageHash(packageName: String): Int =
        kotlin.math.abs(packageName.fold(0) { hash, c -> hash * 31 + c.code })

    private val FRAMEWORK_HASHES: Set<Int> = FrameworkPackages.NAMES.mapTo(HashSet(), ::packageHash)

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
