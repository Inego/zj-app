package inego.zungjunggame.state

/**
 * The platform's container for the small keyed values.
 *
 * Desktop and Android use the libGDX [Preferences] container. The browser
 * installs one live `localStorage` key per state key instead: libGDX's web
 * preferences are a **per-tab snapshot** — the constructor loads the whole
 * container into a map, reads never look at `localStorage` again, and `flush()`
 * deletes and rewrites every key from that snapshot. Two tabs would therefore
 * neither observe each other's writes nor preserve them, which is how one tab's
 * stale session credential came to overwrite another's rotation.
 *
 * Launchers install [factory] before the first store is built — the web
 * preload screen resolves the locale before `Main` starts — so every reader in
 * one boot sees the same container.
 */
object PlatformStateBackend {
    var factory: () -> StateBackend = {
        PreferencesStateBackend(PreferencesStateBackend.STATE_NAME)
    }

    fun create(): StateBackend = factory()
}
