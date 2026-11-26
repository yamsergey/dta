package io.yamsergey.dta.protocol.network

/** What the pre-072 mask looked like; tests that check *where* masking happens compare through [plain]. */
const val MASKED = "[redacted]"

private val MASK_TEXT = Regex("""\[redacted len=\d+ #[0-9a-f]{12}]""")

/** Every `[redacted len=N #fp]` of this text as `[redacted]`: for tests that care about places, not fingerprints. */
fun String.plain(): String = MASK_TEXT.replace(this, MASKED)

fun List<MaskedLocation>.places(): List<String> = map { it.place }
