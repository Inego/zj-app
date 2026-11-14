package inego.zungjunggame.net

import com.badlogic.gdx.utils.Disposable

/**
 * The hand tables of one Shadow Ranked challenge, the home of the wire's
 * `GET /challenges/{publicId}/hands` document.
 *
 * The document is fetched once per open; the Who arrows ([step]) and the
 * dropdown ([toggleDropdown], [select]) are purely local, so `<` and `>` never
 * make a second request. A stale response from a superseded open is dropped by
 * the [gate]. A null or blank [token] clears the document and keeps the last
 * requested id, so an account that signs in later can [reload] it; [clear] is the
 * log-out / expiry path, so a second account never sees the first's tables.
 */
class ShadowRankedHandsClient(
    private val api: ShadowRankedHandsWire = ShadowRankedWire,
) : Disposable {
    var document: ChallengeHands? = null
        private set

    /** The attempt the Who bar is on; the `is_you` row after a successful load. */
    var selectedIndex: Int = 0
        private set

    /** True while the Who dropdown covers the table; a step is a no-op then. */
    var dropdownOpen: Boolean = false
        private set

    var loading: Boolean = false
        private set

    /** The last failure's message; the UI never displays it (it switches on [errorCode]). */
    var error: String? = null
        private set

    /** The server's machine-readable failure code (`NOT_FOUND`, `CHALLENGE_NOT_FINISHED`, …). */
    var errorCode: String? = null
        private set

    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    private val gate = RequestGate()

    /** The id the last [load] asked for, so a later session can [reload] it. */
    private var requestedPublicId: String? = null

    fun clear() {
        gate.supersede()
        requestedPublicId = null
        resetState()
    }

    /** Scope close: drop a response that is still in flight. */
    override fun dispose() = clear()

    fun load(token: String?, publicId: String) {
        requestedPublicId = publicId
        if (token.isNullOrBlank()) {
            gate.supersede()
            resetState()
            return
        }
        resetState()
        loading = true
        api.loadHands(
            token = token,
            publicId = publicId,
            onDone = gate.begin { result ->
                loading = false
                result.fold(
                    onSuccess = { document ->
                        this.document = document
                        error = null
                        errorCode = null
                        dropdownOpen = false
                        val you = document.attempts.indexOfFirst { it.isYou }
                        selectedIndex = if (you >= 0) you else 0
                    },
                    onFailure = { err ->
                        val wire = err as? ShadowRankedWireException
                        error = err.message
                        errorCode = wire?.code
                        if (wire?.error == ShadowRankedWireError.Unauthorized) onUnauthorized?.invoke(token)
                    },
                )
            },
        )
    }

    /** Re-issue the last [load] under a rotated session; a no-op when none was asked. */
    fun reload(token: String?) {
        val id = requestedPublicId ?: return
        load(token, id)
    }

    /** True while [publicId]'s document is loaded or in flight, so an open does not refetch it. */
    fun isActive(publicId: String): Boolean =
        requestedPublicId == publicId && (loading || document != null)

    /** Left/right: wrap through the attempts; a no-op while the dropdown is open. */
    fun step(delta: Int) {
        val count = document?.attempts?.size ?: 0
        if (dropdownOpen || count == 0) return
        selectedIndex = ((selectedIndex + delta) % count + count) % count
    }

    fun toggleDropdown() {
        if (document?.attempts.isNullOrEmpty()) return
        dropdownOpen = !dropdownOpen
    }

    /** Choosing a row closes the dropdown and moves the selection to [index]. */
    fun select(index: Int) {
        val attempts = document?.attempts ?: return
        if (index in attempts.indices) selectedIndex = index
        dropdownOpen = false
    }

    private fun resetState() {
        document = null
        selectedIndex = 0
        dropdownOpen = false
        loading = false
        error = null
        errorCode = null
    }
}
