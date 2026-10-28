package io.yamsergey.dta.sidekick.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** ISO-8601 UTC timestamps without java.time, which needs API 26 or desugaring in the host app (minSdk 24). */
object IsoTime {
    fun format(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
}
