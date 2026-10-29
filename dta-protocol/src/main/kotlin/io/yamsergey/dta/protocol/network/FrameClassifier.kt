package io.yamsergey.dta.protocol.network

/**
 * Turns a raw stack into an [Initiator]: drops the capture's own frames from the top,
 * marks each remaining frame as app or library/framework code, finds the first app
 * frame, and caps the stack at [Initiator.MAX_FRAMES] (FR-011). Pure.
 */
object FrameClassifier {

    /** Library and framework packages; any other class is app code. */
    val LIBRARY_PACKAGES = listOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.", "dalvik.", "libcore.",
        "sun.", "okhttp3.", "okio.", "retrofit2.", "com.squareup.", "io.reactivex.", "rx.",
        "com.neovisionaries.", "org.java_websocket.", "io.yamsergey.dta.sidekick.", "io.yamsergey.dta.shim.",
    )

    /** The capture machinery itself: never part of what the app did. */
    private val CAPTURE_PACKAGES = listOf("io.yamsergey.dta.sidekick.", "io.yamsergey.dta.shim.", "dalvik.system.VMStack")
    private val THREAD_STACK = "java.lang.Thread" to "getStackTrace"

    fun isApp(className: String): Boolean = LIBRARY_PACKAGES.none { className.startsWith(it) }

    /**
     * Glue the compiler generates for lambdas (D8/R8 desugaring, debug builds included): it sits
     * in the app's package but is nobody's code. The lambda's own body (`run$lambda$0`) is app code.
     */
    private fun generated(className: String, method: String) =
        "\$\$ExternalSynthetic" in className || "\$\$Lambda" in className || method.startsWith("\$r8\$lambda\$")

    fun initiator(thread: String, site: InitiatorSite, stack: List<StackTraceElement>): Initiator {
        val trimmed = stack.dropWhile { e ->
            CAPTURE_PACKAGES.any { e.className.startsWith(it) } || (e.className == THREAD_STACK.first && e.methodName == THREAD_STACK.second)
        }
        val kept = trimmed.take(Initiator.MAX_FRAMES).map { e ->
            StackFrame(
                className = e.className,
                method = e.methodName,
                file = e.fileName,
                // Without a file the number is not a source line (a hooked method reports one);
                // line 0 is a synthetic frame's placeholder.
                line = e.lineNumber.takeIf { it > 0 && e.fileName != null },
                app = isApp(e.className) && !generated(e.className, e.methodName),
            )
        }
        return Initiator(
            thread = thread,
            site = site,
            frames = kept,
            firstAppFrame = kept.indexOfFirst { it.app }.takeIf { it >= 0 },
            truncatedFrames = (trimmed.size - kept.size).takeIf { it > 0 },
        )
    }
}
