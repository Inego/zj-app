package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.tutorial.slide.SlideChannel
import inego.zungjunggame.tutorial.slide.SlideFrame
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * The highlight and callout drawn over a focused tile.
 *
 * Two modes, one owner. A diagram focus frames the anchor in accent, redrawing
 * matching tile faces on top so the accent wash does not tint a set (kong,
 * triplet, …), then floats the callout beside the target. A live-board focus
 * has no halo, frame or second face — the tile stays the board's tile — so it
 * only hangs the callout from a leader onto the live rect.
 *
 * The overlay owns the target ([TutorialFocus]), the motion channel and the
 * alpha; this painter owns the ink and the placement of the highlight around a
 * rect someone else measured.
 */
internal class CoachFocusChrome(private val callout: CoachCallout = CoachCallout()) {

    /**
     * Highlight the diagram target at [anchor] and float its callout.
     * Call after the diagram drawers have published their anchors.
     */
    fun paintDiagramFocus(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        screenH: Float,
        anchor: AnchorRect,
        focus: TutorialFocus,
        slideFrame: SlideFrame,
        tiles: CoachDiagramTiles,
        avoidBottom: Float,
        avoidRight: Float = Float.POSITIVE_INFINITY,
    ) {
        val focusA = slideFrame.alphaOf(SlideChannel.Focus)
        if (focusA < 0.01f) return
        val focusSc = slideFrame.scaleOf(SlideChannel.Focus)

        val pad = Fonts.px(6f)
        val cx = anchor.cx
        val cy = anchor.cy
        val hlW = (anchor.w + pad * 2f) * focusSc
        val hlH = (anchor.h + pad * 2f) * focusSc
        val hlLeft = cx - hlW * 0.5f
        val hlBottom = cy - hlH * 0.5f
        batch.setColor(AppPalette.tint(AppPalette.accent, 0.22f * focusA))
        batch.draw(px, hlLeft, hlBottom, hlW, hlH)
        TutorialTargets.parseTile(focus.targetId)?.let { type ->
            // Redraw the faces so the accent wash does not tint the target.
            tiles.redrawFaces(batch, type)
        }
        UiPlate.drawFrame(
            batch, px, hlLeft, hlBottom, hlW, hlH,
            AppPalette.accent.r, AppPalette.accent.g, AppPalette.accent.b, 0.95f * focusA,
            max(2f, Fonts.px(2.5f)),
        )

        paintBesideCallout(
            batch, px, screenW, screenH, anchor, focus.calloutKey, slideFrame,
            avoidBottom, avoidRight,
        )
    }

    /**
     * Board-overlay focus: popup above the live tile with a vertical leader.
     * No halo, frame, or second face — the tile stays the board's tile.
     */
    fun paintLiveFocus(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        live: AnchorRect,
        focus: TutorialFocus,
        slideFrame: SlideFrame,
        copyScrimBottom: Float,
        buttonBandTop: Float,
        avoidRight: Float = Float.POSITIVE_INFINITY,
    ) {
        val calloutKey = focus.calloutKey
        if (calloutKey.isNullOrEmpty()) return
        val calloutA = slideFrame.alphaOf(SlideChannel.Callout)
        if (calloutA < 0.01f) return
        val text = L10n.get(calloutKey)
        if (text.isBlank()) return

        val layout = callout.layout(text, callout.maxBubbleW(minOf(screenW, avoidRight)), Align.center)
        callout.paintLeader(
            batch = batch,
            px = px,
            screenW = screenW,
            anchor = live,
            layout = layout,
            scale = slideFrame.scaleOf(SlideChannel.Callout),
            alpha = calloutA,
            copyScrimBottom = copyScrimBottom,
            buttonBandTop = buttonBandTop,
            avoidRight = avoidRight,
        )
    }

    /** Callout beside a diagram target: right, else left, else above, else below. */
    private fun paintBesideCallout(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        screenH: Float,
        anchor: AnchorRect,
        calloutKey: String?,
        slideFrame: SlideFrame,
        avoidBottom: Float,
        avoidRight: Float,
    ) {
        if (calloutKey.isNullOrEmpty()) return
        val calloutA = slideFrame.alphaOf(SlideChannel.Callout)
        val calloutSc = slideFrame.scaleOf(SlideChannel.Callout)
        if (calloutA < 0.01f) return
        val text = L10n.get(calloutKey)
        if (text.isBlank()) return

        val layout = callout.layout(text, callout.maxBubbleW(minOf(screenW, avoidRight)), Align.left)
        val (bubbleLeft0, bubbleBottom0) = callout.place(
            anchor = anchor,
            bubbleW = layout.bubbleW,
            bubbleH = layout.bubbleH,
            screenW = screenW,
            screenH = screenH,
            avoidBottom = avoidBottom,
            avoidRight = avoidRight,
        )
        // Grow about the center so the bubble scales in place, not from a corner.
        val bcx = bubbleLeft0 + layout.bubbleW * 0.5f
        val bcy = bubbleBottom0 + layout.bubbleH * 0.5f
        val bubbleLeft = bcx - layout.bubbleW * calloutSc * 0.5f
        val bubbleBottom = bcy - layout.bubbleH * calloutSc * 0.5f
        callout.paint(batch, px, layout, bubbleLeft, bubbleBottom, calloutSc, calloutA)
    }
}
