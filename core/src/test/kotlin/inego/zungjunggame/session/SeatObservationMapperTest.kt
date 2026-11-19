package inego.zungjunggame.session

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.net.MeldTileWire
import inego.zungjunggame.net.MeldWire
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.session.online.toSeatObservation
import inego.zungjunggame.session.table.TableViewState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeatObservationMapperTest {

    @Test
    fun meldReconstructionConcealedKongAndOpenKong() {
        val snapshot = SeatSnapshotWire(
            handNumber = 1,
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 50,
            currentPlayer = 0,
            decisionType = "ActOnHand",
            lastDiscarder = null,
            lastDiscarded = null,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = listOf(
                SeatSnapshotViewWire(
                    seat = 0,
                    concealedCount = 7,
                    concealedTiles = listOf(0, 0, 1, 2, 3, 4, 5),
                    drawnTile = 5,
                    hasDrawnTile = true,
                    melds = listOf(
                        // Concealed kong: outer closed, inner open
                        MeldWire(
                            tiles = listOf(
                                MeldTileWire(Tiles.EAST, open = false, rotated = false),
                                MeldTileWire(Tiles.EAST, open = true, rotated = false),
                                MeldTileWire(Tiles.EAST, open = true, rotated = false),
                                MeldTileWire(Tiles.EAST, open = false, rotated = false),
                            ),
                        ),
                        // Open kong: all 4 open
                        MeldWire(
                            tiles = listOf(
                                MeldTileWire(Tiles.SOUTH, open = true, rotated = false),
                                MeldTileWire(Tiles.SOUTH, open = true, rotated = false),
                                MeldTileWire(Tiles.SOUTH, open = true, rotated = false),
                                MeldTileWire(Tiles.SOUTH, open = true, rotated = true),
                            ),
                        ),
                    ),
                    river = emptyList(),
                    seatWindTile = Tiles.EAST,
                ),
                SeatSnapshotViewWire(
                    seat = 1,
                    concealedCount = 13,
                    concealedTiles = null,
                    drawnTile = -1,
                    hasDrawnTile = false,
                    melds = emptyList(),
                    river = emptyList(),
                    seatWindTile = Tiles.SOUTH,
                ),
                SeatSnapshotViewWire(
                    seat = 2,
                    concealedCount = 13,
                    concealedTiles = null,
                    drawnTile = -1,
                    hasDrawnTile = false,
                    melds = emptyList(),
                    river = emptyList(),
                    seatWindTile = Tiles.WEST,
                ),
                SeatSnapshotViewWire(
                    seat = 3,
                    concealedCount = 13,
                    concealedTiles = null,
                    drawnTile = -1,
                    hasDrawnTile = false,
                    melds = emptyList(),
                    river = emptyList(),
                    seatWindTile = Tiles.NORTH,
                ),
            ),
        )

        val obs = snapshot.toSeatObservation()
        assertNotNull(obs)
        assertEquals(PlayerState.PK_CONCEALED_KONG, obs.player.pungsKongs[Tiles.EAST])
        assertEquals(PlayerState.PK_OPEN_KONG, obs.player.pungsKongs[Tiles.SOUTH])
    }

    @Test
    fun meldReconstructionPungAndChow() {
        val snapshot = SeatSnapshotWire(
            handNumber = 1,
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 50,
            currentPlayer = 0,
            decisionType = "ActOnHand",
            lastDiscarder = null,
            lastDiscarded = null,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = listOf(
                SeatSnapshotViewWire(
                    seat = 0,
                    concealedCount = 7,
                    concealedTiles = listOf(0, 0, 1, 2, 3, 4, 5),
                    drawnTile = -1,
                    hasDrawnTile = false,
                    melds = listOf(
                        // Open pung: 3 identical, all open
                        MeldWire(
                            tiles = listOf(
                                MeldTileWire(Tiles.RED, open = true, rotated = false),
                                MeldTileWire(Tiles.RED, open = true, rotated = false),
                                MeldTileWire(Tiles.RED, open = true, rotated = true),
                            ),
                        ),
                        // Chow: 1m, 2m, 3m
                        MeldWire(
                            tiles = listOf(
                                MeldTileWire(0, open = true, rotated = true),
                                MeldTileWire(1, open = true, rotated = false),
                                MeldTileWire(2, open = true, rotated = false),
                            ),
                        ),
                    ),
                    river = emptyList(),
                    seatWindTile = Tiles.EAST,
                ),
                SeatSnapshotViewWire(seat = 1, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.SOUTH),
                SeatSnapshotViewWire(seat = 2, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.WEST),
                SeatSnapshotViewWire(seat = 3, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.NORTH),
            ),
        )

        val obs = snapshot.toSeatObservation()
        assertNotNull(obs)
        assertEquals(PlayerState.PK_OPEN_PUNG, obs.player.pungsKongs[Tiles.RED])
        assertEquals(1, obs.player.openChows[0]) // 1m-2m-3m is chow index 0
    }

    @Test
    fun opponentConcealedTilesNeverLeak() {
        val snapshot = SeatSnapshotWire(
            handNumber = 1,
            mySeat = 1,
            eastSeat = 0,
            liveWallSize = 50,
            currentPlayer = 1,
            decisionType = "ActOnHand",
            lastDiscarder = null,
            lastDiscarded = null,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = listOf(
                // Opponent has concealedTiles = null
                SeatSnapshotViewWire(seat = 0, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.EAST),
                // Own seat
                SeatSnapshotViewWire(seat = 1, concealedCount = 13, concealedTiles = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.SOUTH),
                SeatSnapshotViewWire(seat = 2, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.WEST),
                SeatSnapshotViewWire(seat = 3, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.NORTH),
            ),
        )

        val obs = snapshot.toSeatObservation()
        assertNotNull(obs)
        assertEquals(1, obs.seat)
        assertEquals(Tiles.SOUTH, obs.seatWind)
        // Hand contains only own tiles
        for (t in 0..12) {
            assertEquals(1, obs.player.concealed[t])
        }
        for (t in 13 until NUM_TILE_TYPES) {
            assertEquals(0, obs.player.concealed[t])
        }
    }

    @Test
    fun remainingTilesMatchesGameStateUnknownTileCounts() {
        val state = GameState.generateRandom(Random(12345))
        val human = 0
        val view = TableViewState.fromGameState(state, human)
        val obs = view.toSeatObservation()
        assertNotNull(obs)

        val expected = state.unknownTileCountsByPlayer[human]
        assertContentEquals(expected, obs.remainingTiles)
    }

    @Test
    fun claimedChowTileIsCountedOnceAfterTheClaim() {
        // Seat 0 is on its ActOnHand turn after claiming 2m for a 1m-2m-3m chow.
        // The claimed tile is now in the meld, not the discarder's river, but the
        // snapshot still carries it as lastDiscarded. It must not be subtracted a
        // second time by the last-discard fallback: seat 0 holds one more 2m, so
        // two copies are visible and two are still unseen.
        val chowTile = 1 // 2m
        val snapshot = SeatSnapshotWire(
            handNumber = 1,
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 50,
            currentPlayer = 0,
            decisionType = "ActOnHand",
            lastDiscarder = 1,
            lastDiscarded = chowTile,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = listOf(
                SeatSnapshotViewWire(
                    seat = 0,
                    concealedCount = 10,
                    concealedTiles = listOf(chowTile, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                    drawnTile = -1,
                    hasDrawnTile = false,
                    melds = listOf(
                        MeldWire(
                            tiles = listOf(
                                MeldTileWire(0, open = true, rotated = false),
                                MeldTileWire(chowTile, open = true, rotated = true),
                                MeldTileWire(2, open = true, rotated = false),
                            ),
                        ),
                    ),
                    river = emptyList(),
                    seatWindTile = Tiles.EAST,
                ),
                SeatSnapshotViewWire(seat = 1, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.SOUTH),
                SeatSnapshotViewWire(seat = 2, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.WEST),
                SeatSnapshotViewWire(seat = 3, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.NORTH),
            ),
        )

        val obs = snapshot.toSeatObservation()
        assertNotNull(obs)
        assertEquals(2, obs.remainingTiles[chowTile])
    }

    @Test
    fun returnsNullWhenOwnConcealedTilesNull() {
        val snapshot = SeatSnapshotWire(
            handNumber = 1,
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 50,
            currentPlayer = 0,
            decisionType = "ActOnHand",
            lastDiscarder = null,
            lastDiscarded = null,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = listOf(
                SeatSnapshotViewWire(seat = 0, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.EAST),
                SeatSnapshotViewWire(seat = 1, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.SOUTH),
                SeatSnapshotViewWire(seat = 2, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.WEST),
                SeatSnapshotViewWire(seat = 3, concealedCount = 13, concealedTiles = null, drawnTile = -1, hasDrawnTile = false, melds = emptyList(), river = emptyList(), seatWindTile = Tiles.NORTH),
            ),
        )
        assertNull(snapshot.toSeatObservation())
    }
}
