package io.yamsergey.dta.sidekick.runtime

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.internal.DoubleCheck
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiReadersTest {
    class Repo
    class Api
    class App

    @AfterTest fun stop() { runCatching { stopKoin() } }

    @Test
    fun `koin definitions with kind, qualifier and the instance a created single has`() {
        val koin = startKoin { modules(module { single { Repo() }; factory { Api() }; single(named("base")) { "http://x/" } }) }.koin
        val repo = koin.get<Repo>()
        val graph = DiReaders.koin(DiReadersTest::class.java.classLoader)!!
        assertEquals("koin", graph.framework)
        val byType = graph.bindings.associateBy { it.type }
        assertEquals("singleton", byType[Repo::class.java.name]!!.kind)
        assertEquals(true, byType[Repo::class.java.name]!!.created)
        assertEquals(repo.javaClass.name, byType[Repo::class.java.name]!!.runtimeType)
        assertEquals("factory", byType[Api::class.java.name]!!.kind)
        assertEquals(false, byType[Api::class.java.name]!!.created)
        val base = byType["java.lang.String"]!!
        assertEquals("base", base.qualifier)
        assertEquals(false, base.created)
    }

    @Test
    fun `no koin started reads as no graph`() {
        assertNull(DiReaders.koin(DiReadersTest::class.java.classLoader))
    }

    @DependencyGraph(AppScope::class)
    interface TestGraph

    /** As Metro generates a graph: provider fields (scoped ones double-checked), the graph's own inputs. */
    @Suppress("unused")
    class TestGraphImpl(val appInstance: App) : TestGraph {
        val repoProvider: Provider<Repo> = DoubleCheck.provider(Provider { Repo() })
        val apiProvider: Provider<Api> = Provider { Api() }
        val lazyRepoProvider: Provider<Repo> = DoubleCheck.provider(Provider { Repo() })
    }

    @Test
    fun `a metro graph's providers read without creating anything`() {
        val g = TestGraphImpl(App())
        g.repoProvider.invoke()
        assertTrue(DiReaders.isMetroGraph(g.javaClass))
        val graph = DiReaders.providerGraph("metro", "TestGraph", g)
        val byName = graph.bindings.associateBy { it.name }
        assertEquals(DiBindingExpect("${Repo::class.java.name}", "singleton", true, Repo::class.java.name), byName["repo"]!!.let { DiBindingExpect(it.type, it.kind, it.created, it.runtimeType) })
        assertEquals(DiBindingExpect("${Repo::class.java.name}", "singleton", false, null), byName["lazyRepo"]!!.let { DiBindingExpect(it.type, it.kind, it.created, it.runtimeType) })
        assertEquals("factory", byName["api"]!!.kind)
        assertEquals(DiBindingExpect(App::class.java.name, "instance", true, App::class.java.name), byName["appInstance"]!!.let { DiBindingExpect(it.type, it.kind, it.created, it.runtimeType) })
    }

    private data class DiBindingExpect(val type: String, val kind: String?, val created: Boolean, val runtimeType: String?)
}
