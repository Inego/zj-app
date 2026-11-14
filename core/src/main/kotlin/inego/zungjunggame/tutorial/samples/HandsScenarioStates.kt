package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout
import inego.zungjunggame.engine.LIVE_WALL_DRAWABLE_SIZE
import inego.zungjunggame.engine.NUM_CHOW_TYPES
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerLayout
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import kotlin.random.Random

/**
 * Hand-built board states behind [HandsScenarios].
 *
 * Every waiting hand in this chapter is "an opponent discarded [claimTile] and the
 * human may Hu or pass", so all of them come from one builder and differ only in
 * the tiles dealt. Each factory below re-asserts the deal it promises (offer kind, Hu
 * score, melds) so a bad edit fails here rather than in a lesson.
 *
 * The wall stock, the wall tail, and the unknown-tile table are owned by
 * [ScenarioStateKit]; only the deal itself is chapter-specific.
 */
internal object HandsScenarioStates {

    private const val YOU = ScenarioStateKit.YOU
    private const val RIGHT = ScenarioStateKit.RIGHT
    private const val OPPOSITE = ScenarioStateKit.OPPOSITE
    private const val LEFT = ScenarioStateKit.LEFT

    private data class OpenChowSpec(
        val chowIndex: Int,
        val claimedTile: Int,
        val calledFrom: Int = LEFT,
    )

    private data class OpenMeldSpec(
        val tile: Int,
        val calledFrom: Int,
    )

