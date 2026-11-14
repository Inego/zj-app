package inego.zungjunggame.features.auth

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.render.ui.tapSlopPx
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of the starting-level card for one laid-out frame. */
data class StartingLevelGeometry(
    val panel: Rect?,
    val novice: Rect?,
    val amateur: Rect?,
    val strong: Rect?,
)

/**
 * Starting rating selection before the player's first ranked game:
 * Novice (800), Amateur (1200), Strong (1500).
 *
 * **Ownership.** This overlay owns its own press → drag → release state machine
 * and reports the level a release resolved ([PointerResult]);
 * [StartingLevelComponent] publishes the panel as the gesture region and bridges
 * that verdict to a [inego.zungjunggame.ui.ComponentAction]. No control travels as a hit region.
 */
class StartingLevelOverlay : Disposable, GestureSurface<StartingLevelOverlay.PointerResult> {
    private val glyphLayout = GlyphLayout()

    private fun whitePixel(): Texture = WhitePixel.get()

    var errorText: String = ""
        private set

    var busy: Boolean = false
        private set

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f
    private val hits = ArrayList<Hit>(3)

    /** The level row the in-flight press began on, or null when it began elsewhere. */
    private var pressTarget: HitKind? = null
    private var pressY = Float.NaN
    private var dragged = false

    enum class PointerResult {
        Novice,
        Amateur,
        Strong,
        None,
    }

    private enum class HitKind { Novice, Amateur, Strong }

    private data class Hit(
        val kind: HitKind,
        val left: Float,
        val bottom: Float,
        val w: Float,
        val h: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= left && px <= left + w && py >= bottom && py <= bottom + h
    }

    /**
     * Clear the card for a fresh selection. The owner calls this when it presents
     * the route, so a previous attempt's busy state and error never appear.
     */
    fun reset() {
        errorText = ""
        busy = false
        hits.clear()
        clearGesture()
    }

    fun setBusy(busy: Boolean) {
        this.busy = busy
    }

    fun setError(message: String) {
        this.errorText = message
        this.busy = false
    }

