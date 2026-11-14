package inego.zungjunggame.render.table

data class HandHit(
    val worldX: Float,
    val worldY: Float,
    val w: Float,
    val h: Float,
    val tileType: Int,
    val isDrawnSlot: Boolean,
    val visualId: Int = -1,
    val slotIndex: Int? = null,
) {
    fun contains(px: Float, py: Float): Boolean {
        val hw = w * 0.5f
        val hh = h * 0.5f
        return px >= worldX - hw && px <= worldX + hw &&
            py >= worldY - hh && py <= worldY + hh
    }
}