    private fun buildDiscardOfferState(
        youTiles: IntArray,
        claimTile: Int,
        openChows: List<OpenChowSpec> = emptyList(),
        openPungs: List<OpenMeldSpec> = emptyList(),
        openKongs: List<OpenMeldSpec> = emptyList(),
        discarder: Int = OPPOSITE,
        eastSeat: Int = YOU,
        allowChickenHands: Boolean = false,
        rng: Random = Random(0),
    ): GameState {
        val stock = TileStock(rng)
        val youTilesTotal = youTiles.size + openChows.size * 3 + openPungs.size * 3 + openKongs.size * 4
        val totalDrawn = youTilesTotal + 42

        for ((chowIndex) in openChows) {
            val lowest = (chowIndex / 7) * 9 + (chowIndex % 7)
            stock.take(lowest)
            stock.take(lowest + 1)
            stock.take(lowest + 2)
        }
        for ((tile) in openPungs) {
            repeat(3) { stock.take(tile) }
        }
        for ((tile) in openKongs) {
            repeat(4) { stock.take(tile) }
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
        for ((tile, calledFrom1) in openPungs) {
            pungsKongs[tile] = PlayerState.PK_OPEN_PUNG
            calledFrom[tile] = calledFrom1
        }
        for ((tile, calledFrom1) in openKongs) {
            pungsKongs[tile] = PlayerState.PK_OPEN_KONG
            calledFrom[tile] = calledFrom1
        }

        val chowsArray = IntArray(NUM_CHOW_TYPES)
        val meldKind = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldValue = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldClaimTile = IntArray(PlayerState.MAX_OPEN_MELDS)
        val meldFrom = IntArray(PlayerState.MAX_OPEN_MELDS)
        // Exposure order: chows in list order, then pungs, then kongs.
        for ((slot, c) in openChows.withIndex()) {
            chowsArray[c.chowIndex]++
            meldKind[slot] = PlayerState.MELD_CHOW
            meldValue[slot] = c.chowIndex
            meldClaimTile[slot] = c.claimedTile
            meldFrom[slot] = c.calledFrom
        }
        for ((slot, m) in openPungs.withIndex()) {
            val i = openChows.size + slot
            meldKind[i] = PlayerState.MELD_SET
            meldValue[i] = m.tile
        }
        for ((slot, m) in openKongs.withIndex()) {
            val i = openChows.size + openPungs.size + slot
            meldKind[i] = PlayerState.MELD_SET
            meldValue[i] = m.tile
        }

        val players = arrayOf(
            PlayerLayout(
                concealed = you,
                pungsKongs = pungsKongs,
                pungKongCalledFrom = calledFrom,
                openChows = chowsArray,
                meldCount = openChows.size + openPungs.size + openKongs.size,
                meldKind = meldKind,
                meldValue = meldValue,
                meldClaimTile = meldClaimTile,
                meldFrom = meldFrom,
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
        val hasPending = BooleanArray(NUM_PLAYERS).also { it[LEFT] = true }
        val pending = Array(NUM_PLAYERS) { Choice.pass() }
        val liveWallSize = LIVE_WALL_DRAWABLE_SIZE - stock.drawn.size
        val layout = GameStateLayout(
            wall = wall,
            liveWallSize = liveWallSize,
            players = players,
            unknownTileCountsByPlayer = ScenarioStateKit.unseenCounts(players, discards, claimTile),
            discards = discards,
            lastDiscarder = discarder,
            lastDiscarded = claimTile,
            lastDrawnTile = -1,
            lastDrawnPlayer = -1,
            eastSeat = eastSeat,
            currentPlayer = YOU,
            decisionType = DecisionType.ReactToDiscard,
            allowChickenHands = allowChickenHands,
            pendingReactions = pending,
            hasPendingReaction = hasPending,
            pendingReactionCount = 1,
        )
        return GameState.fromLayout(layout)
    }

    fun valueHonorOfferState(rng: Random = Random(0)): GameState {
        val state = buildValueHonorOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 10 / Pass value honor offer, got $kinds"
        }
        check(!state.allowChickenHands) { "value honor offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 10) {
            "value honor Hu score ${state.discardReactionHuScoreOrNegative()}, expected 10"
        }
        val you = state.players[YOU]
        check(you.pungsKongs[Tiles.RED] == PlayerState.PK_OPEN_PUNG) {
            "expected open pung of Red dragon"
        }
        check(
            you.concealed[state.lastDiscarded] == 0 &&
                you.concealed[Tiles.DOT_3] == 1 &&
                you.concealed[Tiles.DOT_4] == 1,
        ) {
            "expected a sequence wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildValueHonorOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                Tiles.CHAR_5, Tiles.CHAR_6, Tiles.CHAR_7,
                Tiles.DOT_3, Tiles.DOT_4,
                Tiles.DOT_9, Tiles.DOT_9,
            ),
            claimTile = Tiles.DOT_5,
            openPungs = listOf(OpenMeldSpec(Tiles.RED, OPPOSITE)),
            rng = rng,
        )

    fun twoValueHonorsWinState(rng: Random = Random(0)): GameState {
        val state = buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                Tiles.CHAR_5, Tiles.CHAR_6,
                Tiles.DOT_9, Tiles.DOT_9,
            ),
            claimTile = Tiles.CHAR_7,
            openPungs = listOf(
                OpenMeldSpec(Tiles.RED, OPPOSITE),
                OpenMeldSpec(Tiles.EAST, RIGHT),
            ),
            rng = rng,
        )
        state.applyChoice(Choice.hu(20))
        state.applyChoice(Choice.pass())
        check(state.isTerminal() && state.handScore == 20) {
            "expected two value honors score 20, got ${state.handScore}"
        }
        return state
    }

