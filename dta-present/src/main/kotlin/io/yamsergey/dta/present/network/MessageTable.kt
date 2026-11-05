package io.yamsergey.dta.present.network

/**
 * Feature 041: a connection's messages as a table — what the format reads (kind, id, topic) in
 * columns of their own, the message's text left as it is (or the payload a rule names).
 */
data class MessageCells(
    val id: String,
    val time: String,
    /** ↑ sent by the app, ↓ received. */
    val direction: String,
    val kind: String,
    val messageId: String,
    val topic: String,
    /** The payload the app's rule names, else the message's text, on one line. */
    val text: String,
    /** "↩ answers 12:00:01.200 (+40 ms)", timing. */
    val note: String,
)

data class MessageTable(val columns: List<String>, val rows: List<MessageCells>) {
    /** The cell of [column] (one of [columns]) in [row]. */
    fun cell(row: MessageCells, column: String): String = when (column) {
        TIME -> row.time
        DIRECTION -> row.direction
        KIND -> row.kind
        ID -> row.messageId
        TOPIC -> row.topic
        NOTE -> row.note
        else -> row.text
    }

    companion object {
        const val TIME = "Time"
        const val DIRECTION = ""
        const val KIND = "Kind"
        const val ID = "Id"
        const val TOPIC = "Topic"
        const val TEXT = "Message"
        const val NOTE = "Reply"

        fun of(messages: List<EntryRow>): MessageTable {
            val replies = replies(messages)
            val rows = messages.map { m ->
                val e = m.envelope
                val payload = e?.takeIf { it.rule != null }?.payload
                MessageCells(
                    id = m.id,
                    time = m.time,
                    direction = if (m.kind == EntryKind.WS_OUT) "↑" else "↓",
                    kind = e?.kind.orEmpty(),
                    messageId = e?.id.orEmpty(),
                    topic = e?.topic.orEmpty(),
                    text = oneLine(payload ?: m.preview.orEmpty()),
                    note = listOfNotNull(
                        replies[m.id]?.let { (asked, ms) -> "↩ answers ${asked.time}" + (ms?.let { " (+$it ms)" } ?: "") },
                        m.timing.ifEmpty { null },
                    ).joinToString("  "),
                )
            }
            // Columns no message fills are left out.
            val columns = listOfNotNull(
                TIME, DIRECTION,
                KIND.takeIf { rows.any { it.kind.isNotEmpty() } },
                ID.takeIf { rows.any { it.messageId.isNotEmpty() } },
                TOPIC.takeIf { rows.any { it.topic.isNotEmpty() } },
                TEXT,
                NOTE.takeIf { rows.any { it.note.isNotEmpty() } },
            )
            return MessageTable(columns, rows)
        }
    }
}
