package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.ChoiceKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TutorialLiveFocusTest {

    @Test
    fun calloutParksOnTargetId() {
        val park = TutorialLiveFocus.resolve(
            targetId = "river.last",
            calloutKey = "tutorial.claims.last_discard.callout",
            waitKind = null,
        )
        assertEquals(park, TutorialLiveFocus.Park.Target("river.last"))
    }

    @Test
    fun discardPreambleKeepsTileTarget() {
        val park = TutorialLiveFocus.resolve(
            targetId = TutorialTargets.tile(5),
            calloutKey = "tutorial.moves.counterclockwise.click_callout",
            waitKind = ChoiceKind.Discard,
            waitPayload = 5,
        )
        assertEquals(park, TutorialLiveFocus.Park.Target(TutorialTargets.tile(5)))
    }

    @Test
    fun claimPreambleParksOnTheButtonNotTheTile() {
        val park = TutorialLiveFocus.resolve(
            targetId = TutorialTargets.tile(7),
            calloutKey = "tutorial.claims.click_callout",
            waitKind = ChoiceKind.Pung,
            waitPayload = 7,
        )
        assertEquals(park, TutorialLiveFocus.Park.ClaimButton(ChoiceKind.Pung, 7))
    }

    @Test
    fun noCalloutFallsBackToHandPrompt() {
        val park = TutorialLiveFocus.resolve(
            targetId = "river.last",
            calloutKey = null,
            waitKind = null,
        )
        assertEquals(TutorialLiveFocus.Park.HandPrompt, park)
    }
}
