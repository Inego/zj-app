package inego.zungjunggame.session.board

import inego.zungjunggame.platform.newRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How long local play pretends a seat took to answer.
 *
 * Two beats: one seat's "can I take this?" glance ([dwellMs]) and the longer
 * claim-versus-pass decision ([thinkMs]). Both are normal samples, clamped at
 * zero. The stepper decides which seats spend which beat, and takes the slowest
 * glance when nobody can take the tile.
 */
class BotTiming(
    dwellSample: (() -> Long)? = null,
    thinkSample: (() -> Long)? = null,
) {
    /**
     * This instance's noise source. Seeded, never [Random.Default]: the latter is
     * `ThreadLocalRandom`, whose `setSeed` throws on TeaVM and kills the web build
     * on the first sampled beat.
     */
    private val rng: Random = newRandom()

    private val dwellSample: () -> Long =
        dwellSample ?: { normal(rng, DWELL_MEAN_MS, DWELL_STDDEV_MS) }

    private val thinkSample: () -> Long =
        thinkSample ?: { normal(rng, THINK_MEAN_MS, THINK_STDDEV_MS) }

    /** One seat's "can I take this?" glance - the answer is no. */
    fun dwellMs(): Long = dwellSample().coerceAtLeast(0L)

    /** Claim or pass (and which claim); also a bot's own discard. */
    fun thinkMs(): Long = thinkSample().coerceAtLeast(0L)

    companion object {
        /** Local play only; tune here. */
        const val DWELL_MEAN_MS = 700L
        const val DWELL_STDDEV_MS = 200.0
        const val THINK_MEAN_MS = 1000L
        const val THINK_STDDEV_MS = 250.0

        /**
         * One normal sample in ms. Box-Muller over two uniforms; no RNG state
         * is shared with bot tie-breaking.
         */
        private fun normal(rng: Random, meanMs: Long, stdDevMs: Double): Long {
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            val u2 = rng.nextDouble()
            val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
            return (meanMs + z * stdDevMs).roundToLong()
        }
    }
}
