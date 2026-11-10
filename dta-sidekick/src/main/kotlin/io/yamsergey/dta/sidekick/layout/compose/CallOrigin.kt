package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.network.FrameClassifier

/** Feature 054: whose code a composable call is, and its package when known. */
data class CallOrigin(val kind: Kind, val packageName: String? = null) {
    enum class Kind { APP, LIBRARY, UNKNOWN }

    val library: Boolean get() = kind == Kind.LIBRARY
    val unknown: Boolean get() = kind == Kind.UNKNOWN
}

/**
 * Classifies calls by the package their hash names among the app's packages ([index]), with the
 * network initiators' rule (one rule: [FrameClassifier]). Without an index (not read, or not
 * ready) the fixed framework list of [ComposableClassifier] decides, as before 054; the caller
 * states that. [unknownCalls] counts calls whose package could not be told.
 */
class CallClassifier(private val index: PackageIndexLookup?, private val appPackage: String?) {
    var unknownCalls = 0
        private set

    fun of(sourceFile: String?, packageHash: Int): CallOrigin {
        if (sourceFile == null || packageHash == -1 || index == null) {
            return CallOrigin(if (ComposableClassifier.isLibrary(sourceFile, packageHash)) CallOrigin.Kind.LIBRARY else CallOrigin.Kind.APP)
        }
        val packages = index.packagesOf(packageHash)
        val app = packages.map { FrameClassifier.isAppPackage(it, appPackage) }.toSet()
        return when {
            packages.isEmpty() || app.size > 1 -> { unknownCalls++; CallOrigin(CallOrigin.Kind.UNKNOWN) }
            app.single() -> CallOrigin(CallOrigin.Kind.APP, packages.singleOrNull())
            else -> CallOrigin(CallOrigin.Kind.LIBRARY, packages.singleOrNull())
        }
    }
}
