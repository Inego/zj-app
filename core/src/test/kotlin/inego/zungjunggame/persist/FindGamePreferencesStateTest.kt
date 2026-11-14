package inego.zungjunggame.persist

import inego.zungjunggame.net.QueueChicken
import inego.zungjunggame.net.QueueRounds
import java.nio.ByteBuffer
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The persisted Find-game preferences: defaults, round trip, and the gates that
 * keep corrupt data from reaching the matchmaking model.
 */
class FindGamePreferencesStateTest {

    private val store = DefaultStateStore(InMemoryStateBackend())
    private val slot = StateSlot(store, FindGamePreferencesKey)

    @Test
    fun defaultsWhenUnset() {
        val loaded = slot.requireValue()
        assertEquals(FindGamePreferences.DEFAULT_MIN_OTHER_HUMANS, loaded.minOtherHumans)
        assertEquals(QueueRounds.Either, loaded.rounds)
        assertEquals(QueueChicken.Either, loaded.chicken)
    }

    @Test
    fun outOfRangeSliderValueIsCorrupt() {
        assertNull(FindGamePreferencesSchema.decode(encode(min = 10)))
        assertNull(FindGamePreferencesSchema.decode(encode(min = -5)))
    }

    @Test
    fun unknownEnumNamesAreCorrupt() {
        assertNull(FindGamePreferencesSchema.decode(encode(rounds = "ThreeRounds")))
        assertNull(FindGamePreferencesSchema.decode(encode(chicken = "Maybe")))
    }

    @Test
    fun clearFallsBackToDefaults() {
        slot.set(FindGamePreferences(minOtherHumans = 0, rounds = QueueRounds.One))
        slot.clear()
        assertEquals(FindGamePreferences.DEFAULT, slot.requireValue())
    }

    /**
     * v1 stored a hub settings-drawer flag. The hub now always shows its
     * settings, so the flag is dropped — but the three matchmaking values that
     * travelled alongside it must survive the version bump, not reset.
     */
    @Test
    fun v1PayloadMigratesAndDropsTheSettingsDrawer() {
        val backing = InMemoryStateBackend()
        // The frame v1 wrote: a version prefix, then the three values and the
        // drawer flag. Seeded at the backend so the store takes its real
        // migrate-on-read path rather than a hand-built decode.
        val v1Payload = RecordWriter()
            .int(3)
            .field(QueueRounds.One.name)
            .field(QueueChicken.Allowed.name)
            .boolean(true)
            .build()
        backing.put(
            FindGamePreferencesKey.fullName,
            ByteBuffer.allocate(4 + v1Payload.size).putInt(1).put(v1Payload).array(),
        )

        val migrated = StateSlot(DefaultStateStore(backing), FindGamePreferencesKey).requireValue()

        assertEquals(3, migrated.minOtherHumans)
        assertEquals(QueueRounds.One, migrated.rounds)
        assertEquals(QueueChicken.Allowed, migrated.chicken)
    }

    private fun encode(
        min: Int = 1,
        rounds: String = QueueRounds.Either.name,
        chicken: String = QueueChicken.Either.name,
    ): ByteArray = RecordWriter()
        .int(min)
        .field(rounds)
        .field(chicken)
        .build()
}
