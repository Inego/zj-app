package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.tutorial.QuizOptionMark

/**
 * The ink a quiz option's mark glows with.
 *
 * The marks are semantics; the hues belong to [AppPalette]. This helper only
 * says which role a mark maps to, so a painter never names a colour.
 */
internal object QuizMarks {
    fun glowInk(mark: QuizOptionMark?): Color? = when (mark) {
        QuizOptionMark.Hit -> AppPalette.tint(AppPalette.success, 0.95f)
        QuizOptionMark.FalsePositive -> AppPalette.danger
        QuizOptionMark.Miss -> AppPalette.info
        QuizOptionMark.Idle, null -> null
    }
}
