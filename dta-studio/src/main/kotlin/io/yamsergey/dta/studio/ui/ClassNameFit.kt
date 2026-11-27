package io.yamsergey.dta.studio.ui

/**
 * The truncation rule for class names (mockup "Truncation rules", rule 1): the identifying part is never cut.
 * The simple class name stays whole; the package is shortened in the middle by whole segments, keeping the first
 * and the last (`io.…channel.HeapLeakActivity`), and becomes `…` alone when that is still too wide. A nested or
 * synthetic class that cannot fit even then keeps its outer name and the tail of its last part
 * (`HeapLeakActivity$$…Lambda0`). The caller draws [Fit.prefix] muted and [Fit.name] in the text colour, with the
 * full name in the tooltip. Pure: [measure] is the width of a string in the font drawn (px, or characters in tests).
 */
object ClassNameFit {
    /** What to draw: [prefix] (the package, muted) then [name] (the class, text colour). */
    data class Fit(val prefix: String, val name: String) { val text get() = prefix + name }

    fun fit(fullName: String, measure: (String) -> Int, width: Int): Fit {
        val simple = fullName.substringAfterLast('.')
        val pkg = fullName.substringBeforeLast('.', "")
        if (pkg.isEmpty()) return Fit("", shrinkName(simple, measure, width))
        if (measure(fullName) <= width) return Fit("$pkg.", simple)
        val segments = pkg.split('.')
        for (keep in segments.size - 2 downTo 1) {
            val prefix = segments.first() + ".…" + segments.takeLast(keep).joinToString(".") + "."
            if (measure(prefix + simple) <= width) return Fit(prefix, simple)
        }
        return Fit("…", shrinkName("$simple", measure, width - measure("…")))
    }

    /** A nested or synthetic name too wide for [width]: the outer name, its separator, then the tail of the last part. */
    private fun shrinkName(simple: String, measure: (String) -> Int, width: Int): String {
        if (measure(simple) <= width) return simple
        val first = simple.indexOf('$')
        val last = simple.lastIndexOf('$')
        if (first <= 0 || last == simple.length - 1) return simple
        val outer = simple.substring(0, first)
        val sep = simple.substring(first, simple.length).takeWhile { it == '$' }
        val tail = simple.substring(last + 1)
        val head = "$outer$sep…"
        for (n in tail.length downTo 1) {
            val candidate = head + tail.takeLast(n)
            if (measure(candidate) <= width) return candidate
        }
        return head
    }

    /**
     * A resource id (`package:id/name`): the id after the last `/` is the identifying part and stays whole; the
     * package before it gives way from its start (`…channel:id/name`). Only when the id alone is too wide does it end in an ellipsis.
     */
    fun resource(full: String, measure: (String) -> Int, width: Int): String {
        if (measure(full) <= width) return full
        val cut = full.lastIndexOf('/') + 1
        if (cut <= 0) return end(full, measure, width)
        val head = full.substring(0, cut)
        val tail = full.substring(cut)
        for (n in head.length - 1 downTo 0) {
            val candidate = "…" + head.takeLast(n) + tail
            if (measure(candidate) <= width) return candidate
        }
        return if (measure("…/$tail") <= width) "…/$tail" else end(tail, measure, width)
    }

    /** [text] cut at its end with an ellipsis so it fits [width]; whole when it fits. */
    fun end(text: String, measure: (String) -> Int, width: Int): String {
        if (measure(text) <= width) return text
        for (n in text.length - 1 downTo 1) {
            val candidate = text.take(n).trimEnd() + "…"
            if (measure(candidate) <= width) return candidate
        }
        return "…"
    }
}
