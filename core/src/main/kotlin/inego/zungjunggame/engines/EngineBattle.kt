package inego.zungjunggame.engines

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.GameplayLoop
import inego.zungjunggame.engine.NUM_PLAYERS
import java.util.Locale
import kotlin.random.Random
import kotlin.system.exitProcess

/** Winner of a single multi-round 2v2 battle game (highest-scoring seat). */
enum class BattleWinner {
    ENGINE_A,
    ENGINE_B,
    TIE,
}

/**
 * Immutable outcome of one completed battle game.
 *
 * Produced by [EngineBattle.playMatchGame] with no shared mutable state so
 * games can run sequentially or across worker threads without coordination.
 */
data class BattleGameOutcome(
    val winner: BattleWinner,
    val scoreSumA: Int,
    val scoreSumB: Int,
)

/**
 * Associative tally of battle games. A future parallel runner can accumulate one
 * tally per worker and merge them with [plus].
 */
data class BattleTally(
    val games: Int = 0,
    val winsA: Int = 0,
    val winsB: Int = 0,
    val ties: Int = 0,
    val totalScoreA: Long = 0L,
    val totalScoreB: Long = 0L,
) {
    fun record(outcome: BattleGameOutcome): BattleTally = BattleTally(
        games = games + 1,
        winsA = winsA + if (outcome.winner == BattleWinner.ENGINE_A) 1 else 0,
        winsB = winsB + if (outcome.winner == BattleWinner.ENGINE_B) 1 else 0,
        ties = ties + if (outcome.winner == BattleWinner.TIE) 1 else 0,
        totalScoreA = totalScoreA + outcome.scoreSumA,
        totalScoreB = totalScoreB + outcome.scoreSumB,
    )

    operator fun plus(other: BattleTally): BattleTally = BattleTally(
        games = games + other.games,
        winsA = winsA + other.winsA,
        winsB = winsB + other.winsB,
        ties = ties + other.ties,
        totalScoreA = totalScoreA + other.totalScoreA,
        totalScoreB = totalScoreB + other.totalScoreB,
    )
}

/**
 * Headless 2v2 engine battle runner and Gradle CLI entrypoint.
 *
 * Each game seats two copies of engine A and two copies of engine B in a
 * uniformly shuffled seating, plays `rounds * 4` hands with East rotating each
 * hand, and awards the game to whichever engine holds the highest-scoring seat
 * (or [BattleWinner.TIE] if an A seat and a B seat tie for first).
 *
 * Patternless (chicken) hands are always allowed: new players never play the
 * strict hand, so every battle is measured under the novice ruleset.
 *
 * Parallelism design: each game's RNG streams are derived deterministically from
 * `(seed, gameIndex)`, and each worker owns cloned [PlayingEngine] instances
 * (`PlayingEngine.clone()`). Currently single-threaded; parallelising is a
 * drop-in partition of `0 until games` across workers reducing [BattleTally].
 */
object EngineBattle {

    /** Named engine entry in the CLI registry. */
    data class EngineSpec(
        val id: String,
        val aliases: Set<String> = emptySet(),
        val factory: () -> PlayingEngine,
    )

    val REGISTRY: List<EngineSpec> = listOf(
        EngineSpec(
            id = "default",
            aliases = setOf("standard"),
            factory = { DefaultEngine() },
        ),
        EngineSpec(
            id = "greedy-meld",
            aliases = setOf("beginner"),
            factory = { GreedyMeldEngine() },
        ),
        EngineSpec(
            id = "pair-hunter",
            aliases = setOf("novice"),
            factory = { PairHunterEngine() },
        ),
    )

    fun resolveEngine(name: String): EngineSpec? {
        val key = name.trim().lowercase(Locale.ROOT)
        return REGISTRY.firstOrNull { spec ->
            spec.id == key || key in spec.aliases
        }
    }

