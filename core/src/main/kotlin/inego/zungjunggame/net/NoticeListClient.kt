package inego.zungjunggame.net

/**
 * In-memory notice inbox. No Gdx app required when [api] is
 * injected.
 *
 * The inbox is the data behind the notices list and the popup. A refresh replaces
 * the stored rows and, when a fresh unseen notice appears, publishes it as the
 * [popupNotice] the popup route shows. A notice already delivered by email is
 * listed but never becomes the popup: the popup is the in-app delivery.
 * [clear] is the log-out / expiry path, so a second account never
 * sees the first's notices.
 *
 * Seeing a notice — in the popup or in the list — is [markSeen]/[markAllSeen]:
 * the ids are acknowledged locally (so a refresh racing the server call cannot
 * re-pop them) and the server is told, which is what drops a waiting mail.
 */
class NoticeListClient(
    private val api: NoticeLister = NoticeApi,
    private val marker: NoticeMarker = NoticeApi,
) {
    var notices: List<NoticeEntry> = emptyList()
        private set
    var loading: Boolean = false
        private set
    var error: String? = null
        private set

    /** The newest unseen notice waiting for its popup, or null. */
    var popupNotice: NoticeEntry? = null
        private set

    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    /** Fired after a refresh replaces [notices]; the owner decides what to show. */
    var onNoticesChanged: (() -> Unit)? = null

    private val gate = RequestGate()

    /** Ids seen on this client, so a racing refresh cannot re-present them. */
    private val acknowledged = LinkedHashSet<Long>()

    fun clear() {
        gate.supersede()
        notices = emptyList()
        loading = false
        error = null
        popupNotice = null
        acknowledged.clear()
    }

    fun refresh(token: String) {
        loading = true
        error = null
        api.list(
            token,
            gate.begin { result ->
                loading = false
                result.fold(
                    onSuccess = {
                        notices = it.notices
                        error = null
                        recomputePopup()
                        onNoticesChanged?.invoke()
                    },
                    onFailure = { err ->
                        error = err.message
                        if ((err as? NoticeException)?.unauthorized == true) {
                            onUnauthorized?.invoke(token)
                        }
                    },
                )
            },
        )
    }

    /** Record [ids] seen for the account and update the stored rows. */
    fun markSeen(token: String, ids: List<Long>) {
        if (ids.isEmpty()) return
        acknowledged += ids
        val idSet = ids.toSet()
        notices = notices.map { if (it.id in idSet) it.copy(seen = true) else it }
        marker.markSeen(token, ids) { }
    }

    /** Mark every stored notice seen; the notices list open is what does this. */
    fun markAllSeen(token: String) {
        markSeen(token, notices.filter { !it.seen }.map { it.id })
    }

    /**
     * The shown popup is done; pick the next unseen notice, if any.
     */
    fun acknowledgePopup() {
        val notice = popupNotice ?: return
        acknowledged += notice.id
        popupNotice = null
        recomputePopup()
    }

    /**
     * Pick the newest unseen notice for the popup. A notice already delivered by
     * email is skipped: it is listed but never popped.
     */
    private fun recomputePopup() {
        if (popupNotice != null) return
        popupNotice = notices.firstOrNull { !it.seen && !it.emailed && it.id !in acknowledged }
    }
}
