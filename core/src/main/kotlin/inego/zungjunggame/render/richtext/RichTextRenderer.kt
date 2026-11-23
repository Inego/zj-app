package inego.zungjunggame.render.richtext

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.tiles.TileDrawer
import kotlin.math.max

/**
 * Draws a [LaidOutDoc] with [SpriteBatch] + [TileDrawer].
 *
 * [originX]/[originY] is the bottom-left of the content box in world space.
 * Ops already use y-up within that box.
 *
 * LibGDX [BitmapFont.draw] takes the **cap-height top** (top of capitals), not
 * the typographic baseline. Underlines are derived from that draw Y:
 * `trueBaseline = drawY − capHeight`, then a stroke just below it.
 *
 * Span colors come from [TextStyle.color] resolved through [palette].
 * Text uses [Fonts.drawText] so primary + CJK fallback match measure.
 */
class RichTextRenderer(
    private val measurer: GdxTextMeasurer = GdxTextMeasurer(),
    private val palette: RichColorPalette = RichColorPalette.Default,
) {
    /**
     * @param hoverLinkHref when non-null, text runs with that href are drawn
     *   slightly brighter (desktop pointer over a link).
     * @param alpha multiplies all drawn text / tile / underline opacity
     *   (slide fades, overlays). Does not mutate palette colors.
     */
    fun draw(
        batch: SpriteBatch,
        doc: LaidOutDoc,
        drawer: () -> TileDrawer,
        whitePixel: Texture,
        originX: Float,
        originY: Float,
        hoverLinkHref: String? = null,
        alpha: Float = 1f,
    ) {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0.001f) return
        for (op in doc.ops) {
            when (op) {
                is DrawOp.Text -> {
                    val font = measurer.fontFor(op.style, op.role)
                    val base = palette.resolve(op.style.color)
                    val color =
                        if (hoverLinkHref != null &&
                            op.linkHref != null &&
                            op.linkHref == hoverLinkHref
                        ) {
                            // Soft accent lift on hovered links.
                            Color(
                                (base.r * 0.35f + 1f * 0.65f).coerceIn(0f, 1f),
                                (base.g * 0.40f + 0.94f * 0.60f).coerceIn(0f, 1f),
                                (base.b * 0.45f + 0.68f * 0.55f).coerceIn(0f, 1f),
                                base.a * a,
                            )
                        } else {
                            Color(base.r, base.g, base.b, base.a * a)
                        }
                    val bx = originX + op.x
                    // [op.baselineY] is the BitmapFont.draw Y = cap-height top.
                    val drawY = originY + op.baselineY
                    Fonts.drawText(batch, font, op.text, bx, drawY, color)
                    if (op.style.underline && op.text.isNotBlank()) {
                        val textW = Fonts.textWidth(font, op.text)
                        val thick = max(1f, font.lineHeight * 0.06f)
                        // True baseline is [capHeight] below the draw Y (y-up).
                        // Sit the stroke just under that; [descent] is negative.
                        val trueBaseline = drawY - font.data.capHeight
                        val gap = max(1f, -font.data.descent * 0.25f)
                        val ulY = trueBaseline - gap - thick
                        batch.setColor(color)
                        batch.draw(whitePixel, bx, ulY, textW, thick)
                        batch.setColor(Color.WHITE)
                    }
                }
                is DrawOp.Tile -> {
                    val prev = batch.color.cpy()
                    batch.setColor(AppPalette.tint(AppPalette.headerInk, a))
                    drawer().drawOpen(
                        batch,
                        op.type,
                        originX + op.cx,
                        originY + op.cy,
                        op.w,
                        op.h,
                    )
                    batch.color = prev
                }
                is DrawOp.Underline -> {
                    // Legacy ops: [y] is BitmapFont.draw Y (cap top). Stroke under
                    // the true baseline when we know body metrics; else [y] alone.
                    val thick = op.thickness
                    val bodyCap = measurer.capHeight(TextRole.Body)
                    val ulY = originY + op.y - bodyCap - thick - 1f
                    val d = palette.default
                    batch.setColor(d.r, d.g, d.b, d.a * a)
                    batch.draw(
                        whitePixel,
                        originX + op.x0,
                        ulY,
                        op.x1 - op.x0,
                        thick,
                    )
                    batch.setColor(Color.WHITE)
                }
                is DrawOp.Fill -> {
                    val c = palette.resolve(op.color)
                    batch.setColor(c.r, c.g, c.b, c.a * a)
                    batch.draw(whitePixel, originX + op.x, originY + op.y, op.w, op.h)
                    batch.setColor(Color.WHITE)
                }
            }
        }
    }
}
