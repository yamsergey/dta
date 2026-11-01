package io.yamsergey.dta.inject

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T039: the rendered init script; what it does in a real build is InjectionE2eTest's (harness). */
class InitScriptTest {
    private val release = InitScript.render(InjectionConfig("0.10.0"))
    private val snapshot = InitScript.render(InjectionConfig("0.10.0-SNAPSHOT"))

    @Test
    fun `a release version resolves from Maven Central only`() {
        assertTrue("mavenCentral()" in release)
        assertTrue("io.github.yamsergey:dta-sidekick" in release && "'0.10.0'" in release, release)
        assertFalse("maven-snapshots" in release || "mavenLocal()" in release || "cacheChangingModulesFor" in release, release)
    }

    @Test
    fun `a snapshot adds the snapshot repository and mavenLocal, and never caches the changing module`() {
        assertTrue("https://central.sonatype.com/repository/maven-snapshots/" in snapshot)
        assertTrue("mavenLocal()" in snapshot)
        assertTrue("cacheChangingModulesFor(0, 'seconds')" in snapshot)
    }

    @Test
    fun `a snapshot adds project-level repositories only to a project that declares its own`() {
        // In PREFER_PROJECT mode a project that declares any repository ignores the settings'
        // ones: adding ours to a project with none would hide google() and mavenCentral() from it.
        assertTrue("if (needed && !project.repositories.isEmpty())" in snapshot, snapshot)
    }

    @Test
    fun `the plugin's own version names the coordinate - an EAP build's run number is dropped`() {
        assertEquals("0.10.0-SNAPSHOT", InjectionConfig.forRelease("0.10.0-SNAPSHOT.42").version)
        assertEquals("0.10.0", InjectionConfig.forRelease("0.10.0").version)
        assertTrue(InjectionConfig("0.10.0-SNAPSHOT").snapshot)
    }

    @Test
    fun `every debuggable build type, never another, only Android apps`() {
        assertTrue("buildTypes.matching { it.debuggable }" in release, release)
        assertTrue("bt.name + 'Implementation'" in release, release)
        val adds = Regex("""dependencies\.add\(([^,]+),""").findAll(release).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("bt.name + 'Implementation'"), adds, "dependencies are only ever added to debuggable build types")
        assertFalse("releaseImplementation" in release)
        assertTrue("withPlugin('com.android.application')" in release)
        assertFalse("com.android.library" in release, "libraries do not need the sidekick")
    }

    @Test
    fun `variants and application ids are reported`() {
        assertTrue("onVariants" in release && "applicationId" in release && "debuggable" in release, release)
    }

    @Test
    fun `Compose tooling data is added versionless, probed first, and can be turned off`() {
        assertTrue("androidx.compose.ui:ui-tooling-data" in release)
        assertTrue("projectProperties['dta.inject.tooling']" in release)
        listOf("'injected'", "'project-provided'", "'not-applicable'", "'unresolved'", "'off'").forEach { assertTrue(it in release, it) }
        assertTrue("ExternalModuleDependency" in release, "the probe resolves the app's declared external dependencies, never its own configurations")
    }

    @Test
    fun `run mode reads the request, registers dtaRun and writes the report from its action`() {
        assertTrue("projectProperties['dta.run.request']" in release)
        assertTrue("tasks.register('dtaRun')" in release)
        assertTrue("doLast" in release)
        listOf("'selected'", "'no-app-module'", "'module-required'", "'module-not-found'", "'variant-required'", "'variant-not-found'", "'not-debuggable'")
            .forEach { assertTrue(it in release, it) }
    }

    @Test
    fun `the new properties have fixed names`() {
        val config = InjectionConfig("0.10.0")
        assertEquals("dta.inject.tooling", config.toolingProperty)
        assertEquals("dta.run.request", config.runProperty)
    }

    @Test
    fun `a project that declares DTA itself is left alone`() {
        assertTrue("project-provided" in release)
    }

    @Test
    fun `the sidekick is resolved leniently before it is added, and nothing can fail the build`() {
        assertTrue("detachedConfiguration" in release && "UnresolvedDependencyResult" in release)
        assertTrue("'unresolved'" in release && "'unsupported'" in release)
        assertTrue("catch (Throwable" in release)
        // Every project action is guarded: the callbacks' bodies each start with a try.
        Regex("""(beforeSettings|projectsEvaluated|afterEvaluate) \{[^\n]*\n\s*try \{""").findAll(release).count().let {
            assertEquals(3, it, release)
        }
    }

    @Test
    fun `the report goes where the report property says`() {
        assertEquals("dta.inject.report", InjectionConfig("0.10.0").reportProperty)
        assertTrue("projectProperties['dta.inject.report']" in release)
    }

    @Test
    fun `a version that could break out of the script is refused`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { InjectionConfig("1.0'; evil()") }
    }
}
