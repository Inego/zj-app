package inego.zungjunggame.render

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.claim.ClaimActionPanel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keep is a first-class [ChoiceKind.Keep] on ActOnHand when a concealed four-of-a-kind
 * is pending. Pass remains ReactToDiscard-only.
 */
class ClaimPanelKeepTest {
    @Test
    fun actOnHandConcealedKongOffersKeepNotPass() {
        val state = GameState()
        state.decisionType = DecisionType.ActOnHand
        state.currentPlayer = 0
        state.liveWallSize = 40
        val ps = state.players[0]
        for (t in ps.concealed.indices) ps.concealed[t] = 0
        ps.concealed[Tiles.EAST] = 4
        ps.concealed[Tiles.CHAR_1] = 10
        ps.activeConcealedTileCount = -1
        ps.ensureActiveConcealedTiles()

        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        assertTrue(ChoiceKind.ConcealedKong in kinds)
        assertTrue(ChoiceKind.Keep in kinds)
        assertFalse(ChoiceKind.Discard in kinds, "discards gated until Keep or declare")
        assertFalse(ChoiceKind.Pass in kinds, "Pass is only for discard reactions")
    }

    @Test
    fun reactToDiscardAlwaysOffersPass() {
        val state = GameState()
        state.decisionType = DecisionType.ReactToDiscard
        state.currentPlayer = 0
        state.lastDiscarder = 1
        state.lastDiscarded = Tiles.EAST

        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        assertTrue(ChoiceKind.Pass in kinds)
    }

    @Test
    fun buildSpecsMapsEveryChoiceKindToLabelTilesAndFocus() {
        val cases = listOf(
            SpecCase(Choice.pass(), Tiles.EAST, "claim.pass", intArrayOf(), -1),
            SpecCase(Choice.keep(Tiles.EAST), Tiles.EAST, "claim.keep", intArrayOf(), -1),
            SpecCase(Choice.hu(40), Tiles.EAST, "claim.hu", intArrayOf(), -1),
            SpecCase(
                Choice.pung(Tiles.EAST),
                Tiles.EAST,
                "claim.pung",
                intArrayOf(Tiles.EAST, Tiles.EAST, Tiles.EAST),
                2,
            ),
            SpecCase(
                Choice.kong(Tiles.EAST),
                Tiles.EAST,
                "claim.kong",
                intArrayOf(Tiles.EAST, Tiles.EAST, Tiles.EAST, Tiles.EAST),
                3,
            ),
            SpecCase(
                Choice.chow(Tiles.CHAR_1),
                Tiles.CHAR_2,
                "claim.chow",
                intArrayOf(Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3),
                1,
            ),
            SpecCase(
                Choice.chow(Tiles.CHAR_1),
                Tiles.EAST,
                "claim.chow",
                intArrayOf(Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3),
                -1,
            ),
            SpecCase(
                Choice.concealedKong(Tiles.EAST),
                Tiles.EAST,
                "claim.declare_concealed_kong",
                intArrayOf(Tiles.EAST, Tiles.EAST, Tiles.EAST, Tiles.EAST),
                -1,
            ),
            SpecCase(
                Choice.promoteKong(Tiles.EAST),
                Tiles.EAST,
                "claim.promote_kong",
                intArrayOf(Tiles.EAST, Tiles.EAST, Tiles.EAST, Tiles.EAST),
                3,
            ),
        )
        for (case in cases) {
            val choices = ChoiceList()
            choices.pushBack(case.choice)

            val specs = ClaimActionPanel.buildSpecs(case.claimed, choices)
            assertEquals(1, specs.size, "one panel spec for ${case.choice.kind}")
            val spec = specs.single()
            assertEquals(case.choice, spec.choice, "choice for ${case.choice.kind}")
            assertEquals(L10n.get(case.labelKey), spec.label, "label for ${case.choice.kind}")
            assertContentEquals(case.tiles, spec.tiles, "tiles for ${case.choice.kind}")
            assertEquals(case.focus, spec.focusTileIndex, "focus for ${case.choice.kind}")
        }

        val discard = ChoiceList()
        discard.pushBack(Choice.discard(Tiles.EAST))
        assertTrue(
            ClaimActionPanel.buildSpecs(Tiles.EAST, discard).isEmpty(),
            "Discard is a hand-click action with no panel spec",
        )
    }

    @Test
    fun huRanksBeforeMeldsAndPass() {
        val choices = ChoiceList()
        choices.pushBack(Choice.pass())
        choices.pushBack(Choice.pung(Tiles.EAST))
        choices.pushBack(Choice.hu(80))

        val specs = ClaimActionPanel.buildSpecs(Tiles.EAST, choices)
        assertEquals(3, specs.size)
        assertEquals(ChoiceKind.Hu, specs[0].choice.kind)
        assertEquals(ChoiceKind.Pung, specs[1].choice.kind)
        assertEquals(ChoiceKind.Pass, specs[2].choice.kind)
    }

    private class SpecCase(
        val choice: Choice,
        val claimed: Int,
        val labelKey: String,
        val tiles: IntArray,
        val focus: Int,
    )
}
