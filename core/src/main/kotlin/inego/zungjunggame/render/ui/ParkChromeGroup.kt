package inego.zungjunggame.render.ui

/**
 * Several [ParkableChrome] blocks that share one press, e.g. the board's HINT
 * button and claim row.
 *
 * The press belongs to the first block in **tap order** whose grab area is under
 * it — the same order the tap path would resolve, so a park never grabs a press
 * that a tap would have given to a different block. That block then owns
 * drag/release for as long as the finger is down, even when the pointer leaves
 * its rectangle (the block is following it, and clamping can leave the block
 * behind at an edge).
 *
 * Implements [ParkableChrome] itself, so the host registers one block with the
 * pointer layer rather than one per panel.
 *
 * @param blocks blocks in tap order: the first hit wins.
 */
internal class ParkChromeGroup(
    private val blocks: List<ParkableChrome>,
) : ParkableChrome {
    private var owner: ParkableChrome? = null

    override val parkChromeLabel: String get() = owner?.parkChromeLabel ?: "board-chrome"

    /** True while one of the blocks owns a press (the block is being moved). */
    val parking: Boolean get() = owner != null

    override fun hitsParkChrome(worldX: Float, worldY: Float): Boolean =
        blocks.any { it.hitsParkChrome(worldX, worldY) }

    override fun onParkPointerDown(worldX: Float, worldY: Float): Boolean {
        val target = blocks.firstOrNull { it.hitsParkChrome(worldX, worldY) } ?: return false
        // A block that declines the press (its press belongs to a click-only
        // control) must not leave a half-started press behind.
        if (!target.onParkPointerDown(worldX, worldY)) return false
        owner = target
        return true
    }

    override fun onParkPointerDrag(worldX: Float, worldY: Float) {
        owner?.onParkPointerDrag(worldX, worldY)
    }

    override fun onParkPointerUp(worldX: Float, worldY: Float): ParkRelease {
        val target = owner ?: return ParkRelease.Tap
        owner = null
        return target.onParkPointerUp(worldX, worldY)
    }

    override fun cancelPark() {
        owner?.cancelPark()
        owner = null
    }
}
