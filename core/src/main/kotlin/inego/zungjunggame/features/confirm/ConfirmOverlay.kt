package inego.zungjunggame.features.confirm

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.tapSlopPx
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** Pure geometry of a confirm dialog for one viewport size. */
data class ConfirmGeometry(
    val panel: Rect,
    val yes: Rect,
    val no: Rect,
)

/**
 * Modal yes/no dialog with clickable Yes / No buttons (mouse and touch).
 *
 * Desktop also accepts Y / Enter to confirm and N / Esc to cancel
 * (handled in [inego.zungjunggame.Main]).
 * Touch: tap outside the panel cancels; desktop click outside does the same.
 *
 * Title is an L10n key so mid-dialog language switch rebinds next frame.
 *
 * **Ownership.** This overlay owns its own press → drag → release state machine
 * and reports the control a release resolved ([PointerResult]);
 * [ConfirmComponent] publishes the panel as the gesture region and bridges that
 * verdict to a [inego.zungjunggame.ui.ComponentAction]. No control travels as a hit region, and an
 * outside-panel press is the layer's `outside` cancel path, not a surface
 * verdict.
 */
class ConfirmOverlay : Disposable, GestureSurface<ConfirmOverlay.PointerResult> {
    private val glyphLayout = GlyphLayout()


    private fun whitePixel(): Texture = WhitePixel.get()

    /** L10n key for the dialog body (resolved at draw via [title]). */
    private var titleKey: String = ""

    /** Question shown in the dialog body (live from [L10n]). */
    val title: String
        get() = if (titleKey.isEmpty()) "" else L10n.get(titleKey)

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f
    private var yesLeft = 0f
    private var yesBottom = 0f
    private var yesW = 0f
    private var yesH = 0f
    private var noLeft = 0f
    private var noBottom = 0f
    private var noW = 0f
    private var noH = 0f

    /** The control the in-flight press began on. */
    private enum class PressTarget { Yes, No, Panel, None }
    private var pressTarget = PressTarget.None
    private var pressY = Float.NaN
    private var dragged = false

    enum class PointerResult {
        /** Pressed the Yes / Confirm control. */
        Confirm,
        /** Pressed No / Cancel. */
        Cancel,
        /** The press began on the panel body (no action). */
        None,
    }

    /** @param titleKey L10n key for the question (e.g. `confirm.abandon_hand`). */
    fun setQuestion(titleKey: String) {
        this.titleKey = titleKey
    }

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /**
     * Claim the press and record the control it began on: Yes and No first, then
     * the panel body. The pipeline only routes presses inside the layer's gesture
     * regions here, so a press outside the card is the layer's `outside` policy, not a
     * surface verdict.
     */
    override fun onPointerDown(x: Float, y: Float): Boolean {
        pressTarget = when {
            inRect(x, y, yesLeft, yesBottom, yesW, yesH) -> PressTarget.Yes
            inRect(x, y, noLeft, noBottom, noW, noH) -> PressTarget.No
            containsPanel(x, y) -> PressTarget.Panel
            else -> PressTarget.None
        }
        pressY = y
        dragged = false
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): PointerResult? {
        if (pressY.isNaN()) return null
        if (abs(y - pressY) > tapSlopPx()) dragged = true
        return null
    }

    /**
     * The release resolves the control the press began on: a completed press on Yes
     * confirms, on No cancels, anywhere else resolves nothing. A press that began on
     * one control and was dragged is not a tap.
     */
    override fun onPointerUp(x: Float, y: Float): PointerResult {
        val startedOn = pressTarget
        val moved = dragged
        clearGesture()
        return when (startedOn) {
            PressTarget.Yes if !moved -> PointerResult.Confirm
            PressTarget.No if !moved -> PointerResult.Cancel
            else -> PointerResult.None
        }
    }

    override fun cancelGesture() = clearGesture()

    private fun clearGesture() {
        pressTarget = PressTarget.None
        pressY = Float.NaN
        dragged = false
    }

    /** Pure panel/button geometry for [screenW]×[screenH], shared by [render] and callers. */
    fun measure(screenW: Float, screenH: Float): ConfirmGeometry {
        val frame = layoutFor(screenW, screenH)
        return ConfirmGeometry(frame.panel, frame.yes, frame.no)
    }

    private class Frame(
        val panel: Rect,
        val yes: Rect,
        val no: Rect,
        val padX: Float,
        val padY: Float,
        val maxTextW: Float,
        val title: String,
    )

