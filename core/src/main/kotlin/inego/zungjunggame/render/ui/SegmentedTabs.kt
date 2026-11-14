package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Horizontal segmented control: equal-width segments, one selected.
 *
 * Stateless about what the tabs mean — owner supplies labels and reacts to
 * [hitIndex]. Layout is last-[layout] rectangle; call [layout] before [render]
 * / hit tests each frame (or when the host panel resizes).
 */
class SegmentedTabs {
    private val glyphLayout = GlyphLayout()

    private var labels: List<String> = emptyList()
    var selectedIndex: Int = 0
        private set

    private var left = 0f
    private var bottom = 0f
    private var width = 0f
    private var height = 0f

    fun setLabels(labels: List<String>, selectedIndex: Int = 0) {
        this.labels = labels
        this.selectedIndex = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
    }

    fun select(index: Int) {
        if (labels.isEmpty()) return
        selectedIndex = index.coerceIn(0, labels.lastIndex)
    }

    fun layout(left: Float, bottom: Float, width: Float, height: Float) {
        this.left = left
        this.bottom = bottom
        this.width = width
        this.height = height
    }

    fun contains(worldX: Float, worldY: Float): Boolean =
        labels.isNotEmpty() &&
            worldX >= left && worldX <= left + width &&
            worldY >= bottom && worldY <= bottom + height

    /** Segment index under the pointer, or null. */
    fun hitIndex(worldX: Float, worldY: Float): Int? {
        if (!contains(worldX, worldY) || labels.isEmpty()) return null
        val n = labels.size
        val segW = width / n
        if (segW <= 0f) return null
        val i = ((worldX - left) / segW).toInt().coerceIn(0, n - 1)
        return i
    }

    /**
     * Draw the control. [pointerWorldX]/[pointerWorldY] highlight the hovered
     * segment (NaN skips hover).
     */
    fun render(
        batch: SpriteBatch,
        whitePixel: Texture,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        font: BitmapFont = Fonts.button,
    ) {
        if (labels.isEmpty() || width <= 1f || height <= 1f) return

        val n = labels.size
        val segW = width / n
        val border = max(1.2f, Fonts.px(1.5f))
        val hoverIndex =
            if (!pointerWorldX.isNaN() && !pointerWorldY.isNaN()) {
                hitIndex(pointerWorldX, pointerWorldY)
            } else {
                null
            }

        // Outer rim.
        batch.setColor(AppPalette.tint(AppPalette.rim, 0.45f))
        batch.draw(whitePixel, left, bottom, width, height)

        for (i in 0 until n) {
            val segLeft = left + i * segW
            val selected = i == selectedIndex
            val hovered = hoverIndex == i

            val fill = when {
                selected -> AppPalette.tint(AppPalette.plateHover, 0.98f)
                hovered -> AppPalette.tint(AppPalette.plateRaised, 0.96f)
                else -> AppPalette.tint(AppPalette.plateMuted, 0.96f)
            }
            batch.setColor(fill)
            batch.draw(
                whitePixel,
                segLeft + border,
                bottom + border,
                segW - border * 2f,
                height - border * 2f,
            )

            // Divider between segments (skip after last).
            if (i < n - 1) {
                batch.setColor(AppPalette.tint(AppPalette.rim, 0.35f))
                batch.draw(
                    whitePixel,
                    segLeft + segW - border * 0.5f,
                    bottom + border,
                    border,
                    height - border * 2f,
                )
            }

            val label = labels[i]
            glyphLayout.setText(font, label)
            val textColor = when {
                selected -> AppPalette.inkStrong
                hovered -> AppPalette.labelInkHover
                else -> AppPalette.inkWarm
            }
            font.color = textColor
            font.draw(
                batch,
                label,
                segLeft + (segW - glyphLayout.width) * 0.5f,
                bottom + height * 0.5f + glyphLayout.height * 0.5f,
            )
            font.color = Color.WHITE
        }
        batch.setColor(Color.WHITE)
    }
}
