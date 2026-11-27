package io.yamsergey.dta.studio.ui

/** Stub: keeps the old behaviour (nothing shortened). */
object ClassNameFit {
    /** What to draw: [prefix] (the package, muted) then [name] (the class, text colour). */
    data class Fit(val prefix: String, val name: String) { val text get() = prefix + name }

    fun fit(fullName: String, measure: (String) -> Int, width: Int): Fit = Fit("", fullName)

    /** [text] cut at its end with an ellipsis so it fits [width]; whole when it fits. */
    fun end(text: String, measure: (String) -> Int, width: Int): String = text
}
