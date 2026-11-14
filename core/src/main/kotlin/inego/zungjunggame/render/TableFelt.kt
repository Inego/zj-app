package inego.zungjunggame.render

import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.render.ui.AppPalette

/**
 * The table felt. Screen clears and felt-colored fills read this.
 *
 * [color] is shared. Do not mutate it. The value lives in [AppPalette] so the
 * felt moves with the rest of the app style.
 * The Android window color `res/values/color.xml` (`#FF1F6B47`) is the same
 * felt for the splash, which cannot read this object.
 */
object TableFelt {
    val R: Float get() = AppPalette.felt.r
    val G: Float get() = AppPalette.felt.g
    val B: Float get() = AppPalette.felt.b
    val A: Float get() = AppPalette.felt.a

    val color: Color = AppPalette.felt
}