    /**
     * Play one multi-round game with worker-local [engineA] and [engineB].
     *
     * Pure with respect to shared state: safe to call concurrently on distinct
     * engine clones.
     */
    fun playMatchGame(
        gameSeed: Long,
        engineA: PlayingEngine,
        engineB: PlayingEngine,
        rounds: Int,
    ): BattleGameOutcome {
        require(rounds > 0) { "rounds must be positive, got $rounds" }

        val seatRng = Random(mixSeed(gameSeed, SEAT_MIX))
        val seatToEngine = intArrayOf(0, 0, 1, 1)
        for (i in NUM_PLAYERS - 1 downTo 1) {
            val j = seatRng.nextInt(i + 1)
            val tmp = seatToEngine[i]
            seatToEngine[i] = seatToEngine[j]
            seatToEngine[j] = tmp
        }

        val engines = listOf(engineA, engineB)
        val totals = IntArray(NUM_PLAYERS)
        val totalHands = rounds * GameConfig.HANDS_PER_ROUND

        for (hand in 0 until totalHands) {
            val handSeed = mixSeed(gameSeed, HAND_MIX xor hand.toLong())
            val dealRng = Random(mixSeed(handSeed, DEAL_MIX))
            val handResult = GameplayLoop.playGame(
                engines = engines,
                seatToEngine = seatToEngine,
                initialRng = dealRng,
                eastSeat = hand % NUM_PLAYERS,
                // New players only ever play with patternless hands allowed.
                allowChickenHands = true,
            )
            for (seat in 0 until NUM_PLAYERS) {
                totals[seat] += handResult.scoreDeltas[seat]
            }
        }

        var maxScore = totals[0]
        for (seat in 1 until NUM_PLAYERS) {
            if (totals[seat] > maxScore) maxScore = totals[seat]
        }

        var aHasTop = false
        var bHasTop = false
        var sumA = 0
        var sumB = 0
        for (seat in 0 until NUM_PLAYERS) {
            if (seatToEngine[seat] == 0) {
                sumA += totals[seat]
                if (totals[seat] == maxScore) aHasTop = true
            } else {
                sumB += totals[seat]
                if (totals[seat] == maxScore) bHasTop = true
            }
        }

        val winner = when {
            aHasTop && !bHasTop -> BattleWinner.ENGINE_A
            bHasTop && !aHasTop -> BattleWinner.ENGINE_B
            else -> BattleWinner.TIE
        }
        return BattleGameOutcome(
            winner = winner,
            scoreSumA = sumA,
            scoreSumB = sumB,
        )
    }

    /**
     * Run [games] independent multi-round games between [templateA] and
     * [templateB]. Clones the templates for the worker so the caller's instances
     * are untouched and worker isolation matches a future parallel runner.
     *
     * [onProgress] is called after each completed game with the number finished
     * so far and the running [BattleTally]. It is the presentation seam: the
     * runner never prints, so a parallel driver can report the same way from its
     * own aggregate while workers stay silent.
     */
    fun runBattle(
        templateA: PlayingEngine,
        templateB: PlayingEngine,
        games: Int,
        rounds: Int,
        seed: Long = System.nanoTime(),
        onProgress: ((completedGames: Int, tally: BattleTally) -> Unit)? = null,
    ): BattleTally {
        require(games > 0) { "games must be positive, got $games" }
        require(rounds > 0) { "rounds must be positive, got $rounds" }

        val workerA = templateA.clone()
        val workerB = templateB.clone()
        var tally = BattleTally()
        for (gameIndex in 0 until games) {
            val gameSeed = mixSeed(seed, gameIndex.toLong())
            val outcome = playMatchGame(
                gameSeed = gameSeed,
                engineA = workerA,
                engineB = workerB,
                rounds = rounds,
            )
            tally = tally.record(outcome)
            onProgress?.invoke(gameIndex + 1, tally)
        }
        return tally
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.any { it == "--help" || it == "-h" }) {
            printUsage()
            return
        }
        val parsed = parseArgs(args) ?: run {
            printUsage()
            exitProcess(1)
        }

        val specA = resolveEngine(parsed.engineA) ?: fail("Unknown engine A: '${parsed.engineA}'")
        val specB = resolveEngine(parsed.engineB) ?: fail("Unknown engine B: '${parsed.engineB}'")
        if (specA.id == specB.id) {
            fail("Engines A and B must be different (both resolved to '${specA.id}').")
        }

        val engineA = specA.factory()
        val engineB = specB.factory()
        val handsPerGame = parsed.rounds * GameConfig.HANDS_PER_ROUND

        // Header before the first game, so a long run answers "did it start?"
        // immediately instead of looking hung for a minute.
        println(
            "Battle: A=${specA.id} (${engineA.name()}) vs B=${specB.id} (${engineB.name()})",
        )
        println(
            "Config: games=${parsed.games}, rounds=${parsed.rounds} ($handsPerGame hands/game), " +
                "chicken=true, seed=${parsed.seed}",
        )

        val startNanos = System.nanoTime()
        var lastPrintNanos = startNanos
        val tally = runBattle(
            templateA = engineA,
            templateB = engineB,
            games = parsed.games,
            rounds = parsed.rounds,
            seed = parsed.seed,
        ) { completed, running ->
            val now = System.nanoTime()
            // Throttle by wall time, so one game per line never floods a 1000-game
            // run but a slow run still reports at least once a second.
            if (now - lastPrintNanos >= PROGRESS_INTERVAL_NANOS) {
                lastPrintNanos = now
                val elapsed = (now - startNanos) / 1_000_000_000.0
                println(progressLine(completed, parsed.games, running, elapsed))
            }
        }
        val elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000.0

