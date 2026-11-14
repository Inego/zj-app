package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Response/request JSON for `GET /notices` and `POST /notices/seen`. No Gdx app
 * required (unit-testable).
 */
object NoticeJson {
    /** The `POST /notices/seen` body: the ids to mark seen for the account. */
    fun seenRequestBody(ids: List<Long>): String =
        """{"ids":[${ids.joinToString(",")}]}"""

    /** Parse `GET /notices` into the player's notices, newest first. */
    fun parseList(body: String): NoticeList {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty notice list response")
        val array = root.get("notices")
            ?: throw IllegalArgumentException("missing notices")
        val entries = ArrayList<NoticeEntry>()
        if (!array.isNull) {
            var child = array.child
            while (child != null) {
                entries.add(parseEntry(child))
                child = child.next
            }
        }
        return NoticeList(entries)
    }

    fun parseKind(raw: String): NoticeKind = when (raw) {
        "digest" -> NoticeKind.Digest
        "admin" -> NoticeKind.Admin
        // An unknown value must not be presented as a private later play, so it
        // reads as the neutral broadcast shape.
        else -> NoticeKind.LaterPlay
    }

    internal fun parseEntry(value: JsonValue): NoticeEntry {
        val id = value.getLong("id")
        val templateKey = value.getString("template_key")
            ?: throw IllegalArgumentException("missing notice.template_key")
        return NoticeEntry(
            id = id,
            kind = parseKind(value.getString("kind", "later_play")),
            templateKey = templateKey,
            args = parseArgs(value.get("args")),
            createdAt = value.getString("created_at", ""),
            seen = value.getBoolean("seen", false),
            emailed = value.getBoolean("emailed", false),
        )
    }

    private fun parseArgs(array: JsonValue?): List<NoticeArg> {
        if (array == null || array.isNull) return emptyList()
        val args = ArrayList<NoticeArg>()
        var child = array.child
        while (child != null) {
            args.add(parseArg(child))
            child = child.next
        }
        return args
    }

    /** One argument: a string is [NoticeArg.Text], an object is a batch or a count. */
    private fun parseArg(value: JsonValue): NoticeArg {
        if (value.isString) return NoticeArg.Text(value.asString())
        if (value.get("row_template_key") != null) {
            return NoticeArg.Batch(
                rowTemplateKey = value.getString("row_template_key")
                    ?: throw IllegalArgumentException("missing batch.row_template_key"),
                rows = parseRows(value.get("rows")),
            )
        }
        if (value.get("count") != null) {
            return NoticeArg.Count(
                count = value.getLong("count"),
                key = value.getString("key")
                    ?: throw IllegalArgumentException("missing count.key"),
            )
        }
        throw IllegalArgumentException("unknown notice argument")
    }

    private fun parseRows(array: JsonValue?): List<List<NoticeArg>> {
        if (array == null || array.isNull) return emptyList()
        val rows = ArrayList<List<NoticeArg>>()
        var row = array.child
        while (row != null) {
            val cells = ArrayList<NoticeArg>()
            var cell = row.child
            while (cell != null) {
                cells.add(parseArg(cell))
                cell = cell.next
            }
            rows.add(cells)
            row = row.next
        }
        return rows
    }
}
