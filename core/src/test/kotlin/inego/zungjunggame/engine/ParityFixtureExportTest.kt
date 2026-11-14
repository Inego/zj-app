package inego.zungjunggame.engine

import inego.zungjunggame.engines.DefaultEngine
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exports language-neutral JSON test fixtures to `fixtures/rules/`
 * for cross-language validation between Kotlin and Rust (`zj-rules`).
 *
 * That directory is **canonical**: the client is the behavioural reference and the only
 * side allowed to regenerate fixtures. The private `zj-server` work commits a copy of
 * these fixtures so its suite runs standalone; its fixture-sync check fails when the
 * copy drifts from this output, so a regeneration here must be accompanied by
 * re-syncing the server copy.
 */
class ParityFixtureExportTest {

    @Test
    fun exportAllParityFixtures() {
        val fixturesDir = File("../fixtures/rules").canonicalFile
        fixturesDir.mkdirs()
        assertTrue(fixturesDir.exists(), "Fixtures directory must exist: ${fixturesDir.absolutePath}")

        exportHuPatterns(File(fixturesDir, "hu_patterns.json"))
        exportReactions(File(fixturesDir, "reaction_resolution.json"))
        exportGameTraces(File(fixturesDir, "game_traces.json"))
    }

    private fun exportHuPatterns(outFile: File) {
        val cases = ArrayList<Map<String, Any?>>()

        fun addCase(
            name: String,
            spec: String,
            openChows: List<Int> = emptyList(),
            openPungs: List<Int> = emptyList(),
            openKongs: List<Int> = emptyList(),
            concealedKongs: List<Int> = emptyList(),
            playerIndex: Int = 0,
            eastSeat: Int = 0,
            allowChickenHands: Boolean = false,
            claimed: Int? = null,
        ) {
            val ps = parseHandSpec(spec)
            for (idx in openChows) ++ps.openChows[idx]
            for (t in openPungs) ps.pungsKongs[t] = PlayerState.PK_OPEN_PUNG
            for (t in openKongs) ps.pungsKongs[t] = PlayerState.PK_OPEN_KONG
            for (t in concealedKongs) ps.pungsKongs[t] = PlayerState.PK_CONCEALED_KONG
            // The state overload folds the claimed tile into `concealed` before scoring, so
            // the spec holds the thirteen tiles the seat already had.
            if (claimed != null) ++ps.concealed[claimed]

            val details = Scoring.computeHandScoreDetails(
                ps,
                playerIndex = playerIndex,
                eastSeat = eastSeat,
                allowChickenHands = allowChickenHands,
                claimedDiscard = claimed ?: -1,
            )

            val playerState = linkedMapOf<String, Any?>(
                "concealed" to ps.concealed.toList(),
                "open_chows" to ps.openChows.toList(),
                "pungs_kongs" to ps.pungsKongs.toList(),
            )
            // Only emit the key when it is meaningful, so self-draw cases stay byte-identical
            // to the fixtures recorded before series 4.2 looked at the claimed tile.
            if (claimed != null) playerState["claimed"] = claimed

            cases.add(
                mapOf(
                    "name" to name,
                    "player_state" to playerState,
                    "player_index" to playerIndex,
                    "east_seat" to eastSeat,
                    "allow_chicken_hands" to allowChickenHands,
                    "expected_total_score" to details.totalScore,
                    "expected_uncapped_total" to details.uncappedTotal,
                    "expected_patterns" to details.components.map { c ->
                        mapOf(
                            "id" to c.id,
                            "points" to c.points,
                            "format_args" to c.formatArgs.map { (it as Number).toLong() },
                        )
                    },
                ),
            )
        }

        // 1. Thirteen Terminals
        addCase(
            name = "Thirteen Terminals",
            spec = "11m 9m 1p 9p 1s 9s E S W N R G B",
            allowChickenHands = false,
        )

        // 2. Nine Gates in each suit
        addCase(
            name = "Nine Gates (Chars, wait 1m)",
            spec = "1111m 2345678m 999m",
        )
        addCase(
            name = "Nine Gates (Dots, wait 5p)",
            spec = "111p 23455678p 999p",
        )
        addCase(
            name = "Nine Gates (Bamboos, wait 9s)",
            spec = "111s 2345678s 9999s",
        )

        // 3. Seven Pairs variations
        addCase(
            name = "Seven Pairs Simple (No Terminals)",
            spec = "22m 33m 44m 55p 66p 77s 88s",
        )
        addCase(
            name = "Seven Pairs Pure One Suit",
            spec = "11m 22m 33m 44m 55m 77m 99m",
        )
        addCase(
            name = "Seven Pairs Mixed One Suit",
            spec = "11m 22m 33m 44m EE SS WW",
        )
        addCase(
            name = "Seven Pairs All Honors",
            spec = "EE SS WW NN RR GG BB",
        )
        addCase(
            name = "Seven Pairs Pure Greater Terminals",
            spec = "11m 99m 11p 99p 11s 99s 99m", // 4 of 9m
        )
        addCase(
            name = "Seven Pairs Mixed Greater Terminals",
            spec = "11m 99m 11p 99p EE SS WW",
        )

        // 4. Regular Hands: Sequences, Triplets, Honors
        addCase(
            name = "Concealed All Sequences No Terminals",
            spec = "234m 567m 234p 567p 88s",
        )
        addCase(
            name = "All Sequences Open",
            spec = "234m 567m 234p 88s",
            openChows = listOf(11), // 567p is chow index 1*7 + 4 = 11
        )
        addCase(
            name = "All Triplets Concealed",
            spec = "111m 222m 333m 444p 55s",
        )
        addCase(
            name = "All Triplets Open",
            spec = "111m 222m 333m 55s",
            openPungs = listOf(Tiles.DOT_4),
        )
        addCase(
            name = "Pure One Suit All Triplets",
            spec = "111m 222m 333m 444m 55m",
        )
        addCase(
            name = "Mixed One Suit All Sequences",
            spec = "123m 456m 789m EE 789m", // wait: 123m 456m 789m EE + 789m (4 sequences + pair EE)
        )
        addCase(
            name = "All Honors Regular Hand",
            spec = "EEE SSS WWW RRR GG",
        )

        // 5. Dragons
        addCase(
            name = "Big Three Dragons",
            spec = "RRR GGG BBB 123m 99p",
        )
        addCase(
            name = "Small Three Dragons",
            spec = "RRR GGG BB 123m 456p",
        )

        // 6. Winds
        addCase(
            name = "Big Four Winds",
            spec = "EEE SSS WWW NNN 99p",
        )
        addCase(
            name = "Small Four Winds",
            spec = "EEE SSS WWW NN 123m",
        )
        addCase(
            name = "Big Three Winds",
            spec = "EEE SSS WWW 123m 99p",
        )
        addCase(
            name = "Small Three Winds",
            spec = "EEE SSS WW 123m 456p",
        )

        // 7. Kongs
        addCase(
            name = "Four Kongs",
            spec = "99p",
            openKongs = listOf(Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3),
            concealedKongs = listOf(Tiles.CHAR_4),
        )
        addCase(
            name = "Three Kongs",
            spec = "123p 99s",
            openKongs = listOf(Tiles.CHAR_1, Tiles.CHAR_2),
            concealedKongs = listOf(Tiles.CHAR_3),
        )
        addCase(
            name = "Two Kongs",
            spec = "123p 456s 99m",
            openKongs = listOf(Tiles.CHAR_1),
            concealedKongs = listOf(Tiles.CHAR_2),
        )
        addCase(
            name = "One Kong",
            spec = "123p 456s 789m 99s",
            openKongs = listOf(Tiles.CHAR_1),
        )
        // A concealed kong is never exposed, so it must NOT break Concealed Hand (1.2);
        // an open kong of the same shape must. Both cases differ by exactly 5 points.
        addCase(
            name = "One Hidden Kong Keeps Concealed Hand",
            spec = "123m 456m 789m 55p",
            concealedKongs = listOf(Tiles.EAST),
        )
        addCase(
            name = "One Open Kong Breaks Concealed Hand",
            spec = "123m 456m 789m 55p",
            openKongs = listOf(Tiles.EAST),
        )

        // 8. Identical Sequences
        addCase(
            name = "Four Identical Sequences",
            spec = "123m 123m 123m 123m 99p",
        )
        addCase(
            name = "Three Identical Sequences",
            spec = "123m 123m 123m 456p 99s",
        )
        addCase(
            name = "Two Identical Sequences Twice",
            spec = "123m 123m 456p 456p 99s",
        )
        addCase(
            name = "Two Identical Sequences",
            spec = "123m 123m 456p 789s 99p",
        )

        // 9. Similar & Straight Sequences
        addCase(
            name = "Three Similar Sequences",
            spec = "123m 123p 123s 456m 99p",
        )
        addCase(
            name = "Nine Tile Straight",
            spec = "123m 456m 789m 123p 99s",
        )

        // 10. Consecutive & Similar Triplets
        addCase(
            name = "Four Consecutive Triplets",
            spec = "111m 222m 333m 444m 99p",
        )
        addCase(
            name = "Three Consecutive Triplets",
            spec = "222m 333m 444m 123p 99s",
        )
        addCase(
            name = "Three Similar Triplets",
            spec = "222m 222p 222s 123m 99p",
        )
        addCase(
            name = "Small Three Similar Triplets",
            spec = "222m 222p 123s 456s 22s",
        )

        // 11. Concealed Triplets
        addCase(
            name = "Four Concealed Triplets",
            spec = "111m 222m 333m 444p 55s",
        )
        addCase(
            name = "Three Concealed Triplets",
            spec = "111m 222m 333m 55s",
            openChows = listOf(0), // 123m open chow
        )
        addCase(
            name = "Two Concealed Triplets",
            spec = "111m 222m 55s",
            openChows = listOf(0, 1), // 123m, 234m open chows
        )
        // Series 4.2 needs all three tiles from the wall. The claimed winning tile is folded
        // into `concealed` before scoring, so a pung it completed must not be counted — while
        // a claim that completes a chow leaves every pung wall-built.
        addCase(
            name = "Discard Claim Completing a Pung Is Not Concealed",
            spec = "234m 11p 55s 678s 999s",
            claimed = Tiles.DOT_1,
        )
        addCase(
            name = "Discard Claim Completing a Chow Keeps Concealed Pungs",
            spec = "111m 777m 345p 78p 55s",
            claimed = Tiles.DOT_6,
        )
        addCase(
            name = "Self-Drawn Pung Stays Concealed",
            spec = "111m 777m 345p 678p 55s",
        )
        addCase(
            name = "Fourth Copy Claimed for a Chow Leaves the Pung Concealed",
            spec = "33345m 111p 678p 99s",
            claimed = Tiles.CHAR_3,
        )

        // 12. Terminals Group
        addCase(
            name = "Pure Greater Terminals",
            spec = "111m 999m 111p 999p 11s",
        )
        addCase(
            name = "Mixed Greater Terminals",
            spec = "111m 999m 111p EEE 99s",
        )
        addCase(
            name = "Pure Lesser Terminals",
            spec = "123m 789m 123p 789p 99s",
        )
        addCase(
            name = "Mixed Lesser Terminals",
            spec = "123m 789m 123p EEE 99s",
        )

        // 13. Value Honor Sets & Seat Wind
        addCase(
            name = "Value Honor Sets (East seat wind + Red dragon)",
            spec = "EEE RRR 123m 456p 88s",
            playerIndex = 0,
            eastSeat = 0, // Seat 0 is East
        )
        addCase(
            name = "Non-Dealer Seat Wind (West at Seat 2)",
            spec = "WWW 123m 456m 789m 88s",
            playerIndex = 2,
            eastSeat = 0, // Seat 2 is West
        )

        // 14. Pure Chicken Hand (allowChickenHands true vs false)
        // 456p, 234s, 789s, GG + open pung 111m: mixed suits, terminal 1m, chow+pung, open -> 0 patterns!
        addCase(
            name = "Pure Chicken Hand Allowed (1 pt)",
            spec = "456p 234s 789s GG",
            openPungs = listOf(Tiles.CHAR_1),
            allowChickenHands = true,
        )
        addCase(
            name = "Pure Chicken Hand Not Allowed (0 pt)",
            spec = "456p 234s 789s GG",
            openPungs = listOf(Tiles.CHAR_1),
            allowChickenHands = false,
        )

        outFile.writeText(toJson(cases))
    }

