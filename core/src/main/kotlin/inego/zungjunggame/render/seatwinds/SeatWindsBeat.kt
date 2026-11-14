package inego.zungjunggame.render.seatwinds

/**
 * Pure description of one East-player coach body key for the seat-winds diagram.
 *
 * [SeatWindsView.present] applies this deterministically: still snapshot vs
 * looping pause-then-rotate deal-pass.
 */
data class SeatWindsBeat(
    val bodyKey: String,
    /** True: hold, ease 90° ccw, hold, … False: home assignment, no motion. */
    val rotateDeal: Boolean,
    /** Pulse the East wind label so the dealer wind is the tracked object. */
    val pulseEast: Boolean,
)

/**
 * Body-key → [SeatWindsBeat] for the East-player lesson.
 *
 * Unknown keys are null (not a still default) so a typo does not silently
 * freeze the wrong diagram.
 */
object SeatWindsBeats {

    fun of(bodyKey: String): SeatWindsBeat? = when (bodyKey) {
        "tutorial.moves.east_player.four" ->
            SeatWindsBeat(bodyKey, rotateDeal = false, pulseEast = true)
        "tutorial.moves.east_player.pass" ->
            SeatWindsBeat(bodyKey, rotateDeal = true, pulseEast = true)
        else -> null
    }
}
