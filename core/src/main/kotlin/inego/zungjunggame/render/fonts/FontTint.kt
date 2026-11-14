package inego.zungjunggame.render.fonts

import com.badlogic.gdx.graphics.Color

/**
 * [com.badlogic.gdx.graphics.Color.set] / [com.badlogic.gdx.graphics.g2d.BitmapFont.setColor] mutate the
 * target in place. A saved reference is the same object, so "restore" is a no-op
 * unless components are copied first.
 */
object FontTint {
    inline fun withTint(target: Color, tint: Color, draw: () -> Unit) {
        val r = target.r
        val g = target.g
        val b = target.b
        val a = target.a
        target.set(tint)
        try {
            draw()
        } finally {
            target.set(r, g, b, a)
        }
    }
}
