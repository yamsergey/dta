package io.yamsergey.dta.sidekick.hooks

/**
 * Hooked classes and methods as a reader would write them (feature 019): `RealWebSocket`,
 * `RealWebSocket.failWebSocket(Exception, Response, boolean)`. Pure.
 */
object HookNames {

    fun simple(className: String): String = className.substringAfterLast('.').replace('$', '.')

    fun method(className: String, method: String, descriptor: String): String =
        "${simple(className)}.$method(${parameters(descriptor).joinToString(", ")})"

    /** The parameter types of a JVM method descriptor, by simple name: `(ILjava/lang/String;)V` → [int, String]. */
    fun parameters(descriptor: String): List<String> {
        val inner = descriptor.substringAfter('(').substringBefore(')')
        val out = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            var dims = 0
            while (inner[i] == '[') { dims++; i++ }
            val type = when (val c = inner[i]) {
                'L' -> {
                    val end = inner.indexOf(';', i)
                    simple(inner.substring(i + 1, end).replace('/', '.')).also { i = end }
                }
                else -> PRIMITIVES[c] ?: c.toString()
            }
            out += type + "[]".repeat(dims)
            i++
        }
        return out
    }

    private val PRIMITIVES = mapOf(
        'Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short",
        'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double",
    )
}
