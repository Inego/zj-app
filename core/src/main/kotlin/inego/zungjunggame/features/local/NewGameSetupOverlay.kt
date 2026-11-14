package inego.zungjunggame.features.local

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.InfoButton
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.FittedLabel

/** One rounds radio option and the value it selects (`null` = unlimited). */
data class NewGameSetupRoundGeometry(val rect: Rect, val roundsValue: Int?)

/** GL-free snapshot of the setup card's controls for the last laid-out frame. */
data class NewGameSetupGeometry(
    val panel: Rect?,
    val rounds: List<NewGameSetupRoundGeometry>,
    val chickenToggle: Rect?,
    val chickenInfo: Rect?,
    val start: Rect?,
    val cancel: Rect?,
)

/**
 * What a completed press on the setup card resolved.
 *
 * The overlay is a controlled view: the draft it draws arrives through render
 * each frame and it keeps no copy. A control that edits the draft reports the new
 * [draft]; a control that resolves a feature action reports its [selectedId]; a
 * body drag reports the offset it moved to or settled at.
 */
data class NewGameSetupVerdict(
    /** Stable id of the control the release selected, in the overlay's vocabulary. */
    val selectedId: String? = null,
    /** The draft after an edit (rounds radio, chicken checkbox); null otherwise. */
    val draft: GameConfig? = null,
    /** Where a body drag is right now, reported from the drag steps. */
    val dragScrollOffset: Float? = null,
    /** Where a body drag settled, when the release ended a scroll. */
    val settledScrollOffset: Float? = null,
)

/**
 * Modal New Game setup: rounds as a **vertical radio list**
 * (1 / 2 / 4 / Unlimited), then a separate checkbox for chicken hands
 * with a rimless [InfoButton] deep-link to Reference.
 *
 * Desktop also accepts Enter to Start and Esc to Cancel (handled in
 * [inego.zungjunggame.Main]).
 *
 * Visual language matches [inego.zungjunggame.features.confirm.ConfirmOverlay] / [inego.zungjunggame.features.menu.GameMenuOverlay] (dim backdrop,
 * dark card, ivory rim). Title and Start / Cancel stay put; the rounds list
 * and chicken row scroll inside [OverlayViewport] when the card is taller
 * than the safe rect (Android landscape).
 *
 * The card is controlled: [render] is handed the draft and the body offset every
 * frame, and a release reports what it resolved ([NewGameSetupVerdict]). The
 * overlay keeps only the frame it last painted.
 */
