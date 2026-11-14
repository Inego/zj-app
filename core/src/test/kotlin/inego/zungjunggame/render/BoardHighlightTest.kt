package inego.zungjunggame.render

import inego.zungjunggame.render.seats.PlayerAreaLayout
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.table.HighlightWhere
import inego.zungjunggame.render.tiles.TileFocusGlow
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardHighlightTest {

    private val hand = PlayerAreaLayout.Region.Hand
    private val meld = PlayerAreaLayout.Region.Meld
    private val river = PlayerAreaLayout.Region.River
    private val drawn = PlayerAreaLayout.Region.Drawn

    @Test
    fun promptTwoTypesOnYouHitsHandAndMeldNotRiverOrOpponent() {
        val spec = BoardHighlight.prompt(1, 3)
        assertNotNull(spec.match(human = true, hand, 1, pendingDiscard = false, open = true))
        assertNotNull(spec.match(human = true, hand, 3, pendingDiscard = false, open = true))
        assertNotNull(spec.match(human = true, meld, 1, pendingDiscard = false, open = true))
        assertNotNull(spec.match(human = true, drawn, 3, pendingDiscard = false, open = true))
        assertNull(spec.match(human = true, hand, 2, pendingDiscard = false, open = true))
        assertNull(spec.match(human = true, river, 1, pendingDiscard = true, open = true))
        assertNull(spec.match(human = false, hand, 1, pendingDiscard = false, open = false))
        assertTrue(spec.hasPrompt())
        assertEquals(setOf(1, 3), spec.handAnchorTypes())
    }

    @Test
    fun openWhereHitsFaceUpAnywhereIncludingOpponentRiver() {
        val spec = BoardHighlight.prompt(7, where = HighlightWhere.Open)
        assertNotNull(spec.match(human = false, river, 7, pendingDiscard = false, open = true))
        assertNotNull(spec.match(human = false, meld, 7, pendingDiscard = false, open = true))
        assertNotNull(spec.match(human = true, hand, 7, pendingDiscard = false, open = true))
        assertNull(spec.match(human = false, hand, 7, pendingDiscard = false, open = false))
        assertNull(spec.match(human = false, river, 8, pendingDiscard = false, open = true))
    }

    @Test
    fun denyTypesOnRiversHitsRiverCopiesNotHand() {
        val spec = BoardHighlight.deny(7, where = HighlightWhere.Rivers)
        assertEquals(
            TileFocusGlow.Deny,
            spec.match(human = false, river, 7, pendingDiscard = false, open = true)?.glow,
        )
        assertEquals(
            TileFocusGlow.Deny,
            spec.match(human = false, river, 7, pendingDiscard = true, open = true)?.glow,
        )
        assertNull(spec.match(human = true, hand, 7, pendingDiscard = false, open = true))
        assertNull(spec.match(human = false, river, 8, pendingDiscard = true, open = true))
        assertFalse(spec.hasPrompt())
    }

    @Test
    fun denyPendingMatchesOnlyThePendingDiscard() {
        val spec = BoardHighlight.denyPending()
        val hit = spec.match(human = false, river, 9, pendingDiscard = true, open = true)
        assertEquals(TileFocusGlow.Deny, hit?.glow)
        assertNull(spec.match(human = false, river, 9, pendingDiscard = false, open = true))
        assertNull(spec.match(human = true, hand, 9, pendingDiscard = false, open = true))
        assertFalse(spec.hasPrompt())
    }

    @Test
    fun denyPendingAndPromptYouIsTwoGroupsFirstMatchWinsOnPending() {
        val spec = BoardHighlight.denyPendingAndPromptYou(4)
        val pending = spec.match(human = false, river, 4, pendingDiscard = true, open = true)
        assertEquals(TileFocusGlow.Deny, pending?.glow)
        assertEquals(0, pending?.groupIndex)
        val you = spec.match(human = true, hand, 4, pendingDiscard = false, open = true)
        assertEquals(TileFocusGlow.Prompt, you?.glow)
        assertEquals(1, you?.groupIndex)
    }

    @Test
    fun denyPendingAndPromptYouAcceptsSeveralCompanionTypes() {
        val spec = BoardHighlight.denyPendingAndPromptYou(1, 3)
        assertEquals(TileFocusGlow.Deny, spec.match(human = false, river, 2, pendingDiscard = true, open = true)?.glow)
        assertEquals(TileFocusGlow.Prompt, spec.match(human = true, hand, 1, pendingDiscard = false, open = true)?.glow)
        assertEquals(TileFocusGlow.Prompt, spec.match(human = true, hand, 3, pendingDiscard = false, open = true)?.glow)
        assertNull(spec.match(human = true, hand, 2, pendingDiscard = false, open = true))
    }

    @Test
    fun emptySpecMatchesNothing() {
        assertNull(
            BoardHighlight.None.match(
                human = true,
                region = hand,
                tileType = 0,
                pendingDiscard = false,
                open = true,
            ),
        )
        assertFalse(BoardHighlight.None.hasPrompt())
        assertTrue(BoardHighlight.None.handAnchorTypes().isEmpty())
    }

    @Test
    fun tileTargetDerivesPromptOnYou() {
        val focus = TutorialFocus(TutorialTargets.tile(5))
        assertEquals(BoardHighlight.prompt(5), focus.resolvedHighlight(null))
    }

    @Test
    fun explicitHighlightWinsOverTileTarget() {
        val explicit = BoardHighlight.prompt(1, 2)
        val focus = TutorialFocus(TutorialTargets.tile(5), highlight = explicit)
        assertEquals(explicit, focus.resolvedHighlight(null))
    }

    @Test
    fun explicitNoneDoesNotDeriveFromTileTarget() {
        val focus = TutorialFocus(TutorialTargets.tile(5), highlight = BoardHighlight.None)
        assertEquals(BoardHighlight.None, focus.resolvedHighlight(null))
    }

    @Test
    fun riverLastWithoutStateIsDenyPendingOnly() {
        val focus = TutorialFocus("river.last")
        assertEquals(BoardHighlight.denyPending(), focus.resolvedHighlight(null))
    }
}
