package io.yamsergey.dta.studio.agents

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** T031: the plugin offers `app_run` its open projects that have a Gradle wrapper. */
class RunnableProjectsTest {
    @Test
    fun `projects with a wrapper, each once`() {
        val gradle = Files.createTempDirectory("with").toFile().apply { File(this, "gradlew").writeText("") }
        val other = Files.createTempDirectory("without").toFile()
        assertEquals(listOf(gradle), RunnableProjects.of(listOf(gradle.path, other.path, null, gradle.path)))
    }
}
