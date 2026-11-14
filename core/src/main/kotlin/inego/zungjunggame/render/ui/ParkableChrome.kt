package inego.zungjunggame.render.ui

import inego.zungjunggame.render.handend.HandEndAction

/**
 * A block of chrome the player can **press and drag to park** somewhere else on
 * screen, typically because it sits over the board on a small phone.
 *
 * The gesture is the same one the hand-end score card has always had: press the
 * block, release without moving and the tap acts on the control under the
 * finger; move past a drag slop and the *block* moves instead, so the press
 * never doubles as a tap. Implementation lives in
 * [ParkDrag] / [ParkGeometry],
 * so a new movable block is a pose plus an interface implementation, not a
 * second copy of the drag state machine.
 *
 * Hit-testing stays with the block: [hitsParkChrome] must reflect the *parked*
 * pose, exactly like the block's own control hit boxes, so a press lands on the
 * block where it is drawn.
 *
 * Coordinates are the block's own space (board/content space inside the tutorial
 * shell, screen space otherwise) — the host maps world → block before calling.
 */
interface ParkableChrome {
    /**
     * Short name for the diagnostics log (`press park-drag layer=… block=…`).
     *
     * The pointer layer cannot tell *which* movable block took a press, and on a
     * device that is exactly the question — a log that only says "the board
     * parked something" cannot distinguish the HINT button from the claim row.
     */
    val parkChromeLabel: String

    /** True when the block is on screen and [worldX]/[worldY] is on its grab area. */
    fun hitsParkChrome(worldX: Float, worldY: Float): Boolean

    /**
     * Claim this press as a park gesture.
     *
     * @return false to leave the press to the ordinary tap path (the point is
     *   not on this block, or a control here has to stay click-only).
     */
    fun onParkPointerDown(worldX: Float, worldY: Float): Boolean

    /** Follow the pointer. Called every frame while this block owns the press. */
    fun onParkPointerDrag(worldX: Float, worldY: Float)

    /**
     * Release the press.
     *
     * [ParkRelease.consumed] reports whether this block handled the release
     * itself; when false the host re-dispatches the release as an ordinary tap,
     * so a press that never became a drag still acts on the control underneath.
     */
    fun onParkPointerUp(worldX: Float, worldY: Float): ParkRelease

    /** Abort an in-flight press (modal opened, gesture cancelled). Keeps the park. */
    fun cancelPark()
}

/**
 * Outcome of releasing a press owned by a [ParkableChrome].
 *
 * @property consumed true when the block handled the release (it parked, or it
 *   acted on the tap itself) so nothing below may see it.
 * @property handEndAction terminal action the block decided on a tap, if any.
 *   The block reports what it chose; mapping it to an intent stays in the
 *   input/dispatch layer.
 * @property detail extra block-specific facts for the `ZjTouch` release line
 *   (fold toggled, chips hit, …) so one log line still says what happened.
 * @property parkChromeLabel the block that resolved the release
 *   ([ParkableChrome.parkChromeLabel]), set when a drag moved it. The host reads
 *   it to report the player's arrangement to the model.
 * @property parkX the applied horizontal offset when the release was a park drag,
 *   in the block's own space; zero when it never moved.
 * @property parkY the applied vertical offset when the release was a park drag,
 *   in the block's own space; zero when it never moved. Together [parkX]/[parkY]
 *   are the block's *committed* place, which is model state.
 */
data class ParkRelease(
    val consumed: Boolean = false,
    val handEndAction: HandEndAction? = null,
    val detail: String = "",
    val parkChromeLabel: String? = null,
    val parkX: Float = 0f,
    val parkY: Float = 0f,
) {
    companion object {
        /** The release was a park drag, or a tap this block acts on: nothing else may see it. */
        val Consumed: ParkRelease = ParkRelease(consumed = true)

        /** The release was not a park drag: the ordinary tap path owns it. */
        val Tap: ParkRelease = ParkRelease(consumed = false)
    }
}
