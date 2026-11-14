package inego.zungjunggame.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The play page's fragment is authored by the mail link, so the parser accepts
 * the shapes a link or a hand-typed address can produce (with or without `#`,
 * with a trailing slash, any case) and ignores everything else.
 */
class DeepLinkTest {

    @Test
    fun recognizesTheMyChallengesFragment() {
        for (fragment in listOf(
            "#my_challenges",
            "my_challenges",
            "#my_challenges/",
            "#MY_CHALLENGES",
            " #my_challenges ",
        )) {
            assertEquals(DeepLink.Target.MY_CHALLENGES, DeepLink.targetOf(fragment), "fragment: $fragment")
        }
    }

    @Test
    fun ignoresAnythingElse() {
        for (fragment in listOf(
            null,
            "",
            "#",
            "  ",
            "#other",
            "#my_challenges?source=mail",
            "#my-challenges",
        )) {
            assertNull(DeepLink.targetOf(fragment), "fragment: $fragment")
        }
    }
}
