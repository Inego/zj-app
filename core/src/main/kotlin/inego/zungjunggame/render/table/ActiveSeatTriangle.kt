package inego.zungjunggame.render.table

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable

/**
 * Paints the active seat's wash: the flat panel, and the wedge from its inner
 * edge to the screen centre.
 *
 * A separate pair of shapes. The panel is one colour, the panel's wash. The
 * wedge uses that wash on the inner edge and the same wash at alpha 0 at the
 * apex, so the lightening falls off in a straight line and the tip adds nothing.
 * The apex must not carry the felt's own RGB. Vertex colours are interpolated
 * before the blend, and an opaque felt mixed with a translucent white comes out
 * brighter in the middle than the wash at the base.
 *
 * [ImmediateModeRenderer20] carries one colour per vertex;
 * [com.badlogic.gdx.graphics.glutils.ShapeRenderer.triangle] cannot. Both shapes
 * are this one draw: while [ActiveSeatSweep] is turning the panel, the quad is
 * not axis-aligned, so a sprite-batch rect cannot paint it. The wedge is drawn
 * first and the panel on top, so the shared inner edge is the flat wash.
 *
 * [corners] is [ActiveSeatSweep.sample]: inner-along0, inner-along1,
 * outer-along1, outer-along0. The apex is the screen centre.
 *
 * The renderer takes one matrix and does not read the batch transform. The
 * lesson is translated into the tutorial content zone, and the tiles follow
 * that transform, so the matrix passed here is the batch projection multiplied
 * by it. On a full-window board the transform is identity.
 */
class ActiveSeatTriangle : Disposable {
    private var triangles: ImmediateModeRenderer20? = null
    private val combined = Matrix4()

    fun draw(
        batch: SpriteBatch,
        corners: FloatArray,
        apexX: Float,
        apexY: Float,
    ) {
        if (corners.size < 8) return
        val wash = ActiveSeatFelt.wash()
        if (wash[3] <= 0.01f) return
        val apexWash = ActiveSeatFelt.wash(alpha = 0f)

        // The table's sprites are a quad batch. These shapes are this renderer's
        // own draw, then the batch continues. Ending the batch turns blending off.
        batch.end()
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        // Wedge (3) plus two triangles for the panel (6).
        val renderer = triangles ?: ImmediateModeRenderer20(9, false, true, 0).also { triangles = it }
        combined.set(batch.projectionMatrix).mul(batch.transformMatrix)
        renderer.begin(combined, GL20.GL_TRIANGLES)
        vertex(renderer, wash, corners[0], corners[1])
        vertex(renderer, wash, corners[2], corners[3])
        vertex(renderer, apexWash, apexX, apexY)
        vertex(renderer, wash, corners[0], corners[1])
        vertex(renderer, wash, corners[2], corners[3])
        vertex(renderer, wash, corners[4], corners[5])
        vertex(renderer, wash, corners[0], corners[1])
        vertex(renderer, wash, corners[4], corners[5])
        vertex(renderer, wash, corners[6], corners[7])
        renderer.end()
        batch.begin()
    }

    override fun dispose() {
        triangles?.dispose()
        triangles = null
    }

    private fun vertex(renderer: ImmediateModeRenderer20, color: FloatArray, x: Float, y: Float) {
        renderer.color(color[0], color[1], color[2], color[3])
        renderer.vertex(x, y, 0f)
    }
}
