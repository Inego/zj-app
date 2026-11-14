package inego.zungjunggame.net

import inego.zungjunggame.i18n.L10n
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShadowRankedFormatTest {
    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        val cwd = File(".").canonicalFile
        val assets = listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
        ).first { File(it, "i18n").isDirectory }
        L10n.bootstrap(Locale.US, com.badlogic.gdx.files.FileHandle(File(assets, "i18n/ui")))
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
    }

    @Test
    fun ratingValueDropsAWholeNumberAndKeepsCents() {
        assertEquals("1214", ShadowRankedFormat.ratingValue(1214.0))
        assertEquals("1219.33", ShadowRankedFormat.ratingValue(1219.333))
        assertEquals("1200", ShadowRankedFormat.ratingValue(1200.004))
        assertEquals("\u22121.50", ShadowRankedFormat.ratingValue(-1.5))
        assertEquals("0", ShadowRankedFormat.ratingValue(0.0))
    }

    @Test
    fun signedRatingKeepsTwoDecimalsAndASignedZero() {
        assertEquals("+5.33", ShadowRankedFormat.signedRating(5.333333333333333))
        assertEquals("\u22122.67", ShadowRankedFormat.signedRating(-2.6666666666666665))
        assertEquals("+8.00", ShadowRankedFormat.signedRating(8.0))
        assertEquals("0.00", ShadowRankedFormat.signedRating(0.0))
        assertEquals("+0.01", ShadowRankedFormat.signedRating(0.005))
    }

    @Test
    fun signedScoreMarksTheSign() {
        assertEquals("+48", ShadowRankedFormat.signedScore(48))
        assertEquals("\u221250", ShadowRankedFormat.signedScore(-50))
        assertEquals("0", ShadowRankedFormat.signedScore(0))
    }

    @Test
    fun playersLabelSwitchesOnTheSingular() {
        assertEquals(L10n.get("shadowRanked.players_one"), ShadowRankedFormat.playersLabel(1))
        assertEquals(
            L10n.format("shadowRanked.players_many", "3"),
            ShadowRankedFormat.playersLabel(3),
        )
    }

    @Test
    fun scoreLineNamesTheBotAndTheListingPlayer() {
        val line = ShadowRankedFormat.scoreLine(
            listOf(
                ChallengeScoreRow(name = null, score = 32, isBot = true, isYou = false),
                ChallengeScoreRow(name = "Maria", score = 80, isBot = false, isYou = false),
                ChallengeScoreRow(name = "Yanis", score = 48, isBot = false, isYou = true),
            ),
        )
        assertEquals(
            "${L10n.get("shadowRanked.score_bot")} +32 · Maria +80 · ${L10n.get("shadowRanked.score_you")} +48",
            line,
        )
    }

    @Test
    fun ratingLineCarriesItsValue() {
        assertEquals(
            L10n.format("shadowRanked.rating_line", "+5.33"),
            ShadowRankedFormat.ratingLabel(5.333333333333333),
        )
    }
}
