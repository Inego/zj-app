package inego.zungjunggame.render.table

import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.engine.*
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MeldTileWire
import inego.zungjunggame.net.MeldWire
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.render.reference.ReferenceAssetSources
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.online.toTableViewState
import inego.zungjunggame.session.table.MeldGroup
import inego.zungjunggame.session.table.MeldTile
import inego.zungjunggame.session.table.SeatView
import inego.zungjunggame.session.table.TableViewState
import java.io.File
import java.util.*
import kotlin.test.*

class PatternlessNoticeTest {

    @BeforeTest
    fun setUp() {
        val cwd = File(".").absolutePath
        val root = requireNotNull(ReferenceAssetSources.findAssetsRoot()) {
            "assets/ not found from $cwd"
        }
        L10n.bootstrap(Locale.US, FileHandle(File(root, "i18n/ui")))
    }

    /**
     * Build a 14-tile chicken hand for seat 0:
     * Open meld: 1-char pung (terminals, open).
     * Concealed: 4-5-6 dot, 2-3-4 bamboo, 7-8-9 bamboo (chows with terminals & simples across 2 suits).
     * Pair: Green dragon singleton in hand + Green dragon drawn tile.
     * Total: 0 pattern points.
     */
    private fun createChickenHandTableViewState(
        isDiscardTurn: Boolean = true,
        hasDrawn: Boolean = true,
        mySeat: Int = 0,
    ): TableViewState {
        val concealed = listOf(
            Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
            Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
            Tiles.BAMBOO_7, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
            Tiles.GREEN,
        )
        val meld = MeldGroup(
            listOf(
                MeldTile(Tiles.CHAR_1, open = true, rotated = false),
                MeldTile(Tiles.CHAR_1, open = true, rotated = false),
                MeldTile(Tiles.CHAR_1, open = true, rotated = true),
            ),
        )
        return TableViewState(
            mySeat = mySeat,
            eastSeat = 0,
            liveWallSize = 60,
            currentPlayer = if (isDiscardTurn) mySeat else (mySeat + 1) % 4,
            decisionType = if (isDiscardTurn) DecisionType.ActOnHand else DecisionType.ReactToDiscard,
            lastDiscarder = -1,
            lastDiscarded = -1,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = -1,
            winSource = -1,
            isTerminal = false,
            scoreDeltas = null,
            seats = (0 until 4).map { seat ->
                if (seat == mySeat) {
                    SeatView(
                        seat = seat,
                        concealedCount = if (hasDrawn) 11 else 10,
                        concealedTiles = concealed,
                        drawnTile = if (hasDrawn) Tiles.GREEN else -1,
                        hasDrawnTile = hasDrawn,
                        melds = listOf(meld),
                    )
                } else {
                    SeatView(seat = seat, concealedCount = 13, concealedTiles = null)
                }
            },
            myChoices = ChoiceList(),
            isHumanDiscardTurn = isDiscardTurn,
            isHumanClaimTurn = false,
            isHumanActOnHandPanel = false,
        )
    }

    @Test
    fun textResolvesFromL10n() {
        assertEquals("Patternless hands not allowed", PatternlessNotice.text())
    }

    @Test
    fun noticeShowsOnlyWhenChickenDisallowedOnDrawnChickenHand() {
        val chickenView = createChickenHandTableViewState()

        // In a no-chicken game (allowChickenHands = false): notice must appear.
        assertTrue(PatternlessNotice.isApplicable(chickenView, allowChickenHands = false))

        // In a chicken game (allowChickenHands = true): notice must NOT appear.
        assertFalse(PatternlessNotice.isApplicable(chickenView, allowChickenHands = true))
    }

    @Test
    fun noticeDisappearsAfterTileDiscarded() {
        // Player had drawn a tile, but then discarded it -> hasDrawnTile = false, isHumanDiscardTurn = false.
        val discardedView = createChickenHandTableViewState(isDiscardTurn = false, hasDrawn = false)
        assertFalse(PatternlessNotice.isApplicable(discardedView, allowChickenHands = false))
    }

