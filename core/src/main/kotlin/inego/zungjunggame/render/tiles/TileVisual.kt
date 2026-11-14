package inego.zungjunggame.render.tiles

class VisualTile(
    val id: Int,
    var tileType: Int,
    var seatIndex: Int,
    var slotKey: SlotKey,
    var selectableHand: Boolean,
    var isDrawnSlot: Boolean,
    var focusGlow: TileFocusGlow = TileFocusGlow.None,
    var tutorialOutline: TileFocusGlow = TileFocusGlow.None,
    var tutorialGroupKey: Int = 0,
    var current: TilePose,
    var from: TilePose,
    var target: TilePose,
    var elapsed: Float,
    var startDelay: Float,
) {
    fun isSettled(duration: Float, epsilon: Float = 0.01f): Boolean {
        if (startDelay > epsilon) return false
        if (elapsed + epsilon < duration) return false
        return current.nearlyEquals(target)
    }
}
