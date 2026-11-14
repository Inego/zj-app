package inego.zungjunggame.features.online

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.online.LatencyLabel
import inego.zungjunggame.render.online.PopulationLabel
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.tapSlopPx
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.online.ConnectionFailure
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** One action button of the connection card, with the action it raises. */
data class OnlineConnectButtonGeometry(
    val rect: Rect,
    val action: OnlineConnectOverlay.PointerResult,
)

/** GL-free snapshot of the connection card for one laid-out frame. */
data class OnlineConnectGeometry(
    val panel: Rect?,
    val buttons: List<OnlineConnectButtonGeometry>,
)

/**
 * Center card shown while an online table is being joined and no snapshot is on
 * screen yet — connecting, returning, attached-but-not-yet-dealt, or retrying.
 * Cancel leaves the table (clears the stored id). Empty felt around the card
 * still opens the game menu.
 *
 * **Ownership.** This overlay owns its own press → drag → release state machine
 * and reports the control a release resolved ([PointerResult]);
 * [OnlineConnectComponent] publishes the panel as the gesture region and bridges
 * that verdict to a [inego.zungjunggame.ui.ComponentAction]. No control travels as a hit region.
 */
class OnlineConnectOverlay : Disposable, GestureSurface<OnlineConnectOverlay.PointerResult> {
    private val glyphLayout = GlyphLayout()

    private fun whitePixel(): Texture = WhitePixel.get()

    var status: Status = Status.Connecting
        private set

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private data class ButtonHit(
        val result: PointerResult,
        val left: Float,
        val bottom: Float,
        val w: Float,
        val h: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= left && px <= left + w && py >= bottom && py <= bottom + h
    }

    private val buttonHits = ArrayList<ButtonHit>(2)

    /** The control the in-flight press began on (a button result or [PointerResult.Panel]). */
    private var pressTarget: PointerResult? = null
    private var pressY = Float.NaN
    private var dragged = false

    enum class Status {
        Connecting,
        Rejoining,

        /**
         * Socket is up and the seat is held, but no `SeatSnapshot` has arrived.
         * Nothing is wrong — the remaining players have not attached yet.
         */
        WaitingForOpponent,
        Retrying,
        ReconnectRequired,
        AuthExpired,
        TableGone,
    }

    enum class PointerResult { Cancel, Retry, ReturnHome, Panel, Outside }

