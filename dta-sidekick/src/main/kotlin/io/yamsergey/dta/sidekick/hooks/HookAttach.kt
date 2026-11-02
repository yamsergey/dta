package io.yamsergey.dta.sidekick.hooks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.util.Log
import io.yamsergey.dta.sidekick.capability.Reasons
import java.io.File
import java.util.zip.ZipFile

/**
 * Attaches the JVMTI agent and puts the hook shim on the bootstrap class path.
 *
 * Deliberately references no shim class: until the shim jar is on the bootstrap path,
 * linking a class that mentions HookDispatcher/HookRegistry would fail. [Hooks] is the
 * only code that touches the shim, and only after [attached] is true.
 */
object HookAttach {

    private const val TAG = "DtaHooks"
    private const val AGENT_LIB = "libdta-agent.so"
    private const val SHIM_ASSET = "dta-shim.jar"

    @Volatile
    var attached: Boolean = false
        private set

    /** Null while attached; otherwise why hooks are off, in plain words with what to do (feature 019). */
    @Volatile
    var reason: String? = Reasons.HOOKS_STARTING
        private set

    /** The raw cause behind [reason] (runtime words, exception text), for diagnosis only. */
    @Volatile
    var detail: String? = null
        private set

    @Synchronized
    fun attach(context: Context): Boolean {
        if (attached) return true
        try {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val abis = Build.SUPPORTED_ABIS.toList()
            val agentPath = if (Build.VERSION.SDK_INT >= AttachPreconditions.MIN_SDK && debuggable) {
                locateAgent(context, abis)
            } else {
                null
            }
            when (val pre = AttachPreconditions.check(Build.VERSION.SDK_INT, debuggable, agentPath, abis)) {
                is AttachPreconditions.Result.Unavailable -> return fail(pre.reason)
                is AttachPreconditions.Result.Attempt -> {
                    System.load(pre.agentPath)
                    @Suppress("NewApi") // AttachPreconditions returns Attempt only on API 28+
                    Debug.attachJvmtiAgent(pre.agentPath, null, HookAttach::class.java.classLoader)
                    if (!NativeAgent.nativeIsAttached()) return fail(Reasons.AGENT_REFUSED, NativeAgent.nativeAttachError())
                    val shim = extractShim(context)
                    NativeAgent.nativeAddToBootstrap(shim.absolutePath)?.let { return fail(Reasons.SHIM_FAILED, it) }
                }
            }
            attached = true
            reason = null
            detail = null
            return true
        } catch (t: Throwable) {
            return fail(Reasons.HOOKS_SETUP_FAILED, "${t.javaClass.name}: ${t.message}")
        }
    }

    private fun fail(why: String, raw: String? = null): Boolean {
        reason = why
        detail = raw
        Log.w(TAG, "hooks are off: $why" + (raw?.let { " ($it)" } ?: ""))
        return false
    }

    /** nativeLibraryDir when libs are extracted; otherwise copied out of the APK (extractNativeLibs=false). */
    private fun locateAgent(context: Context, abis: List<String>): String? {
        File(context.applicationInfo.nativeLibraryDir ?: "", AGENT_LIB).takeIf { it.isFile }?.let { return it.absolutePath }
        val dest = File(File(context.codeCacheDir, "dta"), AGENT_LIB)
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            for (abi in abis) {
                val entry = apk.getEntry("lib/$abi/$AGENT_LIB") ?: continue
                dest.parentFile?.mkdirs()
                apk.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                return dest.absolutePath
            }
        }
        return null
    }

    /** API 34+ refuses writable dex files on a class path: the copy is made read-only. */
    private fun extractShim(context: Context): File {
        val dest = File(File(context.codeCacheDir, "dta"), SHIM_ASSET)
        dest.parentFile?.mkdirs()
        if (dest.exists()) {
            dest.setWritable(true)
            dest.delete()
        }
        context.assets.open(SHIM_ASSET).use { input -> dest.outputStream().use { input.copyTo(it) } }
        dest.setReadOnly()
        return dest
    }
}
