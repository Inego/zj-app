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
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.TextFit
import inego.zungjunggame.render.ui.tapSlopPx
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max

/** GL-free snapshot of the account card's bounds and controls. */
data class AccountGeometry(
    val panel: Rect?,
    val changeName: Rect? = null,
    val deleteAccount: Rect? = null,
    val closeButton: Rect? = null,
)

/**
 * The player's account card: who they are signed in as, a **Change name** action,
 * and, set apart at the bottom, the subdued **Delete account** link.
 *
 * This is the second-level panel behind the main menu's identity dropdown. The
 * dropdown stays a two-row entry point (Account… / Log out), so the destructive
 * deletion path is not a menu-row neighbour of Log out, while the app still
 * links to the public deletion page that Google Play requires.
 *
 * The overlay owns the press → drag → release machine and reports the control a
 * release resolved ([PointerResult]); [AccountComponent] publishes the card as
 * the gesture region and bridges that verdict to a
 * [inego.zungjunggame.ui.ComponentAction]. The name is the model's: the
 * component hands it in from the account controller each frame.
 */
class AccountOverlay : Disposable, GestureSurface<AccountOverlay.PointerResult> {
    private val glyphLayout = GlyphLayout()

    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var changeLeft = 0f
    private var changeBottom = 0f
    private var changeW = 0f
    private var changeH = 0f

    private var deleteLeft = 0f
    private var deleteBottom = 0f
    private var deleteW = 0f
    private var deleteH = 0f

    /** The signed-in label this frame; the component reads it from the model. */
    private var displayName = ""

    private val closeButton = CloseButton()

    private enum class PressTarget { ChangeName, DeleteAccount, Close, Panel, None }

    private var pressTarget = PressTarget.None
    private var pressY = Float.NaN
    private var dragged = false

    enum class PointerResult {
        /** The player asked for the display-name editor. */
        ChangeName,

        /** The player opened the public account-deletion page. */
        DeleteAccount,

        /** The top-right close control was tapped. */
        Close,

        /** The release resolved no control. */
        None,
    }

