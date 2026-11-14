package inego.zungjunggame.features.auth

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.Input
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MAX_SPECIAL_KEY
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.overlayCommandPressed
import inego.zungjunggame.render.ui.overlayShiftPressed
import inego.zungjunggame.render.ui.OverlayTextField
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.tapSlopPx
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of the alias card's laid-out frame. */
data class AliasCardGeometry(
    val panel: Rect?,
    val submit: Rect?,
    val back: Rect?,
    val field: Rect?,
)

/**
 * The shared account text card: one field with Save/Cancel.
 *
 * On the first Google sign-in the generated `user-<tag>` alias is prefilled and
 * selected, and the player edits it or saves it as-is before entering the hub.
 * The same card is the account's display-name editor, opened from the menu's
 * account dropdown ([RouteIds.CHANGE_DISPLAY_NAME]). It is also the Google Play
 * reviewer's special-key field ([RouteIds.SPECIAL_KEY]): one field, one press
 * machine, and only the labels, length limit and validation differ by
 * [AliasPrompt.Kind].
 *
 * This card is the keyboard-profile surface. Touch collects the text through
 * [NativeAliasPrompt] (the OS dialog) and never shows this card.
 */
class AliasCardOverlay : Disposable, GestureSurface<ModalVerdict> {
    private val glyphLayout = GlyphLayout()

    private fun whitePixel(): Texture = WhitePixel.get()

    /** Which credential this card is currently collecting. */
    private var kind: AliasPrompt.Kind = AliasPrompt.Kind.ChangeName

    private val aliasField = OverlayTextField(MAX_SPECIAL_KEY).apply {
        promptTitle = { L10n.get(kind.fieldKey) }
        acceptEdit = AliasPaintLimit::allows
    }

    val aliasText: String
        get() = aliasField.text

    var errorText: String = ""
        private set

    var busy: Boolean = false
        private set

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f
    private val hits = ArrayList<Hit>(4)

    /** The control the in-flight press began on. */
    private enum class PressTarget { Submit, Back, Field, None }
    private var pressTarget = PressTarget.None
    private var pressY = Float.NaN
    private var dragged = false

    private enum class HitKind { Submit, Back, Field }

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
     * Seed the card with [seed] for [kind] and take the keyboard, for a fresh
     * edit. The seed is kept whole when it predates the current length limits.
     */
    fun edit(kind: AliasPrompt.Kind, seed: String) {
        this.kind = kind
        aliasField.acceptEdit = kind.acceptEdit ?: { _, _ -> true }
        aliasField.setExact(seed)
        aliasField.enabled = true
        aliasField.focus()
        aliasField.selectAll()
        errorText = ""
        busy = false
        hits.clear()
        clearGesture()
    }

    /** The display-name editor's entry point; the special key calls [edit] with its kind. */
    fun edit(displayName: String) {
        edit(AliasPrompt.Kind.ChangeName, displayName)
    }

    /** True while the card's field owns the keyboard. */
    fun hasFocusedTextField(): Boolean = aliasField.focused

    fun setBusy(value: Boolean) {
        busy = value
        aliasField.enabled = !value
        if (value) errorText = ""
    }

