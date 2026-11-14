package inego.zungjunggame.tutorial

import inego.zungjunggame.render.wallbuild.WallBuildBeat
import inego.zungjunggame.render.wallbuild.WallBuildDeal

/**
 * Wall-build deals parked by lesson id.
 *
 * The park holds the last **committed** deal (dice + wall map) for a lesson.
 * [WallBuildBeat.DicePolicy.ClearRoll] invalidates it so the next
 * [WallBuildBeat.DicePolicy.RollOrRestore] tumbles fresh dice; later beats
 * and the board overlay recapture / bind that new deal. Survives leaving the
 * lesson (so Back / recover-on-the-next-unit restore the same hands). Forgotten
 * on a pre-roll beat, a fresh start of that lesson, or [forgetAll].
 */
class TutorialWallDeals {
    private val byLesson = LinkedHashMap<String, WallBuildDeal>()

    fun get(lessonId: String): WallBuildDeal? = byLesson[lessonId]?.copyDeep()

    fun put(lessonId: String, deal: WallBuildDeal) {
        require(lessonId.isNotBlank()) { "blank wall-deal lesson id" }
        byLesson[lessonId] = deal.copyDeep()
    }

    /**
     * Mirror the wall view into the park.
     *
     * - [WallBuildBeat.DicePolicy.ClearRoll]: drop the parked deal so the next
     *   dice entry is a new tumble. Mapping stays in the wall view.
     * - Any other policy, or overlay (`dice == null`): write [deal] when the
     *   view has a committed one. A null [deal] does **not** forget — a
     *   cleared view (lesson change / suspend) must not wipe a parked deal.
     */
    fun syncFromBeat(
        lessonId: String,
        dice: WallBuildBeat.DicePolicy?,
        deal: WallBuildDeal?,
    ) {
        if (dice == WallBuildBeat.DicePolicy.ClearRoll) {
            forget(lessonId)
            return
        }
        if (deal != null) put(lessonId, deal)
    }

    fun forget(lessonId: String) {
        byLesson.remove(lessonId)
    }

    fun forgetAll() {
        byLesson.clear()
    }

    fun snapshot(): Map<String, WallBuildDeal> =
        byLesson.entries.associate { it.key to it.value.copyDeep() }

    fun restore(deals: Map<String, WallBuildDeal>) {
        byLesson.clear()
        for ((id, deal) in deals) {
            if (id.isBlank()) continue
            byLesson[id] = deal.copyDeep()
        }
    }

    val size: Int
        get() = byLesson.size
}
