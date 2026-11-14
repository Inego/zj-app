package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout
import inego.zungjunggame.engine.LIVE_WALL_DRAWABLE_SIZE
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.engine.PlayerLayout
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.table.HighlightWhere
import inego.zungjunggame.tutorial.ChoiceMatch
import inego.zungjunggame.tutorial.ScenarioDef
import inego.zungjunggame.tutorial.ScenarioLayoutSpec
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialStep
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.tutorial.slide.SlidePresentation
import kotlin.random.Random

/**
 * Kongs chapter — big exposed (melded) kong on discard, concealed kong in hand,
 * and small exposed (promoted) kong upgrading an existing open pung.
 */
object KongScenarios {
    private const val YOU = ScenarioStateKit.YOU
    private const val OPPOSITE = ScenarioStateKit.OPPOSITE

    private const val CLICK_CALLOUT = "tutorial.claims.click_callout"

    /**
     * Lesson 1: Big exposed kong claimed on an opponent's discard.
     * You hold a concealed triplet of 8-bamboo ({888s}). Opposite discards the 4th copy.
     * Claiming Kong reveals the open four-of-a-kind, awards One Kong (5 pts), draws
     * a supplement tile from the live wall tail, and requires a discard.
     */
    fun meldedKong(): ScenarioDef {
        val state = meldedKongOfferState()
        val tile = state.lastDiscarded
        val liveBefore = state.liveWallSize
        val alreadyMelded = alreadyMeldedNoKongState(avoidClaimTile = tile)
        return ScenarioDef(
            id = "kongs-melded",
            titleKey = "tutorial.kongs.melded.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            humanSeat = 0,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.kongs.melded.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.kong(tile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("open kong claimed, supplement drawn") { s ->
                    val you = s.players[YOU]
                    you.pungsKongs[tile] == PlayerState.PK_OPEN_KONG &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.currentPlayer == YOU &&
                        s.lastDrawnPlayer == YOU &&
                        s.lastDrawnTile >= 0 &&
                        s.liveWallSize == liveBefore - 1
                },
                TutorialStep.Coach(
                    "tutorial.kongs.melded.reveal",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.melded.one_kong",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.melded.compensation",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(targetId = "wall.live"),
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.melded.must_discard",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.melded.already_melded",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(targetId = "river.last"),
                    layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(alreadyMelded)),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Lesson 2: Concealed kong declared on your turn.
     * You hold all four copies of 7-bamboo ({7777s}) in your concealed hand.
     * On your turn, the engine offers Declare Concealed Kong or Keep.
     * Declaring it reveals the 4 tiles, draws a supplement tile, keeps the hand
     * concealed for scoring, and cannot be robbed.
     */
    fun concealedKong(): ScenarioDef {
        val state = concealedKongOfferState()
        val tile = Tiles.BAMBOO_7
        val liveBefore = state.liveWallSize
        return ScenarioDef(
            id = "kongs-concealed",
            titleKey = "tutorial.kongs.concealed.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            humanSeat = 0,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.kongs.concealed.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        highlight = BoardHighlight.prompt(tile),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.concealedKong(tile)),
                TutorialStep.AssertState("concealed kong declared, supplement drawn") { s ->
                    val you = s.players[YOU]
                    you.pungsKongs[tile] == PlayerState.PK_CONCEALED_KONG &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.currentPlayer == YOU &&
                        s.lastDrawnPlayer == YOU &&
                        s.lastDrawnTile >= 0 &&
                        s.liveWallSize == liveBefore - 1
                },
                TutorialStep.Coach(
                    "tutorial.kongs.concealed.arrangement",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        highlight = BoardHighlight.prompt(tile, where = HighlightWhere.YouMelds),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.concealed.scoring",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.concealed.compensation",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(targetId = "wall.live"),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Lesson 3: Promoted kong upgrading an open pung on your turn.
     * You already have an open pung of 5-dot ({555p}) and just drew the 4th copy.
     * Declaring Promote to Kong adds the tile to the meld, but exposes the player
     * to opponents Robbing the Kong if it completes their winning hand.
     */
    fun promotedKong(): ScenarioDef {
        val state = promotedKongOfferState()
        val tile = Tiles.DOT_5
        val liveBefore = state.liveWallSize
        return ScenarioDef(
            id = "kongs-promoted",
            titleKey = "tutorial.kongs.promoted.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            humanSeat = 0,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.kongs.promoted.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        highlight = BoardHighlight.prompt(tile),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.promoteKong(tile)),
                TutorialStep.AssertState("open pung promoted to kong, supplement drawn") { s ->
                    val you = s.players[YOU]
                    you.pungsKongs[tile] == PlayerState.PK_OPEN_KONG &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.currentPlayer == YOU &&
                        s.lastDrawnPlayer == YOU &&
                        s.lastDrawnTile >= 0 &&
                        s.liveWallSize == liveBefore - 1
                },
                TutorialStep.Coach(
                    "tutorial.kongs.promoted.upgraded",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        highlight = BoardHighlight.prompt(tile),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.promoted.robbing",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.kongs.promoted.compensation",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(targetId = "wall.live"),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    private data class OpenMeldSpec(
        val tile: Int,
        val calledFrom: Int,
    )

    private fun meldedKongOfferState(rng: Random = Random(0)): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8,
                Tiles.DOT_2, Tiles.DOT_3, Tiles.DOT_4,
                Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
                Tiles.CHAR_7, Tiles.CHAR_8,
                Tiles.WHITE, Tiles.WHITE,
            ),
            claimTile = Tiles.BAMBOO_8,
            discarder = OPPOSITE,
            rng = rng,
        )

    private fun alreadyMeldedNoKongState(avoidClaimTile: Int, rng: Random = Random(0)): GameState {
        val candidate = if (avoidClaimTile != Tiles.DOT_5) Tiles.DOT_5 else Tiles.BAMBOO_2
        val state = buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.DOT_2, Tiles.DOT_3, Tiles.DOT_4,
                Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
                Tiles.CHAR_7, Tiles.CHAR_8,
                Tiles.WHITE, Tiles.WHITE,
            ),
            claimTile = candidate,
            openPungs = listOf(OpenMeldSpec(candidate, OPPOSITE)),
            discarder = OPPOSITE,
            rng = rng,
        )
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(ChoiceKind.Kong !in kinds) { "already melded pung should not offer Kong on discard" }
        return state
    }

    private fun concealedKongOfferState(rng: Random = Random(0)): GameState {
        val state = buildActOnHandState(
            youConcealedTiles = intArrayOf(
                Tiles.BAMBOO_7, Tiles.BAMBOO_7, Tiles.BAMBOO_7, Tiles.BAMBOO_7,
                Tiles.DOT_2, Tiles.DOT_3, Tiles.DOT_4,
                Tiles.CHAR_4, Tiles.CHAR_5, Tiles.CHAR_6,
                Tiles.CHAR_8, Tiles.CHAR_9,
                Tiles.DOT_1, Tiles.DOT_1,
            ),
            lastDrawnTile = Tiles.BAMBOO_7,
            rng = rng,
        )
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.ConcealedKong, ChoiceKind.Keep)) {
            "expected ConcealedKong / Keep offer, got $kinds"
        }
        return state
    }

    private fun promotedKongOfferState(rng: Random = Random(0)): GameState {
        val state = buildActOnHandState(
            youConcealedTiles = intArrayOf(
                Tiles.DOT_5,
                Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
                Tiles.CHAR_7, Tiles.CHAR_8,
                Tiles.WHITE, Tiles.WHITE,
            ),
            lastDrawnTile = Tiles.DOT_5,
            openPungs = listOf(OpenMeldSpec(Tiles.DOT_5, OPPOSITE)),
            rng = rng,
        )
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(ChoiceKind.PromoteKong in kinds && ChoiceKind.Discard in kinds) {
            "expected PromoteKong and Discard choices, got $kinds"
        }
        return state
    }

    private fun buildDiscardOfferState(
        youTiles: IntArray,
        claimTile: Int,
        openPungs: List<OpenMeldSpec> = emptyList(),
        discarder: Int = OPPOSITE,
        rng: Random = Random(0),
    ): GameState {
        val stock = TileStock(rng)
        val youTilesTotal = youTiles.size + openPungs.size * 3
        val totalDrawn = youTilesTotal + 42

        for ((tile) in openPungs) {
            repeat(3) { stock.take(tile) }
        }

        val you = IntArray(NUM_TILE_TYPES)
        for (tile in youTiles) {
            stock.take(tile)
            you[tile]++
        }

        val youRiver = stock.takeRandom(avoid = you)
        val claimOnly = IntArray(NUM_TILE_TYPES).also { it[claimTile] = 1 }
        val rightRiver = stock.takeRandom(avoid = claimOnly)
        stock.take(claimTile)

        val right = stock.dealConcealed(13)
        val opposite = stock.dealConcealed(13)
        val left = stock.dealConcealed(13)

        val wall = stock.completeWall(totalDrawn)

        val pungsKongs = IntArray(NUM_TILE_TYPES)
        val calledFrom = IntArray(NUM_TILE_TYPES) { -1 }
        val meldKind = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldValue = IntArray(PlayerState.MAX_OPEN_MELDS)
        for ((slot, m) in openPungs.withIndex()) {
            pungsKongs[m.tile] = PlayerState.PK_OPEN_PUNG
            calledFrom[m.tile] = m.calledFrom
            meldKind[slot] = PlayerState.MELD_SET
            meldValue[slot] = m.tile
        }

        val players: Array<PlayerLayout> = arrayOf(
            PlayerLayout(
                concealed = you,
                pungsKongs = pungsKongs,
                pungKongCalledFrom = calledFrom,
                meldCount = openPungs.size,
                meldKind = meldKind,
                meldValue = meldValue,
            ),
            PlayerLayout(concealed = right),
            PlayerLayout(concealed = opposite),
            PlayerLayout(concealed = left),
        )
        val discards: Array<List<Int>> = arrayOf(
            listOf(youRiver),
            listOf(rightRiver),
            emptyList(),
            emptyList(),
        )

        return GameState.fromLayout(
            GameStateLayout(
                wall = wall,
                liveWallSize = LIVE_WALL_DRAWABLE_SIZE - stock.drawn.size,
                players = players,
                unknownTileCountsByPlayer = ScenarioStateKit.unseenCounts(
                    players,
                    discards,
                    claimTile
                ),
                handRevisions = IntArray(NUM_PLAYERS) { 1 },
                discards = discards,
                lastDiscarder = discarder,
                lastDiscarded = claimTile,
                lastDrawnTile = -1,
                lastDrawnPlayer = -1,
                eastSeat = YOU,
                currentPlayer = YOU,
                decisionType = DecisionType.ReactToDiscard,
                handScore = -1,
                allowChickenHands = false,
                pendingReactions = Array(NUM_PLAYERS) { Choice.pass() },
                hasPendingReaction = BooleanArray(NUM_PLAYERS).also { it[discarder] = true },
                pendingReactionCount = 1,
                declinedConcealedKong = BooleanArray(NUM_TILE_TYPES),
            ),
        )
    }

    private fun buildActOnHandState(
        youConcealedTiles: IntArray,
        lastDrawnTile: Int,
        openPungs: List<OpenMeldSpec> = emptyList(),
        eastSeat: Int = YOU,
        rng: Random = Random(0),
    ): GameState {
        val stock = TileStock(rng)
        val youTilesTotal = youConcealedTiles.size + openPungs.size * 3
        check(youTilesTotal == 14) { "ActOnHand requires 14 tiles for active player, got $youTilesTotal" }

        val totalDrawn = 14 + 13 * 3

        for ((tile) in openPungs) {
            repeat(3) { stock.take(tile) }
        }

        val you = IntArray(NUM_TILE_TYPES)
        for (tile in youConcealedTiles) {
            stock.take(tile)
            you[tile]++
        }

        val right = stock.dealConcealed(13)
        val opposite = stock.dealConcealed(13)
        val left = stock.dealConcealed(13)

        val wall = stock.completeWall(totalDrawn)

        val pungsKongs = IntArray(NUM_TILE_TYPES)
        val calledFrom = IntArray(NUM_TILE_TYPES) { -1 }
        val meldKind = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldValue = IntArray(PlayerState.MAX_OPEN_MELDS)
        for ((slot, m) in openPungs.withIndex()) {
            pungsKongs[m.tile] = PlayerState.PK_OPEN_PUNG
            calledFrom[m.tile] = m.calledFrom
            meldKind[slot] = PlayerState.MELD_SET
            meldValue[slot] = m.tile
        }

        val players: Array<PlayerLayout> = arrayOf(
            PlayerLayout(
                concealed = you,
                pungsKongs = pungsKongs,
                pungKongCalledFrom = calledFrom,
                meldCount = openPungs.size,
                meldKind = meldKind,
                meldValue = meldValue,
            ),
            PlayerLayout(concealed = right),
            PlayerLayout(concealed = opposite),
            PlayerLayout(concealed = left),
        )
        val discards = Array<List<Int>>(NUM_PLAYERS) { emptyList() }

        return GameState.fromLayout(
            GameStateLayout(
                wall = wall,
                liveWallSize = LIVE_WALL_DRAWABLE_SIZE - stock.drawn.size,
                players = players,
                unknownTileCountsByPlayer = ScenarioStateKit.unseenCounts(players, discards),
                handRevisions = IntArray(NUM_PLAYERS) { 1 },
                discards = discards,
                lastDiscarder = -1,
                lastDiscarded = -1,
                lastDrawnTile = lastDrawnTile,
                lastDrawnPlayer = YOU,
                eastSeat = eastSeat,
                currentPlayer = YOU,
                decisionType = DecisionType.ActOnHand,
                handScore = -1,
                allowChickenHands = false,
                pendingReactions = Array(NUM_PLAYERS) { Choice.pass() },
                hasPendingReaction = BooleanArray(NUM_PLAYERS),
                pendingReactionCount = 0,
                declinedConcealedKong = BooleanArray(NUM_TILE_TYPES),
            ),
        )
    }
}