    private fun exportReactions(outFile: File) {
        val cases = listOf(
            mapOf(
                "name" to "Competing Hu, Pung, Chow: Hu wins",
                "source_seat" to 0,
                "submitted_choices" to listOf(
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Chow", "payload" to 0),
                    mapOf("kind" to "Pung", "payload" to 0),
                    mapOf("kind" to "Hu", "payload" to 30),
                ),
                "expected_resolution" to mapOf(
                    "type" to "Winning",
                    "seat" to 3,
                    "choice" to mapOf("kind" to "Hu", "payload" to 30),
                ),
            ),
            mapOf(
                "name" to "Competing Pung and Chow: Pung wins",
                "source_seat" to 0,
                "submitted_choices" to listOf(
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Chow", "payload" to 0),
                    mapOf("kind" to "Pung", "payload" to 0),
                    mapOf("kind" to "Pass", "payload" to 0),
                ),
                "expected_resolution" to mapOf(
                    "type" to "Winning",
                    "seat" to 2,
                    "choice" to mapOf("kind" to "Pung", "payload" to 0),
                ),
            ),
            mapOf(
                "name" to "Competing Kong and Chow: Kong wins",
                "source_seat" to 0,
                "submitted_choices" to listOf(
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Chow", "payload" to 0),
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Kong", "payload" to 0),
                ),
                "expected_resolution" to mapOf(
                    "type" to "Winning",
                    "seat" to 3,
                    "choice" to mapOf("kind" to "Kong", "payload" to 0),
                ),
            ),
            mapOf(
                "name" to "Two Pungs: closer seat counter-clockwise wins",
                "source_seat" to 0,
                "submitted_choices" to listOf(
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Pung", "payload" to 5),
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Pung", "payload" to 5),
                ),
                "expected_resolution" to mapOf(
                    "type" to "Winning",
                    "seat" to 1,
                    "choice" to mapOf("kind" to "Pung", "payload" to 5),
                ),
            ),
            mapOf(
                "name" to "Two Hu bids from West discard: North (seat 3) is closer than East (seat 0)",
                "source_seat" to 2,
                "submitted_choices" to listOf(
                    mapOf("kind" to "Hu", "payload" to 100),
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Hu", "payload" to 20),
                ),
                "expected_resolution" to mapOf(
                    "type" to "Winning",
                    "seat" to 3,
                    "choice" to mapOf("kind" to "Hu", "payload" to 20),
                ),
            ),
            mapOf(
                "name" to "All Pass",
                "source_seat" to 1,
                "submitted_choices" to listOf(
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Pass", "payload" to 0),
                    mapOf("kind" to "Pass", "payload" to 0),
                ),
                "expected_resolution" to mapOf(
                    "type" to "AllPass",
                ),
            ),
        )

        outFile.writeText(toJson(cases))
    }

