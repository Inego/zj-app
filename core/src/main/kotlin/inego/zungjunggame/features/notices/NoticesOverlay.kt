package inego.zungjunggame.features.notices

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.richtext.RichTextView
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of a notices card's bounds after the last render. */
data class NoticesGeometry(
    val panel: Rect?,
)

/**
 * The notices inbox card: a title, a close control, and the
 * notice bodies as reference markup — emphasis, colors, and the inline table —
 * drawn through one scrolling [RichTextView].
 *
 * The same overlay is the **list** (all of a player's notices, newest first) and
 * the **popup** ([compact], one freshly arrived notice) so the two share the parse
 * and render pipeline; the host supplies which notices through its render input.
 *
 * The offset is the owner's: every wheel and drag is emitted through the gesture
 * bridge, and [render] is handed the owner's stored value each frame.
 */
class NoticesOverlay(
    private val compact: Boolean = false,
    sharedTileAssets: (() -> TileAssets)? = null,
) : Disposable, GestureSurface<ModalVerdict> {
    private val glyphLayout = GlyphLayout()
    private val closeButton = CloseButton()
    private val view = RichTextView(sharedTileAssets = sharedTileAssets)


    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var pressTarget: PressTarget = PressTarget.None

    private enum class PressTarget { None, Close, Content, Panel }

    private var appliedMarkup: String? = null

    /** Replace the body content; a no-op when the markup is unchanged (keeps scroll). */
    fun applyMarkup(markup: String) {
        if (markup == appliedMarkup) return
        appliedMarkup = markup
        view.setMarkup(markup)
    }

    /** The body's current scroll offset, for the owner's stored value. */
    override fun scrollOffset(): Float = view.scrollY

    override fun scrollCoasting(): Boolean = view.isCoasting

    /** Advance the body's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = view.advance(deltaSec)

    fun containsPanel(worldX: Float, worldY: Float): Boolean =
        worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH

    /** GL-free snapshot of the card's bounds. */
    fun measureGeometry(): NoticesGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return NoticesGeometry(panel)
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        if (!containsPanel(x, y)) return false
        view.onScroll(amountY)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        view.cancelGesture()
        pressTarget = PressTarget.None
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressTarget = PressTarget.Close
            return true
        }
        if (view.contains(x, y)) {
            pressTarget = PressTarget.Content
            view.onPointerDown(x, y)
        } else {
            pressTarget = PressTarget.Panel
        }
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (pressTarget != PressTarget.Content) return null
        val offset = view.onPointerDrag(y)
        return ModalVerdict(dragScrollOffset = offset)
    }

    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        return when (target) {
            PressTarget.Close -> {
                val hit = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y)
                view.cancelGesture()
                ModalVerdict(close = hit)
            }
            PressTarget.Content -> {
                val result = view.onPointerUp()
                ModalVerdict(settledScrollOffset = if (result.wasDrag) view.scrollY else null)
            }
            PressTarget.Panel -> {
                view.cancelGesture()
                ModalVerdict()
            }
            PressTarget.None -> {
                view.cancelGesture()
                ModalVerdict()
            }
        }
    }

    override fun cancelGesture() {
        pressTarget = PressTarget.None
        view.cancelGesture()
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        title: String,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        Fonts.ensureCreated()

        if (compact) {
            panelW = (screenW * 0.86f).coerceAtMost(Fonts.px(560f))
            panelH = (screenH * 0.72f).coerceAtMost(Fonts.px(520f))
            panelLeft = (screenW - panelW) * 0.5f
            panelBottom = (screenH - panelH) * 0.5f
        } else {
            val marginX = if (screenH > screenW) screenW * 0.04f else screenW * 0.10f
            val marginY = if (screenH > screenW) screenH * 0.04f else screenH * 0.08f
            panelLeft = marginX
            panelBottom = marginY
            panelW = screenW - marginX * 2f
            panelH = screenH - marginY * 2f
        }

        val px = whitePixel()
        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val portrait = screenH > screenW
        val pad = Fonts.px(if (portrait) 16f else 22f)
        val closeInset = CloseButton.cornerInset()
        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH, closeInset)
        closeButton.render(batch, px, pointerWorldX, pointerWorldY)

        val titleFont = Fonts.labelActive
        glyphLayout.setText(titleFont, title)
        val titleBaseline =
            panelBottom + panelH - closeInset - (closeButton.side - glyphLayout.height) * 0.5f
        titleFont.draw(batch, title, panelLeft + pad, titleBaseline)

        val bodyLeft = panelLeft + pad
        val bodyW = panelW - pad * 2f
        val bodyTop = panelBottom + panelH - closeInset - closeButton.side - Fonts.px(12f)
        val bodyBottom = panelBottom + pad
        val bodyH = max(Fonts.px(48f), bodyTop - bodyBottom)

        view.render(
            batch = batch,
            camera = camera,
            left = bodyLeft,
            bottom = bodyBottom,
            width = bodyW,
            height = bodyH,
            scrollY = scrollY,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
        )
    }

    private fun whitePixel(): Texture = WhitePixel.get()

    override fun dispose() {
        view.dispose()
    }
}
