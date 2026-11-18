package inego.zungjunggame.net

/** The two counts `GET /challenges/pool` answers for one draft. */
data class ShadowRankedPoolCounts(
    /** Challenges the server would consider handing this player right now. */
    val available: Int,
    /** The player's own challenges for this draft that are still open. */
    val createdOpen: Int,
)

/** The typed ways a Shadow Ranked pool read can fail. */
enum class ShadowRankedPoolFailure {
    Unauthorized,
    Unavailable,
    Network,
    Timeout,
}

/** The outcome of one Shadow Ranked pool read. */
sealed interface ShadowRankedPoolOutcome {
    /** The server answered: both counts are real, including a real zero. */
    data class Loaded(val counts: ShadowRankedPoolCounts) : ShadowRankedPoolOutcome

    /** The count is unknown; it is never read as zero. */
    data class Failed(val failure: ShadowRankedPoolFailure) : ShadowRankedPoolOutcome
}

/**
 * Reads the selected draft's pool count through `GET /challenges/pool`. The client
 * only maps the [ShadowRankedWireException] the wire already classified into
 * [ShadowRankedPoolFailure]; a rejected session is reported to the injected
 * [onUnauthorized] so the auth lifecycle stays with its owner.
 *
 * The wire owns the timeout: it classifies a request that outlived
 * [ShadowRankedWire.TIMEOUT_MS] as [ShadowRankedWireError.Timeout], which maps to
 * [ShadowRankedPoolFailure.Timeout] here. A `429` or `5xx` is
 * [ShadowRankedPoolFailure.Unavailable]; any other transport failure is
 * [ShadowRankedPoolFailure.Network]. There is no retry: a refusal is not retried
 * immediately.
 */
class ShadowRankedPoolClient(
    private val api: ShadowRankedPoolWire = ShadowRankedWire,
    private val onUnauthorized: (usedToken: String?) -> Unit = {},
) {
    fun load(
        token: String,
        rounds: ChallengeRounds,
        allowChicken: Boolean,
        onDone: (ShadowRankedPoolOutcome) -> Unit,
    ) {
        api.pool(token, rounds, allowChicken) { result ->
            onDone(
                result.fold(
                    onSuccess = { ShadowRankedPoolOutcome.Loaded(it) },
                    onFailure = { ShadowRankedPoolOutcome.Failed(failureFor(it, token)) },
                ),
            )
        }
    }

    private fun failureFor(error: Throwable, token: String): ShadowRankedPoolFailure =
        when ((error as? ShadowRankedWireException)?.error) {
            ShadowRankedWireError.Unauthorized -> {
                onUnauthorized(token)
                ShadowRankedPoolFailure.Unauthorized
            }
            ShadowRankedWireError.Refused -> ShadowRankedPoolFailure.Unavailable
            ShadowRankedWireError.Timeout -> ShadowRankedPoolFailure.Timeout
            ShadowRankedWireError.Network -> ShadowRankedPoolFailure.Network
            null -> ShadowRankedPoolFailure.Unavailable
        }
}
