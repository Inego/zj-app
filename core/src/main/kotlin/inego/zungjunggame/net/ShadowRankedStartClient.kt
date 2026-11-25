package inego.zungjunggame.net

/** The typed ways a Shadow Ranked start can fail. */
enum class ShadowRankedStartFailure {
    Unauthorized,
    Unavailable,
    Network,
    Timeout,
    NoSession,
}

/** The outcome of one Shadow Ranked start request. */
sealed interface ShadowRankedStartOutcome {
    data class Started(val start: ChallengeStart) : ShadowRankedStartOutcome
    data class Failed(val failure: ShadowRankedStartFailure) : ShadowRankedStartOutcome
}

/**
 * Starts a Shadow Ranked attempt through the wire's duplicate-challenge start
 * (`POST /challenges`; the wire name is retained). The client only maps the
 * [ShadowRankedWireException] the wire already classified into
 * [ShadowRankedStartFailure]: a rejected session is reported to the injected
 * [onUnauthorized] so the auth lifecycle stays with its owner, and a null or blank
 * token is [ShadowRankedStartFailure.NoSession] with no request sent.
 *
 * The wire owns the timeout: it classifies a request that outlived
 * [ShadowRankedWire.TIMEOUT_MS] as [ShadowRankedWireError.Timeout], which maps to
 * [ShadowRankedStartFailure.Timeout] here. A refusal is
 * [ShadowRankedStartFailure.Unavailable]; any other transport failure is
 * [ShadowRankedStartFailure.Network].
 */
class ShadowRankedStartClient(
    private val sender: ShadowRankedStartWire = ShadowRankedWire,
    private val onUnauthorized: (usedToken: String?) -> Unit = {},
) {
    fun start(
        token: String?,
        rounds: ChallengeRounds,
        allowChicken: Boolean,
        fastBots: Boolean = false,
        onDone: (ShadowRankedStartOutcome) -> Unit,
    ) {
        if (token.isNullOrBlank()) {
            onDone(ShadowRankedStartOutcome.Failed(ShadowRankedStartFailure.NoSession))
            return
        }
        sender.start(token, rounds, allowChicken, fastBots) { result ->
            onDone(
                result.fold(
                    onSuccess = { ShadowRankedStartOutcome.Started(it) },
                    onFailure = { ShadowRankedStartOutcome.Failed(failureFor(it, token)) },
                ),
            )
        }
    }

    private fun failureFor(error: Throwable, token: String): ShadowRankedStartFailure =
        when ((error as? ShadowRankedWireException)?.error) {
            ShadowRankedWireError.Unauthorized -> {
                onUnauthorized(token)
                ShadowRankedStartFailure.Unauthorized
            }
            ShadowRankedWireError.Refused -> ShadowRankedStartFailure.Unavailable
            ShadowRankedWireError.Timeout -> ShadowRankedStartFailure.Timeout
            ShadowRankedWireError.Network -> ShadowRankedStartFailure.Network
            null -> ShadowRankedStartFailure.Unavailable
        }
}
