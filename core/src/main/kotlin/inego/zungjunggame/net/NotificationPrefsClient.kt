package inego.zungjunggame.net

import inego.zungjunggame.state.StateSlot

/**
 * The player's notification settings for the Settings card.
 *
 * The card renders from this model and its controls call the mutators here, so the
 * value the player sees and the value the server stored cannot disagree. A write is
 * optimistic — the model takes the new value at once and the card does not snap back
 * under the finger — and a refused write restores the last server-confirmed value.
 * [RequestGate] orders the responses, so a read older than an edit is dropped rather
 * than rolling the card back.
 *
 * [slot] holds the last server-confirmed value so a restored Settings card renders
 * it before the boot read answers, instead of flashing the server defaults. Only
 * confirmed responses are stored; an optimistic value the server has not accepted
 * is never durable.
 *
 * No Gdx app required when [slot]/[reader]/[writer] are injected.
 */
class NotificationPrefsClient(
    /** The `notification.prefs` save: the value to render before the first read. */
    private val slot: StateSlot<NotificationPrefs>,
    private val reader: NotificationPrefsReader = NotificationPrefsApi,
    private val writer: NotificationPrefsWriter = NotificationPrefsApi,
) {
    var prefs: NotificationPrefs = slot.requireValue()
        private set
    var error: String? = null
        private set

    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    private val gate = RequestGate()

    /** Drop a session's settings; the next read starts from the defaults. */
    fun clear() {
        gate.supersede()
        slot.clear()
        prefs = NotificationPrefs.DEFAULT
        error = null
    }

    /**
     * Read the settings, then push [language] and [timeZone] onto that row when either differs.
     * A response that outlived a newer one is dropped.
     *
     * The push waits for the read so it cannot overwrite the account's email opt-in and cadence
     * with this device's local copy — only the just-read server row is amended. A null argument
     * leaves the stored value: a device that cannot name its zone must not clear one the server
     * already has, and the same rule holds for the language.
     */
    fun refresh(token: String, language: String? = null, timeZone: String? = null) {
        reader.get(
            token,
            gate.begin { result ->
                result.fold(
                    onSuccess = { stored ->
                        prefs = stored
                        error = null
                        slot.set(stored)
                        val next = stored.copy(
                            language = language ?: stored.language,
                            timeZone = timeZone ?: stored.timeZone,
                        )
                        if (next != stored) write(token, next)
                    },
                    onFailure = { err -> fail(err, token) },
                )
            },
        )
    }

    /** Turn the Score Breakdown email opt-in on or off. */
    fun toggleEmailOptIn(token: String) {
        write(token, prefs.copy(emailOptIn = !prefs.emailOptIn))
    }

    /** Set the hours between result digests (see [NotificationPrefsScale]). */
    fun setDigestIntervalHours(token: String, hours: Int) {
        write(token, prefs.copy(digestIntervalHours = NotificationPrefsScale.coerce(hours)))
    }

    /**
     * Tell the server which language to render this account's notification mail in, and which
     * zone to render its game times in.
     *
     * A null [timeZone] leaves the stored zone. A no-op when both already match, so a locale
     * event that changes nothing sends nothing. The rest of the row (opt-in, cadence) is the
     * last value this client holds.
     */
    fun setLanguage(token: String, tag: String, timeZone: String? = null) {
        val next = prefs.copy(
            language = tag,
            timeZone = timeZone ?: prefs.timeZone,
        )
        if (next == prefs) return
        write(token, next)
    }

    private fun write(token: String, next: NotificationPrefs) {
        val previous = prefs
        prefs = next
        error = null
        // `begin` retires any in-flight read or earlier write, so a response that
        // predates this edit cannot land on top of it.
        writer.put(
            token,
            next,
            gate.begin { result ->
                result.fold(
                    onSuccess = {
                        prefs = it
                        error = null
                        slot.set(it)
                    },
                    onFailure = { err ->
                        prefs = previous
                        fail(err, token)
                    },
                )
            },
        )
    }

    private fun fail(err: Throwable, token: String) {
        error = err.message
        if ((err as? NotificationPrefsException)?.unauthorized == true) onUnauthorized?.invoke(token)
    }
}
