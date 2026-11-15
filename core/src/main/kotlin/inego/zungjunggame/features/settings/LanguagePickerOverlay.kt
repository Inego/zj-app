package inego.zungjunggame.features.settings

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of the picker's card bounds for the last layout. */
data class LanguagePickerGeometry(
    val panel: Rect?,
)

/**
 * Modal list of [AppLocale] entries for multi-language selection.
 *
 * Shows each language’s **endonym** (native self-name) so users can find
 * their language regardless of the current UI locale. Scales as entries are
 * added to [AppLocale] (zh-HK, fr, ja, …).
 *
 * Opened from the game menu; the top-right ✕, Esc or an outside tap dismisses
 * back to the menu (handled by [inego.zungjunggame.Main]). Title is sticky; a
 * long locale list scrolls inside [OverlayViewport].
 */
class LanguagePickerOverlay : Disposable, GestureSurface<ModalVerdict> {
    private val body = OverlayBodyScroll()
    private val closeButton = CloseButton()

    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f
    private var itemH = 0f
    private var itemGap = 0f
    private var rowLeft = 0f
    private var rowW = 0f
    private var locales: List<AppLocale> = emptyList()

    private var pressTarget: PressTarget = PressTarget.None

    private enum class PressTarget { None, Body, Chrome, Close }

    /**
     * Pixels the list is scrolled down (0 = top), reported to the
     * [inego.zungjunggame.ui.GestureBridge]'s consumed-scroll channel so the
     * component can emit it as the route's state.
     */
    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Advance the list's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /** Locale under the pointer, or null. Uses live scroll. */
    fun localeAt(worldX: Float, worldY: Float): AppLocale? {
        if (locales.isEmpty() || itemH <= 0f) return null
        if (!body.hitsContent(worldX, worldY)) return null
        val index = body.rowIndexAt(worldY, 0f, itemH, itemH + itemGap, locales.size)
            ?: return null
        return locales[index]
    }