        val pctA = 100.0 * tally.winsA / tally.games
        val pctB = 100.0 * tally.winsB / tally.games
        val pctTie = 100.0 * tally.ties / tally.games

        println(
            String.format(
                Locale.US,
                "A (%s): %d / %d (%.2f%%)",
                specA.id,
                tally.winsA,
                tally.games,
                pctA,
            ),
        )
        println(
            String.format(
                Locale.US,
                "B (%s): %d / %d (%.2f%%)",
                specB.id,
                tally.winsB,
                tally.games,
                pctB,
            ),
        )
        println(
            String.format(
                Locale.US,
                "Ties: %d / %d (%.2f%%)",
                tally.ties,
                tally.games,
                pctTie,
            ),
        )
        println(
            String.format(
                Locale.US,
                "Elapsed: %.2fs (%.1f games/s, %.0f hands/s)",
                elapsedSec,
                tally.games / elapsedSec,
                tally.games * handsPerGame / elapsedSec,
            ),
        )
    }

    /**
     * One progress line: completion, running win shares, throughput, and ETA.
     * Percentages are of the games *finished so far*, so an early reading is
     * noisy by nature — the final summary is the result.
     */
    private fun progressLine(
        completed: Int,
        games: Int,
        tally: BattleTally,
        elapsedSec: Double,
    ): String {
        val gamesPerSec = if (elapsedSec > 0.0) completed / elapsedSec else 0.0
        val etaSec = if (gamesPerSec > 0.0) (games - completed) / gamesPerSec else Double.POSITIVE_INFINITY
        return String.format(
            Locale.US,
            "[%3.0f%%] %d/%d games | A %d (%.1f%%) B %d (%.1f%%) T %d | %.1f games/s | ETA %s",
            100.0 * completed / games,
            completed,
            games,
            tally.winsA,
            100.0 * tally.winsA / completed,
            tally.winsB,
            100.0 * tally.winsB / completed,
            tally.ties,
            gamesPerSec,
            formatEta(etaSec),
        )
    }

    private fun formatEta(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0.0) return "?"
        val whole = seconds.toLong()
        return if (whole < 60L) {
            String.format(Locale.US, "%.0fs", seconds)
        } else {
            String.format(Locale.US, "%dm %02ds", whole / 60L, whole % 60L)
        }
    }

    private data class CliArgs(
        val engineA: String,
        val engineB: String,
        val games: Int,
        val rounds: Int,
        val seed: Long,
    )

    private fun parseArgs(args: Array<String>): CliArgs? {
        val positional = ArrayList<String>(4)
        var seed: Long? = null

        for (arg in args) {
            when {
                arg.startsWith("--seed=") -> {
                    val raw = arg.removePrefix("--seed=")
                    seed = raw.toLongOrNull() ?: fail("Invalid --seed value: '$raw'")
                }
                arg.startsWith("-") -> fail("Unknown option: '$arg'")
                else -> positional += arg
            }
        }

        if (positional.size != 4) return null
        val games = positional[2].toIntOrNull()
            ?.takeIf { it > 0 }
            ?: fail("games must be a positive integer, got '${positional[2]}'")
        val rounds = positional[3].toIntOrNull()
            ?.takeIf { it > 0 }
            ?: fail("rounds must be a positive integer, got '${positional[3]}'")

        return CliArgs(
            engineA = positional[0],
            engineB = positional[1],
            games = games,
            rounds = rounds,
            seed = seed ?: System.nanoTime(),
        )
    }

    private fun fail(message: String): Nothing {
        System.err.println("Error: $message")
        printUsage()
        exitProcess(1)
    }

    private fun printUsage() {
        val enginesList = REGISTRY.joinToString(", ") { spec ->
            if (spec.aliases.isEmpty()) spec.id else "${spec.id} (aliases: ${spec.aliases.joinToString("/")})"
        }
        System.err.println(
            """
            Usage:
              ./gradlew :core:engineBattle --args="<engineA> <engineB> <games> <rounds> [--seed=N]"

            Battles always allow patternless (chicken) hands, matching novice play.
            Available engines: $enginesList
            Example:
              ./gradlew :core:engineBattle --args="novice default 100 4"
            """.trimIndent(),
        )
    }

    /** SplitMix64 avalanche to derive independent per-game / per-hand seeds. */
    private fun mixSeed(base: Long, salt: Long): Long {
        var z = base + salt * -7046029254386353131L + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    private const val SEAT_MIX: Long = 0x517CC1B727220A95L
    private const val HAND_MIX: Long = 0x2545F4914F6CDD1DL
    private const val DEAL_MIX: Long = -7046029254386353131L

    /** Wall-clock throttle between progress lines (the summary is always printed). */
    private const val PROGRESS_INTERVAL_NANOS: Long = 1_000_000_000L
}
