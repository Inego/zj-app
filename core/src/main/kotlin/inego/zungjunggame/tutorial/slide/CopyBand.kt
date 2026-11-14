package inego.zungjunggame.tutorial.slide

/**
 * The teaching beats that share one visual context, and therefore one copy
 * band.
 *
 * The coach measures every title and body in [scenes] at the current viewport
 * and reserves the tallest. That is the band: a measured fact about the copy
 * that will actually be shown, not a guessed line count. The diagram or live
 * table below the copy then keeps one position for the whole band, so paging
 * between its beats cannot move it.
 *
 * A diagram's band is the consecutive run of its beats. A live board's band is
 * every board-overlay beat in the **campaign**: the table stays open across
 * waits and quizzes, and Next can cross from one lesson's last board beat into
 * the next lesson's first, so the pane it occupies must not change on either
 * kind of boundary.
 *
 * A beat with no continuity key is a band of one: there is no shared content
 * below its copy to hold still.
 */
data class CopyBand(val scenes: List<SlideScene>) {
    init {
        require(scenes.isNotEmpty()) { "copy band needs at least one beat" }
    }

    companion object {
        fun of(scene: SlideScene): CopyBand = CopyBand(listOf(scene))
    }
}
