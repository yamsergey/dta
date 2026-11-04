package io.yamsergey.dta.sidekick.runtime

import io.yamsergey.dta.protocol.data.DiBinding
import io.yamsergey.dta.protocol.data.DiGraph
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.IdentityHashMap

/**
 * Feature 035: DI graphs read from the live objects, whatever the framework, never creating
 * anything. Provider-field graphs (Metro graphs, Dagger and Hilt components) and Koin. JVM only
 * (reflection), so it is tested on real Koin and Metro runtimes without a device.
 */
object DiReaders {
    private val PROVIDER_TYPES = listOf("dev.zacsweers.metro.Provider", "javax.inject.Provider", "jakarta.inject.Provider", "dagger.internal.Provider", "kotlin.Lazy", "dagger.Lazy")

    /** A Metro graph implementation: it implements an interface annotated `@DependencyGraph`. */
    fun isMetroGraph(c: Class<*>): Boolean = c.interfaces.any { i -> i.annotations.any { it.annotationClass.java.name == "dev.zacsweers.metro.DependencyGraph" } }

    /** A plain Dagger component (Hilt's generated components are read as Hilt). */
    fun isDaggerComponent(c: Class<*>): Boolean =
        "_HiltComponents_" !in c.name && (listOfNotNull(c.superclass) + c.interfaces).any { t -> t.annotations.any { it.annotationClass.java.name == "dagger.Component" } }

    /**
     * Every provider and input field of [graph] as a binding: double-checked providers are
     * singletons (created when they hold an instance), instance factories and plain fields are
     * inputs given to the graph, other providers make a new instance each time. [skip] leaves out
     * fields that are the graph's links to other graphs (Hilt's parent components).
     */
    fun providerGraph(framework: String, name: String, graph: Any, skip: (Field) -> Boolean = { false }, note: String? = null): DiGraph {
        val inputs = IdentityHashMap<Any, Boolean>()
        val bindings = fields(graph.javaClass).filter { !skip(it) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.mapNotNull { f ->
            val v = runCatching { f.isAccessible = true; f.get(graph) }.getOrNull() ?: return@mapNotNull null
            if (v === graph) return@mapNotNull null
            val isProvider = PROVIDER_TYPES.any { isA(f.type, it) } || PROVIDER_TYPES.any { isA(v.javaClass, it) } && f.type.name.contains("Provider")
            val bindingName = f.name.removeSuffix("Provider").removeSuffix("Instance").ifEmpty { f.name }
            if (!isProvider) {
                // A graph input is kept both as itself and wrapped in an instance factory: listed once.
                if (inputs.put(v, true) != null) return@mapNotNull null
                return@mapNotNull DiBinding(f.genericType.typeName, f.name, v.javaClass.name, true, "instance")
            }
            val type = (f.genericType as? ParameterizedType)?.actualTypeArguments?.firstOrNull()?.typeName?.removePrefix("? extends ") ?: f.type.name
            val (kind, instance) = read(v)
            if (kind == "instance" && instance != null && inputs.put(instance, true) != null) return@mapNotNull null
            DiBinding(type, bindingName, instance?.javaClass?.name, instance != null || kind == "instance" && instance != null, kind)
        }
        return DiGraph(framework, name, bindings, note)
    }

    /** What a provider object is and the instance it holds, if any, without calling it. */
    private fun read(p: Any): Pair<String, Any?> {
        val c = p.javaClass.name
        return when {
            c == "dev.zacsweers.metro.internal.DoubleCheck" || c == "dev.zacsweers.metro.internal.BaseDoubleCheck" || isA(p.javaClass, "dev.zacsweers.metro.internal.BaseDoubleCheck") -> {
                val initialized = runCatching { p.javaClass.getMethod("isInitialized").invoke(p) as Boolean }.getOrDefault(false)
                "singleton" to if (initialized) runCatching { p.javaClass.getMethod("getValue").invoke(p) }.getOrNull() else null
            }
            c == "dev.zacsweers.metro.internal.InstanceFactory" -> "instance" to fieldValue(p, "value")
            c == "dagger.internal.DoubleCheck" || c == "dagger.internal.SingleCheck" -> {
                val held = fieldValue(p, "instance")
                val sentinel = runCatching { p.javaClass.getDeclaredField("UNINITIALIZED").apply { isAccessible = true }.get(null) }.getOrNull()
                "singleton" to held?.takeIf { it !== sentinel && it.javaClass.name != "java.lang.Object" }
            }
            c == "dagger.internal.InstanceFactory" -> "instance" to fieldValue(p, "instance")
            else -> "factory" to null
        }
    }

    /** The started Koin (GlobalContext), its definitions; null when Koin is absent or not started. */
    fun koin(loader: ClassLoader?): DiGraph? {
        val ctx = runCatching { Class.forName("org.koin.core.context.GlobalContext", false, loader) }.getOrNull() ?: return null
        val koin = runCatching { ctx.getDeclaredField("_koin").apply { isAccessible = true }.get(null) }.getOrNull() ?: return null
        val registry = fieldValue(koin, "instanceRegistry") ?: return DiGraph("koin", "Koin", emptyList(), "Koin is started but its definitions cannot be read (no instance registry in this Koin version)")
        val factories = (fieldValue(registry, "_instances") as? Map<*, *>)?.values?.filterNotNull()
            ?: return DiGraph("koin", "Koin", emptyList(), "Koin is started but its definitions cannot be read (this Koin version keeps them elsewhere)")
        val seen = IdentityHashMap<Any, Boolean>()
        val bindings = factories.filter { seen.put(it, true) == null }.mapNotNull { f ->
            val def = fieldValue(f, "beanDefinition") ?: return@mapNotNull null
            val type = javaName(fieldValue(def, "primaryType")) ?: "?"
            val qualifier = fieldValue(def, "qualifier")?.let { q -> fieldValue(q, "value")?.toString() ?: q.toString() }
            val kind = (fieldValue(def, "kind") as? Enum<*>)?.name?.lowercase()
            val scope = fieldValue(def, "scopeQualifier")?.let { q -> fieldValue(q, "value")?.toString() }
            val (k, instance) = when (kind) {
                "singleton" -> "singleton" to fieldValue(f, "value")
                "scoped" -> "scoped" to (fieldValue(f, "values") as? Map<*, *>)?.values?.firstOrNull()
                else -> "factory" to null
            }
            DiBinding(type, if (k == "scoped" && scope != null) "scope $scope" else type.substringAfterLast('.'), instance?.javaClass?.name, instance != null, k, qualifier)
        }
        return DiGraph("koin", "Koin", bindings.sortedWith(compareBy({ it.kind }, { it.type })))
    }

    private fun javaName(kclass: Any?): String? {
        kclass ?: return null
        (fieldValue(kclass, "jClass") as? Class<*>)?.let { return it.name }
        return runCatching { (Class.forName("kotlin.jvm.JvmClassMappingKt").getMethod("getJavaClass", Class.forName("kotlin.reflect.KClass")).invoke(null, kclass) as Class<*>).name }.getOrNull()
    }

    private fun fieldValue(o: Any, name: String): Any? = fields(o.javaClass).firstOrNull { it.name == name }?.let { f -> runCatching { f.isAccessible = true; f.get(o) }.getOrNull() }

    private fun fields(c: Class<*>): List<Field> = generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }.flatMap { it.declaredFields.asSequence() }.toList()

    private fun isA(type: Class<*>, name: String): Boolean = Values.isA(type, name)
}
