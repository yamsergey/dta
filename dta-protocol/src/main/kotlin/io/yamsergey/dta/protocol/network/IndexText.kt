package io.yamsergey.dta.protocol.network

/**
 * Feature 070 (research R3): what a word index holds for an entry — every text the search words,
 * body filters and body patterns read, normalised as [Fuzzy.Text] compares (letters and digits,
 * no separators) and folded once more (`uppercase` then `lowercase`, as `ignoreCase` and
 * `UNICODE_CASE` compare). The index only chooses which entries are read: anything that could be
 * found in an entry is in its text, or [Result.cut] says the text holds only part of it.
 *
 * The ports are the ones the answer reads with ([NetworkHistoryView]): bodies as this side holds
 * them, binary bodies as the app's schemas decode them.
 */
object IndexText {
    /** Normalised characters kept per body part; beyond it the entry is read as without an index. */
    const val PART_CHARS = 1 shl 20
    /** Bytes of an undecoded binary part kept (they are searched as bytes). */
    const val BINARY_BYTES = 64 * 1024

    data class Result(val text: String, val cut: Boolean)

    fun of(
        entry: NetworkEntry,
        bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
        decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
    ): Result {
        val out = StringBuilder()
        var cut = false
        fun text(s: String?, max: Int = Int.MAX_VALUE) { if (s != null && append(out, s, max)) cut = true }
        fun url(u: String?) {
            u ?: return
            text(u)
            Redactor.url(u, "").first.takeIf { it != u }?.let { text(Redactor.plain(it)) }
        }
        fun headers(list: List<Header>?) {
            list ?: return
            list.forEach { text(it.name); text(it.value) }
            if (Redactor.headers(list, "").first != list) text(Redactor.MASK_PREFIX)
        }
        fun body(info: BodyInfo?, part: BodyPart) {
            info ?: return
            if (body(entry, info, part, bodyBytes, decoded, out)) cut = true
        }
        when (entry) {
            is HttpEntry -> {
                url(entry.url)
                headers(entry.requestHeaders)
                headers(entry.responseHeaders)
                body(entry.requestBody, BodyPart.REQUEST)
                body(entry.responseBody, BodyPart.RESPONSE)
                entry.appRequest?.let { headers(it.headers); body(it.body, BodyPart.APP_REQUEST) }
                entry.appResponse?.let { headers(it.headers); body(it.body, BodyPart.APP_RESPONSE) }
            }
            is WsConnectionEntry -> { url(entry.url); headers(entry.handshakeHeaders) }
            is WsMessageEntry -> {
                body(entry.payload, BodyPart.MESSAGE)
                body(entry.appPayload, BodyPart.APP_MESSAGE)
                entry.envelope?.let { e -> text(e.kind); text(e.topic); text(e.id) }
            }
            is LinkEntry -> url(entry.uri)
        }
        return Result(out.toString(), cut)
    }

    /** One body part; true when what is kept is only part of what could be found in it. */
    private fun body(
        entry: NetworkEntry, info: BodyInfo, part: BodyPart,
        bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?, decoded: ((NetworkEntry, BodyPart) -> String?)?, out: StringBuilder,
    ): Boolean {
        var cut = false
        val bytes = bodyBytes(entry, part)
        // Not here (yet): what is searched now is the preview, but the body may come at any moment and be read
        // by the next answer before its words are written — so the entry is read as without an index.
        if (bytes == null && info.storedBytes > 0) cut = true
        if (info.binary) {
            decoded?.invoke(entry, part)?.let { json ->
                if (append(out, json, PART_CHARS)) cut = true
                // A decoded field's value is compared unescaped (`\n`, `é`): both forms are kept.
                if ('\\' in json && append(out, unescape(json), PART_CHARS)) cut = true
                // Under redaction the decoded JSON is read redacted (006 FR-016): that rendering too.
                if (Redactor.mayChangeWords(json)) {
                    Redactor.body(json, "application/json", "").text?.takeIf { it != json }?.let { if (append(out, Redactor.plain(it), PART_CHARS)) cut = true }
                }
            }
            // `body:` text also looks in the bytes; the search reads them as Latin-1 when no schema decodes them.
            if (bytes != null && bytes.decoded) {
                val n = minOf(bytes.bytes.size, BINARY_BYTES)
                if (n < bytes.bytes.size) cut = true
                append(out, String(bytes.bytes, 0, n, Charsets.ISO_8859_1), Int.MAX_VALUE)
            }
            return cut
        }
        val text = bytes?.let { String(it.bytes, Charsets.UTF_8) } ?: info.preview ?: return cut
        if (append(out, text, PART_CHARS)) cut = true
        if (Redactor.mayChangeWords(text)) {
            Redactor.body(text, info.contentType, "").text?.takeIf { it != text }?.let { if (append(out, Redactor.plain(it), PART_CHARS)) cut = true }
        }
        return cut
    }

    /** Appends [s] normalised, at most [max] characters of it; true when it was cut. */
    fun append(out: StringBuilder, s: String, max: Int): Boolean {
        var n = 0
        for (c in s) {
            if (!c.isLetterOrDigit()) continue
            if (n == max) return true
            out.append(fold(c))
            n++
        }
        return false
    }

    fun fold(c: Char): Char = c.uppercaseChar().lowercaseChar()

    /** [s] normalised as the index holds it. */
    fun normalise(s: String): String = StringBuilder().also { append(it, s, Int.MAX_VALUE) }.toString()

    /** JSON string escapes resolved (not JSON any more; only for the index). */
    private fun unescape(json: String): String {
        val sb = StringBuilder(json.length)
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c != '\\' || i + 1 >= json.length) { sb.append(c); i++; continue }
            when (val e = json[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val hex = json.substring(i + 2, minOf(i + 6, json.length))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 6; continue }
                    sb.append('\\').append(e)
                }
                else -> sb.append(e)
            }
            i += 2
        }
        return sb.toString()
    }
}