    @Test
    fun noticeDoesNotShowForPatternedHand() {
        // Replace open 1-char pung with open Green dragon pung, and pair with simple tile (2-bamboo).
        // That gives Value Honor (Dragon Pung, 5 pts).
        val concealed = listOf(
            Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
            Tiles.BAMBOO_2,
            Tiles.BAMBOO_7, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
            Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
        )
        val dragonMeld = MeldGroup(
            listOf(
                MeldTile(Tiles.GREEN, open = true, rotated = false),
                MeldTile(Tiles.GREEN, open = true, rotated = false),
                MeldTile(Tiles.GREEN, open = true, rotated = true),
            ),
        )
        val patternedView = TableViewState(
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 60,
            currentPlayer = 0,
            decisionType = DecisionType.ActOnHand,
            lastDiscarder = -1,
            lastDiscarded = -1,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = -1,
            winSource = -1,
            isTerminal = false,
            scoreDeltas = null,
            seats = listOf(
                SeatView(
                    seat = 0,
                    concealedCount = 11,
                    concealedTiles = concealed,
                    drawnTile = Tiles.BAMBOO_2,
                    hasDrawnTile = true,
                    melds = listOf(dragonMeld),
                ),
                SeatView(seat = 1, concealedCount = 13, concealedTiles = null),
                SeatView(seat = 2, concealedCount = 13, concealedTiles = null),
                SeatView(seat = 3, concealedCount = 13, concealedTiles = null),
            ),
            myChoices = ChoiceList(),
            isHumanDiscardTurn = true,
            isHumanClaimTurn = false,
            isHumanActOnHandPanel = false,
        )

        assertFalse(PatternlessNotice.isApplicable(patternedView, allowChickenHands = false))
    }

    @Test
    fun noticeDoesNotShowForIncompleteHand() {
        // 1-shanten hand with drawn tile that doesn't complete it.
        val incompleteConcealed = listOf(
            Tiles.DOT_1, Tiles.DOT_2, Tiles.DOT_3,
            Tiles.BAMBOO_1, Tiles.BAMBOO_2, Tiles.BAMBOO_3,
            Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
            Tiles.EAST,
        )
        val incompleteView = TableViewState(
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 60,
            currentPlayer = 0,
            decisionType = DecisionType.ActOnHand,
            lastDiscarder = -1,
            lastDiscarded = -1,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = -1,
            winSource = -1,
            isTerminal = false,
            scoreDeltas = null,
            seats = listOf(
                SeatView(
                    seat = 0,
                    concealedCount = 11,
                    concealedTiles = incompleteConcealed,
                    drawnTile = Tiles.SOUTH,
                    hasDrawnTile = true,
                    melds = listOf(
                        MeldGroup(
                            listOf(
                                MeldTile(Tiles.WEST, open = true, rotated = false),
                                MeldTile(Tiles.WEST, open = true, rotated = false),
                                MeldTile(Tiles.WEST, open = true, rotated = true),
                            ),
                        ),
                    ),
                ),
                SeatView(seat = 1, concealedCount = 13, concealedTiles = null),
                SeatView(seat = 2, concealedCount = 13, concealedTiles = null),
                SeatView(seat = 3, concealedCount = 13, concealedTiles = null),
            ),
            myChoices = ChoiceList(),
            isHumanDiscardTurn = true,
            isHumanClaimTurn = false,
            isHumanActOnHandPanel = false,
        )

        assertFalse(PatternlessNotice.isApplicable(incompleteView, allowChickenHands = false))
    }

