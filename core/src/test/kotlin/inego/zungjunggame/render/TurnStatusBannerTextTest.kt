package inego.zungjunggame.render

import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.render.reference.ReferenceAssetSources
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.table.TurnStatusBanner
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.online.toTableViewState
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The center banner never names the seat that is only *deciding on a claim*.
 *
 * The engine asks the three other seats one at a time, so printing
 * [TableViewState.currentPlayer] during [DecisionType.ReactToDiscard] would tell the
 * whole table who is currently deliberating — and, by elimination, who has already
 * answered — while a claim has to be decided blind. Only a publicly watched turn
 * ([DecisionType.ActOnHand]) may be named, and the viewer's own claim keeps its own copy.
 *
 * Both projections feed this copy, so both are asserted: the local one
 * ([TableViewState.fromGameState], driven by the real engine) and the online one
 * (`SeatSnapshotWire.toTableViewState`).
 */
class TurnStatusBannerTextTest {

    private val names = arrayOf("Alice", "Bob", "Carol", "Dave")

    @BeforeTest
    fun setUp() {
        val cwd = File(".").absolutePath
        val root = requireNotNull(ReferenceAssetSources.findAssetsRoot()) {
            "assets/ not found from $cwd"
        }
        L10n.bootstrap(Locale.US, FileHandle(File(root, "i18n/ui")))
        PlayerLabels.setPlayerNames(names)
    }

    @AfterTest
    fun tearDown() {
        PlayerLabels.clearCustomNames()
    }

    // --- copy -------------------------------------------------------------

    @Test
    fun claimWindowCopyIsTranslatedAndTakesNoSeat() {
        for (locale in listOf(Locale.US, Locale.forLanguageTag("ru"))) {
            L10n.setLocale(locale)
            val copy = L10n.get("turn.claims_open")
            assertNotEquals("turn.claims_open", copy, "missing $locale key")
            assertTrue(copy.isNotBlank(), "blank $locale copy")
            assertFalse(copy.contains("{0}"), "$locale copy must not take a seat name: $copy")
        }
        L10n.setLocale(Locale.US)
    }

    // --- online -----------------------------------------------------------

    @Test
    fun claimWindowBannerIsAnonymous() {
        val expected = L10n.get("turn.claims_open")
        for (reactor in 1 until NUM_PLAYERS) {
            val text = bannerFor(onlineView(currentPlayer = reactor))
            assertEquals(expected, text, "online, reactor $reactor")
            assertFalse(
                text.contains(names[reactor]),
                "the banner must not name reactor ${names[reactor]}: $text",
            )
        }
    }

    @Test
    fun kongRobWindowBannerIsAnonymous() {
        val text = bannerFor(onlineView(currentPlayer = 1, kongRob = true))
        assertEquals(L10n.get("turn.claims_open"), text)
        assertFalse(text.contains(names[1]), "the banner must not name the promoter: $text")
    }

    @Test
    fun ownClaimStillAnnouncesTheCall() {
        val text = TurnStatusBanner.statusText(
            view = onlineView(currentPlayer = 0),
            humanSeat = 0,
            humanDiscarding = false,
            humanClaiming = true,
        )
        assertEquals(L10n.get("turn.your_call"), text)
    }

    @Test
    fun waitingForOthersCopyIsUnchanged() {
        val text = bannerFor(onlineView(currentPlayer = 2, isWaitingForOthers = true))
        assertEquals(L10n.get("turn.waiting_for_others"), text)
    }

    @Test
    fun actOnHandBannerStillNamesTheSeatOnTurn() {
        // A draw/discard is watched by the whole table, so the seat stays named.
        assertEquals(L10n.format("turn.act", names[2]), bannerFor(onlineView(currentPlayer = 2, actOnHand = true)))
        assertNotEquals(
            bannerFor(onlineView(currentPlayer = 1, actOnHand = true)),
            bannerFor(onlineView(currentPlayer = 2, actOnHand = true)),
        )
    }

    @Test
    fun clockLinePreparesUntilTheThinkTimeStarts() {
        assertEquals(
            "Preparing...",
            TurnStatusBanner.clockText(
                preparing = true,
                remainingMs = 40_000L,
                slackMs = 30_000L,
                baseMs = 7_000L,
            ),
        )
        assertEquals(
            "7s + 30s",
            TurnStatusBanner.clockText(
                preparing = false,
                remainingMs = 37_000L,
                slackMs = 30_000L,
                baseMs = 7_000L,
            ),
        )
        assertEquals(
            "6s + 30s",
            TurnStatusBanner.clockText(
                preparing = false,
                remainingMs = 36_000L,
                slackMs = 30_000L,
                baseMs = 7_000L,
            ),
        )
    }

