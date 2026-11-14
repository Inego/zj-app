package inego.zungjunggame.render.wallbuild

import kotlin.math.PI

/**
 * Count (accent) and take-7 (orange) stack-reveal overlays for the wall-build
 * diagram. [WallBuildView] owns dice / wall mapping; this tracks reveal
 * progress and which tiles currently share a highlight outline.
 */
internal class WallBuildStackHighlights {

    companion object {
        /** Seconds between revealing each counted stack (right → left). */
        const val COUNT_STACK_REVEAL_SEC = 0.16f
        /** Seconds between revealing each take-7 stack (gap → right / South). */
        const val TAKE7_STACK_REVEAL_SEC = 0.16f
        private const val PULSE_PERIOD_SEC = 1.25f
    }

    var showCount: Boolean = false
        private set
    /** Dice sum (2..12) once known; 0 until dice have finished. */
    var countStacksTarget: Int = 0
        private set
    var countStacksRevealed: Int = 0
        private set
    var countPulsePhase: Float = 0f
        private set
    private var countRevealElapsed = 0f

    var showTake7: Boolean = false
        private set
    var take7StacksRevealed: Int = 0
        private set
    var take7PulsePhase: Float = 0f
        private set
    private var take7RevealElapsed = 0f

    fun clearCount() {
        showCount = false
        countStacksTarget = 0
        countStacksRevealed = 0
        countRevealElapsed = 0f
        countPulsePhase = 0f
    }

    fun beginCount(stackCountOrZero: Int) {
        countRevealElapsed = 0f
        countPulsePhase = 0f
        countStacksTarget = stackCountOrZero
        countStacksRevealed = 0
        showCount = true
    }

    fun clearTake7() {
        showTake7 = false
        take7StacksRevealed = 0
        take7RevealElapsed = 0f
        take7PulsePhase = 0f
    }

    fun beginTake7Reveal() {
        take7RevealElapsed = 0f
        take7PulsePhase = 0f
        take7StacksRevealed = 0
        showTake7 = true
    }

    /** All 7 lit immediately (dead / dead_out). */
    fun setTake7Full() {
        showTake7 = true
        take7StacksRevealed = WallSlotLayout.TAKE7_STACKS
    }

    /**
     * After dice rest, take their sum and reveal that many stacks from the right
     * end of East’s wall (alongI 16 → leftward), one stack at a time.
     */
    fun updateCount(dtSec: Float, diceAtRest: Boolean, diceSum: Int) {
        countPulsePhase =
            (countPulsePhase + dtSec * (2f * PI.toFloat() / PULSE_PERIOD_SEC)) %
                (2f * PI.toFloat())
        if (countStacksTarget <= 0) {
            if (!diceAtRest) return
            countStacksTarget = diceSum.coerceIn(2, 12)
            countStacksRevealed = 0
            countRevealElapsed = 0f
        }
        if (countStacksRevealed >= countStacksTarget) return
        countRevealElapsed += dtSec
        val next = (countRevealElapsed / COUNT_STACK_REVEAL_SEC).toInt()
            .coerceIn(0, countStacksTarget)
        countStacksRevealed = next
    }

    /**
     * After the gap is open, reveal [WallSlotLayout.TAKE7_STACKS] stacks from
     * the gap’s right edge rightward (East remainder, then South).
     */
    fun updateTake7(dtSec: Float, breakStackCount: Int) {
        take7PulsePhase =
            (take7PulsePhase + dtSec * (2f * PI.toFloat() / PULSE_PERIOD_SEC)) %
                (2f * PI.toFloat())
        if (breakStackCount <= 0) return
        if (take7StacksRevealed >= WallSlotLayout.TAKE7_STACKS) return
        take7RevealElapsed += dtSec
        val next = (take7RevealElapsed / TAKE7_STACK_REVEAL_SEC).toInt()
            .coerceIn(0, WallSlotLayout.TAKE7_STACKS)
        take7StacksRevealed = next
    }

    /** Whether [tileIndex] is in one of the currently revealed count stacks. */
    fun isCountHighlighted(tileIndex: Int, tileInSlot: (Int) -> Int): Boolean {
        if (!showCount || countStacksRevealed <= 0) return false
        for (k in 0 until countStacksRevealed) {
            val alongI = (WallSlotLayout.STACKS_PER_SIDE - 1) - k
            for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
                val slot = WallSlotLayout.slotId(WallSlotLayout.EAST_SIDE, alongI, floor)
                if (tileInSlot(slot) == tileIndex) return true
            }
        }
        return false
    }

    /** Whether [tileIndex] is in one of the currently revealed take-7 stacks. */
    fun isTake7Highlighted(
        tileIndex: Int,
        breakStackCount: Int,
        tileInSlot: (Int) -> Int,
    ): Boolean {
        if (!showTake7 || take7StacksRevealed <= 0) return false
        if (breakStackCount <= 0) return false
        for (k in 0 until take7StacksRevealed) {
            val sideAlong = WallSlotLayout.take7Stack(breakStackCount, k) ?: continue
            val (side, alongI) = sideAlong
            for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
                val slot = WallSlotLayout.slotId(side, alongI, floor)
                if (tileInSlot(slot) == tileIndex) return true
            }
        }
        return false
    }
}