class NewGameSetupOverlay(
    /**
     * The finite round counts the radio offers (`null` = Unlimited), top to bottom.
     * The local setup offers 1 / 2 / 4 / Unlimited; the duplicate setup offers
     * exactly the two ranked axes, 1 and 2.
     */
    private val roundOptions: List<Int?> = DEFAULT_ROUND_OPTIONS,
    /** The card title's bundle key; the local and duplicate setups differ. */
    private val titleKey: String = "setup.title",
) : Disposable, GestureSurface<NewGameSetupVerdict> {
    private val glyphLayout = GlyphLayout()
    private val chickenInfo = InfoButton()
    private val body = OverlayBodyScroll()

    private fun whitePixel(): Texture = WhitePixel.get()

    /** The draft [render] was handed this frame; the owner's value. */
    private var draft: GameConfig = GameConfig.DEFAULT

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private val hits = ArrayList<Hit>(12)
    private var chickenInfoRect: Rect? = null
    private var pressTarget: PressTarget = PressTarget.None

    private enum class PressTarget { None, Body, Control, Chrome }

    private enum class HitKind {
        /** Finite round count stored in [Hit.roundsValue], or unlimited when null. */
        RoundsChoice,
        ChickenToggle,
        Start,
        Cancel,
    }

    private data class Hit(
        val kind: HitKind,
        val left: Float,
        val bottom: Float,
        val w: Float,
        val h: Float,
        /** For [HitKind.RoundsChoice]: the rounds value to apply (`null` = unlimited). */
        val roundsValue: Int? = null,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= left && px <= left + w && py >= bottom && py <= bottom + h
    }

    private sealed interface PointerResult {
        /** Start pressed — the draft is the owner's stored value. */
        data object Start : PointerResult
        /** Cancel pressed or outside-panel dismiss when cancel is allowed. */
        data object Cancel : PointerResult
        /** Info next to chicken hands — open Reference on the chicken-hand page. */
        data object ChickenHandInfo : PointerResult
        /** A rounds/chicken edit produced a new draft; no feature action. */
        data class Draft(val value: GameConfig) : PointerResult
    }

    /**
     * Pixels the body is scrolled down (0 = top); the offset the bridge reads after
     * a consumed wheel.
     */
    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Advance the body's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    /**
     * Pure snapshot of the controls the last [render] laid out: card, rounds
     * options, chicken checkbox + info, and footer buttons. No GL / fonts.
     */
    fun measureGeometry(): NewGameSetupGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        val rounds = hits
            .filter { it.kind == HitKind.RoundsChoice }
            .map { NewGameSetupRoundGeometry(Rect(it.left, it.bottom, it.w, it.h), it.roundsValue) }
        return NewGameSetupGeometry(
            panel = panel,
            rounds = rounds,
            chickenToggle = rectOf(HitKind.ChickenToggle),
            chickenInfo = chickenInfoRect,
            start = rectOf(HitKind.Start),
            cancel = rectOf(HitKind.Cancel),
        )
    }

    private fun rectOf(kind: HitKind): Rect? =
        hits.firstOrNull { it.kind == kind }?.let { Rect(it.left, it.bottom, it.w, it.h) }

    /**
     * Seed the control layout without GL / fonts ([NewGameSetupGeometry] is what
     * [render] would have produced). Test-only; mirrors the other overlays'
     * `seedLayoutForTest`.
     */
    internal fun seedLayoutForTest(
        geometry: NewGameSetupGeometry,
        draft: GameConfig = GameConfig.DEFAULT,
    ) {
        this.draft = draft
        geometry.panel?.let {
            panelLeft = it.left
            panelBottom = it.bottom
            panelW = it.width
            panelH = it.height
        }
        hits.clear()
        for ((rect, roundsValue) in geometry.rounds) {
            hits.add(
                Hit(
                    HitKind.RoundsChoice,
                    rect.left,
                    rect.bottom,
                    rect.width,
                    rect.height,
                    roundsValue = roundsValue,
                ),
            )
        }
        geometry.chickenToggle?.let {
            hits.add(Hit(HitKind.ChickenToggle, it.left, it.bottom, it.width, it.height))
        }
        geometry.start?.let {
            hits.add(Hit(HitKind.Start, it.left, it.bottom, it.width, it.height))
        }
        geometry.cancel?.let {
            hits.add(Hit(HitKind.Cancel, it.left, it.bottom, it.width, it.height))
        }
        chickenInfoRect = geometry.chickenInfo
        // The rounds/chicken rows live in the scroll body in [render]; mirror that
        // so pointer-down takes the body branch and [applyPointer] runs on release.
        geometry.panel?.let {
            body.layout(it.left, it.bottom, it.width, it.height, contentHeight = it.height, scrollY = 0f)
        }
    }

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        body.wheel(amountY)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        body.cancel()
        pressTarget = PressTarget.None
        if (!containsPanel(x, y)) return false
        val sticky = stickyControlAt(x, y)
        if (sticky != null) {
            pressTarget = PressTarget.Control
            return true
        }
        if (body.contains(x, y) && body.beginPointer(x, y)) {
            pressTarget = PressTarget.Body
            return true
        }
        pressTarget = PressTarget.Chrome
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): NewGameSetupVerdict? {
        if (pressTarget != PressTarget.Body) return null
        return NewGameSetupVerdict(dragScrollOffset = body.dragTo(y))
    }

    override fun onPointerUp(x: Float, y: Float): NewGameSetupVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        if (x.isNaN() || y.isNaN()) {
            body.cancel()
            return NewGameSetupVerdict()
        }
        return when (target) {
            PressTarget.Body -> {
                val wasDrag = body.endPointer()
                if (wasDrag) return NewGameSetupVerdict(settledScrollOffset = body.scrollY)
                val result = applyPointer(x, y)
                NewGameSetupVerdict(
                    selectedId = result?.toSelectId(),
                    draft = (result as? PointerResult.Draft)?.value,
                )
            }
            PressTarget.Control -> {
                body.cancel()
                val result = when (stickyControlAt(x, y)) {
                    HitKind.Start -> PointerResult.Start
                    HitKind.Cancel -> PointerResult.Cancel
                    else -> null
                }
                NewGameSetupVerdict(selectedId = result?.toSelectId())
            }
            PressTarget.Chrome, PressTarget.None -> {
                body.cancel()
                NewGameSetupVerdict()
            }
        }
    }

    /** Abort an in-flight body drag when the gesture is cancelled (OS cancel). */
    override fun cancelGesture() {
        body.cancel()
        pressTarget = PressTarget.None
    }

    private fun stickyControlAt(worldX: Float, worldY: Float): HitKind? {
        for (i in hits.lastIndex downTo 0) {
            val hit = hits[i]
            if (hit.kind == HitKind.Start || hit.kind == HitKind.Cancel) {
                if (hit.contains(worldX, worldY)) return hit.kind
            }
        }
        return null
    }

    /** Resolve a tap: report the edited draft, a terminal result, or nothing. */
    private fun applyPointer(worldX: Float, worldY: Float): PointerResult? {
        if (chickenInfo.contains(worldX, worldY)) {
            return PointerResult.ChickenHandInfo
        }
        for (i in hits.lastIndex downTo 0) {
            val hit = hits[i]
            if (!hit.contains(worldX, worldY)) continue
            return when (hit.kind) {
                HitKind.RoundsChoice ->
                    PointerResult.Draft(draft.copy(rounds = hit.roundsValue))

                HitKind.ChickenToggle ->
                    PointerResult.Draft(draft.copy(allowChickenHands = !draft.allowChickenHands))

                HitKind.Start -> PointerResult.Start
                HitKind.Cancel -> PointerResult.Cancel
            }
        }
        return null
    }

    private fun PointerResult.toSelectId(): String? = when (this) {
        PointerResult.Start -> SELECT_START
        PointerResult.Cancel -> SELECT_CANCEL
        PointerResult.ChickenHandInfo -> SELECT_CHICKEN_INFO
        is PointerResult.Draft -> null
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        scrollY: Float,
        draft: GameConfig,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        this.draft = draft
        Fonts.ensureCreated()
        hits.clear()

        val px = whitePixel()
        val touch = InputProfile.detect() == InputProfile.Touch

        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)

        val titleFont = Fonts.labelActive
        val sectionFont = Fonts.button
        val padX = Fonts.px(28f)
        val padY = Fonts.px(20f)
        val titleGap = Fonts.px(16f)
        val sectionLabelGap = Fonts.px(10f)
        val blockGap = Fonts.px(20f)
        val dividerGap = Fonts.px(16f)
        val rowH = max(Fonts.px(40f), sectionFont.lineHeight + Fonts.px(14f))
        val checkH = max(Fonts.px(44f), sectionFont.lineHeight + Fonts.px(18f))
        val btnH = max(Fonts.px(48f), sectionFont.lineHeight + Fonts.px(22f))
        val btnGap = Fonts.px(12f)
        val groupPad = Fonts.px(8f)
        val rowGap = Fonts.px(4f)
        val nRounds = roundOptions.size
        val listH = groupPad * 2f + nRounds * rowH + (nRounds - 1) * rowGap
        val title = L10n.get(titleKey)
        val sectionRounds = L10n.get("setup.rounds")
        val labelChicken = L10n.get("setup.chicken")
        val startLabel = L10n.get("setup.start")
        val cancelLabel = L10n.get("setup.cancel")
        val infoSide = InfoButton.defaultSide()
        val infoGap = InfoButton.siblingGap()

        glyphLayout.setText(titleFont, title)
        var contentW = glyphLayout.width
        for (label in listOf(
            sectionRounds,
            startLabel,
            cancelLabel,
        ) + roundOptions.map { roundOptionLabel(it) }) {
            glyphLayout.setText(sectionFont, label)
            // Radio disc + gap + label.
            contentW = max(contentW, glyphLayout.width + Fonts.px(48f))
        }
        // Checkbox + label + gap + info button.
        glyphLayout.setText(sectionFont, labelChicken)
        contentW = max(
            contentW,
            Fonts.px(4f) + Fonts.px(22f) + Fonts.px(14f) + glyphLayout.width +
                infoGap + infoSide,
        )

        val avail = OverlayViewport.available(screenW, screenH)
        val portrait = screenH > screenW
        val maxPanelW = OverlayViewport.maxPanelWidth(
            screenW * (if (portrait) 0.90f else 0.72f),
            avail,
            screenW,
            Fonts.px(24f),
        )
        val minPanelW = if (touch) {
            max(Fonts.px(300f), screenW * 0.42f).coerceAtMost(maxPanelW)
        } else {
            Fonts.px(300f).coerceAtMost(maxPanelW)
        }
        panelW = max(minPanelW, contentW + padX * 2f).coerceAtMost(maxPanelW)
        panelW = panelW.coerceAtMost(avail.width)

        val divH = max(1f, Fonts.px(1.5f))
        val chromeH = padY + titleFont.lineHeight + titleGap + blockGap + btnH + padY
        val bodyContentH = sectionFont.lineHeight + sectionLabelGap + listH +
            blockGap + divH + dividerGap + checkH
        val bodyInset = padY + btnH + blockGap
        val fitted = body.fitAndPlace(
            avail = avail,
            panelW = panelW,
            chromeH = chromeH,
            bodyContentH = bodyContentH,
            bodyInset = bodyInset,
            scrollY = scrollY,
        )
        panelH = fitted.cardH
        panelLeft = fitted.left
        panelBottom = fitted.bottom

        val contentLeft = panelLeft + padX
        val contentWInner = body.contentWidth(panelW, padX * 2f, Fonts.px(48f))

        // Card.
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        // Title (sticky).
        glyphLayout.setText(titleFont, title)
        titleFont.draw(
            batch,
            title,
            panelLeft + (panelW - glyphLayout.width) * 0.5f,
            panelBottom + panelH - padY,
        )

        val hoverX = body.hoverX(pointerWorldX)
        val hoverY = body.hoverY(pointerWorldY)

        body.drawBody(batch, camera, px, pointerWorldX, pointerWorldY) {
            var yTop = body.contentTop
            glyphLayout.setText(sectionFont, sectionRounds)
            sectionFont.color = AppPalette.inkMuted
            sectionFont.draw(batch, sectionRounds, contentLeft, yTop)
            sectionFont.color = Color.WHITE
            yTop -= sectionFont.lineHeight + sectionLabelGap

            val groupBottom = yTop - listH
            drawRadioList(
                batch = batch,
                px = px,
                left = contentLeft,
                bottom = groupBottom,
                w = contentWInner,
                h = listH,
                rowH = rowH,
                rowGap = rowGap,
                groupPad = groupPad,
                pointerWorldX = hoverX,
                pointerWorldY = hoverY,
            )
            yTop = groupBottom - blockGap

            batch.setColor(AppPalette.tint(AppPalette.rimInert, 0.45f))
            batch.draw(px, contentLeft, yTop - divH, contentWInner, divH)
            batch.setColor(Color.WHITE)
            yTop -= divH + dividerGap

            val chickenBottom = yTop - checkH
            val checkRowW = (contentWInner - infoSide - infoGap).coerceAtLeast(Fonts.px(120f))
            drawCheckRow(
                batch = batch,
                px = px,
                label = labelChicken,
                checked = draft.allowChickenHands,
                left = contentLeft,
                bottom = chickenBottom,
                w = checkRowW,
                h = checkH,
                kind = HitKind.ChickenToggle,
                pointerX = hoverX,
                pointerY = hoverY,
            )
            val infoLeft = contentLeft + checkRowW + infoGap
            val infoBottom = chickenBottom + (checkH - infoSide) * 0.5f
            chickenInfo.layoutAt(
                left = infoLeft,
                bottom = infoBottom,
                side = infoSide,
            )
            chickenInfoRect = Rect(infoLeft, infoBottom, infoSide, infoSide)
            chickenInfo.render(batch, px, hoverX, hoverY)
        }

        // --- Action buttons (sticky footer) ---
        val btnBottom = panelBottom + padY
        glyphLayout.setText(sectionFont, startLabel)
        val startW = max(Fonts.px(100f), glyphLayout.width + Fonts.px(40f))
        glyphLayout.setText(sectionFont, cancelLabel)
        val cancelW = max(Fonts.px(100f), glyphLayout.width + Fonts.px(40f))
        val pairW = startW + cancelW + btnGap
        val pairLeft = panelLeft + (panelW - pairW) * 0.5f
        drawActionButton(
            batch, px, startLabel, pairLeft, btnBottom, startW, btnH,
            pointerWorldX, pointerWorldY, primary = true, HitKind.Start,
        )
        drawActionButton(
            batch, px, cancelLabel, pairLeft + startW + btnGap, btnBottom, cancelW, btnH,
            pointerWorldX, pointerWorldY, primary = false, HitKind.Cancel,
        )
    }

    /**
     * Framed exclusive choice: vertical radio list (1 / 2 / 4 / Unlimited),
     * not independent checkboxes.
     */
    private fun drawRadioList(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        rowH: Float,
        rowGap: Float,
        groupPad: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        // Group well — recessed frame so options read as one control.
        OverlayChrome.drawPanel(
            batch, px, left, bottom, w, h,
            AppPalette.panelDeep,
            AppPalette.tint(AppPalette.controlRim, 0.50f),
            OverlayChrome.buttonRimPx(),
        )

        val innerLeft = left + groupPad
        val innerW = w - groupPad * 2f
        val n = roundOptions.size
        // Top-to-bottom order: index 0 at the top of the group.
        for (i in roundOptions.indices) {
            val rounds = roundOptions[i]
            val rowBottom = bottom + groupPad + (n - 1 - i) * (rowH + rowGap)
            drawRadioRow(
                batch = batch,
                px = px,
                label = roundOptionLabel(rounds),
                selected = draft.rounds == rounds,
                left = innerLeft,
                bottom = rowBottom,
                w = innerW,
                h = rowH,
                roundsValue = rounds,
                pointerX = pointerWorldX,
                pointerY = pointerWorldY,
            )
        }
    }

    private fun drawRadioRow(
        batch: SpriteBatch,
        px: Texture,
        label: String,
        selected: Boolean,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        roundsValue: Int?,
        pointerX: Float,
        pointerY: Float,
    ) {
        hits += Hit(HitKind.RoundsChoice, left, bottom, w, h, roundsValue = roundsValue)
        val hovered =
            !pointerX.isNaN() && !pointerY.isNaN() &&
                hits.last().contains(pointerX, pointerY)

        if (selected || hovered) {
            val bg = when {
                selected && hovered -> AppPalette.tint(AppPalette.plateSelectedHover, 0.55f)
                selected -> AppPalette.tint(AppPalette.plateSelected, 0.45f)
                else -> AppPalette.tint(AppPalette.plateRaised, 0.45f)
            }
            batch.setColor(bg)
            batch.draw(px, left, bottom, w, h)
            batch.setColor(Color.WHITE)
        }

        // Circular radio outline (square approximation — distinct from chicken checkbox).
        val disc = min(Fonts.px(18f), h * 0.48f)
        val discLeft = left + Fonts.px(6f)
        val discBottom = bottom + (h - disc) * 0.5f
        val outline = max(1.5f, Fonts.px(2f))
        val rim = when {
            selected -> AppPalette.tint(AppPalette.accent, 0.95f)
            hovered -> AppPalette.tint(AppPalette.inkWarm, 0.90f)
            else -> AppPalette.tint(AppPalette.inkWarm, 0.85f)
        }
        UiPlate.drawFrame(batch, px, discLeft, discBottom, disc, disc, rim, outline)
        if (selected) {
            val inset = Fonts.px(4f)
            batch.setColor(AppPalette.accent)
            batch.draw(
                px,
                discLeft + inset,
                discBottom + inset,
                disc - inset * 2f,
                disc - inset * 2f,
            )
        }
        batch.setColor(Color.WHITE)

        val font = Fonts.button
        val textLeft = discLeft + disc + Fonts.px(14f)
        FittedLabel.draw(
            batch = batch,
            font = font,
            text = label,
            left = textLeft,
            centerY = bottom + h * 0.5f,
            maxWidth = (left + w - textLeft).coerceAtLeast(1f),
            color = when {
                selected -> AppPalette.labelInkHover
                hovered -> AppPalette.ink
                else -> AppPalette.inkWarm
            },
        )
    }

    private fun drawCheckRow(
        batch: SpriteBatch,
        px: Texture,
        label: String,
        checked: Boolean,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        kind: HitKind,
        pointerX: Float,
        pointerY: Float,
    ) {
        hits += Hit(kind, left, bottom, w, h)
        val hovered =
            !pointerX.isNaN() && !pointerY.isNaN() &&
                hits.last().contains(pointerX, pointerY)
        // Flat row — no full-width card chrome so it does not look like another segment.
        if (hovered) {
            batch.setColor(AppPalette.plateRaised)
            batch.draw(px, left, bottom, w, h)
            batch.setColor(Color.WHITE)
        }

        val box = Fonts.px(22f)
        val boxLeft = left + Fonts.px(4f)
        val boxBottom = bottom + (h - box) * 0.5f
        // Square checkbox outline (distinct from the segmented rounds control).
        val outline = max(1.5f, Fonts.px(2f))
        UiPlate.drawFrame(
            batch, px, boxLeft, boxBottom, box, box,
            0.90f, 0.88f, 0.78f, 0.95f, outline,
        )
        if (checked) {
            val inset = Fonts.px(5f)
            batch.setColor(AppPalette.success)
            batch.draw(
                px,
                boxLeft + inset,
                boxBottom + inset,
                box - inset * 2f,
                box - inset * 2f,
            )
        }
        batch.setColor(Color.WHITE)

        val font = Fonts.button
        val textLeft = boxLeft + box + Fonts.px(14f)
        FittedLabel.draw(
            batch = batch,
            font = font,
            text = label,
            left = textLeft,
            centerY = bottom + h * 0.5f,
            maxWidth = (left + w - textLeft).coerceAtLeast(1f),
            color = if (hovered) AppPalette.inkStrong else AppPalette.ink,
        )
    }

    private fun drawActionButton(
        batch: SpriteBatch,
        px: Texture,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        pointerX: Float,
        pointerY: Float,
        primary: Boolean,
        kind: HitKind,
    ) {
        hits += Hit(kind, left, bottom, w, h)
        val hovered =
            !pointerX.isNaN() && !pointerY.isNaN() &&
                inRect(pointerX, pointerY, left, bottom, w, h)
        val bg = OverlayChrome.actionFill(hovered = hovered, primary = primary)
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

    override fun dispose() {
    }

    /** The label for one radio row: the count, or Unlimited. */
    private fun roundOptionLabel(rounds: Int?): String =
        rounds?.toString() ?: L10n.get("setup.unlimited")

    companion object {
        internal const val SELECT_START = "start"
        internal const val SELECT_CANCEL = "cancel"
        internal const val SELECT_CHICKEN_INFO = "chicken-info"

        /** The local setup's radio: 1 / 2 / 4 / Unlimited (`null` = Unlimited). */
        val DEFAULT_ROUND_OPTIONS: List<Int?> = listOf(1, 2, GameConfig.DEFAULT_ROUNDS, null)
    }
}