    @Test
    fun shutdownWarningBannerShowsLocalizedTemplateInWarningPhase() {
        val view = onlineView(currentPlayer = 0)
        val text = TurnStatusBanner.statusText(
            view = view,
            humanSeat = 0,
            humanDiscarding = true,
            humanClaiming = false,
            shutdownPhase = "warning",
        )
        assertEquals(L10n.get("online.shutdown_warning"), text)
    }

    @Test
    fun shutdownWarningBannerIncludesOperatorMessage() {
        val view = onlineView(currentPlayer = 0)
        val text = TurnStatusBanner.statusText(
            view = view,
            humanSeat = 0,
            humanDiscarding = false,
            humanClaiming = false,
            shutdownPhase = "warning",
            shutdownMessage = "Maintenance in 15m",
        )
        assertEquals(L10n.format("online.shutdown_warning_reason", "Maintenance in 15m"), text)
    }

    @Test
    fun reconnectRequiredTakesPrecedenceOverShutdownWarning() {
        val view = onlineView(currentPlayer = 0)
        val text = TurnStatusBanner.statusText(
            view = view,
            humanSeat = 0,
            humanDiscarding = false,
            humanClaiming = false,
            connectionStatus = ConnectionStatus.ReconnectRequired,
            shutdownPhase = "warning",
            shutdownMessage = "Restart",
        )
        assertEquals(L10n.get("online.reconnect_required"), text)
    }

    @Test
    fun shutdownFinalPhaseDoesNotOverrideTurnBanner() {
        val view = onlineView(currentPlayer = 0)
        val text = TurnStatusBanner.statusText(
            view = view,
            humanSeat = 0,
            humanDiscarding = true,
            humanClaiming = false,
            shutdownPhase = "final",
        )
        assertEquals(L10n.get("turn.your_discard"), text)
    }

    // --- local ------------------------------------------------------------

    @Test
    fun localClaimWindowBannerIsAnonymous() {
        val state = GameState.fromWallOrder(canonicalWall(Random(4)), eastSeat = 0)
        val board = BoardController()
        val discarder = state.currentPlayer

        val discard = board.allowedChoices(state).first { it.kind == ChoiceKind.Discard }
        board.tryApply(state, discard)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)

        val reactor = state.currentPlayer
        assertNotEquals(discarder, reactor)

        val view = TableViewState.fromGameState(state, humanSeat = discarder)
        val text = bannerFor(view, humanSeat = discarder)
        assertEquals(L10n.get("turn.claims_open"), text)
        assertFalse(text.contains(names[reactor]), "the banner must not name reactor ${names[reactor]}: $text")
    }

    // --- helpers ----------------------------------------------------------

    private fun bannerFor(view: TableViewState, humanSeat: Int = view.mySeat): String =
        TurnStatusBanner.statusText(
            view = view,
            humanSeat = humanSeat,
            humanDiscarding = false,
            humanClaiming = false,
        )

    private fun onlineView(
        currentPlayer: Int,
        actOnHand: Boolean = false,
        kongRob: Boolean = false,
        isWaitingForOthers: Boolean = false,
    ): TableViewState = SeatSnapshotWire(
        handNumber = 1,
        mySeat = 0,
        eastSeat = 0,
        liveWallSize = 70,
        currentPlayer = currentPlayer,
        decisionType = if (actOnHand) "ActOnHand" else "ReactToDiscard",
        lastDiscarder = 0,
        lastDiscarded = 10,
        isPendingDiscard = !actOnHand,
        isKongRobWindow = kongRob,
        handScore = 0,
        winnerSeat = null,
        winSource = null,
        isTerminal = false,
        scoreDeltas = null,
        seats = (0 until NUM_PLAYERS).map { seat ->
            SeatSnapshotViewWire(
                seat = seat,
                concealedCount = 13,
                concealedTiles = null,
                drawnTile = -1,
                hasDrawnTile = false,
                melds = emptyList(),
                river = emptyList(),
                seatWindTile = seat,
            )
        },
    ).toTableViewState(isWaitingForOthers = isWaitingForOthers)

    private fun canonicalWall(rng: Random): IntArray {
        val list = ArrayList<Int>(136)
        for (t in 0 until NUM_TILE_TYPES) repeat(4) { list.add(t) }
        list.shuffle(rng)
        return list.toIntArray()
    }
}
