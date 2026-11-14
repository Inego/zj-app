package inego.zungjunggame.render

import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.render.fonts.FontTint
import kotlin.test.Test
import kotlin.test.assertEquals

class FontTintTest {

    @Test
    fun withTintRestoresAfterInPlaceSet() {
        val target = Color(1f, 0.8f, 0.2f, 1f)
        FontTint.withTint(target, Color(0.1f, 0.2f, 0.3f, 0.25f)) {
            assertEquals(0.25f, target.a, 1e-5f)
            assertEquals(0.1f, target.r, 1e-5f)
        }
        assertEquals(1f, target.a, 1e-5f)
        assertEquals(1f, target.r, 1e-5f)
        assertEquals(0.8f, target.g, 1e-5f)
        assertEquals(0.2f, target.b, 1e-5f)
    }
}
