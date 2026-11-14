package inego.zungjunggame.render.table

/**
 * How [PlayTableView] treats engine chrome.
 *
 * [Play] — live table: hover, clicks, HINT. Overlay coaches that are an action
 * preamble (discard or claim wait) stay here so the expected control plays.
 *
 * [ReadOnly] — snapshot: draw the same tiles and Hu / claim / Keep panel the
 * engine would, frozen (no hover, no hits). Lecture overlays use this so
 * Blessing of Heaven still *shows* Hu 160 without offering a button.
 *
 * A live free-play or online session publishes [Play] on [TableRenderInputs]
 * every frame, so a leftover lecture lock cannot freeze the next hand.
 */
enum class TableInteract {
    Play,
    ReadOnly,
    ;

    val allowsHover: Boolean get() = this == Play

    val allowsHits: Boolean get() = this == Play

    companion object {
        /**
         * Board-overlay lectures are [ReadOnly]. Action-preamble overlays
         * (coach then a wait) and anything that is not a board overlay are [Play].
         */
        fun forOverlay(boardOverlay: Boolean, actionPreamble: Boolean): TableInteract =
            if (boardOverlay && !actionPreamble) ReadOnly else Play
    }
}
