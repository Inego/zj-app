package inego.zungjunggame.ui

/**
 * Ordered rect → action map, rebuilt with the pixels and the sole authority for
 * hit-testing.
 *
 * A component clears the map at the top of each frame and adds a region exactly
 * where it paints a control, so a control that is skipped cannot leave a stale
 * rectangle behind. [rank] declares overlap precedence explicitly: **lower rank
 * wins**, and equal ranks fall back to insertion order.
 *
 * Pure arithmetic — no `SpriteBatch`, no fonts, no GL — so hit geometry is
 * unit-testable in headless tests.
 *
 * @param A action type; for components this is [ComponentAction].
 */
class HitRegions<A : Any> {
    private class Entry<T>(
        val rect: Rect,
        val action: T,
        val rank: Int,
    )

    private val entries = ArrayList<Entry<A>>(16)

    /** Number of live regions. */
    val size: Int get() = entries.size

    /** Drop every region; call once per frame before [add]. */
    fun clear() {
        entries.clear()
    }

    /**
     * Register [rect] as resolving to [action].
     *
     * @param rank precedence, lower wins; equal ranks keep insertion order.
     */
    fun add(rect: Rect, action: A, rank: Int = Int.MAX_VALUE) {
        entries += Entry(rect, action, rank)
    }

    /** Action of the lowest-ranked region containing `(x, y)`, or null. */
    fun hitAt(x: Float, y: Float): A? {
        var found: Entry<A>? = null
        var bestRank = Int.MAX_VALUE
        for (entry in entries) {
            if (!entry.rect.contains(x, y)) continue
            if (found == null || entry.rank < bestRank) {
                bestRank = entry.rank
                found = entry
            }
        }
        return found?.action
    }

    /** Every rect registered for [action], in insertion order. */
    fun rectsFor(action: A): List<Rect> = entries
        .filter { it.action == action }
        .map { it.rect }

    /** One registered region: its action, rect, and declared rank. */
    data class Snapshot<T : Any>(val action: T, val rect: Rect, val rank: Int)

    /** Every live region as an action/rect/rank snapshot, in insertion order. */
    fun snapshot(): List<Snapshot<A>> = entries
        .map { Snapshot(it.action, it.rect, it.rank) }
}
