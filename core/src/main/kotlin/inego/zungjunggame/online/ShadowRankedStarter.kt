package inego.zungjunggame.online

import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.net.ChallengeRounds
import inego.zungjunggame.net.ChallengeStart
import inego.zungjunggame.net.ShadowRankedStartClient
import inego.zungjunggame.net.ShadowRankedStartFailure
import inego.zungjunggame.net.ShadowRankedStartOutcome

/**
 * The draft a fresh Shadow Ranked start holds before the player picks: the
 * shortest ranked axis, no chicken. Shared by the hub's start reading and its
 * rounds/chicken controls, so an unedited and a first-edited draft agree.
 */
internal val DEFAULT_SHADOW_RANKED_DRAFT: GameConfig =
    GameConfig(rounds = 1, allowChickenHands = false)

/** The explicit states of one Shadow Ranked start lifecycle. */
sealed interface ShadowRankedStartState {
    data object Idle : ShadowRankedStartState
    data object Starting : ShadowRankedStartState
    data class Started(val start: ChallengeStart) : ShadowRankedStartState
    data class Failed(val failure: ShadowRankedStartFailure) : ShadowRankedStartState
}

/**
 * Owns the Shadow Ranked start lifecycle: `Idle → Starting → (Started | Failed)`
 * and back to [ShadowRankedStartState.Idle] on [cancel]/[reset]. Seams arrive
 * through the constructor: the current access [token], the [client], and an
 * [onStarted] callback the effect uses to enter the table.
 *
 * The client owns the token-null decision ([ShadowRankedStartFailure.NoSession]),
 * so the starter only drives the state machine around it. [cancel] abandons an
 * in-flight start and drops its late response; [retry] replays the remembered
 * draft and only from [ShadowRankedStartState.Failed].
 */
class ShadowRankedStarter(
    private val token: () -> String?,
    private val client: ShadowRankedStartClient = ShadowRankedStartClient(),
    private val onStarted: (ChallengeStart) -> Unit = {},
) : Disposable {
    private var state: ShadowRankedStartState = ShadowRankedStartState.Idle
    private var lastConfig: GameConfig? = null
    private var lastFastBots: Boolean = false
    private var generation = 0

    fun snapshot(): ShadowRankedStartState = state

    fun start(config: GameConfig, fastBots: Boolean = false) {
        if (state is ShadowRankedStartState.Starting) return
        lastConfig = config
        lastFastBots = fastBots
        val gen = ++generation
        state = ShadowRankedStartState.Starting
        client.start(
            token = token(),
            rounds = config.rounds.toChallengeRounds(),
            allowChicken = config.allowChickenHands,
            fastBots = fastBots,
        ) { outcome ->
            if (gen != generation) return@start
            when (outcome) {
                is ShadowRankedStartOutcome.Started -> {
                    state = ShadowRankedStartState.Started(outcome.start)
                    onStarted(outcome.start)
                }
                is ShadowRankedStartOutcome.Failed ->
                    state = ShadowRankedStartState.Failed(outcome.failure)
            }
        }
    }

    /** Abandon an in-flight start; a response that lands later is ignored. */
    fun cancel() {
        if (state !is ShadowRankedStartState.Starting) return
        generation++
        state = ShadowRankedStartState.Idle
    }

    /** Replay the remembered draft. Only a failure can be retried. */
    fun retry(fastBots: Boolean? = null) {
        if (state !is ShadowRankedStartState.Failed) return
        val config = lastConfig ?: return
        start(config, fastBots = fastBots ?: lastFastBots)
    }

    /** Log-out path: drop any in-flight start and the remembered draft. */
    fun reset() {
        generation++
        lastConfig = null
        lastFastBots = false
        state = ShadowRankedStartState.Idle
    }

    /** Scope close: drop a late response exactly as [reset] does. */
    override fun dispose() = reset()
}

/**
 * Shadow Ranked's wire axis is exactly one or two rounds, so the draft's
 * `1 → [ChallengeRounds.One]` and `2 → [ChallengeRounds.Two]`. The local 4-round
 * default and unlimited are not Shadow Ranked choices and map to
 * [ChallengeRounds.One]; the hub's start section only offers the two.
 */
internal fun Int?.toChallengeRounds(): ChallengeRounds =
    if (this == 2) ChallengeRounds.Two else ChallengeRounds.One
