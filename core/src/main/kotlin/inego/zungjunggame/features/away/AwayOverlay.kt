package inego.zungjunggame.features.away

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

/** GL-free snapshot of the away card's panel and resume control after the last render. */
data class AwayGeometry(
    val panel: Rect?,
    val resume: Rect?,
)

/**
 * Blocking modal overlay shown when a player returns to the window/tab after
 * being detected as away during an ongoing online match.
 *
 * All moves during the away duration were forfeited. Clicking "Resume play"
 * clears away state on the server, hides the overlay, and restores manual control.
 *
 * **Ownership.** This overlay owns its own press → drag → release state machine
 * and reports the control a release resolved ([PointerResult]);
 * [AwayComponent] publishes the panel as the gesture region and bridges that
 * verdict to a [inego.zungjunggame.ui.ComponentAction]. No control travels as a
 * hit region, and an outside-panel press is the layer's `outside` policy, not a
 * surface verdict.
 */
class AwayOverlay : Disposable, GestureSurface<AwayOverlay.PointerResult> {
    private val glyphLayout = GlyphLayout()

    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var resumeLeft = 0f
    private var resumeBottom = 0f
    private var resumeW = 0f
    private var resumeH = 0f

    /** Press began on the resume control: a release there is a tap, not a drag. */
    private var pressOnResume = false
    private var pressY = Float.NaN
    private var dragged = false

    enum class PointerResult {
        /** Pressed the resume control. */
        Resume,
        /** The press resolved nothing. */
        None,
    }

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /** GL-free snapshot of the panel and resume control (null before the first render). */
    fun measureGeometry(): AwayGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        val resume = if (resumeW > 0f && resumeH > 0f) {
            Rect(resumeLeft, resumeBottom, resumeW, resumeH)
        } else {
            null
        }
        return AwayGeometry(panel, resume)
    }

    /**
     * Claim the press and record whether it began on the resume control. The
     * pipeline only routes presses inside the layer's gesture regions here, so a
     * press outside the card is the layer's `outside` policy, not a surface verdict.
     */
    override fun onPointerDown(x: Float, y: Float): Boolean {
        pressOnResume = inRect(x, y, resumeLeft, resumeBottom, resumeW, resumeH)
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
     * The release resolves the control the press began on: a completed press on
     * resume resumes, anywhere else resolves nothing. A press that began on the
     * control and was dragged is not a tap.
     */
    override fun onPointerUp(x: Float, y: Float): PointerResult {
        val startedOnResume = pressOnResume
        val moved = dragged
        clearGesture()
        return if (startedOnResume && !moved) PointerResult.Resume else PointerResult.None
    }

    override fun cancelGesture() = clearGesture()

    private fun clearGesture() {
        pressOnResume = false
        pressY = Float.NaN
        dragged = false
    }

    fun render(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        Fonts.ensureCreated()

        val px = whitePixel()
        val touch = InputProfile.detect() == InputProfile.Touch

        // Dim backdrop (blocking modal).
        batch.setColor(AppPalette.scrimHeavy)
        batch.draw(px, 0f, 0f, screenW, screenH)

        val titleFont = Fonts.labelActive
        val bodyFont = Fonts.body
        val btnFont = Fonts.button

        val padX = Fonts.px(28f)
        val padY = Fonts.px(22f)
        val titleGap = Fonts.px(14f)
        val messageGap = Fonts.px(22f)
        val btnH = max(Fonts.px(48f), btnFont.lineHeight + Fonts.px(22f))

        val portrait = screenH > screenW
        val maxPanelRatio = if (portrait) 0.88f else 0.68f
        val maxPanelW = (screenW * maxPanelRatio).coerceAtMost(screenW - Fonts.px(24f))

        val title = L10n.get("online.away_title")
        val message = L10n.get("online.away_message")
        val resumeLabel = L10n.get("online.away_resume")

        glyphLayout.setText(btnFont, resumeLabel)
        val resumeLabelW = glyphLayout.width
        val btnW = max(Fonts.px(160f), resumeLabelW + Fonts.px(48f))

        val minPanelW = if (touch) {
            max(Fonts.px(340f), screenW * 0.40f).coerceAtMost(maxPanelW)
        } else {
            Fonts.px(320f).coerceAtMost(maxPanelW)
        }

        glyphLayout.setText(titleFont, title)
        val titleW = glyphLayout.width

        val desiredW = max(titleW, btnW) + padX * 2f
        panelW = max(minPanelW, desiredW).coerceAtMost(maxPanelW)

        val maxTextW = panelW - padX * 2f

        glyphLayout.setText(titleFont, title, Color.WHITE, maxTextW, Align.center, false)
        val titleHeight = glyphLayout.height

        glyphLayout.setText(bodyFont, message, AppPalette.inkSoft, maxTextW, Align.center, true)
        val messageHeight = glyphLayout.height

        panelH = padY * 2f + titleHeight + titleGap + messageHeight + messageGap + btnH
        val avail = OverlayViewport.available(screenW, screenH)
        val clamped = OverlayViewport.clampSize(panelW, panelH, avail)
        panelW = clamped.first
        panelH = clamped.second
        val placed = OverlayViewport.placeCentered(panelW, panelH, avail)
        panelLeft = placed.first
        panelBottom = placed.second

        // Panel body + ivory rim (matches game menu / confirm chrome).
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        // Draw title
        val titleY = panelBottom + panelH - padY
        titleFont.draw(
            batch,
            title,
            panelLeft + padX,
            titleY,
            maxTextW,
            Align.center,
            false,
        )

        // Draw message
        val messageY = titleY - titleHeight - titleGap
        bodyFont.draw(
            batch,
            message,
            panelLeft + padX,
            messageY,
            maxTextW,
            Align.center,
            true,
        )

        // Draw Resume button
        resumeW = btnW
        resumeH = btnH
        resumeLeft = panelLeft + (panelW - resumeW) * 0.5f
        resumeBottom = panelBottom + padY

        drawButton(
            batch, px, resumeLabel, resumeLeft, resumeBottom, resumeW, resumeH,
            pointerWorldX, pointerWorldY,
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
    ) {
        val hovered =
            !pointerX.isNaN() && !pointerY.isNaN() &&
                inRect(pointerX, pointerY, left, bottom, w, h)
        val bg = OverlayChrome.rowFill(hovered)
        val rim = OverlayChrome.rowRim()
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
     * without a GL frame, since the panel and resume rects are otherwise only set
     * by [render] (see `AwayComponentTest`).
     */
    internal fun seedLayoutForTest(
        panel: Rect,
        resume: Rect,
    ) {
        panelLeft = panel.left
        panelBottom = panel.bottom
        panelW = panel.width
        panelH = panel.height
        resumeLeft = resume.left
        resumeBottom = resume.bottom
        resumeW = resume.width
        resumeH = resume.height
        clearGesture()
    }

    override fun dispose() {
    }

}