    fun allTripletsOfferState(rng: Random = Random(0)): GameState {
        val state = buildAllTripletsOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 30 / Pass all triplets offer, got $kinds"
        }
        check(!state.allowChickenHands) { "all triplets offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 30) {
            "all triplets Hu score ${state.discardReactionHuScoreOrNegative()}, expected 30"
        }
        val you = state.players[YOU]
        check(you.pungsKongs[Tiles.CHAR_1] == PlayerState.PK_OPEN_PUNG &&
            you.pungsKongs[Tiles.DOT_3] == PlayerState.PK_OPEN_PUNG &&
            you.pungsKongs[Tiles.BAMBOO_6] == PlayerState.PK_OPEN_PUNG) {
            "expected three open pungs (1-man, 3-dot, 6-bamboo)"
        }
        check(you.concealed[state.lastDiscarded] == 1) {
            "expected singleton pair wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildAllTripletsOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.CHAR_8, Tiles.CHAR_8, Tiles.CHAR_8,
                Tiles.DOT_9,
            ),
            claimTile = Tiles.DOT_9,
            openPungs = listOf(
                OpenMeldSpec(Tiles.CHAR_1, RIGHT),
                OpenMeldSpec(Tiles.DOT_3, OPPOSITE),
                OpenMeldSpec(Tiles.BAMBOO_6, LEFT),
            ),
            rng = rng,
        )

    fun nineTileStraightOfferState(rng: Random = Random(0)): GameState {
        val state = buildNineTileStraightOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 40 / Pass nine-tile straight offer, got $kinds"
        }
        check(!state.allowChickenHands) { "nine-tile straight offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 40) {
            "nine-tile straight Hu score ${state.discardReactionHuScoreOrNegative()}, expected 40"
        }
        val you = state.players[YOU]
        check(you.openChows[0] == 1 && you.meldCount == 1) {
            "expected 1 open chow of 1-2-3 man"
        }
        check(
            you.concealed[state.lastDiscarded] == 0 &&
                you.concealed[Tiles.CHAR_7] == 1 &&
                you.concealed[Tiles.CHAR_8] == 1,
        ) {
            "expected a sequence wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildNineTileStraightOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.CHAR_4, Tiles.CHAR_5, Tiles.CHAR_6,
                Tiles.CHAR_7, Tiles.CHAR_8,
                Tiles.DOT_5, Tiles.DOT_5, Tiles.DOT_5,
                Tiles.BAMBOO_9, Tiles.BAMBOO_9,
            ),
            claimTile = Tiles.CHAR_9,
            openChows = listOf(OpenChowSpec(chowIndex = 0, claimedTile = Tiles.CHAR_1, calledFrom = LEFT)),
            rng = rng,
        )

    fun sevenPairsOfferState(rng: Random = Random(0)): GameState {
        val state = buildSevenPairsOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 30 / Pass seven pairs offer, got $kinds"
        }
        check(!state.allowChickenHands) { "seven pairs offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 30) {
            "seven pairs Hu score ${state.discardReactionHuScoreOrNegative()}, expected 30"
        }
        val you = state.players[YOU]
        check(you.pungsKongs.all { it == 0 } && you.openChows.all { it == 0 }) {
            "expected no open melds for seven pairs"
        }
        check(you.concealed[state.lastDiscarded] == 1) {
            "expected a singleton wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildSevenPairsOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.CHAR_1, Tiles.CHAR_1,
                Tiles.CHAR_4, Tiles.CHAR_4,
                Tiles.DOT_2, Tiles.DOT_2,
                Tiles.DOT_5, Tiles.DOT_5,
                Tiles.DOT_8, Tiles.DOT_8,
                Tiles.BAMBOO_3, Tiles.BAMBOO_3,
                Tiles.BAMBOO_7,
            ),
            claimTile = Tiles.BAMBOO_7,
            rng = rng,
        )

    fun twoIdenticalSequencesOfferState(rng: Random = Random(0)): GameState {
        val state = buildTwoIdenticalSequencesOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 10 / Pass two identical sequences offer, got $kinds"
        }
        check(!state.allowChickenHands) { "two identical sequences offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 10) {
            "two identical sequences Hu score ${state.discardReactionHuScoreOrNegative()}, expected 10"
        }
        val you = state.players[YOU]
        check(you.pungsKongs[Tiles.DOT_5] == PlayerState.PK_OPEN_PUNG) {
            "expected open pung of 5-dot"
        }
        check(
            you.concealed[Tiles.BAMBOO_2] == 2 &&
                you.concealed[Tiles.BAMBOO_3] == 2 &&
                you.concealed[Tiles.BAMBOO_4] == 1,
        ) {
            "expected two identical sequences waiting on second 4-bamboo"
        }
        return state
    }

    private fun buildTwoIdenticalSequencesOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.BAMBOO_2, Tiles.BAMBOO_2,
                Tiles.BAMBOO_3, Tiles.BAMBOO_3,
                Tiles.BAMBOO_4,
                Tiles.CHAR_6, Tiles.CHAR_7, Tiles.CHAR_8,
                Tiles.BAMBOO_9, Tiles.BAMBOO_9,
            ),
            claimTile = Tiles.BAMBOO_4,
            openPungs = listOf(OpenMeldSpec(Tiles.DOT_5, OPPOSITE)),
            rng = rng,
        )

    fun twoConcealedTripletsOfferState(rng: Random = Random(0)): GameState {
        val state = buildTwoConcealedTripletsOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 5 / Pass two concealed triplets offer, got $kinds"
        }
        check(!state.allowChickenHands) { "two concealed triplets offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 5) {
            "two concealed triplets Hu score ${state.discardReactionHuScoreOrNegative()}, expected 5"
        }
        val you = state.players[YOU]
        check(you.openChows[0] == 1 && you.meldCount == 1) {
            "expected open chow of 1-2-3 man"
        }
        check(
            you.concealed[Tiles.DOT_3] == 3 &&
                you.concealed[Tiles.BAMBOO_6] == 3 &&
                you.concealed[Tiles.CHAR_4] == 1 &&
                you.concealed[Tiles.CHAR_5] == 1,
        ) {
            "expected two concealed triplets waiting on sequence completion"
        }
        return state
    }

    private fun buildTwoConcealedTripletsOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.DOT_3, Tiles.DOT_3, Tiles.DOT_3,
                Tiles.BAMBOO_6, Tiles.BAMBOO_6, Tiles.BAMBOO_6,
                Tiles.CHAR_4, Tiles.CHAR_5,
                Tiles.BAMBOO_9, Tiles.BAMBOO_9,
            ),
            claimTile = Tiles.CHAR_6,
            openChows = listOf(OpenChowSpec(chowIndex = 0, claimedTile = Tiles.CHAR_1, calledFrom = LEFT)),
            rng = rng,
        )

    fun threeSimilarTripletsOfferState(rng: Random = Random(0)): GameState {
        val state = buildThreeSimilarTripletsOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 120 / Pass three similar triplets offer, got $kinds"
        }
        check(!state.allowChickenHands) { "three similar triplets offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 120) {
            "three similar triplets Hu score ${state.discardReactionHuScoreOrNegative()}, expected 120"
        }
        val you = state.players[YOU]
        check(
            you.pungsKongs[Tiles.CHAR_5] == PlayerState.PK_OPEN_PUNG &&
                you.pungsKongs[Tiles.DOT_5] == PlayerState.PK_OPEN_PUNG,
        ) {
            "expected two open pungs of 5-man and 5-dot"
        }
        check(
            you.concealed[Tiles.BAMBOO_5] == 3 &&
                you.concealed[Tiles.BAMBOO_7] == 1 &&
                you.concealed[Tiles.BAMBOO_8] == 1,
        ) {
            "expected 5-bamboo triplet and sequence wait on 78 bamboo"
        }
        return state
    }

    private fun buildThreeSimilarTripletsOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.BAMBOO_5, Tiles.BAMBOO_5, Tiles.BAMBOO_5,
                Tiles.BAMBOO_7, Tiles.BAMBOO_8,
                Tiles.CHAR_1, Tiles.CHAR_1,
            ),
            claimTile = Tiles.BAMBOO_9,
            openPungs = listOf(
                OpenMeldSpec(Tiles.CHAR_5, RIGHT),
                OpenMeldSpec(Tiles.DOT_5, OPPOSITE),
            ),
            rng = rng,
        )

    fun smallThreeSimilarTripletsWinState(rng: Random = Random(0)): GameState {
        val state = buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
                Tiles.BAMBOO_7, Tiles.BAMBOO_8,
                Tiles.BAMBOO_5, Tiles.BAMBOO_5,
            ),
            claimTile = Tiles.BAMBOO_9,
            openPungs = listOf(
                OpenMeldSpec(Tiles.CHAR_5, RIGHT),
                OpenMeldSpec(Tiles.DOT_5, OPPOSITE),
            ),
            rng = rng,
        )
        state.applyChoice(Choice.hu(30))
        state.applyChoice(Choice.pass())
        check(state.isTerminal() && state.handScore == 30) {
            "expected small three similar triplets score 30, got ${state.handScore}"
        }
        return state
    }

    fun oneSuitOfferState(rng: Random = Random(0)): GameState {
        val state = buildOneSuitOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 40 / Pass mixed one-suit offer, got $kinds"
        }
        check(!state.allowChickenHands) { "mixed one-suit offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 40) {
            "mixed one-suit Hu score ${state.discardReactionHuScoreOrNegative()}, expected 40"
        }
        val you = state.players[YOU]
        check(you.openChows[8] == 1 && you.meldCount == 1) {
            "expected open chow of 2-3-4 dot"
        }
        check(
            you.concealed[Tiles.DOT_1] == 2 &&
                you.concealed[Tiles.DOT_7] == 1 &&
                you.concealed[Tiles.DOT_8] == 1 &&
                you.concealed[Tiles.WEST] == 3,
        ) {
            "expected mixed one-suit waiting on 9-dot with West wind triplet"
        }
        return state
    }

    private fun buildOneSuitOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.DOT_1, Tiles.DOT_1,
                Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                Tiles.DOT_7, Tiles.DOT_8,
                Tiles.WEST, Tiles.WEST, Tiles.WEST,
            ),
            claimTile = Tiles.DOT_9,
            openChows = listOf(OpenChowSpec(chowIndex = 8, claimedTile = Tiles.DOT_2, calledFrom = LEFT)),
            rng = rng,
        )

    fun pureOneSuitWinState(rng: Random = Random(0)): GameState {
        val state = buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.DOT_1, Tiles.DOT_1,
                Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                Tiles.DOT_7, Tiles.DOT_8,
                Tiles.DOT_5, Tiles.DOT_5, Tiles.DOT_5,
            ),
            claimTile = Tiles.DOT_9,
            openChows = listOf(OpenChowSpec(chowIndex = 8, claimedTile = Tiles.DOT_2, calledFrom = LEFT)),
            rng = rng,
        )
        state.applyChoice(Choice.hu(80))
        state.applyChoice(Choice.pass())
        check(state.isTerminal() && state.handScore == 80) {
            "expected pure one-suit score 80, got ${state.handScore}"
        }
        return state
    }

    fun kongsOfferState(rng: Random = Random(0)): GameState {
        val state = buildKongsOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 20 / Pass kongs offer, got $kinds"
        }
        check(!state.allowChickenHands) { "kongs offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 20) {
            "kongs Hu score ${state.discardReactionHuScoreOrNegative()}, expected 20"
        }
        val you = state.players[YOU]
        check(
            you.pungsKongs[Tiles.CHAR_1] == PlayerState.PK_OPEN_KONG &&
                you.pungsKongs[Tiles.DOT_5] == PlayerState.PK_OPEN_KONG,
        ) {
            "expected two open kongs (1-man and 5-dot)"
        }
        check(
            you.concealed[state.lastDiscarded] == 0 &&
                you.concealed[Tiles.BAMBOO_6] == 1 &&
                you.concealed[Tiles.BAMBOO_7] == 1,
        ) {
            "expected a sequence wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildKongsOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                Tiles.BAMBOO_6, Tiles.BAMBOO_7,
                Tiles.DOT_9, Tiles.DOT_9,
            ),
            claimTile = Tiles.BAMBOO_8,
            openKongs = listOf(
                OpenMeldSpec(Tiles.CHAR_1, OPPOSITE),
                OpenMeldSpec(Tiles.DOT_5, OPPOSITE),
            ),
            rng = rng,
        )

    fun noTerminalsOfferState(rng: Random = Random(0)): GameState {
        val state = buildNoTerminalsOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 5 / Pass no terminals offer, got $kinds"
        }
        check(!state.allowChickenHands) { "no terminals offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 5) {
            "no terminals Hu score ${state.discardReactionHuScoreOrNegative()}, expected 5"
        }
        val you = state.players[YOU]
        check(you.pungsKongs[Tiles.DOT_5] == PlayerState.PK_OPEN_PUNG) {
            "expected open pung of 5-dot"
        }
        check(
            you.concealed[state.lastDiscarded] == 0 &&
                you.concealed[Tiles.BAMBOO_6] == 1 &&
                you.concealed[Tiles.BAMBOO_7] == 1,
        ) {
            "expected a sequence wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildNoTerminalsOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.CHAR_2, Tiles.CHAR_3, Tiles.CHAR_4,
                Tiles.BAMBOO_3, Tiles.BAMBOO_4, Tiles.BAMBOO_5,
                Tiles.BAMBOO_6, Tiles.BAMBOO_7,
                Tiles.CHAR_8, Tiles.CHAR_8,
            ),
            claimTile = Tiles.BAMBOO_8,
            openPungs = listOf(OpenMeldSpec(Tiles.DOT_5, OPPOSITE)),
            rng = rng,
        )

    fun allSequencesOfferState(rng: Random = Random(0)): GameState {
        val state = buildAllSequencesOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 5 / Pass all sequences offer, got $kinds"
        }
        check(!state.allowChickenHands) { "all sequences offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 5) {
            "all sequences Hu score ${state.discardReactionHuScoreOrNegative()}, expected 5"
        }
        val you = state.players[YOU]
        check(you.openChows[0] == 1 && you.meldCount == 1) {
            "expected 1 open chow of 1-2-3 man"
        }
        check(
            you.concealed[state.lastDiscarded] == 0 &&
                you.concealed[Tiles.BAMBOO_7] == 1 &&
                you.concealed[Tiles.BAMBOO_8] == 1,
        ) {
            "expected a sequence wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildAllSequencesOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                Tiles.BAMBOO_7, Tiles.BAMBOO_8,
                Tiles.RED, Tiles.RED,
            ),
            claimTile = Tiles.BAMBOO_9,
            openChows = listOf(OpenChowSpec(chowIndex = 0, claimedTile = Tiles.CHAR_1, calledFrom = LEFT)),
            rng = rng,
        )

    /**
     * Concealed triplet of 1m + concealed 234s, 789s, pair of Green, and
     * mid-sequence wait on 4p and 6p. Opposite discarded 5p.
     * Completely concealed, mixed suits, sequences + triplet + terminals → Concealed Hand (5).
     */
    fun concealedOfferState(rng: Random = Random(0)): GameState {
        val state = buildConcealedOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 5 / Pass concealed offer, got $kinds"
        }
        check(!state.allowChickenHands) { "concealed offer requires allowChickenHands false" }
        check(state.discardReactionHuScoreOrNegative() == 5) {
            "concealed Hu score ${state.discardReactionHuScoreOrNegative()}, expected 5"
        }
        val you = state.players[YOU]
        check(you.pungsKongs.all { it == 0 } && you.openChows.all { it == 0 }) {
            "expected no open melds for concealed hand"
        }
        check(
            you.concealed[state.lastDiscarded] == 0 &&
                you.concealed[Tiles.DOT_4] == 1 &&
                you.concealed[Tiles.DOT_6] == 1,
        ) {
            "expected a mid-sequence wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildConcealedOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.CHAR_1, Tiles.CHAR_1, Tiles.CHAR_1,
                Tiles.DOT_4, Tiles.DOT_6,
                Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                Tiles.BAMBOO_7, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
                Tiles.GREEN, Tiles.GREEN,
            ),
            claimTile = Tiles.DOT_5,
            rng = rng,
        )

    /**
     * Open pung of 1m (claimed from Right) + concealed 456p, 234s, 789s,
     * Green singleton. Opposite discarded Green. Not concealed, not all
     * sequences, has terminals, mixed suits → chicken (1).
     */
    fun chickenOfferState(rng: Random = Random(0)): GameState {
        val state = buildChickenOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu 1 / Pass chicken offer, got $kinds"
        }
        check(state.allowChickenHands) { "chicken offer requires allowChickenHands" }
        check(state.discardReactionHuScoreOrNegative() == 1) {
            "chicken Hu score ${state.discardReactionHuScoreOrNegative()}, expected 1"
        }
        check(state.players[YOU].pungsKongs[Tiles.CHAR_1] == PlayerState.PK_OPEN_PUNG) {
            "expected open pung of 1-man"
        }
        check(state.players[YOU].concealed[state.lastDiscarded] == 1) {
            "expected a singleton wait on ${state.lastDiscarded}"
        }
        return state
    }

    private fun buildChickenOfferState(rng: Random): GameState =
        buildDiscardOfferState(
            youTiles = intArrayOf(
                Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
                Tiles.BAMBOO_7, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
                Tiles.GREEN,
            ),
            claimTile = Tiles.GREEN,
            openPungs = listOf(OpenMeldSpec(Tiles.CHAR_1, RIGHT)),
            allowChickenHands = true,
            rng = rng,
        )
}
