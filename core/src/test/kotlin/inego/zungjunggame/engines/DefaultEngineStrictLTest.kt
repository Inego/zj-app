package inego.zungjunggame.engines

import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals

/** Strict L / pattern-floor helpers used by [DefaultEngine]. */
class DefaultEngineStrictLTest {
    @Test
    fun emptyClosedStrictLZeroFloorConcealedHandFive() {
        val ps = PlayerState()
        assertEquals(0, strictLockedPatternPoints(ps))
        assertEquals(5, patternFloorPoints(ps))
    }

    @Test
    fun openRedPungValueHonorOnly() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.RED] = PlayerState.PK_OPEN_PUNG
        assertEquals(10, strictLockedPatternPoints(ps))
        assertEquals(10, patternFloorPoints(ps))
    }

    @Test
    fun twoIdenticalOpenChowsSeriesTen() {
        val ps = PlayerState()
        ps.openChows[0] = 2 // chars 123
        assertEquals(10, strictLockedPatternPoints(ps))
    }

    @Test
    fun nineTileStraightOpenChows() {
        val ps = PlayerState()
        ps.openChows[0] = 1
        ps.openChows[3] = 1
        ps.openChows[6] = 1
        assertEquals(40, strictLockedPatternPoints(ps))
    }

    @Test
    fun threeDragonPungsValueHonorPlusBigThree() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.RED] = PlayerState.PK_OPEN_PUNG
        ps.pungsKongs[Tiles.GREEN] = PlayerState.PK_OPEN_PUNG
        ps.pungsKongs[Tiles.WHITE] = PlayerState.PK_OPEN_PUNG
        assertEquals(30 + 130, strictLockedPatternPoints(ps))
    }

    @Test
    fun oneOpenKongFive() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.CHAR_5] = PlayerState.PK_OPEN_KONG
        assertEquals(5, strictLockedPatternPoints(ps))
    }

    @Test
    fun twoConcealedKongsSeriesFloor() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.DOT_2] = PlayerState.PK_CONCEALED_KONG
        ps.pungsKongs[Tiles.DOT_5] = PlayerState.PK_CONCEALED_KONG
        // 4.2.1 = 5 and 4.3.2 = 20 → 25
        assertEquals(5 + 20, strictLockedPatternPoints(ps))
    }

    /** A concealed kong is not exposed, so Concealed Hand (1.2) credit is still earned. */
    @Test
    fun concealedKongKeepsConcealedHandCredit() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.DOT_2] = PlayerState.PK_CONCEALED_KONG
        assertEquals(5, strictLockedPatternPoints(ps)) // 4.3.1 One Kong
        assertEquals(5 + 5, patternFloorPoints(ps)) // + Concealed Hand credit
    }

    @Test
    fun openKongDropsConcealedHandCredit() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.DOT_2] = PlayerState.PK_OPEN_KONG
        assertEquals(5, strictLockedPatternPoints(ps)) // 4.3.1 One Kong
        assertEquals(5, patternFloorPoints(ps)) // no Concealed Hand credit
    }

    @Test
    fun threeSimilarOpenTriplets() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.CHAR_5] = PlayerState.PK_OPEN_PUNG
        ps.pungsKongs[Tiles.DOT_5] = PlayerState.PK_OPEN_PUNG
        ps.pungsKongs[Tiles.BAMBOO_5] = PlayerState.PK_OPEN_PUNG
        assertEquals(120, strictLockedPatternPoints(ps))
    }

    @Test
    fun threeConsecutiveSuitedOpenPungs() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.CHAR_3] = PlayerState.PK_OPEN_PUNG
        ps.pungsKongs[Tiles.CHAR_4] = PlayerState.PK_OPEN_PUNG
        ps.pungsKongs[Tiles.CHAR_5] = PlayerState.PK_OPEN_PUNG
        assertEquals(100, strictLockedPatternPoints(ps))
    }

    @Test
    fun seatWindValueHonorDependsOnSeat() {
        val ps = PlayerState()
        ps.pungsKongs[Tiles.EAST] = PlayerState.PK_OPEN_PUNG
        assertEquals(10, strictLockedPatternPoints(ps, Tiles.EAST))
        // Same pung is guest wind for South seat: not value honor.
        assertEquals(0, strictLockedPatternPoints(ps, Tiles.SOUTH))
    }
}
