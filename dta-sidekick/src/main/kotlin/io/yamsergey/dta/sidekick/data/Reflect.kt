package io.yamsergey.dta.sidekick.data

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Reflection on the app's own classes (constitution I: the sidekick never links against them). */
internal object Reflect {
    fun method(target: Any, name: String, vararg types: Class<*>): Method? = method(target.javaClass, name, *types)

    fun method(type: Class<*>, name: String, vararg types: Class<*>): Method? {
        var c: Class<*>? = type
        while (c != null) {
            runCatching { return c.getDeclaredMethod(name, *types).apply { isAccessible = true } }
            c = c.superclass
        }
        return runCatching { type.getMethod(name, *types) }.getOrNull()
    }

    /** A method by name and parameter count, public ones first (interfaces included). */
    fun methodNamed(type: Class<*>, name: String, params: Int): Method? =
        type.methods.firstOrNull { it.name == name && it.parameterTypes.size == params }
            ?: generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == name && it.parameterTypes.size == params }?.apply { isAccessible = true }

    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: methodNamed(target.javaClass, name, args.size)
            ?: throw NoSuchMethodException("${target.javaClass.name}.$name")
        return invoke(m, target, *args)
    }

    fun invoke(m: Method, target: Any?, vararg args: Any?): Any? = try {
        m.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }

    fun fields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { runCatching { it.isAccessible = true } }
            .toList()

    fun field(target: Any, name: String): Any? = fields(target.javaClass).firstOrNull { it.name == name }?.get(target)

    /**
     * Calls a Kotlin suspend function of the app (its last parameter a Continuation) and waits
     * for the result on this thread (never the main thread: the RPC thread).
     */
    fun callSuspend(target: Any, method: Method, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var outcome: Result<Any?>? = null
        val continuation = object : Continuation<Any?> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                outcome = result
                latch.countDown()
            }
        }
        val direct = invoke(method, target, *args, continuation)
        if (direct !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) return direct
        check(latch.await(30, TimeUnit.SECONDS)) { "${method.name} did not finish within 30 s" }
        return outcome!!.getOrThrow()
    }
}
