package io.yamsergey.dta.sidekick.util

/** A [TaskScheduler] whose tasks run only when time is advanced, in due order. */
class ManualScheduler : TaskScheduler {
    var now = 0L
    private val tasks = mutableListOf<Triple<Long, Int, () -> Unit>>()
    private var seq = 0

    val pending: Int get() = tasks.size

    override fun nowMs() = now

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val entry = Triple(now + delayMs, seq++, task)
        tasks += entry
        return { tasks.remove(entry) }
    }

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { it.first <= until }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
            tasks.remove(next)
            now = next.first
            next.third()
        }
        now = until
    }
}
