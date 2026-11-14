package inego.zungjunggame.features.notices

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.net.NoticeArg
import inego.zungjunggame.net.NoticeEntry

/**
 * Turns a stored notice (template key + ordered arguments) into the reference
 * markup the rich-text engine draws, in the player's locale.
 *
 * A scalar argument is inserted as text; a batch argument repeats its row
 * template once per row, in order, and the run stands in for that one placeholder
 * so a parent template that spells out a pipe-table header renders the rows
 * beneath it. The client owns this expansion: `L10n.format` fills `{0}`, `{1}`, …
 * and `RichMarkupParser` then draws the emphasis, colors, and inline table.
 */
object NoticeMarkup {
    /** The whole list as one document: every notice, newest first, or a status line. */
    fun list(
        notices: List<NoticeEntry>,
        loading: Boolean = false,
        error: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (notices.isEmpty()) {
            return when {
                error != null -> error
                loading -> L10n.get("history.loading")
                else -> L10n.get("notice.empty")
            }
        }
        return notices.joinToString("\n\n") { notice(it, nowMs) }
    }

    /**
     * One notice's body markup, led by the line saying when it was sent.
     *
     * The stamp is the notice's own row line: a `<br/>` keeps it on a line of its
     * own inside the same paragraph, so the body still wraps to the panel width
     * (one line per source row would fight the wrap on a narrow screen). A notice
     * whose [NoticeEntry.createdAt] is missing or unparseable renders body only —
     * a wrong time is worse than no time.
     */
    fun notice(entry: NoticeEntry, nowMs: Long = System.currentTimeMillis()): String {
        val rendered = entry.args.map { renderArg(it) }
        val body = L10n.format(entry.templateKey, *rendered.toTypedArray())
        val sent = MatchHistoryFormat.sentLabel(entry.createdAt, nowMs) ?: return body
        return "[muted]$sent[/]<br/> $body"
    }

    /**
     * One argument: text as itself, a count filled into its bundle phrase, a
     * batch as its rows rendered with the row template and joined by newlines.
     */
    private fun renderArg(arg: NoticeArg): String = when (arg) {
        is NoticeArg.Text -> arg.value
        is NoticeArg.Count -> renderCount(arg)
        is NoticeArg.Batch -> arg.rows.joinToString("\n") { row ->
            L10n.format(arg.rowTemplateKey, *row.map { renderArg(it) }.toTypedArray())
        }
    }

    /** `count.key` with its `{0}` filled by the number; the template carries the noun. */
    private fun renderCount(count: NoticeArg.Count): String =
        L10n.format(count.key, count.count.toString())
}
