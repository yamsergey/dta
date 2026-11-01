package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.FileRef
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ScreenImage
import io.yamsergey.dta.protocol.layout.ScreenImageState
import io.yamsergey.dta.protocol.layout.ScreenWindow
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Feature 014 (contract "Daemon order", research R1–R4): the screen around an unchanged tree
 * capture — windows, image A, tree, image B, windows — so the result says when the picture
 * may not be the screen the tree describes. Image B, the closest to the tree's end, is returned.
 * Nothing runs in the app: every step is a host command.
 */
class ScreenCapture(
    /** `adb shell dumpsys <args>` stdout for the serial. */
    private val dumpsys: (serial: String, args: List<String>) -> String,
    /** `adb exec-out screencap -p` bytes; throws with the reason when it fails. */
    private val screencap: (serial: String) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun capture(serial: String, pkg: String?, output: String?, tree: suspend () -> LayoutCapture): LayoutCapture {
        val before = runCatching { WindowDump.parse(dumpsys(serial, WINDOWS)) }
        val policy = runCatching { WindowDump.policy(dumpsys(serial, POLICY)) }.getOrNull()
        val statements = mutableListOf<DegradationMarker>()
        val windowsBefore = before.getOrNull()?.let { WindowDump.screenWindows(it, pkg) }

        val secure = windowsBefore?.firstOrNull { it.secure }
        val skip: Pair<ScreenImageState, String>? = when {
            before.isFailure -> ScreenImageState.NOT_TAKEN to "the windows on screen could not be read: ${before.exceptionOrNull()?.message}"
            policy?.screenOn == false -> ScreenImageState.NOT_TAKEN to "the screen is off"
            secure != null -> ScreenImageState.BLOCKED to "${secure.name} is a secure window: the system shows it black in screen captures"
            else -> null
        }
        if (policy?.screenOn == false) statements += marker(DegradationCodes.SCREEN_OFF, "the screen is off")
        if (policy?.keyguardShowing == true && policy.screenOn != false) {
            statements += marker(DegradationCodes.SCREEN_LOCKED, "the lock screen is showing: the image shows it, not the app")
        }

        val imageA = if (skip == null) runCatching { screencap(serial) } else null
        val captured = tree()
        val imageB = if (skip == null && imageA?.isSuccess == true) runCatching { screencap(serial) } else null
        val after = runCatching { WindowDump.parse(dumpsys(serial, WINDOWS)) }.getOrNull()
        val windows = after?.let { WindowDump.screenWindows(it, pkg) } ?: windowsBefore.orEmpty()

        val aligned = ScreenAlignment.align(captured.windows, windows, absolute = captured.source == CaptureSource.UIAUTOMATOR)
        val failure = imageA?.exceptionOrNull() ?: imageB?.exceptionOrNull()
        val image = when {
            skip != null -> {
                statements += marker(if (skip.first == ScreenImageState.BLOCKED) DegradationCodes.SCREEN_BLOCKED else DegradationCodes.SCREEN_NOT_TAKEN, skip.second)
                ScreenImage(state = skip.first, reason = skip.second)
            }
            failure != null -> {
                val reason = "screencap failed: ${failure.message ?: failure.javaClass.simpleName}"
                statements += marker(DegradationCodes.SCREEN_NOT_TAKEN, reason)
                ScreenImage(state = ScreenImageState.NOT_TAKEN, reason = reason)
            }
            else -> {
                val a = imageA!!.getOrThrow()
                val b = imageB!!.getOrThrow()
                changes(windowsBefore.orEmpty(), windows, a, b)?.let { statements += marker(DegradationCodes.SCREEN_CHANGED_DURING_CAPTURE, it) }
                when (aligned.alignment.kind) {
                    AlignmentKind.NOT_ALIGNED -> statements += marker(DegradationCodes.SCREEN_NOT_ALIGNED, aligned.alignment.reason.orEmpty())
                    AlignmentKind.SCALED -> statements += marker(DegradationCodes.SCREEN_SCALED, "the tree's bounds are scaled by ${aligned.alignment.scale} to the image")
                    else -> {}
                }
                val size = ScreenDiff.size(b)
                ScreenImage(
                    state = ScreenImageState.PRESENT,
                    widthPx = size?.first,
                    heightPx = size?.second,
                    displayId = after?.firstOrNull { it.owner == pkg && it.visible }?.displayId ?: 0,
                    takenAtMs = clock(),
                    alignment = aligned.alignment,
                    data = if (output == null) Base64.getEncoder().encodeToString(b) else null,
                    file = output?.let { write(it, b) },
                )
            }
        }
        val degraded = captured.degraded.orEmpty() + statements
        return captured.copy(screenImage = image, screenWindows = aligned.windows, degraded = degraded.ifEmpty { null })
    }

    /** What changed between the two looks, or null when nothing did. */
    private fun changes(before: List<ScreenWindow>, after: List<ScreenWindow>, a: ByteArray, b: ByteArray): String? {
        val parts = mutableListOf<String>()
        val beforeKeys = before.map { it.name to it.bounds }
        val afterKeys = after.map { it.name to it.bounds }
        val beforeNames = before.map { it.name }.toSet()
        val afterNames = after.map { it.name }.toSet()
        after.filter { it.name !in beforeNames }.forEach { parts += "${it.name} appeared" }
        before.filter { it.name !in afterNames }.forEach { parts += "${it.name} closed" }
        after.filter { it.name in beforeNames && (it.name to it.bounds) !in beforeKeys }.forEach { parts += "${it.name} moved to ${it.bounds.show()}" }
        if (parts.isEmpty() && beforeKeys.sortedBy { it.toString() } != afterKeys.sortedBy { it.toString() }) parts += "the windows on screen changed"
        when (val diff = ScreenDiff.compare(a, b, after.filter { it.app }.map { it.bounds })) {
            is ScreenDiff.Result.Changed -> parts += "the app's pixels changed in ${diff.box.show()}"
            is ScreenDiff.Result.Resized -> parts += "the screen changed size from ${diff.widthA}×${diff.heightA} to ${diff.widthB}×${diff.heightB}"
            ScreenDiff.Result.Same -> {}
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun write(path: String, bytes: ByteArray): FileRef {
        val file = File(path)
        file.absoluteFile.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return FileRef(file.path, bytes.size.toLong(), sha)
    }

    private fun marker(code: String, detail: String) = DegradationMarker("layout", "$code: $detail")

    private fun Rect.show() = "[$left,$top][$right,$bottom]"

    private companion object {
        val WINDOWS = listOf("window", "windows")
        val POLICY = listOf("window", "policy")
    }
}
