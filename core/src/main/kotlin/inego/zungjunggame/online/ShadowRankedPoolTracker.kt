package inego.zungjunggame.online

import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.net.ChallengeRounds
import inego.zungjunggame.net.ShadowRankedPoolClient
import inego.zungjunggame.net.ShadowRankedPoolCounts
import inego.zungjunggame.net.ShadowRankedPoolOutcome

/**
 * The Shadow Ranked tab's pool reading: the last answer for the signed-in account,
 * in memory only.
 *
 * The counts are **not** route state. They change with other players' actions and
 * with time, so restoring them after a restart would display a fact that may be
 * hours old; this tracker is rebuilt empty with the process.
 *
 * A draft change keeps the previous line on screen until the new answer lands, and
 * the line is then corrected in place — the row under the controls must not blank
 * for a round trip. A read that fails clears it: those numbers belonged to the
 * draft the player just left, and showing them as the new draft's would be worse
 * than showing none. An unknown count is never `0`.
 *
 * Every read carries a generation and only the newest may apply, so `A → B → A`
 * with the first A landing last cannot overwrite the second A. The answer records
 * the account it was read for, so another account's counts can never paint.
 * [clear] drops the answer and every in-flight response, which is the sign-out /
 * account-switch path.
 */
class ShadowRankedPoolTracker(
    private val client: ShadowRankedPoolClient = ShadowRankedPoolClient(),
) : Disposable {
    private var applied: Pair<String, ShadowRankedPoolCounts>? = null
    private var generation = 0

    /**
     * The last counts read for [account], or null before the first answer and after
     * a failed read. The counts may belong to a draft the player has just changed
     * away from; they are replaced as soon as the new answer lands.
     */
    fun counts(account: String?): ShadowRankedPoolCounts? {
        if (account.isNullOrBlank()) return null
        return applied?.takeIf { it.first == account }?.second
    }

    /**
     * Read one draft. A null account or token clears the answer without sending
     * anything, and [generation] drops a response that is still in flight.
     */
    fun load(
        account: String?,
        token: String?,
        rounds: ChallengeRounds,
        allowChicken: Boolean,
    ) {
        if (account.isNullOrBlank() || token.isNullOrBlank()) {
            clear()
            return
        }
        val gen = ++generation
        client.load(token, rounds, allowChicken) { outcome ->
            if (gen == generation) {
                applied = when (outcome) {
                    is ShadowRankedPoolOutcome.Loaded -> account to outcome.counts
                    is ShadowRankedPoolOutcome.Failed -> null
                }
            }
        }
    }

    /** Log-out / account change: drop the answer and any response still in flight. */
    fun clear() {
        generation++
        applied = null
    }

    /** Scope close: drop a response still in flight, as [clear] does. */
    override fun dispose() = clear()
}
