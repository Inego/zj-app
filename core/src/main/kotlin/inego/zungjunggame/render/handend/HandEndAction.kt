package inego.zungjunggame.render.handend

/** Terminal hand-end button (under the center banner or score breakdown). */
enum class HandEndAction {
    /** Open the session score table. */
    ScoreTable,
    /** Deal the next hand within the current game. */
    NextHand,
    /** Open New Game setup (last hand of a finite game, or explicit restart). */
    NewGame,
    /** Start matchmaking again with the previous online queue settings. */
    PlayAgain,
    /** Leave a finished online table and return to Online home. */
    ReturnHome,
    /** Open the Shadow Ranked comparison tables for a finished challenge. */
    Comparison,
}
