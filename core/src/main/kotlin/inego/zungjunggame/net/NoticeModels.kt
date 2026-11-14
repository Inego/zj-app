package inego.zungjunggame.net

/** What a notice is, which tells the client how to present it. */
enum class NoticeKind {
    /** One comparison result delivered on its own. */
    LaterPlay,
    /** A day's held results gathered into one notice. */
    Digest,
    /** A broadcast with no player on it. */
    Admin,
}

/**
 * One argument of a notice template.
 *
 * The arguments are ordered: [Text] fills `{0}`, `{1}`, …; a [Batch] stands in
 * for one placeholder whose value is a run of rows, and the engine repeats its
 * row template once per row.
 */
sealed interface NoticeArg {
    /** A value inserted as text. */
    data class Text(val value: String) : NoticeArg

    /**
     * A raw count substituted into its bundle phrase at render time.
     *
     * [key] is the bundle key; the renderer fills its `{0}` with [count].
     */
    data class Count(
        val count: Long,
        val key: String,
    ) : NoticeArg

    /**
     * A run of rows, each rendered with [rowTemplateKey] and drawn as an inline
     * table. A cell is text or a count, so a per-row phrase ("3 players") inflects
     * in the reader's locale too.
     */
    data class Batch(
        val rowTemplateKey: String,
        val rows: List<List<NoticeArg>>,
    ) : NoticeArg
}

/**
 * One notice as the client reads it from `GET /notices`.
 *
 * The text is rendered from [templateKey] and [args] in the reader's locale;
 * [seen] is whether this player has already seen it on any client, which is what
 * drops a waiting mail. [emailed] is whether the server already delivered it by
 * email: such a notice is listed but never popped up, because the popup is the
 * in-app delivery.
 */
data class NoticeEntry(
    val id: Long,
    val kind: NoticeKind,
    val templateKey: String,
    val args: List<NoticeArg>,
    val createdAt: String,
    val seen: Boolean,
    val emailed: Boolean,
)

/** One player's notices, newest first, from `GET /notices`. */
data class NoticeList(val notices: List<NoticeEntry>)
