package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    @Test
    fun chickenHandScoresOne() {
        // 123456789m 123p 11s — all sequences, open? fully concealed chicken with
        // all sequences + concealed + no terminals?  has terminals (1,9).
        // Use a pure chicken: 234567m 234p 234s 55z  -> wait honors.
        // 234m 567m 234p 567p 88s
        val ps = hand("234m 567m 234p 567p 88s")
        val details = Scoring.computeHandScoreDetails(ps, playerIndex = 0)
        assertTrue(details.totalScore > 0, "expected winning hand, got 0")
        // Concealed Hand 5 + All Sequences 5 + No Terminals 5 = 15
        assertEquals(15, details.totalScore)
    }

    @Test
    fun sevenPairsBaseScore() {
        val ps = hand("11m 22m 33m 44p 55p 66s 77s")
        val details = Scoring.computeHandScoreDetails(ps, playerIndex = 0)
        assertTrue(details.totalScore >= 30)
        assertTrue(details.components.any { it.id == PatternIds.SEVEN_PAIRS })
    }

    @Test
    fun thirteenTerminals() {
        val ps = PlayerState()
        // 13 terminals/honors with one pair (1m)
        val tiles = intArrayOf(
            0, 0, // 1m pair
            8, // 9m
            9, // 1p
            17, // 9p
            18, // 1s
            26, // 9s
            27, 28, 29, 30, // winds
            31, 32, 33, // dragons
        )
        for (t in tiles) ++ps.concealed[t]
        val details = Scoring.computeHandScoreDetails(ps, playerIndex = 0)
        assertEquals(160, details.totalScore)
        assertEquals(PatternIds.THIRTEEN_TERMINALS, details.components.single().id)
    }

    @Test
    fun allTriplets() {
        // 111m 222m 333m 444p 55s
        val ps = hand("111m 222m 333m 444p 55s")
        val details = Scoring.computeHandScoreDetails(ps, playerIndex = 0)
        assertTrue(details.components.any { it.id == PatternIds.ALL_TRIPLETS })
        assertTrue(details.totalScore >= 30)
    }

    // --- Series 4.2: a pung completed by the claimed discard is exposed ---------

    /** A discard-reaction state whose winning tile is [claimed]. */
    private fun claimState(claimed: Int): GameState {
        val state = GameState()
        state.liveWallSize = 20 // not the final discard: keeps 9.x incidentals out
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = claimed
        return state
    }

    private fun deal(state: GameState, seat: Int, spec: String) {
        hand(spec).concealed.copyInto(state.players[seat].concealed)
    }

    private fun concealedTripletIds(details: HandScoreDetails): List<String> =
        details.components.map { it.id }.filter { it.startsWith(PatternIds.CONCEALED_TRIPLETS) }

    @Test
    fun discardClaimCompletingAPungIsNotAConcealedTriplet() {
        // 234m 11p 55s 678s 999s, winning on 1p. The claim builds 111p, so only 999s is
        // still a triplet built entirely from the wall: series 4.2 owes nothing and the
        // hand is worth Concealed Hand alone.
        val state = claimState(Tiles.DOT_1)
        deal(state, seat = 1, spec = "234m 11p 55s 678s 999s")

        val details = Scoring.computeHandScoreDetails(state, winner = 1, includeClaimedDiscard = true)

        assertEquals(emptyList(), concealedTripletIds(details), "components: ${details.components}")
        assertTrue(details.components.any { it.id == PatternIds.CONCEALED_HAND })
        assertEquals(5, details.totalScore, "components: ${details.components}")
    }

    @Test
    fun discardClaimCompletingAChowKeepsTheWallBuiltTriplets() {
        // 111m 777m 345p 78p 55s, winning on 6p. The claim completes 678p, so both pungs
        // came from the wall and Two Concealed Triplets still scores.
        val state = claimState(Tiles.DOT_6)
        deal(state, seat = 1, spec = "111m 777m 345p 78p 55s")

        val details = Scoring.computeHandScoreDetails(state, winner = 1, includeClaimedDiscard = true)

        assertEquals(listOf(PatternIds.TWO_CONCEALED_TRIPLETS), concealedTripletIds(details))
        assertEquals(10, details.totalScore, "components: ${details.components}")
    }

    @Test
    fun selfDrawKeepsTheWallBuiltTriplets() {
        // The same shape drawn from the wall: nothing is claimed, so 4.2 is untouched.
        val ps = hand("111m 777m 345p 678p 55s")

        val details = Scoring.computeHandScoreDetails(
            ps,
            playerIndex = 0,
            allowChickenHands = false,
        )

        assertEquals(listOf(PatternIds.TWO_CONCEALED_TRIPLETS), concealedTripletIds(details))
        assertEquals(10, details.totalScore, "components: ${details.components}")
    }

    @Test
    fun claimedFourthCopyTakesNothingAwayFromThePung() {
        // 33345m 111p 678p 99s, winning on 3m. The claim completes the 345m chow out of the
        // fourth 3m, leaving three wall tiles for the 333m pung, so both pungs still count.
        val state = claimState(Tiles.CHAR_3)
        deal(state, seat = 1, spec = "33345m 111p 678p 99s")

        val details = Scoring.computeHandScoreDetails(state, winner = 1, includeClaimedDiscard = true)

        assertEquals(listOf(PatternIds.TWO_CONCEALED_TRIPLETS), concealedTripletIds(details))
        assertEquals(10, details.totalScore, "components: ${details.components}")
    }

    private fun hand(spec: String): PlayerState {
        val ps = PlayerState()
        val token = StringBuilder()
        fun flush() {
            if (token.isEmpty()) return
            for (tile in parseToken(token.toString())) ++ps.concealed[tile]
            token.clear()
        }
        for (ch in spec) {
            if (ch.isWhitespace()) {
                flush()
            } else {
                token.append(ch)
            }
        }
        flush()
        return ps
    }

    private fun parseToken(token: String): List<Int> {
        require(token.length >= 2)
        val allLetters = token.all { it.isLetter() }
        if (allLetters) {
            return token.map { honorTile(it) }
        }
        val suit = token.last().lowercaseChar()
        return token.dropLast(1).map { ch ->
            require(ch in '1'..'9')
            suitedTile(suit, ch - '0')
        }
    }

    private fun suitedTile(suit: Char, rank: Int): Int = when (suit) {
        'm', 'c' -> Tiles.CHAR_1 + (rank - 1)
        'p', 'd' -> Tiles.DOT_1 + (rank - 1)
        's', 'b' -> Tiles.BAMBOO_1 + (rank - 1)
        else -> error("bad suit $suit")
    }

    private fun honorTile(ch: Char): Int = when (ch.uppercaseChar()) {
        'E' -> Tiles.EAST
        'S' -> Tiles.SOUTH
        'W' -> Tiles.WEST
        'N' -> Tiles.NORTH
        'R' -> Tiles.RED
        'G' -> Tiles.GREEN
        'B' -> Tiles.WHITE
        else -> error("bad honor $ch")
    }
}
