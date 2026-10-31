package io.yamsergey.dta.run

import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where a run keeps its files under the DTA home (plan, Storage): per project the init script,
 * request and report (stable paths, so a reused configuration cache stays valid); the build
 * logs; and the injection reports the daemon reads per application id (research R6).
 */
class RunFiles(val home: File) {
    fun projectDir(project: File): File = File(home, "run/" + hash(project.canonicalPath)).apply { mkdirs() }

    fun script(project: File): File = File(projectDir(project), "dta.gradle")

    fun request(project: File): File = File(projectDir(project), "request.json")

    fun report(project: File): File = File(projectDir(project), "report.json")

    fun newLog(now: LocalDateTime = LocalDateTime.now()): File =
        File(home, "logs/run-" + now.format(STAMP) + ".log").apply { parentFile.mkdirs() }

    fun injection(applicationId: String): File = File(home, "injection/$applicationId.json")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}
