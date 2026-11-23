package inego.zungjunggame.render.hint

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.ParkRelease
import inego.zungjunggame.render.ui.ParkableChrome
import inego.zungjunggame.render.ui.UiChip
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.ParkDrag
import inego.zungjunggame.render.ui.ParkGeometry
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of the HINT button's hit box for layout. */
data class HintButtonGeometry(
    val hit: Rect?,
)

/**
 * Cross-platform HINT control above the right end of the human hand row.
 * Same action as desktop H:
 * - first press: classic engine co-best + start UCB flat MC in the background
 * - after ~1 s: MC avgΔ / n labels; highlight switches to best avgΔ
 * - unique pick: second press plays it (button stays active/cyan)
 * - multi-tie: button greys (exhausted); player must choose
 *
 * On touch, sits farther above the tiles with a larger hit target so a
 * fingertip is less likely to hit the rightmost hand tiles instead.
 *
 * Parkable ([ParkableChrome]): the button covers the rightmost river/hand tiles
 * on a small phone, so pressing it and dragging moves it — the same
 * press → slop → park gesture as the hand-end score card. A press that never
 * moves is still a tap on HINT. The hit box follows the parked button, so the
 * control is always where it is drawn.
 */
class HintButtonOverlay(
    private val chip: UiChip,
) : ParkableChrome {
    private val glyphLayout = GlyphLayout()
    private var hit: Hit? = null

    /** Park gesture for the button (offset from its pose above the hand row). */
    private val park = ParkDrag()

    /** Visual state mirroring [HintUi] (Idle / Active cyan / Exhausted muted). */
    enum class Mode {
        Idle,
        Active,
        Exhausted,
    }

    data class Hit(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean {
            val hw = w * 0.5f
            val hh = h * 0.5f
            return px >= cx - hw && px <= cx + hw &&
                py >= cy - hh && py <= cy + hh
        }
    }

    fun clear() {
        hit = null
    }

    /**
     * Seed the player's remembered place, an offset from the button's rest pose.
     * The board hands it in when it appears; the next render clamps it through the
     * same [ParkGeometry] the drag uses.
     */
    fun setPark(x: Float, y: Float) {
        park.setPark(x, y)
    }

    fun contains(worldX: Float, worldY: Float): Boolean =
        hit?.contains(worldX, worldY) == true

    /**
     * The button's placed pose for this input and the current park state. Pure:
     * it reads the park and never advances it, so the measure path can compute the
     * same box a later [render] draws.
     */
    data class Layout(
        val cx: Float,
        val cy: Float,
        val width: Float,
        val height: Float,
        val hit: Hit,
        val restLeft: Float,
        val restBottom: Float,
        val parkX: Float,
        val parkY: Float,
        val bounds: Rect,
        /** Face scale the label is painted at, so its ink matches the laid-out plate. */
        val labelScale: Float,
    )

    /** Current hit box for this pose; no prior render needed. */
    fun hitBox(
        rowRightX: Float,
        rowCenterY: Float,
        handH: Float,
        screenW: Float,
        screenH: Float,
        edgeInsetBottom: Float,
    ): Hit = layout(rowRightX, rowCenterY, handH, screenW, screenH, edgeInsetBottom).hit

    fun layout(
        rowRightX: Float,
        rowCenterY: Float,
        handH: Float,
        screenW: Float,
        screenH: Float,
        edgeInsetBottom: Float,
    ): Layout {
        val touch = InputProfile.detect() == InputProfile.Touch
        // Android draws the whole chip — plate *and* label — at two thirds of its
        // design size. The plate is sized for a fingertip and for the park gesture;
        // on a phone it crowds the felt above the hand, and the label shrinks with
        // it rather than staying full size inside a smaller plate.
        val labelScale =
            if (Gdx.app?.type == Application.ApplicationType.Android) ANDROID_SCALE else 1f
        val buttonFont = Fonts.button
        val label = L10n.get("hint.label")
        glyphLayout.setText(buttonFont, label)
        val padX = if (touch) Fonts.px(20f) else Fonts.px(14f)
        val padY = if (touch) Fonts.px(14f) else Fonts.px(8f)
        val minW = if (touch) Fonts.px(72f) else Fonts.px(56f)
        val minH = if (touch) Fonts.px(44f) else Fonts.px(28f)
        val btnW = max(glyphLayout.width + padX * 2f, minW) * labelScale
        val btnH = max(glyphLayout.height + padY * 2f, minH) * labelScale

        val rightX =
            if (!rowRightX.isNaN()) rowRightX
            else screenW * 0.5f + handH * 4f
        val baseY =
            if (!rowCenterY.isNaN()) rowCenterY
            else edgeInsetBottom
        val handTop = baseY + handH * 0.5f
        // Desktop used to sit only ~0.22× handH above the tiles; that collides with
        // the two-line MC avgΔ / n labels on the rightmost discard. Always reserve the
        // full label stack so the button does not jump when stats appear ~1 s later.
        val mcLabelReserve = mcHintLabelStackHeight()
        val clearance =
            if (touch) max(handH * 0.55f, max(Fonts.px(40f), mcLabelReserve))
            else max(handH * 0.22f, max(Fonts.px(12f), mcLabelReserve))
        val restCx = rightX - btnW * 0.5f
        val restCy = handTop + clearance + btnH * 0.5f

        // Park: the button starts above the right end of the hand and the player
        // can move it anywhere on the board.
        val restLeft = restCx - btnW * 0.5f
        val restBottom = restCy - btnH * 0.5f
        val bounds = OverlayViewport.available(screenW, screenH)
        val placed = ParkGeometry.place(
            restLeft = restLeft,
            restBottom = restBottom,
            blockW = btnW,
            blockH = btnH,
            parkX = park.parkX,
            parkY = park.parkY,
            bounds = bounds,
        )
        val cx = placed.left + btnW * 0.5f
        val cy = placed.bottom + btnH * 0.5f
        // Wider hit only left/right (not down toward the tiles).
        val hitW = if (touch) btnW + Fonts.px(16f) else btnW
        return Layout(
            cx = cx,
            cy = cy,
            width = btnW,
            height = btnH,
            hit = Hit(cx, cy, hitW, btnH),
            restLeft = restLeft,
            restBottom = restBottom,
            parkX = placed.parkX,
            parkY = placed.parkY,
            bounds = bounds,
            labelScale = labelScale,
        )
    }

    fun render(
        batch: SpriteBatch,
        rowRightX: Float,
        rowCenterY: Float,
        handH: Float,
        screenW: Float,
        screenH: Float,
        edgeInsetBottom: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
        mode: Mode = Mode.Idle,
        metricText: String? = null,
    ) {
        val geom = layout(rowRightX, rowCenterY, handH, screenW, screenH, edgeInsetBottom)
        park.setPark(geom.parkX, geom.parkY)
        park.setGeometry(geom.restLeft, geom.restBottom, geom.width, geom.height, geom.bounds)
        val buttonHit = geom.hit
        hit = buttonHit

        val hovered =
            !pointerWorldX.isNaN() &&
                !pointerWorldY.isNaN() &&
                buttonHit.contains(pointerWorldX, pointerWorldY)
        // A clamped button can stop while the finger keeps going, so the grab
        // chrome also stands in for hover while the button is being dragged.
        val grabbing = park.moved

        val buttonFont = Fonts.button
        val label = L10n.get("hint.label")
        val btnW = geom.width
        val btnH = geom.height
        val cx = geom.cx
        val cy = geom.cy
        val left = cx - btnW * 0.5f
        val bottom = cy - btnH * 0.5f
        val litUp = (hovered || grabbing) && mode != Mode.Exhausted
        val bg = when {
            litUp -> AppPalette.plateHover
            mode == Mode.Active -> AppPalette.infoFill
            mode == Mode.Exhausted -> AppPalette.buttonFillDisabled
            else -> AppPalette.plate
        }
        val border = when {
            litUp -> AppPalette.tint(AppPalette.accent, 0.98f)
            mode == Mode.Active -> AppPalette.infoRim
            mode == Mode.Exhausted -> AppPalette.rimDisabled
            else -> AppPalette.tint(AppPalette.panelRim, 0.82f)
        }
        chip.drawChrome(
            batch = batch,
            left = left,
            bottom = bottom,
            w = btnW,
            h = btnH,
            bg = bg,
            border = border,
            borderT = UiChip.borderThickness(btnH, emphasized = mode == Mode.Active && !litUp),
            shadow = Fonts.px(2f),
            sheenAlpha = if (litUp) 0.14f else 0.06f,
        )

        val labelColor = when (mode) {
            Mode.Active if !litUp -> AppPalette.infoInkSoft
            Mode.Exhausted -> AppPalette.tint(AppPalette.inkInert, 0.90f)
            else -> AppPalette.ink
        }
        buttonFont.color = labelColor
        // The plate was laid out from the label measured at scale 1; shrink the ink
        // by the same factor so it stays centred in the smaller plate.
        Fonts.withFaceScale(buttonFont, geom.labelScale) {
            buttonFont.draw(
                batch,
                label,
                cx - glyphLayout.width * geom.labelScale * 0.5f,
                cy + glyphLayout.height * geom.labelScale * 0.5f,
            )
        }
        buttonFont.color = Color.WHITE

        if (!metricText.isNullOrEmpty()) {
            drawMetric(batch, metricText, cx, bottom, bottom + btnH, geom.bounds)
        }
    }

    private fun drawMetric(
        batch: SpriteBatch,
        text: String,
        buttonCx: Float,
        buttonBottom: Float,
        buttonTop: Float,
        bounds: Rect,
    ) {
        Fonts.ensureCreated()
        val font = Fonts.button
        val gap = Fonts.px(4f)
        val margin = Fonts.px(4f)
        Fonts.withFaceScale(font, METRIC_SCALE) {
            val textW = Fonts.textWidth(font, text)
            val textH = font.capHeight
            val minLeft = bounds.left + margin
            val maxLeft = max(minLeft, bounds.right - margin - textW)
            val x = (buttonCx - textW * 0.5f).coerceIn(minLeft, maxLeft)
            val aboveBaseline = buttonTop + gap + textH
            val y = if (aboveBaseline <= bounds.top - margin) {
                aboveBaseline
            } else {
                buttonBottom - gap
            }
            Fonts.drawText(batch, font, text, x, y, METRIC_INK)
        }
    }

    // ── ParkableChrome ───────────────────────────────────────────────────────
    // Same press → drag → park gesture as the claim row and the hand-end card;
    // a press that never moves stays an ordinary HINT tap.

    override val parkChromeLabel: String get() = PARK_LABEL

    override fun hitsParkChrome(worldX: Float, worldY: Float): Boolean =
        hit?.contains(worldX, worldY) == true

    override fun onParkPointerDown(worldX: Float, worldY: Float): Boolean {
        if (!hitsParkChrome(worldX, worldY)) return false
        park.begin(worldX, worldY)
        return true
    }

    override fun onParkPointerDrag(worldX: Float, worldY: Float) {
        park.drag(worldX, worldY)
    }

    override fun onParkPointerUp(worldX: Float, worldY: Float): ParkRelease {
        if (!park.release().moved) return ParkRelease.Tap
        return ParkRelease(
            consumed = true,
            parkChromeLabel = PARK_LABEL,
            parkX = park.parkX,
            parkY = park.parkY,
        )
    }

    override fun cancelPark() = park.cancel()

    companion object {
        /** Stable identity of this block for [ParkableChrome.parkChromeLabel]. */
        const val PARK_LABEL = "hint-button"

        /**
         * Android draws the chip at two thirds of its design size — a third
         * smaller. One factor for plate and label together, so the proportions
         * inside the chip are unchanged; desktop and the browser keep 1.
         */
        private const val ANDROID_SCALE = 0.67f
        /** Tiny readout above the HINT chip (Fonts.button is 18 px → ~12 px). */
        private const val METRIC_SCALE = 0.68f
        private val METRIC_INK: Color = AppPalette.inkMuted

        /**
         * Vertical space used by [McHintLabels.drawTwoLine] above a hand
         * tile (gap + smaller playout line + stack gap + avgΔ line + pad under the button).
         */
        private fun mcHintLabelStackHeight(): Float {
            val avgLineH = Fonts.small.capHeight
            val nLineH = avgLineH * McHintLabels.PLAYOUTS_SCALE
            val gapAboveChoice = Fonts.px(2f)
            val lineGap = Fonts.px(2f)
            val stackGap = max(lineGap, avgLineH * 0.12f)
            val padBelowButton = Fonts.px(6f)
            return gapAboveChoice + nLineH + stackGap + avgLineH + padBelowButton
        }
    }
}
