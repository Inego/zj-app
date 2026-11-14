package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout
import inego.zungjunggame.engine.LIVE_WALL_DRAWABLE_SIZE
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_SUITED_TILES
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerLayout
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.platform.newRandom
import kotlin.random.Random

/**
 * Hand-built board states behind [ClaimScenarios].
 *
 * Every claim beat needs a table where a specific tile is in flight and the human
 * may or must react, so these share one shape: deal the human's tiles, put one
 * opponent discard on the river, and leave the reaction window open. Each factory
 * re-asserts the offer it promises, so a bad edit fails here rather than in a lesson.
 *
 * The stock and the wall are owned by [TileStock]; the seat names and the
 * unknown-tile table by [ScenarioStateKit].
 */
internal object ClaimScenarioStates {

    private const val YOU = ScenarioStateKit.YOU
    private const val RIGHT = ScenarioStateKit.RIGHT
    private const val OPPOSITE = ScenarioStateKit.OPPOSITE
    private const val LEFT = ScenarioStateKit.LEFT

    fun priorityOfferState(rng: Random = Random(42)): GameState {
        val claimTile = Tiles.BAMBOO_3
        val stock = TileStock(rng)

        stock.take(claimTile)

        // 2. You hold Bamboo 1 and 2 (waiting for 3 to Chow)
        val you = IntArray(NUM_TILE_TYPES)
        stock.take(Tiles.BAMBOO_1)
        you[Tiles.BAMBOO_1] = 1
        stock.take(Tiles.BAMBOO_2)
        you[Tiles.BAMBOO_2] = 1

        val youCandidates = (0 until NUM_TILE_TYPES).filter {
            it != claimTile && it != Tiles.BAMBOO_4 && it != Tiles.BAMBOO_1 && it != Tiles.BAMBOO_2
        }.shuffled(rng)
        for (i in 0 until 11) {
            val t = youCandidates[i]
            stock.take(t)
            you[t] = 1
        }

        // 3. Right holds a pair of Bamboo 3 (waiting for 3 to Pung)
        val rightHand = IntArray(NUM_TILE_TYPES)
        repeat(2) { stock.take(claimTile) }
        rightHand[claimTile] = 2

        val rightCandidates = (0 until NUM_TILE_TYPES).filter {
            it != claimTile && stock.remaining(it) > 0
        }.shuffled(rng)
        for (i in 0 until 11) {
            val t = rightCandidates[i]
            stock.take(t)
            rightHand[t] = 1
        }

        // 4. Rivers: 1 tile each for You, Right, Opposite
        val youRiver = stock.take((NUM_SUITED_TILES until NUM_TILE_TYPES).first { stock.remaining(it) > 0 })
        val rightRiver = stock.take((NUM_SUITED_TILES until NUM_TILE_TYPES).first { stock.remaining(it) > 0 })
        val oppRiver = stock.take((NUM_SUITED_TILES until NUM_TILE_TYPES).first { stock.remaining(it) > 0 })

        // 5. Deal 13 to Opposite and 13 to Left
        val oppHand = stock.dealConcealed(13)
        val leftHand = stock.dealConcealed(13)

        val wall = stock.completeWall(56)

        val players = arrayOf(
            PlayerLayout(concealed = you),
            PlayerLayout(concealed = rightHand),
            PlayerLayout(concealed = oppHand),
            PlayerLayout(concealed = leftHand),
        )
        val discards: Array<List<Int>> = arrayOf(
            listOf(youRiver),
            listOf(rightRiver),
            listOf(oppRiver),
            emptyList(),
        )
        val hasPending = BooleanArray(NUM_PLAYERS).also {
            it[RIGHT] = true
            it[OPPOSITE] = true
        }
        val pending = Array(NUM_PLAYERS) { Choice.pass() }.also {
            it[RIGHT] = Choice.pung(claimTile)
        }

        val layout = GameStateLayout(
            wall = wall,
            liveWallSize = LIVE_WALL_DRAWABLE_SIZE - stock.drawn.size,
            players = players,
            unknownTileCountsByPlayer = ScenarioStateKit.unseenCounts(players, discards, claimTile),
            discards = discards,
            lastDiscarder = LEFT,
            lastDiscarded = claimTile,
            lastDrawnTile = -1,
            lastDrawnPlayer = -1,
            eastSeat = YOU,
            currentPlayer = YOU,
            decisionType = DecisionType.ReactToDiscard,
            pendingReactions = pending,
            hasPendingReaction = hasPending,
            pendingReactionCount = 2,
        )
        val state = GameState.fromLayout(layout)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(ChoiceKind.Chow in kinds) { "expected Chow choice for You, got $kinds" }
        return state
    }

