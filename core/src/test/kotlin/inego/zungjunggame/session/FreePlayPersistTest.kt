package inego.zungjunggame.session

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.ui.stack.SessionKind
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Free-play writes the `session.freeplay` key only while
 * [SessionKind.FreePlay]. The gate is now a lazily evaluated provider instead
 * of a field written by the retired settable session kind.
 */
class FreePlayPersistTest {

    @Test
    fun persistRefusedInMenuAndTutorialWrittenInFreePlay() {
        val slot = sessionSlot()
        var kind = SessionKind.Menu
        val session = GameSession(
            sessionSlot = slot,
            gameConfigSlot = gameConfigSlot(),
            humanSeat = 0,
            persistAllowed = { kind == SessionKind.FreePlay },
        )

        // Active board while Menu: deal auto-save must be refused.
        session.startGame(LocalGameConfig(GameConfig(rounds = 1)))
        assertNull(slot.get())

        kind = SessionKind.Tutorial
        session.persist()
        assertNull(slot.get())

        kind = SessionKind.Online
        session.persist()
        assertNull(slot.get())

        kind = SessionKind.FreePlay
        session.persist()
        assertNotNull(slot.get())
    }

    @Test
    fun persistAllowedDefaultsTrueForStandaloneSession() {
        val slot = sessionSlot()
        val session = GameSession(
            sessionSlot = slot,
            gameConfigSlot = gameConfigSlot(),
        )
        // No explicit gate: standalone sessions keep the historical "allow" default.
        session.startGame(LocalGameConfig(GameConfig(rounds = 1)))
        assertNotNull(slot.get())
    }

    /** Wiping free-play clears the key rather than leaving a stale save. */
    @Test
    fun clearGameRemovesTheSave() {
        val slot = sessionSlot()
        val session = GameSession(
            sessionSlot = slot,
            gameConfigSlot = gameConfigSlot(),
        )
        session.startGame(LocalGameConfig(GameConfig(rounds = 1)))
        session.persist()
        assertNotNull(slot.get())

        session.clearGame()

        assertNull(slot.get())
    }
}