    private fun exportGameTraces(outFile: File) {
        val traces = ArrayList<Map<String, Any?>>()

        // Record a trace using DefaultEngine on seed 42
        for (seed in listOf(42L, 999L)) {
            val rng = Random(seed)
            val wall = IntArray(WALL_SIZE) { it / 4 }
            for (i in WALL_SIZE - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = wall[i]
                wall[i] = wall[j]
                wall[j] = tmp
            }

            val state = GameState.fromWallOrder(wall, eastSeat = 0, allowChickenHands = true)
            val steps = ArrayList<Map<String, Any?>>()
            val engine = DefaultEngine()

            var stepCount = 0
            val choiceRng = Random(seed + 1)
            while (!state.isTerminal() && stepCount < 60) {
                val choices = state.enumerateChoices()
                if (choices.isEmpty()) break
                val decision = engine.select(state, choices, choiceRng)
                val selected = choices[decision.choiceIndex]

                steps.add(
                    mapOf<String, Any?>(
                        "step" to stepCount,
                        "current_player" to state.currentPlayer,
                        "decision_type" to state.decisionType.name,
                        "live_wall_size" to state.liveWallSize,
                        "last_discarder" to state.lastDiscarder,
                        "last_discarded" to state.lastDiscarded,
                        "applied_choice" to mapOf<String, Any?>(
                            "kind" to selected.kind.name,
                            "payload" to selected.payload,
                        ),
                    ),
                )

                state.applyChoice(selected)
                stepCount++
            }

            traces.add(
                mapOf<String, Any?>(
                    "name" to "Trace seed $seed",
                    "initial_wall" to wall.toList(),
                    "east_seat" to 0,
                    "allow_chicken_hands" to true,
                    "steps" to steps,
                    "final_state" to mapOf<String, Any?>(
                        "is_terminal" to state.isTerminal(),
                        "hand_score" to state.handScore,
                        "winner_seat" to state.winnerSeat,
                        "win_source" to state.winSource,
                        "score_deltas" to state.computeScoreDeltas().toList(),
                    ),
                ),
            )
        }

        outFile.writeText(toJson(traces))
    }