    fun setError(message: String) {
        busy = false
        aliasField.enabled = true
        errorText = message
    }

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /**
     * Pure snapshot of the controls the last [render] laid out: the card, the
     * edit field and its buttons. No GL.
     */
    fun measureGeometry(): AliasCardGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        fun rectFor(kind: HitKind): Rect? = hits.lastOrNull { it.kind == kind }
            ?.let { Rect(it.left, it.bottom, it.w, it.h) }
        return AliasCardGeometry(
            panel = panel,
            submit = rectFor(HitKind.Submit),
            back = rectFor(HitKind.Back),
            field = rectFor(HitKind.Field),
        )
    }

    /**
     * Claim the press, forward it to the field (which places the caret or opens the
     * OS prompt), and remember the control it began on. Claim even while busy so it
     * is not read as an outside tap and dismisses the card; a busy card resolves
     * nothing, its controls being inert.
     */
    override fun onPointerDown(x: Float, y: Float): Boolean {
        aliasField.pointerDown(x, y, overlayShiftPressed())
        val kind = hits.lastOrNull { it.contains(x, y) }?.kind
        pressTarget = if (busy) {
            PressTarget.None
        } else {
            when (kind) {
                HitKind.Submit -> PressTarget.Submit
                HitKind.Back -> PressTarget.Back
                HitKind.Field -> PressTarget.Field
                null -> PressTarget.None
            }
        }
        pressY = y
        dragged = false
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (busy) return null
        if (pressY.isNaN()) return null
        if (abs(y - pressY) > tapSlopPx()) dragged = true
        aliasField.pointerDrag(x)
        return null
    }

    /**
     * The release resolves the control the press began on: a completed press on
     * Save or Cancel is reported as the row it selected, a press on the field or
     * anywhere else resolves nothing. A press that began on one control and was
     * dragged is not a tap.
     */
    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        aliasField.pointerUp()
        val startedOn = pressTarget
        val moved = dragged
        clearGesture()
        return when (startedOn) {
            PressTarget.Submit if !moved -> ModalVerdict(selectedId = ROW_SUBMIT)
            PressTarget.Back if !moved -> ModalVerdict(selectedId = ROW_BACK)
            else -> ModalVerdict()
        }
    }

    override fun cancelGesture() {
        aliasField.pointerUp()
        clearGesture()
    }

    private fun clearGesture() {
        pressTarget = PressTarget.None
        pressY = Float.NaN
        dragged = false
    }

    fun keyDown(keycode: Int): Boolean {
        return !busy && aliasField.keyDown(keycode, overlayCommandPressed(), overlayShiftPressed())
    }

    fun keyTyped(character: Char): Boolean {
        return !busy && aliasField.keyTyped(character, overlayCommandPressed())
    }

    fun backspace() {
        if (busy) return
        aliasField.buffer.backspace()
    }

    /** True when [keycode] is the card's submit key (Enter) and it can be taken. */
    fun isSubmitKey(keycode: Int): Boolean =
        (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) && !busy

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
        val title = L10n.get(kind.titleKey)
        val saveLabel = if (busy) L10n.get(kind.busyKey) else L10n.get(kind.submitKey)
        val cancelLabel = L10n.get("alias.cancel")
        val fieldLabel = L10n.get(kind.fieldKey)

        val padX = Fonts.px(28f)
        val padY = Fonts.px(20f)
        val titleGap = Fonts.px(14f)
        val btnH = max(Fonts.px(48f), buttonFont.lineHeight + Fonts.px(22f))
        val btnGap = Fonts.px(12f)
        val fieldH = max(Fonts.px(44f), buttonFont.lineHeight + Fonts.px(18f))
        val labelGap = Fonts.px(6f)
        val errorGap = Fonts.px(10f)

        val portrait = screenH > screenW
        // The special key is an opaque, longer string than a display name; give
        // its field the widest card the viewport allows so a long paste stays
        // readable instead of running past the panel.
        val wide = portrait || kind == AliasPrompt.Kind.SpecialKey
        val maxPanelW = (screenW * (if (wide) 0.88f else 0.56f)).coerceAtMost(screenW - Fonts.px(24f))
        glyphLayout.setText(titleFont, title, Color.WHITE, maxPanelW - padX * 2f, Align.center, true)
        var contentW = glyphLayout.width
        for (label in listOf(saveLabel, cancelLabel, fieldLabel, errorText)) {
            if (label.isEmpty()) continue
            glyphLayout.setText(bodyFont, label)
            contentW = max(contentW, glyphLayout.width)
        }
        glyphLayout.setText(buttonFont, cancelLabel)
        val cancelBtnW = glyphLayout.width + Fonts.px(28f)
        glyphLayout.setText(buttonFont, saveLabel)
        val saveBtnW = glyphLayout.width + Fonts.px(28f)
        val neededButtonsW = max(cancelBtnW, saveBtnW) * 2f + btnGap
        contentW = max(contentW, neededButtonsW)
        panelW = max(Fonts.px(300f), contentW + padX * 2f).coerceAtMost(maxPanelW)

        val titleWrapW = panelW - padX * 2f
        glyphLayout.setText(titleFont, title, Color.WHITE, titleWrapW, Align.center, true)
        val titleH = glyphLayout.height
        val errorH = if (errorText.isNotEmpty()) {
            glyphLayout.setText(bodyFont, errorText, Color.WHITE, titleWrapW, Align.left, true)
            glyphLayout.height + errorGap
        } else {
            0f
        }
        panelH = padY * 2f + titleH + titleGap +
            bodyFont.lineHeight + labelGap + fieldH +
            errorH + Fonts.px(16f) + btnH
        val avail = OverlayViewport.available(screenW, screenH)
        val clamped = OverlayViewport.clampSize(panelW, panelH, avail)
        panelW = clamped.first
        panelH = clamped.second
        val placed = OverlayViewport.placeCentered(panelW, panelH, avail)
        panelLeft = placed.first
        panelBottom = placed.second

        batch.setColor(AppPalette.scrimLight)
        batch.draw(px, 0f, 0f, screenW, screenH)

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val contentLeft = panelLeft + padX
        val contentWInner = panelW - padX * 2f
        titleFont.draw(
            batch, title,
            contentLeft, panelBottom + panelH - padY,
            contentWInner, Align.center, true,
        )

        var yTop = panelBottom + panelH - padY - titleH - titleGap
        bodyFont.color = AppPalette.inkMuted
        bodyFont.draw(batch, fieldLabel, contentLeft, yTop)
        bodyFont.color = Color.WHITE
        yTop -= bodyFont.lineHeight + labelGap
        val fieldBottom = yTop - fieldH
        drawField(
            batch, px, buttonFont,
            left = contentLeft, bottom = fieldBottom, w = contentWInner, h = fieldH,
            pointerWorldX = pointerWorldX, pointerWorldY = pointerWorldY,
        )
        if (errorText.isNotEmpty()) {
            bodyFont.color = AppPalette.danger
            bodyFont.draw(
                batch, errorText,
                contentLeft, fieldBottom - errorGap,
                contentWInner, Align.left, true,
            )
            bodyFont.color = Color.WHITE
        }
        val btnW = (contentWInner - btnGap) / 2f
        val btnBottom = panelBottom + padY
        drawButton(
            batch, px, buttonFont, cancelLabel,
            contentLeft, btnBottom, btnW, btnH,
            HitKind.Back, pointerWorldX, pointerWorldY,
        )
        drawButton(
            batch, px, buttonFont, saveLabel,
            contentLeft + btnW + btnGap, btnBottom, btnW, btnH,
            HitKind.Submit, pointerWorldX, pointerWorldY,
        )
    }

    private fun drawField(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        hits += Hit(HitKind.Field, left, bottom, w, h)
        aliasField.layout(left, bottom, w, h)
        aliasField.render(batch, px, font, pointerWorldX, pointerWorldY)
    }

    private fun drawButton(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        kind: HitKind,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        hits += Hit(kind, left, bottom, w, h)
        val hovered =
            !busy &&
                !pointerWorldX.isNaN() &&
                !pointerWorldY.isNaN() &&
                hits.last().contains(pointerWorldX, pointerWorldY)
        val bg = when {
            busy -> AppPalette.tint(AppPalette.plateDeep, 0.80f)
            else -> OverlayChrome.rowFill(hovered)
        }
        val rim = OverlayChrome.rowRim()
        OverlayChrome.drawButton(
            batch, px, font, glyphLayout, label,
            left, bottom, w, h, bg, rim,
            if (hovered) AppPalette.inkStrong else AppPalette.ink,
        )
    }

    /**
     * Geometry seam for plain JVM tests: the press machine must be provable
     * without a GL frame, since the panel and button rects are otherwise only set
     * by [render].
     */
    internal fun seedLayoutForTest(
        panel: Rect,
        submit: Rect,
        back: Rect,
        busy: Boolean = false,
    ) {
        this.busy = busy
        panelLeft = panel.left
        panelBottom = panel.bottom
        panelW = panel.width
        panelH = panel.height
        hits.clear()
        hits += Hit(HitKind.Submit, submit.left, submit.bottom, submit.width, submit.height)
        hits += Hit(HitKind.Back, back.left, back.bottom, back.width, back.height)
        clearGesture()
    }

    override fun dispose() {
    }

    companion object {
        /** The Submit control, as the id its release reports. */
        const val ROW_SUBMIT = "submit"

        /** The Back/Save control, as the id its release reports. */
        const val ROW_BACK = "back"
    }
}
