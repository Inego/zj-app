package inego.zungjunggame.platform

import kotlin.random.Random

/**
 * Seeded [Random] that works on TeaVM.
 *
 * [Random.Default] uses `ThreadLocalRandom`. Java's `ThreadLocalRandom.setSeed`
 * always throws [UnsupportedOperationException]; TeaVM's `Random` constructor
 * calls `setSeed`, so the first `Random.Default.nextInt()` crashes the web build.
 * Same pattern as [inego.zungjunggame.session.GameSession] (`Random(System.nanoTime())`).
 */
fun newRandom(): Random = Random(System.nanoTime())