    private fun layoutFor(screenW: Float, screenH: Float): Frame {
        Fonts.ensureCreated()

        val itemFont = Fonts.button
        val padX = Fonts.px(28f)
        val padY = Fonts.px(20f)
        val titleGap = Fonts.px(18f)
        val btnH = max(Fonts.px(48f), itemFont.lineHeight + Fonts.px(22f))
        val btnGap = Fonts.px(12f)

        val touch = InputProfile.detect() == InputProfile.Touch
        val portrait = screenH > screenW
        val maxPanelRatio = if (portrait) 0.88f else 0.72f
        val maxPanelW = (screenW * maxPanelRatio).coerceAtMost(screenW - Fonts.px(24f))

        val yesLabel = L10n.get("confirm.yes")
        val noLabel = L10n.get("confirm.no")
        val title = this.title
        glyphLayout.setText(itemFont, yesLabel)
        val yesLabelW = glyphLayout.width
        glyphLayout.setText(itemFont, noLabel)
        val noLabelW = glyphLayout.width
        // Comfortable button width from label + padding (match menu row density).
        val btnW = max(Fonts.px(100f), max(yesLabelW, noLabelW) + Fonts.px(40f))
        val pairW = btnW * 2f + btnGap

        glyphLayout.setText(Fonts.labelActive, title)
        val titleW = glyphLayout.width

        val minPanelW = if (touch) {
            max(Fonts.px(320f), screenW * 0.36f).coerceAtMost(maxPanelW)
        } else {
            Fonts.px(280f).coerceAtMost(maxPanelW)
        }
        val desiredW = max(titleW, pairW) + padX * 2f
        val panelW = max(minPanelW, desiredW).coerceAtMost(maxPanelW)

        val maxTextW = panelW - padX * 2f
        glyphLayout.setText(Fonts.labelActive, title, Color.WHITE, maxTextW, Align.center, true)

        val panelH = padY * 2f + glyphLayout.height + titleGap + btnH
        val avail = OverlayViewport.available(screenW, screenH)
        val clamped = OverlayViewport.clampSize(panelW, panelH, avail)
        val placed = OverlayViewport.placeCentered(clamped.first, clamped.second, avail)

        val pairLeft = placed.first + (clamped.first - pairW) * 0.5f
        val btnBottom = placed.second + padY

        return Frame(
            panel = Rect(placed.first, placed.second, clamped.first, clamped.second),
            yes = Rect(pairLeft, btnBottom, btnW, btnH),
            no = Rect(pairLeft + btnW + btnGap, btnBottom, btnW, btnH),
            padX = padX,
            padY = padY,
            maxTextW = maxTextW,
            title = title,
        )
    }

    fun render(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        val frame = layoutFor(screenW, screenH)
        val px = whitePixel()

        panelLeft = frame.panel.left
        panelBottom = frame.panel.bottom
        panelW = frame.panel.width
        panelH = frame.panel.height
        yesLeft = frame.yes.left
        yesBottom = frame.yes.bottom
        yesW = frame.yes.width
        yesH = frame.yes.height
        noLeft = frame.no.left
        noBottom = frame.no.bottom
        noW = frame.no.width
        noH = frame.no.height

        // Dim backdrop.
        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)

        // Panel body + ivory rim (matches game menu chrome).
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        Fonts.labelActive.draw(
            batch,
            frame.title,
            panelLeft + frame.padX,
            panelBottom + panelH - frame.padY,
            frame.maxTextW,
            Align.center,
            true,
        )

        drawButton(
            batch, px, L10n.get("confirm.yes"), yesLeft, yesBottom, yesW, yesH,
            pointerWorldX, pointerWorldY, positive = true,
        )
        drawButton(
            batch, px, L10n.get("confirm.no"), noLeft, noBottom, noW, noH,
            pointerWorldX, pointerWorldY, positive = false,
        )
    }

    private fun drawButton(
        batch: SpriteBatch,
        px: Texture,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        pointerX: Float,
        pointerY: Float,
        positive: Boolean,
    ) {
        val hovered =
            !pointerX.isNaN() && !pointerY.isNaN() &&
                inRect(pointerX, pointerY, left, bottom, w, h)
        val bg = OverlayChrome.actionFill(hovered = hovered, primary = positive)
        val rim = OverlayChrome.actionRim()
        val font = Fonts.button
        OverlayChrome.drawButton(
            batch, px, font, label,
            left, bottom, w, h, bg, rim,
            if (hovered) AppPalette.inkStrong else AppPalette.ink,
        )
    }

    private fun inRect(
        px: Float,
        py: Float,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
    ): Boolean =
        px >= left && px <= left + w && py >= bottom && py <= bottom + h

    /**
     * Geometry seam for plain JVM tests: the press machine must be provable
     * without a GL frame, since the panel and button rects are otherwise only set
     * by [render] (see `ConfirmComponentTest`).
     */
    internal fun seedLayoutForTest(
        panel: Rect,
        yes: Rect,
        no: Rect,
    ) {
        panelLeft = panel.left
        panelBottom = panel.bottom
        panelW = panel.width
        panelH = panel.height
        yesLeft = yes.left
        yesBottom = yes.bottom
        yesW = yes.width
        yesH = yes.height
        noLeft = no.left
        noBottom = no.bottom
        noW = no.width
        noH = no.height
        clearGesture()
    }

    override fun dispose() {
    }

}