    /**
     * Pure snapshot of the controls the last [render] laid out: card and the
     * three level rows. No GL / fonts.
     */
    fun measureGeometry(): StartingLevelGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        fun rectFor(kind: HitKind): Rect? = hits.lastOrNull { it.kind == kind }
            ?.let { Rect(it.left, it.bottom, it.w, it.h) }
        return StartingLevelGeometry(
            panel = panel,
            novice = rectFor(HitKind.Novice),
            amateur = rectFor(HitKind.Amateur),
            strong = rectFor(HitKind.Strong),
        )
    }

    /**
     * Claim the press and record the level row it began on.
     */
    override fun onPointerDown(x: Float, y: Float): Boolean {
        pressTarget = hits.lastOrNull { it.contains(x, y) }?.kind
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
     * The release resolves the row the press began on: a completed press picks
     * that level, anywhere else (or a drag) resolves nothing. A busy card claims
     * the press but picks nothing — it stays blocking while the request is in
     * flight.
     */
    override fun onPointerUp(x: Float, y: Float): PointerResult {
        val startedOn = pressTarget
        val moved = dragged
        clearGesture()
        if (busy) return PointerResult.None
        return when {
            startedOn == null || moved -> PointerResult.None
            startedOn == HitKind.Novice -> PointerResult.Novice
            startedOn == HitKind.Amateur -> PointerResult.Amateur
            startedOn == HitKind.Strong -> PointerResult.Strong
            else -> PointerResult.None
        }
    }

    override fun cancelGesture() = clearGesture()

    private fun clearGesture() {
        pressTarget = null
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
        hits.clear()

        val px = whitePixel()
        val titleFont = Fonts.labelActive
        val bodyFont = Fonts.label
        val buttonFont = Fonts.button

        val title = L10n.get("starting_level.title")
        val body = L10n.get("starting_level.body")
        val noviceLabel = L10n.get("starting_level.novice")
        val amateurLabel = L10n.get("starting_level.amateur")
        val strongLabel = L10n.get("starting_level.strong")
        val settingLabel = L10n.get("starting_level.setting")

        val padX = Fonts.px(28f)
        val padY = Fonts.px(22f)
        val titleGap = Fonts.px(12f)
        val bodyGap = Fonts.px(18f)
        val btnH = max(Fonts.px(46f), buttonFont.lineHeight + Fonts.px(20f))
        val btnGap = Fonts.px(10f)
        val errorGap = Fonts.px(12f)

        val portrait = screenH > screenW
        val maxPanelW = (screenW * (if (portrait) 0.88f else 0.52f)).coerceAtMost(screenW - Fonts.px(24f))

        glyphLayout.setText(titleFont, title, Color.WHITE, maxPanelW - padX * 2f, Align.center, true)
        var contentW = glyphLayout.width
        glyphLayout.setText(bodyFont, body, Color.WHITE, maxPanelW - padX * 2f, Align.center, true)
        contentW = max(contentW, glyphLayout.width)

        for (label in listOf(noviceLabel, amateurLabel, strongLabel, errorText, settingLabel)) {
            if (label.isEmpty()) continue
            glyphLayout.setText(buttonFont, label)
            contentW = max(contentW, glyphLayout.width)
        }

        panelW = max(Fonts.px(320f), contentW + padX * 2f).coerceAtMost(maxPanelW)
        glyphLayout.setText(titleFont, title, Color.WHITE, panelW - padX * 2f, Align.center, true)
        val titleH = glyphLayout.height
        glyphLayout.setText(bodyFont, body, Color.WHITE, panelW - padX * 2f, Align.center, true)
        val bodyH = glyphLayout.height

        val statusText = when {
            busy -> settingLabel
            errorText.isNotEmpty() -> errorText
            else -> ""
        }
        val statusH = if (statusText.isNotEmpty()) {
            glyphLayout.setText(bodyFont, statusText, Color.WHITE, panelW - padX * 2f, Align.center, true)
            glyphLayout.height + errorGap
        } else {
            0f
        }

        panelH = padY * 2f + titleH + titleGap + bodyH + bodyGap + (btnH * 3f) + (btnGap * 2f) + statusH

        val avail = OverlayViewport.available(screenW, screenH)
        val clamped = OverlayViewport.clampSize(panelW, panelH, avail)
        panelW = clamped.first
        panelH = clamped.second
        val placed = OverlayViewport.placeCentered(panelW, panelH, avail)
        panelLeft = placed.first
        panelBottom = placed.second

        // Scrim
        batch.setColor(AppPalette.scrimMid)
        batch.draw(px, 0f, 0f, screenW, screenH)

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val contentLeft = panelLeft + padX
        val contentWInner = panelW - padX * 2f

        // Title
        titleFont.draw(
            batch, title,
            contentLeft, panelBottom + panelH - padY,
            contentWInner, Align.center, true,
        )

        // Body / blurb
        val bodyTop = panelBottom + panelH - padY - titleH - titleGap
        bodyFont.color = AppPalette.inkMuted
        bodyFont.draw(
            batch, body,
            contentLeft, bodyTop,
            contentWInner, Align.center, true,
        )
        bodyFont.color = Color.WHITE

        // Buttons
        var btnTop = bodyTop - bodyH - bodyGap
        val buttons = listOf(
            Triple(HitKind.Novice, noviceLabel, AppPalette.successFillStrong),
            Triple(HitKind.Amateur, amateurLabel, AppPalette.infoFillStrong),
            Triple(HitKind.Strong, strongLabel, AppPalette.tint(AppPalette.warning, 0.95f)),
        )

        for ((kind, label, accentColor) in buttons) {
            val btnBottom = btnTop - btnH
            val hit = Hit(kind, contentLeft, btnBottom, contentWInner, btnH)
            hits.add(hit)

            val hovered = !busy && hit.contains(pointerWorldX, pointerWorldY)

            // Button background
            if (busy) {
                batch.setColor(AppPalette.tint(AppPalette.buttonFillDisabled, 0.70f))
            } else if (hovered) {
                batch.setColor(accentColor.r * 1.25f, accentColor.g * 1.25f, accentColor.b * 1.25f, 0.95f)
            } else {
                batch.setColor(accentColor.r * 0.85f, accentColor.g * 0.85f, accentColor.b * 0.85f, 0.90f)
            }
            batch.draw(px, contentLeft, btnBottom, contentWInner, btnH)

            // Button border
            val btnBorder = max(1f, Fonts.px(1.5f))
            UiPlate.drawFrame(
                batch, px, contentLeft, btnBottom, contentWInner, btnH,
                AppPalette.panelRim.r, AppPalette.panelRim.g, AppPalette.panelRim.b, if (hovered && !busy) 0.85f else 0.40f,
                btnBorder,
            )

            // Button text
            buttonFont.color = if (busy) AppPalette.inkDisabled else AppPalette.headerInk
            val textY = btnBottom + (btnH + buttonFont.capHeight) / 2f
            buttonFont.draw(batch, label, contentLeft, textY, contentWInner, Align.center, false)

            btnTop -= btnH + btnGap
        }

        // Status / error text
        if (statusText.isNotEmpty()) {
            val statusTop = btnTop + btnGap - errorGap
            bodyFont.color = if (busy) AppPalette.inkMuted else AppPalette.danger
            bodyFont.draw(
                batch, statusText,
                contentLeft, statusTop,
                contentWInner, Align.center, true,
            )
            bodyFont.color = Color.WHITE
        }

        batch.setColor(Color.WHITE)
    }

    /**
     * Geometry seam for plain JVM tests: the press machine must be provable
     * without a GL frame, since the panel and level rows are otherwise only set
     * by [render] (see `StartingLevelFeatureTest`).
     */
    internal fun seedLayoutForTest(
        panel: Rect,
        novice: Rect,
        amateur: Rect,
        strong: Rect,
        busy: Boolean = false,
    ) {
        this.busy = busy
        panelLeft = panel.left
        panelBottom = panel.bottom
        panelW = panel.width
        panelH = panel.height
        hits.clear()
        hits.add(Hit(HitKind.Novice, novice.left, novice.bottom, novice.width, novice.height))
        hits.add(Hit(HitKind.Amateur, amateur.left, amateur.bottom, amateur.width, amateur.height))
        hits.add(Hit(HitKind.Strong, strong.left, strong.bottom, strong.width, strong.height))
        clearGesture()
    }

    override fun dispose() {
    }

}
