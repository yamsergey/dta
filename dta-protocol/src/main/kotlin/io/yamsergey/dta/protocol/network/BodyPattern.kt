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
 * Time for body patterns in one answer: each body at most [perBodyMs], all of them at most
 * [totalMs]; a body over its time, or any body once the total is spent, is not matched and
 * is counted in [skipped] (stated in the answer: constitution III).
 */
class PatternBudget(
    private val perBodyMs: Long = 50,
    private val totalMs: Long = 1000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    var skipped = 0
        private set
    private var spentNs = 0L

    /** The first match in [text], null when none; [Skipped] when time ran out. */
    fun find(pattern: Pattern, text: String): Outcome {
        val leftNs = totalMs * 1_000_000 - spentNs
        if (leftNs <= 0) { skipped++; return Outcome.Skipped }
        val started = nanoTime()
        val deadline = started + minOf(perBodyMs * 1_000_000, leftNs)
        return try {
            val m: Matcher = pattern.matcher(Deadline(text, deadline, nanoTime))
            if (m.find()) Outcome.Found(m.start(), m.end()) else Outcome.None
        } catch (_: OutOfTime) {
            skipped++
            Outcome.Skipped
        } finally {
            spentNs += nanoTime() - started
        }
    }

    sealed interface Outcome {
        data class Found(val start: Int, val end: Int) : Outcome
        data object None : Outcome
        data object Skipped : Outcome
    }

    private object OutOfTime : RuntimeException() {
        private fun readResolve(): Any = OutOfTime
        override fun fillInStackTrace(): Throwable = this
    }

    /** Text that throws once its deadline passes; the matcher reads it all the time, also while backtracking. */
    private class Deadline(private val text: CharSequence, private val deadline: Long, private val nanoTime: () -> Long) : CharSequence {
        private var reads = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char {
            if (++reads and 1023 == 0 && nanoTime() > deadline) throw OutOfTime
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Deadline(text.subSequence(startIndex, endIndex), deadline, nanoTime)
        override fun toString(): String = text.toString()
    }
}
