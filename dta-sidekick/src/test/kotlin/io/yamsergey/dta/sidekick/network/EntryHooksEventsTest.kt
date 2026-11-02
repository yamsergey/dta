package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.shim.ControlHandler
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.sidekick.hooks.Hooks.Install
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 019 (US1): an event is hooked through the first of its known signatures present in the app. */
class EntryHooksEventsTest {

    private class Target

    /** Answers from [outcomes] by descriptor; MethodAbsent for any other; records every attempt. */
    private class FakeInstaller(private val outcomes: Map<String, Install>) : HookInstaller {
        val attempts = mutableListOf<String>()
        private fun outcome(cls: Class<*>, method: String, descriptor: String): Install {
            attempts += "$method$descriptor"
            return outcomes[descriptor] ?: Install.MethodAbsent(cls.name, method, descriptor)
        }
        override fun entry(cls: Class<*>, method: String, descriptor: String, handler: EntryHandler) = outcome(cls, method, descriptor)
        override fun control(cls: Class<*>, method: String, descriptor: String, handler: ControlHandler) = outcome(cls, method, descriptor)
    }

    private val newest = HookSignature("failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;Z)V", since = "OkHttp 5")
    private val oldest = HookSignature("failWebSocket", "(Ljava/lang/Exception;Lokhttp3/Response;)V", since = "OkHttp 4")

    @Test
    fun `the first present signature is hooked and the older ones are not tried`() {
        val installer = FakeInstaller(mapOf(newest.descriptor to Install.Installed, oldest.descriptor to Install.Installed))
        val hooks = EntryHooks(Target::class.java, installer)
        hooks.on("failures", newest, oldest) { _, _ -> }
        val event = hooks.events.single()
        assertEquals(newest, event.installed)
        assertEquals(listOf("failWebSocket${newest.descriptor}"), installer.attempts)
    }

    @Test
    fun `an older signature is used when the newest is absent`() {
        val installer = FakeInstaller(mapOf(oldest.descriptor to Install.Installed))
        val hooks = EntryHooks(Target::class.java, installer)
        hooks.on("failures", newest, oldest) { _, _ -> }
        assertEquals(oldest, hooks.events.single().installed)
        assertEquals(2, hooks.events.single().tried.size)
    }

    @Test
    fun `none present leaves the event not captured with every signature tried`() {
        val hooks = EntryHooks(Target::class.java, FakeInstaller(emptyMap()))
        hooks.control("failures", newest, oldest) { _, _ -> EntryHooks.PROCEED }
        val event = hooks.events.single()
        assertNull(event.installed)
        assertEquals(listOf(newest, oldest), event.tried.map { it.first })
    }

    @Test
    fun `a refused present signature does not fall back to an older one`() {
        val installer = FakeInstaller(mapOf(newest.descriptor to Install.Failed("Android refused to reload the class with the hook", "x"), oldest.descriptor to Install.Installed))
        val hooks = EntryHooks(Target::class.java, installer)
        hooks.on("failures", newest, oldest) { _, _ -> }
        assertNull(hooks.events.single().installed)
        assertEquals(1, installer.attempts.size)
    }

    @Test
    fun `the library state carries every event of every class`() {
        val a = EntryHooks(Target::class.java, FakeInstaller(mapOf("()V" to Install.Installed)))
        a.on("connections", "connect", "()V") { _, _ -> }
        val b = EntryHooks(Target::class.java, FakeInstaller(emptyMap()))
        b.on("close", "close", "(I)V") { _, _ -> }
        val state = EntryHooks.state("lib", a, b)
        assertEquals(listOf("connections", "close"), state.events.map { it.event })
        assertEquals(true, state.present)
    }
}
