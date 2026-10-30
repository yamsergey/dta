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
    fun `the plugin's own version names the coordinate - an EAP build's run number is dropped`() {
        assertEquals("0.10.0-SNAPSHOT", InjectionConfig.forRelease("0.10.0-SNAPSHOT.42").version)
        assertEquals("0.10.0", InjectionConfig.forRelease("0.10.0").version)
        assertTrue(InjectionConfig("0.10.0-SNAPSHOT").snapshot)
    }

    @Test
    fun `only debugImplementation, only Android apps`() {
        assertTrue("'debugImplementation'" in release)
        assertFalse("releaseImplementation" in release || "'implementation'" in release)
        assertTrue("withPlugin('com.android.application')" in release)
        assertFalse("com.android.library" in release, "libraries do not need the sidekick")
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
