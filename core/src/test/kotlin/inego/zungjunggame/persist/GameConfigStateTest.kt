package inego.zungjunggame.persist

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The persisted half of New Game setup: the versioned key's default, its round
 * trip, the version gate, and corrupt-data fallback.
 */
class GameConfigStateTest {

    private val store = DefaultStateStore(InMemoryStateBackend())
    private val slot = StateSlot(store, GameConfigKey)

    @Test
    fun defaultWhenUnset() {
        assertEquals(GameConfig.DEFAULT, slot.requireValue())
    }

    @Test
    fun unlimitedRoundsRoundTripsAsUnlimited() {
        slot.set(GameConfig(rounds = null, allowChickenHands = false))
        assertNull(slot.requireValue().rounds)
        assertFalse(slot.requireValue().allowChickenHands)
    }

    @Test
    fun storedZeroRoundsIsCorruptAndFallsBackToDefault() {
        // `0` is how the legacy prefs spelled unlimited; the schema spells it as
        // an empty field, so a stored 0 can only be corrupt.
        val zeroed = RecordWriter().field("0").boolean(false).build()
        assertNull(GameConfigSchema.decode(zeroed))
    }

    @Test
    fun forwardVersionIsRejectedAndFallsBackToDefault() {
        val backend = InMemoryStateBackend()
        val futureVersion = GameConfigSchema.version + 1
        backend.put(
            GameConfigKey.fullName,
            ByteBuffer.allocate(4 + 1).putInt(futureVersion).put(0).array(),
        )
        assertEquals(GameConfig.DEFAULT, DefaultStateStore(backend).read(GameConfigKey))
    }

    @Test
    fun truncationTrailingGarbageAndEmptyBytesAreCorrupt() {
        val encoded = GameConfigSchema.encode(GameConfig.DEFAULT)
        assertNull(GameConfigSchema.decode(encoded.copyOfRange(0, encoded.size - 1)))
        assertNull(GameConfigSchema.decode(encoded + "1:x".toByteArray()))
        assertNull(GameConfigSchema.decode(ByteArray(0)))
    }
}