    /**
     * Mid-hand snapshot: You hold 13 as four sequences plus one honor
     * (pair wait). You and Right each have one river tile; Opposite discarded
     * the matching honor; Left already passed, so the human sees Hu / Pass.
     */
    fun huPairWaitOfferState(rng: Random = newRandom()): GameState {
        val state = buildHuPairWaitOfferState(rng)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Hu, ChoiceKind.Pass)) {
            "could not build a Hu/Pass pair-wait offer, got $kinds"
        }
        check(state.discardReactionHuScoreOrNegative() > 0) {
            "pair-wait Hu score ${state.discardReactionHuScoreOrNegative()}"
        }
        check(state.players[YOU].concealed[state.lastDiscarded] == 1) {
            "expected a singleton wait on ${state.lastDiscarded}"
        }
        return state
    }

    /**
     * Mid-hand snapshot: You hold 13 with exactly one pair; You and Right each
     * have one river tile; Opposite has just discarded the pair's tile; Left
     * already passed, so the human sees Pung / Pass.
     */
    fun pungOfferState(rng: Random = newRandom()): GameState =
        claimDiscardOfferState(
            heldCopies = 2,
            expectedKinds = setOf(ChoiceKind.Pung, ChoiceKind.Pass),
            failMsg = "could not build a Pung/Pass offer (exactly one pair, Opposite discarded it)",
            rng = rng,
        )

    /**
     * Mid-hand snapshot: You hold 13 with exactly two tiles that complete
     * one sequence with Left's discard; You and Right each have one river
     * tile; Left has just discarded; Opposite already passed, so the human
     * sees Chow / Pass.
     */
    fun chowOfferState(rng: Random = newRandom()): GameState =
        chowDiscardOfferState(
            lastDiscarder = LEFT,
            alreadyPassed = OPPOSITE,
            expectedKinds = setOf(ChoiceKind.Chow, ChoiceKind.Pass),
            requireSingleChow = true,
            failMsg = "could not build a Chow/Pass offer (exactly one sequence, Left discarded it)",
            rng = rng,
        )

    /**
     * Same table as [offer]: same hand, same discarded tile, but Opposite
     * discarded it (Left already passed). Chow is not offered.
     */
    fun chowFromOppositeState(offer: GameState): GameState {
        check(offer.lastDiscarder == LEFT) { "expected Left's discard, got ${offer.lastDiscarder}" }
        val src = GameStateLayout.from(offer)
        val hasPending = BooleanArray(NUM_PLAYERS).also { it[LEFT] = true }
        val state = GameState.fromLayout(
            GameStateLayout(
                wall = src.wall,
                liveWallSize = src.liveWallSize,
                players = src.players,
                unknownTileCountsByPlayer = src.unknownTileCountsByPlayer,
                handRevisions = src.handRevisions,
                discards = src.discards,
                lastDiscarder = OPPOSITE,
                lastDiscarded = src.lastDiscarded,
                lastDrawnTile = src.lastDrawnTile,
                lastDrawnPlayer = src.lastDrawnPlayer,
                eastSeat = src.eastSeat,
                currentPlayer = YOU,
                decisionType = DecisionType.ReactToDiscard,
                handScore = src.handScore,
                allowChickenHands = src.allowChickenHands,
                pendingReactions = Array(NUM_PLAYERS) { Choice.pass() },
                hasPendingReaction = hasPending,
                pendingReactionCount = 1,
                declinedConcealedKong = src.declinedConcealedKong,
            ),
        )
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Pass)) {
            "Opposite discard of a chow tile should be Pass-only, got $kinds"
        }
        return state
    }

    /**
     * You hold a pair of a tile already in Opposite's river; Opposite just
     * discarded an honor You do not hold. Pass-only.
     */
    fun staleRiverNoClaimState(rng: Random = newRandom()): GameState {
        val state = buildStaleRiverOfferState(rng)
        val stale = state.discards[OPPOSITE].single()
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        check(kinds == setOf(ChoiceKind.Pass)) {
            "stale-river contrast should be Pass-only, got $kinds"
        }
        check(state.players[YOU].concealed[stale] == 2) {
            "expected a pair of $stale in hand"
        }
        check(state.lastDiscarded != stale) {
            "pending discard should not be the stale river tile"
        }
        check(state.lastDiscarder == OPPOSITE) {
            "expected Opposite's discard, got ${state.lastDiscarder}"
        }
        return state
    }

    /**
     * Fresh deal, not a rearrangement of the pung-offer hand: You hold one
     * copy, Opposite discarded the same tile, offer is Pass-only (no pung on a
     * singleton, and not a winning pair-wait).
     * [avoidClaimTile] keeps the discard different from the lesson's pung tile.
     */
    fun singletonNoPungState(
        avoidClaimTile: Int,
        rng: Random = newRandom(),
    ): GameState {
        repeat(32) {
            val state = buildClaimDiscardOfferState(heldCopies = 1, rng)
            if (state.lastDiscarded == avoidClaimTile) return@repeat
            val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
            if (kinds == setOf(ChoiceKind.Pass)) {
                check(state.players[YOU].concealed[state.lastDiscarded] == 1) {
                    "expected one copy of ${state.lastDiscarded}"
                }
                return state
            }
        }
        error("could not build a singleton contrast (one copy, Pass-only)")
    }

    private fun claimDiscardOfferState(
        heldCopies: Int,
        expectedKinds: Set<ChoiceKind>,
        failMsg: String,
        rng: Random,
    ): GameState {
        require(heldCopies in 2..3) { "heldCopies $heldCopies, expected 2 (pung) or 3 (kong)" }
        repeat(32) {
            val state = buildClaimDiscardOfferState(heldCopies, rng)
            val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
            if (kinds == expectedKinds) return state
        }
        error(failMsg)
    }

    /**
     * Four sequences + a White singleton. All Sequences (and Concealed Hand)
     * keep the win legal with chicken hands off. Honor wait cannot chow, and
     * one copy cannot pung — the discard completes the pair only.
     */
    private fun buildHuPairWaitOfferState(rng: Random): GameState {
        val stock = TileStock(rng)

        val claimTile = Tiles.WHITE
        val youTiles = intArrayOf(
            Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
            Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
            Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
            Tiles.BAMBOO_7, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
            claimTile,
        )
        val you = IntArray(NUM_TILE_TYPES)
        for (tile in youTiles) {
            stock.take(tile)
            you[tile]++
        }
        check(you.sum() == 13) { "pair-wait hand ${you.sum()}, expected 13" }

        val youRiver = stock.takeRandom(avoid = you)
        val claimOnly = IntArray(NUM_TILE_TYPES).also { it[claimTile] = 1 }
        val rightRiver = stock.takeRandom(avoid = claimOnly)
        stock.take(claimTile)

        val right = stock.dealConcealed(13)
        val opposite = stock.dealConcealed(13)
        val left = stock.dealConcealed(13)

        val wall = stock.completeWall(55)

        val players = arrayOf(
            PlayerLayout(concealed = you),
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
            lastDiscarder = OPPOSITE,
            lastDiscarded = claimTile,
            lastDrawnTile = -1,
            lastDrawnPlayer = -1,
            eastSeat = YOU,
            currentPlayer = YOU,
            decisionType = DecisionType.ReactToDiscard,
            pendingReactions = pending,
            hasPendingReaction = hasPending,
            pendingReactionCount = 1,
        )
        return GameState.fromLayout(layout)
    }

    /**
     * You hold a pair of one tile already in Opposite's river. Opposite just
     * discarded an honor You do not hold (cannot pung, chow, or hu), so the
     * offer is Pass-only. Left/You/Right each have a river tile so the table
     * looks mid-hand.
     */
    private fun buildStaleRiverOfferState(rng: Random): GameState {
        val stock = TileStock(rng)

        val staleTile = rng.nextInt(NUM_TILE_TYPES)
        val you = IntArray(NUM_TILE_TYPES)
        repeat(2) { stock.take(staleTile) }
        you[staleTile] = 2

        val singles = (0 until NUM_TILE_TYPES).filter { it != staleTile }.shuffled(rng)
        repeat(11) { i ->
            val tile = singles[i]
            stock.take(tile)
            you[tile] = 1
        }

        stock.take(staleTile)

        val honorOptions = ArrayList<Int>(7)
        for (t in NUM_SUITED_TILES until NUM_TILE_TYPES) {
            if (you[t] == 0 && t != staleTile && stock.remaining(t) > 0) honorOptions += t
        }
        check(honorOptions.isNotEmpty()) { "no free honor for pending discard" }
        val freshTile = stock.take(honorOptions[rng.nextInt(honorOptions.size)])

        val avoidRivers = you.copyOf().also { it[freshTile] = 1 }
        val youRiver = stock.takeRandom(avoid = avoidRivers)
        val rightRiver = stock.takeRandom(avoid = avoidRivers)
        val leftRiver = stock.takeRandom(avoid = avoidRivers)

        val right = stock.dealConcealed(13)
        val opposite = stock.dealConcealed(13)
        val left = stock.dealConcealed(13)

        val wall = stock.completeWall(57)

        val players = arrayOf(
            PlayerLayout(concealed = you),
            PlayerLayout(concealed = right),
            PlayerLayout(concealed = opposite),
            PlayerLayout(concealed = left),
        )
        val discards: Array<List<Int>> = arrayOf(
            listOf(youRiver),
            listOf(rightRiver),
            listOf(staleTile),
            listOf(leftRiver),
        )
        val hasPending = BooleanArray(NUM_PLAYERS).also { it[LEFT] = true }
        val pending = Array(NUM_PLAYERS) { Choice.pass() }
        val liveWallSize = LIVE_WALL_DRAWABLE_SIZE - stock.drawn.size
        val layout = GameStateLayout(
            wall = wall,
            liveWallSize = liveWallSize,
            players = players,
            unknownTileCountsByPlayer = ScenarioStateKit.unseenCounts(players, discards, freshTile),
            discards = discards,
            lastDiscarder = OPPOSITE,
            lastDiscarded = freshTile,
            lastDrawnTile = -1,
            lastDrawnPlayer = -1,
            eastSeat = YOU,
            currentPlayer = YOU,
            decisionType = DecisionType.ReactToDiscard,
            pendingReactions = pending,
            hasPendingReaction = hasPending,
            pendingReactionCount = 1,
        )
        return GameState.fromLayout(layout)
    }

    private fun buildClaimDiscardOfferState(heldCopies: Int, rng: Random): GameState {
        val stock = TileStock(rng)

        val claimTile = rng.nextInt(NUM_TILE_TYPES)
        val you = IntArray(NUM_TILE_TYPES)
        repeat(heldCopies) { stock.take(claimTile) }
        you[claimTile] = heldCopies

        val singles = (0 until NUM_TILE_TYPES).filter { it != claimTile }.shuffled(rng)
        val singleCount = 13 - heldCopies
        for (i in 0 until singleCount) {
            val tile = singles[i]
            stock.take(tile)
            you[tile] = 1
        }

        val youRiver = stock.takeRandom(avoid = you)
        val claimOnly = IntArray(NUM_TILE_TYPES).also { it[claimTile] = 1 }
        val rightRiver = stock.takeRandom(avoid = claimOnly)
        stock.take(claimTile)

        val right = stock.dealConcealed(13)
        val opposite = stock.dealConcealed(13)
        val left = stock.dealConcealed(13)

        val wall = IntArray(WALL_SIZE)
        check(stock.drawn.size == 55) { "drawn ${stock.drawn.size}, expected 55" }
                for (i in stock.drawn.indices) wall[i] = stock.drawn[i]
        var w = stock.drawn.size
        val rest = ArrayList<Int>(WALL_SIZE - stock.drawn.size)
        for (t in 0 until NUM_TILE_TYPES) {
            repeat(stock.remaining(t)) { rest.add(t) }
        }
        rest.shuffle(rng)
        // Next live-wall draw is a type You do not hold, so a kong supplement
        // outline lands only on the parked drawn slot.
        val uniqueIdx = rest.indexOfFirst { you[it] == 0 }
        if (uniqueIdx > 0) {
            val swap = rest[0]
            rest[0] = rest[uniqueIdx]
            rest[uniqueIdx] = swap
        }
        for (tile in rest) wall[w++] = tile
        check(w == WALL_SIZE)

        val players = arrayOf(
            PlayerLayout(concealed = you),
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
            lastDiscarder = OPPOSITE,
            lastDiscarded = claimTile,
            lastDrawnTile = -1,
            lastDrawnPlayer = -1,
            eastSeat = YOU,
            currentPlayer = YOU,
            decisionType = DecisionType.ReactToDiscard,
            pendingReactions = pending,
            hasPendingReaction = hasPending,
            pendingReactionCount = 1,
        )
        return GameState.fromLayout(layout)
    }

    private fun chowDiscardOfferState(
        lastDiscarder: Int,
        alreadyPassed: Int,
        expectedKinds: Set<ChoiceKind>,
        requireSingleChow: Boolean,
        failMsg: String,
        rng: Random,
    ): GameState {
        repeat(32) {
            val state = buildChowDiscardOfferState(lastDiscarder, alreadyPassed, rng)
            val choices = state.enumerateChoices().asList()
            val kinds = choices.map { it.kind }.toSet()
            val chowOk = !requireSingleChow || choices.count { it.kind == ChoiceKind.Chow } == 1
            if (kinds == expectedKinds && chowOk) return state
        }
        error(failMsg)
    }

    /**
     * Same table as [buildClaimDiscardOfferState], but You hold two tiles that
     * complete one sequence with [lastDiscarder]'s suited discard (not a pair
     * of that tile, so Pung is not offered).
     */
    private fun buildChowDiscardOfferState(
        lastDiscarder: Int,
        alreadyPassed: Int,
        rng: Random,
    ): GameState {
        require(lastDiscarder in 0 until NUM_PLAYERS && lastDiscarder != YOU) {
            "lastDiscarder $lastDiscarder"
        }
        require(alreadyPassed in 0 until NUM_PLAYERS && alreadyPassed != YOU && alreadyPassed != lastDiscarder) {
            "alreadyPassed $alreadyPassed"
        }
        val stock = TileStock(rng)

        val claimTile = rng.nextInt(NUM_SUITED_TILES)
        val held = pickChowHeldTiles(claimTile, rng)
        val you = IntArray(NUM_TILE_TYPES)
        for (tile in held) {
            stock.take(tile)
            you[tile] = 1
        }

        val skip = BooleanArray(NUM_TILE_TYPES)
        skip[claimTile] = true
        for (partner in chowPartners(claimTile)) {
            if (you[partner] == 0) skip[partner] = true
        }
        val singles = (0 until NUM_TILE_TYPES).filter { !skip[it] && you[it] == 0 }.shuffled(rng)
        repeat(11) { i ->
            val tile = singles[i]
            stock.take(tile)
            you[tile] = 1
        }

        val youRiver = stock.takeRandom(avoid = you)
        val claimOnly = IntArray(NUM_TILE_TYPES).also { it[claimTile] = 1 }
        val rightRiver = stock.takeRandom(avoid = claimOnly)
        stock.take(claimTile)

        val right = stock.dealConcealed(13)
        val opposite = stock.dealConcealed(13)
        val left = stock.dealConcealed(13)

        val wall = stock.completeWall(55)

        val players = arrayOf(
            PlayerLayout(concealed = you),
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
        val hasPending = BooleanArray(NUM_PLAYERS).also { it[alreadyPassed] = true }
        val pending = Array(NUM_PLAYERS) { Choice.pass() }
        val liveWallSize = LIVE_WALL_DRAWABLE_SIZE - stock.drawn.size
        val layout = GameStateLayout(
            wall = wall,
            liveWallSize = liveWallSize,
            players = players,
            unknownTileCountsByPlayer = ScenarioStateKit.unseenCounts(players, discards, claimTile),
            discards = discards,
            lastDiscarder = lastDiscarder,
            lastDiscarded = claimTile,
            lastDrawnTile = -1,
            lastDrawnPlayer = -1,
            eastSeat = YOU,
            currentPlayer = YOU,
            decisionType = DecisionType.ReactToDiscard,
            pendingReactions = pending,
            hasPendingReaction = hasPending,
            pendingReactionCount = 1,
        )
        return GameState.fromLayout(layout)
    }

    /** Two concealed tiles that complete exactly one chow with [claimTile]. */
    private fun pickChowHeldTiles(claimTile: Int, rng: Random): IntArray {
        val rank = claimTile % 9
        val options = ArrayList<IntArray>(3)
        if (rank >= 2) options += intArrayOf(claimTile - 2, claimTile - 1)
        if (rank in 1..7) options += intArrayOf(claimTile - 1, claimTile + 1)
        if (rank <= 6) options += intArrayOf(claimTile + 1, claimTile + 2)
        check(options.isNotEmpty()) { "no chow shape for $claimTile" }
        return options[rng.nextInt(options.size)]
    }

    /** Same-suit tiles that can join [claimTile] in a chow, excluding itself. */
    private fun chowPartners(claimTile: Int): List<Int> {
        val rank = claimTile % 9
        val out = ArrayList<Int>(4)
        if (rank >= 1) out += claimTile - 1
        if (rank >= 2) out += claimTile - 2
        if (rank <= 7) out += claimTile + 1
        if (rank <= 6) out += claimTile + 2
        return out
            }
        }
