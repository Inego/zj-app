package inego.zungjunggame.render.tiles

import kotlin.math.abs

/**
 * World-space pose for a single tile (center, size, rotation, face).
 * Used as both current and target state for current→target animation.
 */
data class TilePose(
    val x: Float,
    val y: Float,
    val rotationDeg: Float,
    val w: Float,
    val h: Float,
    /** True when the face (symbol) should be shown. */
    val open: Boolean,
) {
    fun nearlyEquals(other: TilePose, posEps: Float = 0.5f, angleEps: Float = 0.5f): Boolean {
        if (open != other.open) return false
        if (abs(x - other.x) > posEps) return false
        if (abs(y - other.y) > posEps) return false
        if (abs(w - other.w) > posEps) return false
        if (abs(h - other.h) > posEps) return false
        return TileEasing.angleDeltaDeg(rotationDeg, other.rotationDeg) <= angleEps
    }
}

/** Logical home of a desired table slot — stabilizes matching across frames. */
enum class SlotKind {
    Hand,
    Drawn,
    River,
    Meld,
}

data class SlotKey(
    val kind: SlotKind,
    val seat: Int,
    /** Hand/river index, or meld group index. */
    val index: Int = 0,
    /** Tile index within a meld group. */
    val subIndex: Int = 0,
)

/**
 * Per-tile emphasis. Play **halo** ([Soft], [Match], [WinDiscard], [WinHand])
 * is the engine cue in free play **and** tutorial. Tutorial **outline**
 * ([Prompt], [Deny]) is an extra ring on top — it does not replace the halo.
 */
enum class TileFocusGlow {
    None,
    /** Soft gold halo: pending claim discard, or the parked mid-hand wall-draw. */
    Soft,
    /**
     * Orange outline on a live-hand tile or open-meld copy the tutorial wants
     * noticed. Independent of play halo. Does not lift. Overlay coaches punch
     * the same face through dim bands instead of stacking.
     */
    Prompt,
    /**
     * Cyan halo: every **visible** copy of the face the pointer is on, in any
     * region and at any turn (hand, melds, rivers).
     */
    Match,
    /**
     * Red outline: a tile that cannot be claimed (tutorial overlay). Typical:
     * pending discard (`river.last`) or an earlier river copy the claim window
     * has closed on. Stacks on the play [Soft] halo when both apply.
     */
    Deny,
    /** Orange halo: the discard that completed a Hu. */
    WinDiscard,
    /** Soft gold halo: every tile in the revealed winning hand (concealed + melds). */
    WinHand,
    /** Cyan outline: secondary tutorial highlight for multi-group contrasts. */
    Secondary,
    ;

    val isPlayHalo: Boolean
        get() = this == Soft || this == Match || this == WinDiscard || this == WinHand

    val isTutorialOutline: Boolean
        get() = this == Prompt || this == Deny || this == Secondary
}

/**
 * One target slot derived from layout for a given frame.
 * [seatIndex] is the owner seat (closed backs are blue for every seat).
 */
data class DesiredSlot(
    val pose: TilePose,
    val tileType: Int,
    val seatIndex: Int,
    val slotKey: SlotKey,
    val selectableHand: Boolean = false,
    val isDrawnSlot: Boolean = false,
    /** Engine halo ([TileFocusGlow.isPlayHalo]). */
    val focusGlow: TileFocusGlow = TileFocusGlow.None,
    /** Extra tutorial ring ([TileFocusGlow.isTutorialOutline]); stacks on [focusGlow]. */
    val tutorialOutline: TileFocusGlow = TileFocusGlow.None,
    /** Author [inego.zungjunggame.render.table.HighlightGroup] index; clustering never merges different keys. */
    val tutorialGroupKey: Int = 0,
)