    /**
     * Pure snapshot of the card the last [render] laid out (or the last
     * [seedLayoutForTest] installed). No GL / fonts.
     */
    fun measureGeometry(): LanguagePickerGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return LanguagePickerGeometry(panel)
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        body.wheel(amountY)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        body.cancel()
        pressTarget = PressTarget.None
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressTarget = PressTarget.Close
            return true
        }
        if (body.contains(x, y) && body.beginPointer(x, y)) {
            pressTarget = PressTarget.Body
            return true
        }
        pressTarget = PressTarget.Chrome
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (pressTarget != PressTarget.Body) return null
        return ModalVerdict(dragScrollOffset = body.dragTo(y))
    }

    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        return when (target) {
            PressTarget.Body -> {
                val wasDrag = body.endPointer()
                val locale = if (!wasDrag && !x.isNaN() && !y.isNaN()) {
                    localeAt(x, y)
                } else {
                    null
                }
                ModalVerdict(
                    selectedId = locale?.name,
                    settledScrollOffset = if (wasDrag) body.scrollY else null,
                )
            }
            PressTarget.Close -> {
                val hit = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y)
                body.cancel()
                ModalVerdict(close = hit)
            }
            PressTarget.Chrome, PressTarget.None -> {
                body.cancel()
                ModalVerdict()
            }
        }
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        Fonts.ensureCreated()
        Fonts.ensureFallback()
        locales = AppLocale.entries.toList()

        val px = whitePixel()
        val current = LocaleController.current

        batch.setColor(AppPalette.scrimLight)
        batch.draw(px, 0f, 0f, screenW, screenH)

        val titleFont = Fonts.labelActive
        val itemFont = Fonts.button
        val title = L10n.get("menu.language_title")
        val titleW = Fonts.textWidth(titleFont, title)

        val padX = Fonts.px(28f)
        val padY = Fonts.px(20f)
        val titleGap = Fonts.px(14f)
        // The ✕ sits in the title's band, so its slot is part of the header the
        // card must be wide enough to hold. Sizes are read from the control, not
        // its last layout, which is 0 on the first frame.
        val closeInset = CloseButton.cornerInset()
        val closeSide = CloseButton.defaultSide()
        val closeSlot = closeInset + closeSide + Fonts.px(10f)
        itemH = max(Fonts.px(48f), itemFont.lineHeight + Fonts.px(22f))
        itemGap = Fonts.px(8f)

        var contentW = titleW
        for (locale in locales) {
            val label = LocaleController.endonym(locale)
            contentW = max(contentW, Fonts.textWidth(itemFont, label))
        }

        val avail = OverlayViewport.available(screenW, screenH)
        val portrait = screenH > screenW
        val maxPanelW = OverlayViewport.maxPanelWidth(
            screenW * (if (portrait) 0.88f else 0.72f),
            avail,
            screenW,
            Fonts.px(24f),
        )
        panelW = max(Fonts.px(240f), contentW + padX * 2f)
        // The title is centred, so the control's slot has to clear it on both
        // sides — a translation that ran under the button would be unreadable.
        panelW = max(panelW, titleW + closeSlot * 2f)
        panelW = if (InputProfile.detect() == InputProfile.Touch) {
            max(panelW, screenW * 0.36f).coerceAtMost(maxPanelW)
        } else {
            panelW.coerceAtMost(maxPanelW)
        }
        panelW = panelW.coerceAtMost(avail.width)

        // The header band must also hold the ✕ clear of the first row: its slot
        // plus the list's gap is a second lower bound on the chrome.
        val chromeH = max(
            padY * 2f + titleFont.lineHeight + titleGap,
            closeInset + closeSide + titleGap + padY,
        )
        val bodyContentH = locales.size * itemH +
            (locales.size - 1).coerceAtLeast(0) * itemGap
        val fitted = body.fitAndPlace(
            avail = avail,
            panelW = panelW,
            chromeH = chromeH,
            bodyContentH = bodyContentH,
            bodyInset = padY,
            scrollY = scrollY,
        )
        panelH = fitted.cardH
        panelLeft = fitted.left
        panelBottom = fitted.bottom
        rowLeft = panelLeft + Fonts.px(12f)
        rowW = body.contentWidth(panelW, Fonts.px(24f), Fonts.px(24f))

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )
        // The title is sticky chrome, so the ✕ is the card's corner control, not
        // part of the scrolled list.
        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH, closeInset)

        Fonts.drawText(
            batch,
            titleFont,
            title,
            panelLeft + (panelW - titleW) * 0.5f,
            panelBottom + panelH - padY,
            Color.WHITE,
        )
        closeButton.render(
            batch = batch,
            whitePixel = px,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
        )

        val hoverLocale = localeAt(body.hoverX(pointerWorldX), body.hoverY(pointerWorldY))

        body.drawBody(batch, camera, px, pointerWorldX, pointerWorldY) {
            var itemTop = body.contentTop
            for (locale in locales) {
                val bottom = itemTop - itemH
                if (bottom < body.bottom + body.height && itemTop > body.bottom) {
                    drawRow(
                        batch = batch,
                        px = px,
                        locale = locale,
                        bottom = bottom,
                        selected = locale == current,
                        hovered = locale == hoverLocale,
                    )
                }
                itemTop = bottom - itemGap
            }
        }
    }

    private fun drawRow(
        batch: SpriteBatch,
        px: Texture,
        locale: AppLocale,
        bottom: Float,
        selected: Boolean,
        hovered: Boolean,
    ) {
        val itemFont = Fonts.button
        val bg = OverlayChrome.rowFill(hovered, selected)
        val rim = OverlayChrome.rowRim(selected)
        OverlayChrome.drawPanel(
            batch, px, rowLeft, bottom, rowW, itemH, bg, rim, OverlayChrome.buttonRimPx(),
        )

        val endonym = LocaleController.endonym(locale)
        val textW = Fonts.textWidth(itemFont, endonym)
        val textColor =
            if (hovered || selected) AppPalette.inkStrong
            else AppPalette.ink
        Fonts.drawText(
            batch = batch,
            primary = itemFont,
            text = endonym,
            x = rowLeft + (rowW - textW) * 0.5f,
            y = bottom + itemH * 0.5f + itemFont.capHeight * 0.5f,
            color = textColor,
        )
    }

    internal fun seedLayoutForTest(
        panelLeft: Float = 200f,
        panelBottom: Float = 12f,
        panelW: Float = 400f,
        panelH: Float = 400f,
        itemH: Float = 144f,
        itemGap: Float = 24f,
        bodyBottom: Float = 32f,
        bodyH: Float = 300f,
        scrollY: Float = 0f,
        locales: List<AppLocale> = AppLocale.entries,
    ) {
        this.panelLeft = panelLeft
        this.panelBottom = panelBottom
        this.panelW = panelW
        this.panelH = panelH
        this.itemH = itemH
        this.itemGap = itemGap
        this.locales = locales
        val bodyContentH = locales.size * itemH + (locales.size - 1).coerceAtLeast(0) * itemGap
        body.layout(panelLeft, bodyBottom, panelW, bodyH, bodyContentH, scrollY)
        rowLeft = panelLeft + 12f
        rowW = panelW - 24f
    }

    internal val overflowingForTest: Boolean get() = body.overflowingForTest
    internal val maxScrollYForTest: Float get() = body.maxScrollYForTest

    /**
     * The offset that brings [locale]'s row fully inside the body, moving
     * minimally from [currentScrollY]. A query against the last layout — it moves
     * nothing; the component emits the value it resolves.
     */
    fun revealLocaleOffset(locale: AppLocale?, currentScrollY: Float = body.scrollY): Float {
        if (locale == null || itemH <= 0f) return currentScrollY
        val idx = locales.indexOf(locale)
        if (idx < 0) return currentScrollY
        return body.revealRowOffset(
            index = idx,
            headerH = 0f,
            rowH = itemH,
            strideY = itemH + itemGap,
            currentScrollY = currentScrollY,
        )
    }

    override fun dispose() {
    }
}
