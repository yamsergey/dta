package io.yamsergey.dta.present

/** Sizes and durations as every surface shows them. */
object Units {
    fun bytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> "%.1f GB".format(n / 1024.0 / 1024.0 / 1024.0)
        n >= 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
        n >= 1024 -> "%.1f KB".format(n / 1024.0)
        else -> "$n B"
    }

    fun millis(ms: Long): String = if (ms >= 1000) "%.1f s".format(ms / 1000.0) else "$ms ms"

    /** "1 store", "8 stores": the count with its noun in the right number; [many] for an irregular plural. */
    fun count(n: Number, one: String, many: String = one + "s"): String = "${"%,d".format(n.toLong())} ${if (n.toLong() == 1L) one else many}"
}
