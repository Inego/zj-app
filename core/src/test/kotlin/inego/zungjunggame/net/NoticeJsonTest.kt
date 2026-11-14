package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoticeJsonTest {
    @Test
    fun parseListReadsTextCountsAndBatchesInOrder() {
        val parsed = NoticeJson.parseList(
            """
            {
              "notices": [
                {
                  "id": 7,
                  "kind": "later_play",
                  "template_key": "notice.later_play",
                  "args": [
                    "Maria",
                    {
                      "count": 14,
                      "key": "notice.digest.comparisons"
                    },
                    {
                      "row_template_key": "notice.later_play_row",
                      "rows": [["7K3H-M9PQ", "+48"]]
                    }
                  ],
                  "created_at": "2026-09-08T12:00:00Z",
                  "seen": false,
                  "emailed": true
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, parsed.notices.size)
        val notice = parsed.notices.single()
        assertEquals(7L, notice.id)
        assertEquals(NoticeKind.LaterPlay, notice.kind)
        assertEquals("notice.later_play", notice.templateKey)
        assertEquals(
            NoticeArg.Text("Maria"),
            notice.args[0],
        )
        assertEquals(
            NoticeArg.Count(14L, "notice.digest.comparisons"),
            notice.args[1],
        )
        assertEquals(
            NoticeArg.Batch(
                "notice.later_play_row",
                listOf(listOf(NoticeArg.Text("7K3H-M9PQ"), NoticeArg.Text("+48"))),
            ),
            notice.args[2],
        )
        assertEquals(false, notice.seen)
        assertEquals(true, notice.emailed)
    }

    @Test
    fun anAbsentEmailedFlagDefaultsToNotEmailed() {
        val parsed = NoticeJson.parseList(
            """{"notices":[{"id":1,"template_key":"notice.later_play"}]}""",
        )
        assertEquals(false, parsed.notices.single().emailed, "an old server reads as not emailed")
    }

    @Test
    fun parseListAllowsAnEmptyInbox() {
        assertTrue(NoticeJson.parseList("""{"notices":[]}""").notices.isEmpty())
    }

    @Test
    fun parseEntryRejectsANoticeWithoutATemplate() {
        assertFailsWith<IllegalArgumentException> {
            NoticeJson.parseList("""{"notices":[{"id":1,"args":[]}]}""")
        }
    }

    @Test
    fun unknownKindFallsBackToTheNeutralShape() {
        assertEquals(NoticeKind.Digest, NoticeJson.parseKind("digest"))
        assertEquals(NoticeKind.Admin, NoticeJson.parseKind("admin"))
        assertEquals(NoticeKind.LaterPlay, NoticeJson.parseKind("mystery"))
    }

    @Test
    fun seenRequestBodyIsAPlainIdArray() {
        assertEquals("""{"ids":[1,2,3]}""", NoticeJson.seenRequestBody(listOf(1, 2, 3)))
    }
}
