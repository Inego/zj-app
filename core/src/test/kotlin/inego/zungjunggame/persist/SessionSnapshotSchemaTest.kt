package inego.zungjunggame.persist

import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.SessionScoreTable
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import kotlin.random.Random
import kotlin.test.*

class SessionSnapshotSchemaTest {

    @Test
    fun sessionTableRestoreMatchesRunningTotals() {
        val table = SessionScoreTable()
        val s1 = GameState()
        s1.handScore = 10
        s1.currentPlayer = 0
        s1.decisionType = DecisionType.ActOnHand
        table.recordTerminal(s1)
        val s2 = GameState()
        s2.handScore = 5
        s2.currentPlayer = 2
        s2.decisionType = DecisionType.ActOnHand
        table.recordTerminal(s2)

        val snapshot = SessionSnapshot(
            scoreRows = table.rows(),
            game = GameState.generateRandom(Random(1)),
            nextEastSeat = 0,
            currentHandRecorded = true,
            dealSeed = 1L,
        )
        val decoded = assertNotNull(SessionSnapshotSchema.decode(SessionSnapshotSchema.encode(snapshot)))
        val restored = SessionScoreTable()
        restored.restore(decoded.scoreRows)
        assertEquals(2, restored.handCount)
        assertContentEquals(table.runningTotals(), restored.runningTotals())
        assertEquals(table.rows(), restored.rows())
    }

    @Test
    fun corruptPayloadReturnsNull() {
        assertNull(SessionSnapshotSchema.decode(ByteArray(0)))
        assertNull(SessionSnapshotSchema.decode(byteArrayOf(1, 2, 3, 4)))
        val good = SessionSnapshotSchema.encode(
            SessionSnapshot(
                scoreRows = emptyList(),
                game = GameState.generateRandom(Random(0)),
                nextEastSeat = 0,
                currentHandRecorded = false,
                dealSeed = 0L,
            ),
        )
        good[0] = (good[0].toInt() xor 0xFF).toByte()
        assertNull(SessionSnapshotSchema.decode(good))
    }

    @Test
    fun versionsTheSchemaCannotReadFallBackToTheKeyDefault() {
        val backend = InMemoryStateBackend()
        val store = DefaultStateStore(backend)
        val payload = SessionSnapshotSchema.encode(
            SessionSnapshot(
                scoreRows = emptyList(),
                game = GameState.generateRandom(Random(5)),
                nextEastSeat = 0,
                currentHandRecorded = false,
                dealSeed = 5L,
            ),
        )

        // An older version is not migrated: the key falls back to its default.
        backend.put(SessionSnapshotKey.fullName, frame(3, payload))
        assertNull(store.read(SessionSnapshotKey))

        // A forward version is a save from a newer build: unusable, not a crash.
        backend.put(SessionSnapshotKey.fullName, frame(SessionSnapshotSchema.version + 1, payload))
        assertNull(store.read(SessionSnapshotKey))
    }

    /** The store's framing: a 4-byte big-endian schema version, then the payload. */
    private fun frame(version: Int, payload: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(4 + payload.size).putInt(version).put(payload).array()
}
