package inego.zungjunggame.session

import inego.zungjunggame.engine.GameState
import kotlin.random.Random

/**
 * Live board surface shared by free play and tutorial.
 *
 * [inego.zungjunggame.Main] reads the current hand through this façade — free play
 * via [GameSession], tutorial via [inego.zungjunggame.tutorial.TutorialRunner] —
 * so table / AI / input board access stay mode-agnostic.
 *
 * Free-play multi-hand concerns (scores, deal, persist) stay on [GameSession].
 * Tutorial-specific progress and director state stay on the tutorial runner.
 */
interface LiveHand {
    /** Current hand, or null when no board is loaded. */
    val state: GameState?

    /** True while a hand/session is live (free-play game active or tutorial running). */
    val active: Boolean

    /** Seat controlled by the human at the table. */
    val humanSeat: Int

    /**
     * RNG for engine / assist picks on this hand.
     * Free play reseeds per deal; tutorial may ignore or supply a fixed stream.
     */
    val choiceRng: Random
}