    @Test
    fun onlineProjectionCorrectlyDetectsChickenHand() {
        val wire = SeatSnapshotWire(
            handNumber = 1,
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 50,
            currentPlayer = 0,
            decisionType = "ActOnHand",
            lastDiscarder = -1,
            lastDiscarded = -1,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = (0 until 4).map { s ->
                if (s == 0) {
                    SeatSnapshotViewWire(
                        seat = s,
                        concealedCount = 11,
                        concealedTiles = listOf(
                            Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                            Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                            Tiles.BAMBOO_7, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
                            Tiles.GREEN,
                        ),
                        drawnTile = Tiles.GREEN,
                        hasDrawnTile = true,
                        melds = listOf(
                            MeldWire(
                                listOf(
                                    MeldTileWire(Tiles.CHAR_1, open = true, rotated = false),
                                    MeldTileWire(Tiles.CHAR_1, open = true, rotated = false),
                                    MeldTileWire(Tiles.CHAR_1, open = true, rotated = true),
                                ),
                            ),
                        ),
                        river = emptyList(),
                        seatWindTile = s,
                    )
                } else {
                    SeatSnapshotViewWire(
                        seat = s,
                        concealedCount = 13,
                        concealedTiles = null,
                        drawnTile = -1,
                        hasDrawnTile = false,
                        melds = emptyList(),
                        river = emptyList(),
                        seatWindTile = s,
                    )
                }
            },
        )

        val decision = ActionableDecision(
            decisionId = "1",
            isDiscard = true,
            isClaim = false,
            isActOnHandPanel = false,
            choices = ChoiceList().apply {
                pushBack(Choice(ChoiceKind.Discard, Tiles.GREEN))
            },
        )
        val onlineView = wire.toTableViewState(decision = decision)
        assertTrue(PatternlessNotice.isApplicable(onlineView, allowChickenHands = false))
        assertFalse(PatternlessNotice.isApplicable(onlineView, allowChickenHands = true))
    }

    @Test
    fun scoringIsPatternlessHandFunctionDirectVerification() {
        val ps = PlayerState()
        // 4-5-6 dot
        ps.concealed[Tiles.DOT_4]++
        ps.concealed[Tiles.DOT_5]++
        ps.concealed[Tiles.DOT_6]++
        // 2-3-4 bamboo
        ps.concealed[Tiles.BAMBOO_2]++
        ps.concealed[Tiles.BAMBOO_3]++
        ps.concealed[Tiles.BAMBOO_4]++
        // 7-8-9 bamboo
        ps.concealed[Tiles.BAMBOO_7]++
        ps.concealed[Tiles.BAMBOO_8]++
        ps.concealed[Tiles.BAMBOO_9]++
        // Pair: Green dragon
        ps.concealed[Tiles.GREEN] += 2
        // Open meld: 1-char pung
        ps.pungsKongs[Tiles.CHAR_1] = PlayerState.PK_OPEN_PUNG

        assertTrue(Scoring.isPatternlessHand(ps, playerIndex = 0, eastSeat = 0))

        // Change pair from Green dragon to 5-dot, open pung from 1-char to 5-char, and bamboo 7-8-9 to 5-6-7:
        // Now every tile is simple (2-8) -> All Simples (5 pts) -> no longer patternless!
        val psSimples = PlayerState()
        psSimples.concealed[Tiles.DOT_4]++
        psSimples.concealed[Tiles.DOT_5] += 3 // 4-5-6 dot + 5-5 dot pair
        psSimples.concealed[Tiles.DOT_6]++
        psSimples.concealed[Tiles.BAMBOO_2]++
        psSimples.concealed[Tiles.BAMBOO_3]++
        psSimples.concealed[Tiles.BAMBOO_4]++
        psSimples.concealed[Tiles.BAMBOO_5]++
        psSimples.concealed[Tiles.BAMBOO_6]++
        psSimples.concealed[Tiles.BAMBOO_7]++
        psSimples.pungsKongs[Tiles.CHAR_5] = PlayerState.PK_OPEN_PUNG

        assertFalse(Scoring.isPatternlessHand(psSimples, playerIndex = 0, eastSeat = 0))
    }
}
