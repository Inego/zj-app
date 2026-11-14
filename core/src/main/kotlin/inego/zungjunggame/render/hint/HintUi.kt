package inego.zungjunggame.render.hint

import inego.zungjunggame.engine.Choice

/**
 * On-table HINT lifecycle (H / HINT button).
 *
 * Lives off [inego.zungjunggame.engines.PlayingEngine.rank] everywhere; on
 * platforms where MC playouts run (desktop, see `platform.McHints`) it is
 * refined by UCB flat Monte Carlo (see `hints.UcbFlatMcHintSearch`):
 * - [Off]: no highlight / no MC search
 * - First press: classic [inego.zungjunggame.engines.PlayingEngine.rank] co-best
 *   until the first ~1 s MC refresh (and for good, where MC is off)
 * - Then: highlight best arm(s) by average score delta; labels show avgΔ / n
 * - [Active]: unique recommended choice; second press plays it
 * - [Exhausted]: multiple co-best / tied best avgΔ (button greys);
 *   further HINT is inert until the turn changes (player must choose)
 *
 * Highlights stay visible while [Active] or [Exhausted]. Cleared when the human
 * acts or legality drops to empty. Per-option MC labels live on [McHintLabels]
 * and appear/refresh about once per second (desktop only).
 */
sealed class HintUi {
    data object Off : HintUi()

    /** Unique best arm; second HINT plays it. */
    data class Active(val choices: List<Choice>) : HintUi() {
        init {
            require(choices.isNotEmpty()) { "Active hint needs at least one choice" }
        }
    }

    /** Multi-way best avgΔ: highlights stay, button disabled. */
    data class Exhausted(val choices: List<Choice>) : HintUi() {
        init {
            require(choices.isNotEmpty()) { "Exhausted hint needs at least one choice" }
        }
    }

    /** Choices still drawn as recommended (Active or Exhausted). */
    fun highlightedChoices(): List<Choice> = when (this) {
        Off -> emptyList()
        is Active -> choices
        is Exhausted -> choices
    }
}
