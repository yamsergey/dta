package io.yamsergey.dta.studio.data

/**
 * D-122: an app that has just connected is still starting; what a first read finds is partial (measured 2026-11-27: right after
 * `connected` ViewModels list 0 or 1 item, the screen's CartViewModel comes about a second later). Reads again, showing each
 * answer, until two answers in a row are the same (or [maxReads] were made).
 */
suspend fun <T> settle(read: suspend () -> T, show: (T) -> Unit, everyMs: Long, maxReads: Int, same: (T, T) -> Boolean = { a, b -> a == b }) {
    var previous: T? = null
    repeat(maxReads) { n ->
        val answer = read()
        show(answer)
        if (n > 0 && same(previous as T, answer)) return
        previous = answer
        kotlinx.coroutines.delay(everyMs)
    }
}
