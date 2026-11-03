package io.yamsergey.dta.run

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/**
 * The project's own Gradle wrapper as a child process (research R1, R8): its output goes to
 * the log and, line by line, to the run. Cancelling the run stops Gradle (and, through the
 * wrapper, the build in the Gradle daemon); so does this process exiting.
 */
class ProcessGradleRunner : GradleRunner {
    override suspend fun run(project: File, args: List<String>, env: Map<String, String>, log: File, onLine: (String) -> Unit): Int {
        log.parentFile?.mkdirs()
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(listOf(File(project, "gradlew").absolutePath) + args)
                .directory(project)
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .start()
        }
        val stop = thread(start = false) { stopTree(process) }
        Runtime.getRuntime().addShutdownHook(stop)
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { stopTree(process) }
                thread(name = "dta-gradle-output", isDaemon = true) {
                    log.bufferedWriter().use { out ->
                        process.inputStream.bufferedReader().forEachLine { line ->
                            out.appendLine(line)
                            out.flush()
                            runCatching { onLine(line) }
                        }
                    }
                    val code = process.waitFor()
                    if (continuation.isActive) continuation.resumeWith(Result.success(code))
                }
            }
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(stop) }
            if (process.isAlive) runInterruptible(Dispatchers.IO) { process.destroy() }
        }
    }

    /**
     * The wrapper first, then what it started: stopping a child first lets the wrapper run its next
     * command before it is stopped itself. The children are listed before, as they leave the tree
     * once their parent is gone.
     */
    private fun stopTree(process: Process) {
        val children = process.descendants().toList()
        process.destroy()
        children.forEach { it.destroy() }
    }
}
