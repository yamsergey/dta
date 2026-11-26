package io.yamsergey.dta.sidekick.runtime

import io.yamsergey.dta.protocol.data.HeapClassRow
import io.yamsergey.dta.protocol.data.LoaderKind
import io.yamsergey.dta.protocol.data.classOrigin

/** Feature 074 (B1): the pure part of the heap histogram — names, loaders, origin. */
internal object HeapRows {
    /** One class as the native walk summed it. */
    data class Raw(val name: String, val loader: LoaderKind, val loaderLabel: String?, val count: Long, val bytes: Long)

    /** ART answers `java.lang.BootClassLoader` (not null) for platform classes; null also means boot. */
    fun loaderKind(loaderClassName: String?, isAppLoader: Boolean): LoaderKind = when {
        loaderClassName == null || loaderClassName == "java.lang.BootClassLoader" -> LoaderKind.BOOT
        isAppLoader -> LoaderKind.APP
        else -> LoaderKind.OTHER
    }

    /** Binary name; arrays as `byte[]`. */
    fun className(cls: Class<*>): String {
        var c = cls
        var dims = 0
        while (c.isArray) { c = c.componentType!!; dims++ }
        return c.name + "[]".repeat(dims)
    }

    /** Rows for classes with instances, biggest first; the loader is named only where class names collide. */
    fun assemble(raw: List<Raw>, namespaces: List<String>): List<HeapClassRow> {
        val live = raw.filter { it.count > 0 }
        val collide = live.groupBy { it.name }.filterValues { it.size > 1 }.keys
        return live
            .map { r ->
                HeapClassRow(
                    className = r.name,
                    loader = if (r.name in collide) r.loaderLabel else null,
                    origin = classOrigin(r.name, r.loader, namespaces),
                    count = r.count,
                    bytes = r.bytes,
                )
            }
            .sortedWith(compareByDescending<HeapClassRow> { it.bytes }.thenBy { it.className })
    }

    /** The app's namespaces: its application id and the packages of what it declares (activities, application class). */
    fun namespaces(applicationId: String, classNames: List<String?>): List<String> {
        val out = linkedSetOf(applicationId)
        for (n in classNames) {
            val i = n?.lastIndexOf('.') ?: -1
            if (n != null && i > 0) out += n.substring(0, i).let { pkg -> if (pkg == applicationId || pkg.startsWith("$applicationId.")) applicationId else pkg }
        }
        return out.toList()
    }
}
