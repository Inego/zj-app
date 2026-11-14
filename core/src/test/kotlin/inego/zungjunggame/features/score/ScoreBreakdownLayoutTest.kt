package inego.zungjunggame.features.score

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.features.score.ScoreBreakdownLayout.ResultSlot
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The finished-game place, and the row that owns it.
 *
 * [ScoreBreakdownLayout.resolveResult] is the one decision the card height and
 * the painter share. With a rating table the place is that table's caption
 * (the place alone — not joined with "Rating changes") and the hand table does
 * not also spend a row on it. Without a rating table the place stays a body line.
 */
class ScoreBreakdownLayoutTest {

    @BeforeTest
    fun resetBundle() {
        L10n.bootstrap(Locale.US)
    }

    @Test
    fun ratingTableCaptionIsThePlaceAlone() {
        val plan = resolve(place = "  ${L10n.get("history.place_4")}  ", hasRatingLines = true)
        assertEquals(ResultSlot.RatingCaption, plan.slot)
        assertEquals(L10n.get("history.place_4"), plan.line)
    }

    @Test
    fun bodySlotKeepsTheBarePlaceWhenTheCardHasNoRatings() {
        val plan = resolve(place = L10n.get("history.place_4"), hasRatingLines = false)
        assertEquals(ResultSlot.Body, plan.slot)
        assertEquals(L10n.get("history.place_4"), plan.line)
    }

    @Test
    fun noFinishedGameSpendsNoRow() {
        val plan = resolve(place = "   ", hasRatingLines = true)
        assertEquals(ResultSlot.None, plan.slot)
        assertEquals("", plan.line)
    }

    private fun resolve(
        place: String,
        hasRatingLines: Boolean,
    ) = ScoreBreakdownLayout.resolveResult(
        gameResult = place,
        hasRatingLines = hasRatingLines,
    )
}