    /** Sets the name the next measured/painted frame shows. */
    fun setDisplayName(name: String) {
        displayName = name
    }

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /**
     * Pure snapshot of the card the last [render] laid out. No GL / fonts.
     */
    fun measureGeometry(): AccountGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        val change = if (changeW > 0f && changeH > 0f) {
            Rect(changeLeft, changeBottom, changeW, changeH)
        } else {
            null
        }
        val delete = if (deleteW > 0f && deleteH > 0f) {
            Rect(deleteLeft, deleteBottom, deleteW, deleteH)
        } else {
            null
        }
        val close = if (closeButton.side > 0f) {
            Rect(closeButton.leftX, closeButton.bottomY, closeButton.side, closeButton.side)
        } else {
            null
        }
        return AccountGeometry(panel, change, delete, close)
    }

    /** No scroll region: the card is sized to its controls. */
    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean = false

    override fun onPointerDown(x: Float, y: Float): Boolean {
        pressTarget = when {
            closeButton.contains(x, y) -> PressTarget.Close
            inRect(x, y, changeLeft, changeBottom, changeW, changeH) -> PressTarget.ChangeName
            inRect(x, y, deleteLeft, deleteBottom, deleteW, deleteH) -> PressTarget.DeleteAccount
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
     * The release resolves the control the press began on: a completed press on
     * **Change name** opens the editor, on **Delete account** opens the deletion
     * page, on the close control dismisses the card. A drag resolves nothing.
     */
    override fun onPointerUp(x: Float, y: Float): PointerResult {
        val startedOn = pressTarget
        val moved = dragged
        clearGesture()
        if (moved) return PointerResult.None
        return when (startedOn) {
            PressTarget.ChangeName -> PointerResult.ChangeName
            PressTarget.DeleteAccount -> PointerResult.DeleteAccount
            PressTarget.Close -> PointerResult.Close
            else -> PointerResult.None
        }
    }

    override fun cancelGesture() = clearGesture()

    private fun clearGesture() {
        pressTarget = PressTarget.None
        pressY = Float.NaN
        dragged = false
    }

    private fun inRect(x: Float, y: Float, left: Float, bottom: Float, w: Float, h: Float): Boolean =
        w > 0f && h > 0f &&
            x >= left && x <= left + w && y >= bottom && y <= bottom + h

    fun render(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        Fonts.ensureCreated()

        val px = whitePixel()
        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)

        val titleFont = Fonts.labelActive
        val nameFont = Fonts.label
        val buttonFont = Fonts.button

        val title = L10n.get("account.title")
        val changeLabel = L10n.get("alias.change_name")
        val deleteLabel = L10n.get("account.delete_account")
        val name = displayName.trim()

        val padX = Fonts.px(28f)
        val padY = Fonts.px(22f)
        val titleGap = Fonts.px(12f)
        val nameGap = Fonts.px(18f)
        val buttonH = max(Fonts.px(46f), buttonFont.lineHeight + Fonts.px(20f))
        val linkGap = Fonts.px(14f)
        val linkH = max(Fonts.px(42f), buttonFont.lineHeight + Fonts.px(16f))

        val portrait = screenH > screenW
        val avail = OverlayViewport.available(screenW, screenH)
        val maxPanelW = OverlayViewport.maxPanelWidth(
            screenW * (if (portrait) 0.88f else 0.52f),
            avail,
            screenW,
            Fonts.px(24f),
        )

        glyphLayout.setText(titleFont, title, Color.WHITE, maxPanelW - padX * 2f, Align.center, true)
        var contentW = glyphLayout.width
        if (name.isNotEmpty()) {
            contentW = max(contentW, Fonts.textWidth(nameFont, name))
        }
        for (label in listOf(changeLabel, deleteLabel)) {
            glyphLayout.setText(buttonFont, label)
            contentW = max(contentW, glyphLayout.width + Fonts.px(24f))
        }
        panelW = max(Fonts.px(300f), contentW + padX * 2f).coerceAtMost(maxPanelW)

        glyphLayout.setText(titleFont, title, Color.WHITE, panelW - padX * 2f, Align.center, true)
        val titleH = glyphLayout.height
        val nameH = if (name.isEmpty()) 0f else Fonts.textHeight(nameFont, name)

        panelH = padY * 2f + titleH + titleGap + nameH + nameGap + buttonH + linkGap + linkH
        val clamped = OverlayViewport.clampSize(panelW, panelH, avail)
        panelW = clamped.first
        panelH = clamped.second
        val placed = OverlayViewport.placeCentered(panelW, panelH, avail)
        panelLeft = placed.first
        panelBottom = placed.second

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH)
        closeButton.render(
            batch = batch,
            whitePixel = px,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
        )

        val contentLeft = panelLeft + padX
        val contentWInner = (panelW - padX * 2f).coerceAtLeast(1f)

        // Title
        titleFont.draw(
            batch, title,
            contentLeft, panelBottom + panelH - padY,
            contentWInner, Align.center, true,
        )

        // Signed-in name
        var top = panelBottom + panelH - padY - titleH - titleGap
        if (name.isNotEmpty()) {
            val fitted = TextFit.ellipsize(nameFont, name, contentWInner)
            val nameX = contentLeft + (contentWInner - Fonts.textWidth(nameFont, fitted)) * 0.5f
            Fonts.drawText(batch, nameFont, fitted, nameX, top, AppPalette.inkMuted)
        }
        top -= nameH + nameGap

        // Change name: a standard tappable row, the card's primary action.
        changeLeft = contentLeft
        changeBottom = top - buttonH
        changeW = contentWInner
        changeH = buttonH
        val changeHovered = inRect(pointerWorldX, pointerWorldY, changeLeft, changeBottom, changeW, changeH)
        OverlayChrome.drawButton(
            batch, px, buttonFont, changeLabel,
            changeLeft, changeBottom, changeW, changeH,
            OverlayChrome.rowFill(changeHovered),
            OverlayChrome.rowRim(),
            if (changeHovered) AppPalette.inkStrong else AppPalette.ink,
        )

        // Delete account: a plain, subdued link at the bottom, set off from the
        // row above it so it never reads as the card's primary action.
        top = changeBottom - linkGap
        deleteLeft = contentLeft
        deleteBottom = top - linkH
        deleteW = contentWInner
        deleteH = linkH
        val deleteHovered = inRect(pointerWorldX, pointerWorldY, deleteLeft, deleteBottom, deleteW, deleteH)
        if (deleteHovered) {
            batch.setColor(AppPalette.plateRaised)
            batch.draw(px, deleteLeft, deleteBottom, deleteW, deleteH)
            batch.setColor(Color.WHITE)
        }
        buttonFont.color = if (deleteHovered) AppPalette.danger else AppPalette.dangerInkSoft
        buttonFont.draw(
            batch, TextFit.ellipsize(buttonFont, deleteLabel, deleteW - Fonts.px(4f)),
            deleteLeft + Fonts.px(4f), deleteBottom + (linkH + buttonFont.capHeight) * 0.5f,
            (deleteW - Fonts.px(4f)).coerceAtLeast(1f), Align.left, false,
        )
        buttonFont.color = Color.WHITE

        batch.setColor(Color.WHITE)
    }

    override fun dispose() {
    }
}
