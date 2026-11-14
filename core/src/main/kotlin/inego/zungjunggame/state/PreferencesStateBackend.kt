package inego.zungjunggame.state

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Preferences

/**
 * [StateBackend] over libGDX [Preferences]: each blob is stored as lowercase hex
 * under its namespaced name. Uniform across desktop, Android, and web
 * (`localStorage`), so persisted state survives a restart, process death, or a
 * page reload.
 *
 * [put] and [remove] stage into the editor and are not flushed; callers batch
 * durability through [DefaultStateStore.flush]. On Android the editor is not the
 * map [Preferences.getString] reads — that read sees only the last
 * [Preferences.flush] — so accepted bytes (and removals) stay in [pending] until
 * [flush] publishes the editor and drops them.
 *
 * The container is [preferences] when given, otherwise
 * `Gdx.app.getPreferences(prefsName)`. A missing [Gdx.app] makes every access a
 * no-op, so a production store can be constructed freely.
 */
class PreferencesStateBackend(
    private val prefsName: String,
    preferences: Preferences? = null,
) : StateBackend {
    private var cached: Preferences? = preferences

    /**
     * Bytes accepted since the last [flush]; a null value is a removal.
     * Dropped after [flush] because `Editor.apply` has then published them into
     * the map [get] falls back to.
     */
    private val pending = LinkedHashMap<String, ByteArray?>()

    private fun prefs(): Preferences? {
        cached?.let { return it }
        val app = Gdx.app ?: return null
        return app.getPreferences(prefsName).also { cached = it }
    }

    override fun isAvailable(): Boolean = prefs() != null

    override fun get(name: String): ByteArray? {
        if (pending.containsKey(name)) return pending[name]?.copyOf()
        val prefs = prefs() ?: return null
        if (!prefs.contains(name)) return null
        return decodeHex(prefs.getString(name, ""))
    }

    override fun put(name: String, value: ByteArray) {
        val prefs = prefs() ?: return
        val copy = value.copyOf()
        pending[name] = copy
        prefs.putString(name, encodeHex(copy))
    }

    override fun remove(name: String) {
        val prefs = prefs() ?: return
        pending[name] = null
        prefs.remove(name)
    }

    override fun names(): Set<String> {
        val stored = prefs()?.get()?.keys?.toMutableSet() ?: mutableSetOf()
        for ((name, value) in pending) {
            if (value == null) stored.remove(name) else stored.add(name)
        }
        return stored
    }

    override fun flush() {
        val prefs = prefs() ?: return
        prefs.flush()
        pending.clear()
    }

    companion object {
        /**
         * Container name for the keyed values that fit in preferences.
         *
         * Deliberately **not** [FileStateBackend.STATE_DIR]: desktop preferences
         * are a *file* at `<prefs dir>/<name>`, so a file-backed store sharing the
         * name could never create its directory there and every file-backed key
         * would silently fail to persist.
         */
        const val STATE_NAME = "zj-state"
    }

    private fun encodeHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val text = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            text.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return text.toString()
    }

    private fun decodeHex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val bytes = ByteArray(text.length / 2)
        for (i in bytes.indices) {
            val hi = hexDigit(text[i * 2])
            val lo = hexDigit(text[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return bytes
    }

    private fun hexDigit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