    private fun parseHandSpec(spec: String): PlayerState {
        val ps = PlayerState()
        val tokens = spec.trim().split("\\s+".toRegex())
        for (token in tokens) {
            if (token.isEmpty()) continue
            if (token.all { it.isLetter() }) {
                for (ch in token) ++ps.concealed[honorTile(ch)]
            } else {
                val suit = token.last().lowercaseChar()
                for (ch in token.dropLast(1)) {
                    ++ps.concealed[suitedTile(suit, ch - '0')]
                }
            }
        }
        return ps
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

    // Simple, dependency-free JSON serializer
    private fun toJson(value: Any?): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> {
                append('"')
                for (c in value) {
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
                append('"')
            }
            is Number, is Boolean -> append(value.toString())
            is Map<*, *> -> {
                append("{")
                var first = true
                for ((k, v) in value) {
                    if (!first) append(",")
                    appendJson(k.toString())
                    append(":")
                    appendJson(v)
                    first = false
                }
                append("}")
            }
            is List<*> -> {
                append("[")
                var first = true
                for (item in value) {
                    if (!first) append(",")
                    appendJson(item)
                    first = false
                }
                append("]")
            }
            is IntArray -> {
                append("[")
                append(value.joinToString(","))
                append("]")
            }
            else -> appendJson(value.toString())
        }
    }
}
