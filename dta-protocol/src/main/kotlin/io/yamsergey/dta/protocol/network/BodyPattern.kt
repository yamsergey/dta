package io.yamsergey.dta.protocol.network

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Feature 053: body patterns (`body:/…/`, `req:/…/`, `resp:/…/`). Case is ignored by default,
 * like `body:` text; inline flags such as `(?-i)`, `(?s)`, `(?m)` change that.
 */
object BodyPattern {
    const val MAX_LENGTH = 1000

    /** The compiled pattern, or the reason it cannot be one (for the line's problem). */
    fun compile(text: String): Result {
        if (text.isEmpty()) return Result.Bad("empty pattern")
        if (text.length > MAX_LENGTH) return Result.Bad("pattern longer than $MAX_LENGTH characters")
        return try {
            Result.Ok(Pattern.compile(text, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE))
        } catch (e: PatternSyntaxException) {
            Result.Bad("pattern: ${e.description}" + if (e.index >= 0) " at ${e.index}" else "")
        }
    }

    sealed interface Result {
        data class Ok(val pattern: Pattern) : Result
        data class Bad(val reason: String) : Result
    }
}

/**
 * Feature 071: whether a search was stopped (a changed query, the stop control, a closed client).
 * No time limit cuts a pattern (FR-005); a stop reaches into a match ([BodyPattern.find]).
 */
fun interface PatternStop {
    fun stopped(): Boolean

    companion object {
        val NONE = PatternStop { false }
    }
}

/** Feature 071: a search ended by its stop; a cancellation, not a failure. */
class SearchStoppedException(message: String = "the search was stopped") : java.util.concurrent.CancellationException(message)

/** The first match of [pattern] in [text] as (start, end), null when none; [check] runs every 1,024 reads of the text and throws to stop. */
fun BodyPattern.find(pattern: Pattern, text: String, check: () -> Unit): Pair<Int, Int>? {
    check()
    val m: Matcher = pattern.matcher(Checked(text, check))
    return if (m.find()) m.start() to m.end() else null
}

/** Text whose reads call [check] now and then: the matcher reads it all the time, also while backtracking. */
private class Checked(private val text: CharSequence, private val check: () -> Unit) : CharSequence {
    private var reads = 0
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        if (++reads and 1023 == 0) check()
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Checked(text.subSequence(startIndex, endIndex), check)
    override fun toString(): String = text.toString()
}
