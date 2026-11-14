package inego.zungjunggame.ui

/**
 * One pointer a device reports down, in world space.
 *
 * [id] is the identity the platform gave that pointer: the slot libGDX exposes
 * through `Gdx.input.isTouched(id)` / `getX(id)`. Desktop has one pointer and it
 * is always 0. A touch platform assigns slots, stable for that pointer's whole
 * life but reused by the next touch once it lifts, so an id identifies a pointer
 * *while it is down* and nothing more — two frames that report the same id with
 * no lift between them are the same pointer.
 */
data class PointerSample(val id: Int, val x: Float, val y: Float)

/**
 * The pointers one frame reports down, primary first — the multi-pointer form of
 * `Gdx.input.isTouched(0)..isTouched(maxPointers - 1)`, carried on [Constraints]
 * for the frame's consumers.
 *
 * Iterate it to reach every pointer, or read [primary] / [secondary] for the one-
 * and two-finger cases. Slots are ordered ascending, so [primary] is slot 0 when
 * it is down — the pointer the legacy single-pointer path reads — and otherwise
 * the lowest slot still down: a freed slot is handed to a later touch, so
 * [primary] is not necessarily the finger that touched first. A two-finger
 * gesture is the pair, not the order.
 *
 * Coordinates are world space, unprojected exactly like [Constraints.pointerX],
 * so a sample is directly comparable with a hit test. The set describes the
 * frame, not a live device: a pointer that lifted is gone rather than left stale,
 * and a desktop hover is not a pointer down. Lives in `ui/` because the frame's
 * inputs are [Constraints]' business and `ui/` may not import `input/`; the
 * device backends that produce it are in `input/` and `app/`.
 */
data class FramePointers(private val samples: List<PointerSample>) : Iterable<PointerSample> {

    /** How many pointers are down this frame. */
    val count: Int get() = samples.size

    /** The frame's lowest-slot pointer, or null when no pointer is down. */
    val primary: PointerSample? get() = samples.firstOrNull()

    /** The frame's next-highest slot, or null on a one-pointer frame. */
    val secondary: PointerSample? get() = samples.getOrNull(1)

    override fun iterator(): Iterator<PointerSample> = samples.iterator()

    /**
     * This set expressed in [space]'s local coordinates: the same translation the
     * frame's `pointerX`/`pointerY` get, so a component laid out inside a scope
     * compares samples with its own geometry. A pointer outside [space]'s rect (or
     * inside a reserved rect) is not in that space and is dropped, exactly as
     * `pointerX`/`pointerY` become `NaN` there.
     */
    fun inSpace(space: Transform): FramePointers {
        if (samples.isEmpty()) return this
        val mapped = samples.mapNotNull { sample ->
            val (x, y) = space.toLocal(sample.x, sample.y) ?: return@mapNotNull null
            PointerSample(sample.id, x, y)
        }
        return if (mapped.isEmpty()) EMPTY else FramePointers(mapped)
    }

    companion object {
        /** No pointer down: the desktop cursor between presses, a lifted touch. */
        val EMPTY = FramePointers(emptyList())
    }
}