    fun setStatus(status: Status) {
        this.status = status
    }

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /**
     * Pure snapshot of the controls the last [render] laid out: card and one
     * rect per action button, in paint order. No GL / fonts.
     */
    fun measureGeometry(): OnlineConnectGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return OnlineConnectGeometry(
            panel = panel,
            buttons = buttonHits.map {
                OnlineConnectButtonGeometry(Rect(it.left, it.bottom, it.w, it.h), it.result)
            },
        )
    }

    /**
     * Claim the press and record the control it began on: a button first, else the
     * panel body.
     */
    override fun onPointerDown(x: Float, y: Float): Boolean {
        pressTarget = buttonHits.lastOrNull { it.contains(x, y) }?.result
            ?: PointerResult.Panel
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
     * The release resolves the button the press began on; a drag or a press that
     * began on the panel body resolves the panel when the lift is still inside it,
     * and outside otherwise. The pipeline only routes in-panel presses here, so the
     * panel/outside split is the layer's own boundary, not a button verdict.
     */
    override fun onPointerUp(x: Float, y: Float): PointerResult {
        val startedOn = pressTarget
        val moved = dragged
        clearGesture()
        if (startedOn != null && startedOn != PointerResult.Panel && !moved) return startedOn
        return if (containsPanel(x, y)) PointerResult.Panel else PointerResult.Outside
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
        latencyMs: Int? = null,
        snapshot: PresenceSnapshot? = null,
    ) {
        Fonts.ensureCreated()

        val px = whitePixel()
        val touch = InputProfile.detect() == InputProfile.Touch
        val titleFont = Fonts.labelActive
        val itemFont = Fonts.button
        val infoFont = Fonts.small
        val padX = Fonts.px(28f)
        val padY = Fonts.px(20f)
        val titleGap = Fonts.px(18f)
        val btnH = max(Fonts.px(48f), itemFont.lineHeight + Fonts.px(22f))

        val title = title(status)
        val populationText = snapshot
            ?.takeIf { status != Status.AuthExpired && status != Status.TableGone }
            ?.let { PopulationLabel.text(it) }
            .orEmpty()
        val latencyText = latencyMs?.let { LatencyLabel.text(it) }.orEmpty()
        val actionSpecs = actionSpecs()

        val portrait = screenH > screenW
        val maxPanelRatio = if (portrait) 0.88f else 0.72f
        val maxPanelW = (screenW * maxPanelRatio).coerceAtMost(screenW - Fonts.px(24f))
        var widestBtn = Fonts.px(100f)
        for ((_, label) in actionSpecs) {
            glyphLayout.setText(itemFont, label)
            widestBtn = max(widestBtn, glyphLayout.width + Fonts.px(40f))
        }
        glyphLayout.setText(titleFont, title)
        val titleW = glyphLayout.width
        val populationW = if (populationText.isEmpty()) {
            0f
        } else {
            glyphLayout.setText(infoFont, populationText)
            glyphLayout.width
        }
        val latencyW = if (latencyText.isEmpty()) {
            0f
        } else {
            glyphLayout.setText(infoFont, latencyText)
            glyphLayout.width
        }
        val minPanelW = if (touch) {
            max(Fonts.px(320f), screenW * 0.36f).coerceAtMost(maxPanelW)
        } else {
            Fonts.px(280f).coerceAtMost(maxPanelW)
        }
        panelW = max(minPanelW, maxOf(titleW, widestBtn, latencyW, populationW) + padX * 2f).coerceAtMost(maxPanelW)
        val maxTextW = panelW - padX * 2f
        glyphLayout.setText(titleFont, title, Color.WHITE, maxTextW, Align.center, true)
        val titleH = glyphLayout.height
        val lineGap = Fonts.px(8f)
        val populationH = if (populationText.isEmpty()) {
            0f
        } else {
            glyphLayout.setText(infoFont, populationText)
            glyphLayout.height + lineGap
        }
        val latencyH = if (latencyText.isEmpty()) {
            0f
        } else {
            glyphLayout.setText(infoFont, latencyText)
            glyphLayout.height + lineGap
        }
        val buttonCount = actionSpecs.size
        val btnGap = Fonts.px(8f)
        panelH = padY * 2f + titleH + titleGap + populationH + latencyH + btnH * buttonCount +
            btnGap * (buttonCount - 1).coerceAtLeast(0)
        panelLeft = (screenW - panelW) * 0.5f
        panelBottom = (screenH - panelH) * 0.5f

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val titleTop = panelBottom + panelH - padY
        titleFont.draw(
            batch,
            title,
            panelLeft + padX,
            titleTop,
            maxTextW,
            Align.center,
            true,
        )
        var infoTop = titleTop - titleH - lineGap
        if (populationText.isNotEmpty()) {
            glyphLayout.setText(infoFont, populationText)
            infoFont.color = PopulationLabel.defaultColor
            infoFont.draw(
                batch,
                populationText,
                panelLeft + (panelW - glyphLayout.width) * 0.5f,
                infoTop,
            )
            infoFont.color = Color.WHITE
            infoTop -= glyphLayout.height + lineGap
        }
        if (latencyText.isNotEmpty()) {
            val ink = latencyMs?.let { LatencyLabel.color(it) } ?: Color.WHITE
            infoFont.color = ink
            glyphLayout.setText(infoFont, latencyText)
            infoFont.draw(
                batch,
                latencyText,
                panelLeft + (panelW - glyphLayout.width) * 0.5f,
                infoTop,
            )
            infoFont.color = Color.WHITE
        }

        buttonHits.clear()
        val t = OverlayChrome.buttonRimPx()
        var btnBottom = panelBottom + padY
        for (i in actionSpecs.lastIndex downTo 0) {
            val (result, label) = actionSpecs[i]
            val btnW = widestBtn
            val btnLeft = panelLeft + (panelW - btnW) * 0.5f
            buttonHits += ButtonHit(result, btnLeft, btnBottom, btnW, btnH)
            drawActionButton(
                batch, px, itemFont, label,
                btnLeft, btnBottom, btnW, btnH, t,
                pointerWorldX, pointerWorldY,
            )
            btnBottom += btnH + btnGap
        }
    }

    internal fun actionSpecs(): List<Pair<PointerResult, String>> = when (status) {
        Status.ReconnectRequired -> listOf(
            PointerResult.Retry to L10n.get("online.retry_now"),
            PointerResult.ReturnHome to L10n.get("online.return_home"),
        )
        // An expired session cannot be renewed from this card: the main menu's
        // Log in is the one way in, so the card only offers the way home.
        Status.AuthExpired -> listOf(
            PointerResult.ReturnHome to L10n.get("online.return_home"),
        )
        Status.TableGone -> listOf(
            PointerResult.ReturnHome to L10n.get("online.return_home"),
        )
        // Every recoverable state offers Cancel so the player can never be trapped.
        Status.Connecting, Status.Rejoining, Status.WaitingForOpponent, Status.Retrying -> listOf(
            PointerResult.Cancel to L10n.get("online.cancel"),
        )
    }

    private fun drawActionButton(
        batch: SpriteBatch,
        px: Texture,
        font: com.badlogic.gdx.graphics.g2d.BitmapFont,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        t: Float,
        pointerX: Float,
        pointerY: Float,
    ) {
        val hovered = !pointerX.isNaN() && !pointerY.isNaN() &&
            pointerX >= left && pointerX <= left + w &&
            pointerY >= bottom && pointerY <= bottom + h
        OverlayChrome.drawButton(
            batch, px, font, label,
            left, bottom, w, h,
            OverlayChrome.actionFill(hovered = hovered),
            OverlayChrome.actionRim(),
            if (hovered) AppPalette.inkStrong else AppPalette.ink,
            t,
        )
    }

    /**
     * Geometry seam for plain JVM tests: the press machine must be provable
     * without a GL frame, since the panel and buttons are otherwise only set by
     * [render] (see `OnlineConnectFeatureTest`). The status and button list are
     * the real ones the render path would build, so the release follows the live
     * status → action mapping.
     */
    internal fun seedLayoutForTest(
        status: Status,
        panel: Rect,
        buttons: List<OnlineConnectButtonGeometry>,
    ) {
        this.status = status
        panelLeft = panel.left
        panelBottom = panel.bottom
        panelW = panel.width
        panelH = panel.height
        buttonHits.clear()
        for ((rect, action) in buttons) {
            buttonHits.add(
                ButtonHit(
                    action,
                    rect.left,
                    rect.bottom,
                    rect.width,
                    rect.height,
                ),
            )
        }
        clearGesture()
    }

    override fun dispose() {
    }

    companion object {
        /**
         * The card's headline for [status]. The one mapping from a status to its
         * copy, so [render] and the tests read the same keys; a test that restated
         * this table would be a second source of truth.
         */
        internal fun title(status: Status): String = L10n.get(titleKey(status))

        /** The i18n key behind [title]; the structural form a test can assert. */
        internal fun titleKey(status: Status): String = when (status) {
            Status.Connecting -> "online.connect"
            Status.Rejoining -> "online.rejoin"
            Status.WaitingForOpponent -> "online.waiting_opponent"
            Status.Retrying -> "online.retry"
            Status.ReconnectRequired -> "online.reconnect_required"
            Status.AuthExpired -> "online.auth_expired"
            Status.TableGone -> "online.table_gone"
        }

        /**
         * Maps the live host state onto the card to show while no snapshot is on screen.
         *
         * [ConnectionStatus.Connected] means the socket is healthy and the seat is held; the
         * only thing missing is a `SeatSnapshot`. It must never render as a connection error.
         * This covers the first attach (waiting for the rest of the reservation) and, for a
         * single round-trip, the resync after a reconnect.
         *
         * [failure] is checked first because it outlives [connectionStatus]: a failed
         * ticket moves the host to [ConnectionStatus.ReconnectRequired] with a reason the
         * player has to act on.
         *
         * [connectionStatus] is null while no online host is attached — a restored
         * table presence has not adopted yet — and that reads as [Status.Connecting],
         * never a rejoin: nothing is being rejoined until a host says so.
         * [ConnectionStatus.Local] is the local fallback host, which is not an online
         * connection at all; it maps the same way rather than inventing a rejoin.
         */
        fun statusFor(
            connectionStatus: ConnectionStatus?,
            failure: ConnectionFailure?,
        ): Status = when (failure) {
            ConnectionFailure.Authentication -> Status.AuthExpired
            ConnectionFailure.TableUnavailable -> Status.TableGone
            ConnectionFailure.Unreachable -> when (connectionStatus) {
                ConnectionStatus.Reconnecting -> Status.Rejoining
                ConnectionStatus.ReconnectRequired -> Status.ReconnectRequired
                else -> Status.Retrying
            }
            null -> when (connectionStatus) {
                null, ConnectionStatus.Connecting, ConnectionStatus.Local -> Status.Connecting
                ConnectionStatus.Connected -> Status.WaitingForOpponent
                ConnectionStatus.Reconnecting -> Status.Rejoining
                ConnectionStatus.ReconnectRequired -> Status.ReconnectRequired
                ConnectionStatus.Disconnected -> Status.Rejoining
            }
        }
    }
}
