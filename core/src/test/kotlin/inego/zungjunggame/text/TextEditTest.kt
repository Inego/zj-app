package inego.zungjunggame.text

import inego.zungjunggame.net.AliasPolicy
import inego.zungjunggame.net.MAX_ALIAS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextEditTest {

    @Test
    fun aValueOverTheStorageCapCanStillBeShortened() {
        val first = "e" + "\u0301".repeat(60)
        val legacy = first + "x" + "\u0301".repeat(40)
        val replacement = "y" + "\u0301".repeat(39)

        val take = TextEdit.replacement(
            text = legacy,
            start = first.length,
            end = legacy.length,
            incoming = replacement,
            maxLength = MAX_ALIAS,
            acceptEdit = AliasPolicy::allowsEdit,
        )

        assertEquals(replacement, take)
    }

    @Test
    fun aCapCutNeverTakesHalfACodePoint() {
        val emoji = "\uD83D\uDE00"

        assertNull(
            TextEdit.replacement("A", 1, 1, emoji, 2, { _, _ -> true }),
        )
        assertEquals(
            "B",
            TextEdit.replacement("A", 1, 1, "B$emoji", 2, { _, _ -> true }),
        )
    }

    @Test
    fun aDeletionThatWouldSplitAClusterIsRefused() {
        val emoji = "\uD83D\uDE00"
        val text = "A$emoji"

        assertNull(TextEdit.replacement(text, 2, 3, "", MAX_ALIAS, { _, _ -> true }))
        assertNull(TextEdit.replacement(text, 1, 2, "", MAX_ALIAS, { _, _ -> true }))
        assertEquals("", TextEdit.replacement(text, 1, 3, "", MAX_ALIAS, { _, _ -> true }))

        val marked = "e\u0301"
        assertNull(TextEdit.replacement(marked, 1, 2, "", MAX_ALIAS, { _, _ -> true }))
        assertEquals("", TextEdit.replacement(marked, 0, 2, "", MAX_ALIAS, { _, _ -> true }))
    }

    @Test
    fun aPrivateUseCodePointNeverEntersTheValue() {
        // U+F60A is what the desktop backend produced for U+1F60A before the
        // character callback kept the full code point.
        assertNull(TextEdit.replacement("A", 1, 1, "\uF60A", MAX_ALIAS, { _, _ -> true }))
        assertEquals(
            "B",
            TextEdit.replacement("A", 1, 1, "B\uF60A", MAX_ALIAS, { _, _ -> true }),
        )
    }

    @Test
    fun aLegacyValueThatHoldsAPrivateUseCodePointCanStillBeShortened() {
        val legacy = "e" + "\u0301".repeat(100) + "\uF60A"
        val replacement = "y" + "\u0301".repeat(39)

        val take = TextEdit.replacement(
            text = legacy,
            start = 0,
            end = legacy.length,
            incoming = replacement,
            maxLength = MAX_ALIAS,
            acceptEdit = AliasPolicy::allowsEdit,
        )

        assertEquals(replacement, take)
    }
}
