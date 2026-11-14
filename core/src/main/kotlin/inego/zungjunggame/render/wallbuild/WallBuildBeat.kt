package inego.zungjunggame.render.wallbuild

/**
 * Pure description of what one wall-build coach body key means for the diagram.
 *
 * [WallBuildView.present] applies this deterministically: layout and overlays are
 * a function of (beat, [WallBuildLessonState]), not of ad-hoc flag deltas or
 * “hope the last hide kept the dice.”
 */
data class WallBuildBeat(
    val bodyKey: String,
    val phase: WallBuildPhase,
    val showSeatWinds: Boolean,
    val highlightEast: Boolean,
    val dice: DicePolicy,
    val showCountHighlight: Boolean,
    val take7: Take7Policy,
    val showWallBreak: Boolean,
    val showDeadWall: Boolean,
    val showDealHands: Boolean,
    val showDeal13thTile: Boolean = false,
    /** East's opening draw to 14 (after all seats have 13). */
    val showDeal14thEast: Boolean = false,
    /**
     * Players look at hands: deal squares + singles re-form a line of 13
     * (14 for East). East open unsorted; other seats closed.
     */
    val showLookHands: Boolean = false,
    /**
     * With [showLookHands]: reorder each seat’s line by ascending tile type
     * (same convention as [inego.zungjunggame.render.seats.PlayerAreaLayout.expandHandWithDrawn]).
     */
    val showSortHands: Boolean = false,
) {
    /**
     * How this beat interacts with the lesson-scoped dice roll.
     *
     * - [ClearRoll] — pre-roll beats; drop the roll so the next dice entry is fresh.
     * - [RollOrRestore] — dice beat: tumble once if no roll, else restore settled.
     * - [ShowSettled] — post-roll with pips: ensure a roll exists, show settled.
     * - [KeepRollHidden] — post-roll without pips (hands): ensure roll, hide dice.
     */
    enum class DicePolicy {
        ClearRoll,
        RollOrRestore,
        ShowSettled,
        KeepRollHidden,
    }

    /** Orange take-7 stack highlight. */
    enum class Take7Policy {
        Off,
        /** Reveal stacks one-by-one (take7 beat). */
        Reveal,
        /** All 7 lit (dead / dead_out). */
        Full,
    }
}

/**
 * Body-key → [WallBuildBeat] table for the orientation wall lesson.
 */
object WallBuildBeats {

    fun of(bodyKey: String): WallBuildBeat? {
        val phase = phaseFor(bodyKey) ?: return null
        return when (bodyKey) {
            "tutorial.orient.wall.faces",
            "tutorial.orient.wall.copies",
            // Same open ×4 pack as copies; coach introduces wall-as-draw-pile.
            "tutorial.orient.wall.drawpile",
            "tutorial.orient.wall.ritual",
            "tutorial.orient.wall.facedown",
            "tutorial.orient.wall.shuffle",
            "tutorial.orient.wall.formed",
            "tutorial.orient.wall.pushed",
            -> base(bodyKey, phase)

            "tutorial.orient.wall.seats" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
            )

            "tutorial.orient.wall.east",
            "tutorial.orient.wall.deal",
            -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
            )

            "tutorial.orient.wall.dice" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.RollOrRestore,
            )

            "tutorial.orient.wall.count" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.ShowSettled,
                showCountHighlight = true,
            )

            "tutorial.orient.wall.break" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.ShowSettled,
                showWallBreak = true,
            )

            "tutorial.orient.wall.take7" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.ShowSettled,
                showWallBreak = true,
                take7 = WallBuildBeat.Take7Policy.Reveal,
            )

            "tutorial.orient.wall.dead",
            "tutorial.orient.wall.dead_out",
            -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.ShowSettled,
                showWallBreak = true,
                showDeadWall = true,
                take7 = WallBuildBeat.Take7Policy.Full,
            )

            // Hands: 12 tiles each (3 rounds x 2 stacks per seat).
            "tutorial.orient.wall.hands" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                dice = WallBuildBeat.DicePolicy.KeepRollHidden,
                showWallBreak = true,
                showDeadWall = true,
                showDealHands = true,
                showDeal13thTile = false,
            )

            // Hands 13: 13th tile dealt to each seat.
            "tutorial.orient.wall.hands_13" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                dice = WallBuildBeat.DicePolicy.KeepRollHidden,
                showWallBreak = true,
                showDeadWall = true,
                showDealHands = true,
                showDeal13thTile = true,
            )

            // East opens at 14: one more live-wall tile to the dealer.
            "tutorial.orient.wall.east_14" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.KeepRollHidden,
                showWallBreak = true,
                showDeadWall = true,
                showDealHands = true,
                showDeal13thTile = true,
                showDeal14thEast = true,
            )

            // Look at hands: line of 13/14; East open unsorted, others closed.
            "tutorial.orient.wall.look_hands" -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.KeepRollHidden,
                showWallBreak = true,
                showDeadWall = true,
                showDealHands = true,
                showDeal13thTile = true,
                showDeal14thEast = true,
                showLookHands = true,
            )

            // Sort hands: same line; reorder by ascending tile type (game display order).
            "tutorial.orient.wall.sort_hands",
            // Coach only: East’s first action is a discard (diagram same as sort_hands).
            "tutorial.orient.wall.east_discard",
            -> base(bodyKey, phase).copy(
                showSeatWinds = true,
                highlightEast = true,
                dice = WallBuildBeat.DicePolicy.KeepRollHidden,
                showWallBreak = true,
                showDeadWall = true,
                showDealHands = true,
                showDeal13thTile = true,
                showDeal14thEast = true,
                showLookHands = true,
                showSortHands = true,
            )

            else -> null
        }
    }

    fun phaseFor(bodyKey: String): WallBuildPhase? = when (bodyKey) {
        "tutorial.orient.wall.faces" -> WallBuildPhase.Faces
        "tutorial.orient.wall.copies",
        "tutorial.orient.wall.drawpile",
        "tutorial.orient.wall.ritual",
        -> WallBuildPhase.Copies
        "tutorial.orient.wall.facedown" -> WallBuildPhase.FaceDown
        "tutorial.orient.wall.shuffle" -> WallBuildPhase.Shuffle
        "tutorial.orient.wall.formed" -> WallBuildPhase.Wall
        "tutorial.orient.wall.pushed",
        "tutorial.orient.wall.seats",
        "tutorial.orient.wall.east",
        "tutorial.orient.wall.deal",
        "tutorial.orient.wall.dice",
        "tutorial.orient.wall.count",
        "tutorial.orient.wall.break",
        "tutorial.orient.wall.take7",
        "tutorial.orient.wall.dead",
        "tutorial.orient.wall.dead_out",
        "tutorial.orient.wall.hands",
        "tutorial.orient.wall.hands_13",
        "tutorial.orient.wall.east_14",
        "tutorial.orient.wall.look_hands",
        "tutorial.orient.wall.sort_hands",
        "tutorial.orient.wall.east_discard",
        -> WallBuildPhase.WallPush
        else -> null
    }

    private fun base(bodyKey: String, phase: WallBuildPhase) = WallBuildBeat(
        bodyKey = bodyKey,
        phase = phase,
        showSeatWinds = false,
        highlightEast = false,
        dice = WallBuildBeat.DicePolicy.ClearRoll,
        showCountHighlight = false,
        take7 = WallBuildBeat.Take7Policy.Off,
        showWallBreak = false,
        showDeadWall = false,
        showDealHands = false,
        showDeal13thTile = false,
        showDeal14thEast = false,
        showLookHands = false,
        showSortHands = false,
    )
}
