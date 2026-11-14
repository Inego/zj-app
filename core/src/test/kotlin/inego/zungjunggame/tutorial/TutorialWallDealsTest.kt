package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.render.wallbuild.WallBuildBeat
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildToGameState
import inego.zungjunggame.tutorial.samples.MoveOrderScenarios
import inego.zungjunggame.tutorial.samples.OrientationScenarios
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parked wall-deal ownership: last committed deal survives hosted-lesson
 * change and binds overlay (never the dummy layout). ClearRoll invalidates
 * the park so the next dice beat tumbles a new result.
 */
class TutorialWallDealsTest {

    @Test
    fun parkGetAndForgetAreByLessonId() {
        val deals = TutorialWallDeals()
        val wall = WallBuildDeal.invent(Random(1))
        val other = WallBuildDeal.invent(Random(2))
        assertNotEquals(wall, other)
        deals.put("orient-wall-dead", wall)
        deals.put("other", other)
        assertEquals(wall, deals.get("orient-wall-dead"))
        assertEquals(other, deals.get("other"))
        deals.forget("other")
        assertNull(deals.get("other"))
        assertEquals(wall, deals.get("orient-wall-dead"))
        deals.forgetAll()
        assertNull(deals.get("orient-wall-dead"))
        assertEquals(0, deals.size)
    }

    @Test
    fun snapshotRoundTripPreservesDealUnderOtherLesson() {
        val deals = TutorialWallDeals()
        val wall = WallBuildDeal.invent(Random(7))
        deals.put("orient-wall-dead", wall)
        val snap = deals.snapshot()
        val restored = TutorialWallDeals()
        restored.restore(snap)
        assertEquals(wall, restored.get("orient-wall-dead"))
        // Mutating the snapshot map's arrays must not leak.
        snap.getValue("orient-wall-dead").slotToTile[0] =
            (snap.getValue("orient-wall-dead").slotToTile[0] + 1) %
                snap.getValue("orient-wall-dead").slotToTile.size
        assertEquals(wall, restored.get("orient-wall-dead"))
    }

    @Test
    fun dummyLayoutIsNotTheParkedDeal() {
        val wall = OrientationScenarios.wallDead()
        val liveCount = wall.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.live_count"
        }
        assertTrue(liveCount >= 0)
        val runner = TutorialRunner.startAtStep(
            scenario = wall,
            targetStepIndex = liveCount,
            choiceRng = Random(8),
            blockOnCoach = true,
        )
        val dummyEast = runner.state!!.players[0].concealed.copyOf()
        val deal = WallBuildDeal.invent(Random(42))
        val fromDeal = WallBuildToGameState.openingState(deal)
        assertFalse(
            dummyEast.contentEquals(fromDeal.players[0].concealed),
            "dummy Random(0) layout must not match an invented wall deal",
        )
        runner.rematerializeState(fromDeal)
        assertContentEquals(fromDeal.players[0].concealed, runner.state!!.players[0].concealed)
        assertEquals(NUM_TILE_TYPES, dummyEast.size)
    }

    @Test
    fun parkedDealSurvivesHostedLessonChangeAndBindsLiveCount() {
        val wall = OrientationScenarios.wallDead()
        val discarding = MoveOrderScenarios.discarding()
        assertTrue(TutorialSlideNav.usesWallDeal(wall))
        assertFalse(TutorialSlideNav.usesWallDeal(discarding))

        val deals = TutorialWallDeals()
        val deal = WallBuildDeal.invent(Random(42))
        deals.put(wall.id, deal)

        // Persist as if recovered on Discarding (hosted lesson ≠ wall).
        val restored = TutorialWallDeals()
        restored.restore(deals.snapshot())
        val parked = assertNotNull(restored.get(wall.id))
        assertEquals(deal, parked)

        val liveCount = wall.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.live_count"
        }
        val onLive = TutorialRunner.startAtStep(
            scenario = wall,
            targetStepIndex = liveCount,
            choiceRng = Random(3),
            blockOnCoach = true,
        )
        val dummyEast = onLive.state!!.players[0].concealed.copyOf()
        val opening = WallBuildToGameState.openingState(parked)
        assertFalse(dummyEast.contentEquals(opening.players[0].concealed))
        onLive.rematerializeState(opening)
        assertContentEquals(opening.players[0].concealed, onLive.state!!.players[0].concealed)

        // Same deal would be installed on east_discard / Next app_table.
        val appTable = wall.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.app_table"
        }
        val onApp = TutorialRunner.startAtStep(
            scenario = wall,
            targetStepIndex = appTable,
            choiceRng = Random(3),
            blockOnCoach = true,
        )
        onApp.rematerializeState(WallBuildToGameState.openingState(parked))
        assertContentEquals(
            onLive.state!!.players[0].concealed,
            onApp.state!!.players[0].concealed,
        )
    }

    @Test
    fun clearRollForgetsParkSoNextDiceCanInvent() {
        val deals = TutorialWallDeals()
        val first = WallBuildDeal.invent(Random(1))
        deals.put("orient-wall-dead", first)
        deals.syncFromBeat(
            lessonId = "orient-wall-dead",
            dice = WallBuildBeat.DicePolicy.ClearRoll,
            deal = first,
        )
        assertNull(deals.get("orient-wall-dead"), "pre-roll beat must drop the parked deal")

        val rolled = WallBuildDeal.invent(Random(2))
        assertNotEquals(first, rolled)
        deals.syncFromBeat(
            lessonId = "orient-wall-dead",
            dice = WallBuildBeat.DicePolicy.RollOrRestore,
            deal = rolled,
        )
        assertEquals(rolled, deals.get("orient-wall-dead"))

        deals.syncFromBeat(
            lessonId = "orient-wall-dead",
            dice = WallBuildBeat.DicePolicy.ShowSettled,
            deal = rolled,
        )
        assertEquals(rolled, deals.get("orient-wall-dead"))

        // Overlay / cleared view: null deal must not wipe the park.
        deals.syncFromBeat(
            lessonId = "orient-wall-dead",
            dice = null,
            deal = null,
        )
        assertEquals(rolled, deals.get("orient-wall-dead"))

        val liveCount = OrientationScenarios.wallDead().steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.live_count"
        }
        val onLive = TutorialRunner.startAtStep(
            scenario = OrientationScenarios.wallDead(),
            targetStepIndex = liveCount,
            choiceRng = Random(3),
            blockOnCoach = true,
        )
        onLive.rematerializeState(WallBuildToGameState.openingState(rolled))
        assertContentEquals(
            WallBuildToGameState.openingState(rolled).players[0].concealed,
            onLive.state!!.players[0].concealed,
        )
    }
}
