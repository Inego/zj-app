package inego.zungjunggame.teavm

import inego.zungjunggame.state.HexCodec
import inego.zungjunggame.state.PreferencesStateBackend
import inego.zungjunggame.state.StateBackend

/** `WebPreferences` marks a string value with this key suffix. */
private const val STRING_SUFFIX = "s"

/** The `localStorage` prefix `WebPreferences` used for [container]. */
fun webStateStoragePrefix(storagePrefix: String, container: String): String =
    "pref:$storagePrefix:$container:"

/** The `localStorage` key holding [name]'s hex blob. */
fun webStateStorageKey(storagePrefix: String, container: String, name: String): String =
    webStateStoragePrefix(storagePrefix, container) + name + STRING_SUFFIX

/**
 * [StateBackend] over one `localStorage` key per state key.
 *
 * libGDX's web preferences cannot serve an origin with two tabs: the
 * constructor loads the whole container into a per-tab map, reads answer from
 * that map and never look at `localStorage` again, and `flush()` deletes and
 * rewrites every key from the snapshot. A second tab would therefore never
 * observe the first tab's write and would overwrite it with its own stale
 * copy — including the refresh token the server rotates one-shot.
 *
 * Every access here goes to the live store, which the browser shares across the
 * origin and serializes, so the copy two tabs agree on is the copy each read
 * sees. The key layout is the one `WebPreferences` used
 * ([webStateStorageKey]), so a session written before this backend existed is
 * still read.
 */
class WasmStateBackend(
    storagePrefix: String,
    container: String = PreferencesStateBackend.STATE_NAME,
) : StateBackend {
    private val prefix = webStateStoragePrefix(storagePrefix, container)

    override fun isAvailable(): Boolean = NativeLocalStorage.isAvailable()

    override fun get(name: String): ByteArray? =
        NativeLocalStorage.getItem(storageKey(name))?.let(HexCodec::decode)

    override fun put(name: String, value: ByteArray) {
        NativeLocalStorage.setItem(storageKey(name), HexCodec.encode(value))
    }

    override fun remove(name: String) {
        NativeLocalStorage.removeItem(storageKey(name))
    }

    override fun names(): Set<String> =
        NativeLocalStorage.keysWithPrefix(prefix)
            .split('\n')
            .asSequence()
            .filter { it.endsWith(STRING_SUFFIX) && it.length > STRING_SUFFIX.length }
            .mapTo(LinkedHashSet()) { it.dropLast(STRING_SUFFIX.length) }

    /** Writes are already in `localStorage`; there is nothing staged to publish. */
    override fun flush() = Unit

    private fun storageKey(name: String): String = prefix + name + STRING_SUFFIX
}
