package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.tutorial.slide.layout.SlideChromeLayout
import inego.zungjunggame.tutorial.slide.layout.SlideViewport

/**
 * Bottom action row: Confirm, post-pass Next/Try again, or Back alone.
 *
 * The plates are the coach's Back / Next family, not a second design:
 * [SlideChromeLayout] owns their height, their pad and the width a label earns,
 * [TableChip] paints them, so a quiz Back is the same control as a coach Back.
 * A row whose labels cannot fit shares the width instead of each dock inventing
 * its own fraction of the content zone.
 */
object QuizActionDock {

    private val glyph = GlyphLayout()

    /** One plate in the row; [minDesignPx] is its role's floor, not its size. */
    private class Button(
        val kind: QuizControlKind,
        val id: String,
        val label: String,
        val minDesignPx: Float,
        val emphasized: Boolean,
    )

    fun drawPostPass(
        batch: SpriteBatch,
        px: Texture,
        vp: SlideViewport,
        contentLeft: Float,
        contentW: Float,
        dockBottom: Float,
        btnH: Float,
        backLabel: String,
        tryAgainLabel: String,
        nextLabel: String,
        pointerX: Float,
        pointerY: Float,
        alpha: Float,
        hits: QuizHitRegistrar,
    ) {
        val buttons = buildList {
            if (backLabel.isNotEmpty()) add(back(backLabel))
            add(
                Button(
                    QuizControlKind.TryAgain, "try_again", tryAgainLabel,
                    SlideChromeLayout.ACTION_BUTTON_MIN_PX, emphasized = false,
                ),
            )
            add(
                Button(
                    QuizControlKind.Next, "next", nextLabel,
                    SlideChromeLayout.ACTION_BUTTON_MIN_PX, emphasized = true,
                ),
            )
        }
        drawRow(
            batch, px, vp, contentLeft, contentW, dockBottom, btnH,
            buttons, pointerX, pointerY, alpha, hits,
        )
    }

    fun drawConfirm(
        batch: SpriteBatch,
        px: Texture,
        vp: SlideViewport,
        contentLeft: Float,
        contentW: Float,
        dockBottom: Float,
        btnH: Float,
        backLabel: String,
        confirmLabel: String,
        pointerX: Float,
        pointerY: Float,
        alpha: Float,
        hits: QuizHitRegistrar,
    ) {
        val buttons = buildList {
            if (backLabel.isNotEmpty()) add(back(backLabel))
            add(
                Button(
                    QuizControlKind.Confirm, "confirm", confirmLabel,
                    SlideChromeLayout.ACTION_BUTTON_MIN_PX, emphasized = true,
                ),
            )
        }
        drawRow(
            batch, px, vp, contentLeft, contentW, dockBottom, btnH,
            buttons, pointerX, pointerY, alpha, hits,
        )
    }

    fun drawBackOnly(
        batch: SpriteBatch,
        px: Texture,
        vp: SlideViewport,
        contentLeft: Float,
        contentW: Float,
        dockBottom: Float,
        btnH: Float,
        backLabel: String,
        pointerX: Float,
        pointerY: Float,
        alpha: Float,
        hits: QuizHitRegistrar,
    ) {
        if (backLabel.isEmpty()) return
        drawRow(
            batch, px, vp, contentLeft, contentW, dockBottom, btnH,
            listOf(back(backLabel)),
            pointerX, pointerY, alpha, hits,
        )
    }

    private fun back(label: String): Button =
        Button(QuizControlKind.Back, "back", label, SlideChromeLayout.BACK_BUTTON_MIN_PX, false)

    /** Lay [buttons] as one centered row, paint each plate, publish its hit rect. */
    private fun drawRow(
        batch: SpriteBatch,
        px: Texture,
        vp: SlideViewport,
        contentLeft: Float,
        contentW: Float,
        dockBottom: Float,
        btnH: Float,
        buttons: List<Button>,
        pointerX: Float,
        pointerY: Float,
        alpha: Float,
        hits: QuizHitRegistrar,
    ) {
        val gap = vp.px(12f)
        val widths = SlideChromeLayout.fitActionRow(
            widths = buttons.map {
                SlideChromeLayout.actionButtonWidth(vp, labelWidth(it.label), it.minDesignPx)
            },
            gap = gap,
            maxW = contentW,
        )
        val blockW = widths.sum() + gap * (buttons.size - 1)
        var x = contentLeft + (contentW - blockW) * 0.5f
        for (i in buttons.indices) {
            val button = buttons[i]
            val w = widths[i]
            hits.add(button.kind, button.id, x, dockBottom, w, btnH)
            TableChip.draw(
                batch, px, button.label, x, dockBottom, w, btnH,
                pointerX, pointerY,
                tone = if (button.emphasized) TableChip.Tone.Emphasized else TableChip.Tone.Idle,
                alpha = alpha,
            )
            x += w + gap
        }
    }

    /** Natural (unwrapped) width of [label] at the button face. */
    private fun labelWidth(label: String): Float {
        glyph.setText(Fonts.button, label)
        return glyph.width
    }
}
