package inego.zungjunggame.state

import com.badlogic.gdx.Preferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Android libGDX [Preferences] keeps puts in an editor and serves gets from the
 * committed map until [Preferences.flush]. The backend's memory has to cover
 * that gap; durability stays on flush.
 */
class PreferencesStateBackendTest {

    @Test
    fun readsReturnUnflushedEditsAndFlushPublishesThem() {
        val prefs = SplitPreferences()
        val backend = PreferencesStateBackend("zj-state", prefs)
        val untouched = byteArrayOf(1, 2, 3)
        val replaced = byteArrayOf(0x00, 0x0f, 0xff.toByte())
        val added = byteArrayOf(9)

        backend.put("untouched", untouched)
        backend.put("replaced", byteArrayOf(7))
        backend.put("kept", byteArrayOf(4))
        backend.flush()

        backend.put("replaced", replaced)
        backend.put("added", added)
        backend.remove("kept")

        assertContentEquals(untouched, backend.get("untouched"))
        assertContentEquals(replaced, backend.get("replaced"))
        assertContentEquals(added, backend.get("added"))
        assertNull(backend.get("kept"))
        assertEquals(setOf("untouched", "replaced", "added"), backend.names())

        backend.flush()

        val reread = PreferencesStateBackend("zj-state", prefs)
        assertContentEquals(untouched, reread.get("untouched"))
        assertContentEquals(replaced, reread.get("replaced"))
        assertContentEquals(added, reread.get("added"))
        assertNull(reread.get("kept"))
        assertEquals(setOf("untouched", "replaced", "added"), reread.names())
    }

    /**
     * Puts land in the editor; gets, contains, and [get] read only the committed
     * map. [flush] copies the editor across, which is what Android `apply` does
     * to the in-memory `SharedPreferences` before it returns.
     */
    private class SplitPreferences : Preferences {
        private val committed = LinkedHashMap<String, String>()
        private var editor: LinkedHashMap<String, String?>? = null

        private fun edit(): LinkedHashMap<String, String?> =
            editor ?: LinkedHashMap<String, String?>().also { editor = it }

        override fun putString(key: String, value: String): Preferences {
            edit()[key] = value
            return this
        }

        override fun getString(key: String): String = getString(key, "")

        override fun getString(key: String, defValue: String): String = committed[key] ?: defValue

        override fun contains(key: String): Boolean = committed.containsKey(key)

        override fun get(): Map<String, *> = LinkedHashMap(committed)

        override fun remove(key: String) {
            edit()[key] = null
        }

        override fun flush() {
            val staged = editor ?: return
            for ((key, value) in staged) {
                if (value == null) committed.remove(key) else committed[key] = value
            }
            editor = null
        }

        override fun putBoolean(key: String, value: Boolean): Preferences = unused()
        override fun putInteger(key: String, value: Int): Preferences = unused()
        override fun putLong(key: String, value: Long): Preferences = unused()
        override fun putFloat(key: String, value: Float): Preferences = unused()
        override fun put(vals: Map<String, *>): Preferences = unused()
        override fun getBoolean(key: String): Boolean = unused()
        override fun getInteger(key: String): Int = unused()
        override fun getLong(key: String): Long = unused()
        override fun getFloat(key: String): Float = unused()
        override fun getBoolean(key: String, defValue: Boolean): Boolean = unused()
        override fun getInteger(key: String, defValue: Int): Int = unused()
        override fun getLong(key: String, defValue: Long): Long = unused()
        override fun getFloat(key: String, defValue: Float): Float = unused()
        override fun clear() = unused()

        private fun unused(): Nothing = error("split preferences double does not implement this")
    }
}
