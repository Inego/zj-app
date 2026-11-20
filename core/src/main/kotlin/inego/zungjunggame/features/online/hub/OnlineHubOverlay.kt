package inego.zungjunggame.features.online.hub

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.online.LatencyLabel
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.Hyperlink
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.TextFit
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.OnlineTab
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * GL-free shell geometry for one laid-out hub frame. Every rect is in the frame
 * the shell painted: the column's bands are already shifted by [scrollY], while
 * [body] is the unscrolled row measure and [viewport] is the card's fixed inner
 * box.
 */
data class OnlineHubGeometry(    val panel: Rect,
    val header: Rect,
    /**
     * The header's **identity row**: the alias and the ping. Both are the signed-in
     * player's; the hub is never shown to a signed-out one.
     */
    val identityRow: Rect,
    /**
     * The header's **ranked row**: the rating — which is itself the viewer's
     * history link — and the **Leaderboard** link. Its height is the links' touch
     * target.
     */
    val rankedRow: Rect,
    val tabStrip: Rect,
    val liveTab: Rect,
    val shadowTab: Rect,
    /**
     * The **Leaderboard** link and its touch target, which is the whole
     * [rankedRow]: a target sized to its ink was the one control a finger could not
     * find. Right-anchored before the close button, after the rating's slot, so it
     * never sits on the identity row. `measure` is font-free, so the slot is a
     * fixed 128dp sized for [Fonts.button] and the painter fits the label into it.
     */
    val leaderboard: Rect,
    /**
     * The viewer's **rating, as their own history link**: the number printed at
     * the head of the ranked row is the way into the player's rating history, so
     * the header needs no separate label for it. The rect is the rating's cell and
     * its touch target. It sits left of the Leaderboard link's fixed slot, so
     * neither can reach the other.
     */
    val rating: Rect,
    val close: Rect,
    /**
     * The active tab body's **unscrolled** measure: its row width and the top its
     * first row starts at. Every other rect here is shifted by [scrollY]; this is
     * the layout input the panel lays its rows into, and the panel applies the
     * offset itself so its own hit regions move with the rows.
     */
    val body: Rect,
    /**
     * The card's inner box, rim excluded: the clip every scrolled band is drawn
     * inside, and the gesture viewport the card scrolls in.
     */
    val viewport: Rect,
    /** Whole scrolled content height: top padding, header, tab strip, body, bottom padding. */
    val contentHeight: Float,
    /** The offset the shell was measured at, clamped to the card's scroll range. */
    val scrollY: Float,
    val activeTab: OnlineTab,
    /**
     * The hub-level **Rejoin** band: the way back to a table the player is still
     * seated at, shown above the tab strip so it belongs to the hub rather than to
     * either tab. Null when there is no table to return to.
     */
    val rejoin: Rect? = null,
    /**
     * The Live tab label's in-progress-search dot; null when no Live search is
     * running. It sits on the tab the Live search belongs to even while the
     * player reads the Shadow Ranked tab.
     */
    val liveSearchIndicator: Rect? = null,
)

/**
 * The unified Online hub's screen shell: one card that hugs the taller of the two
 * tab bodies up to the viewport, with a two-tab strip, a rejoin band, and a close
 * button, routing the body to the active tab panel.
 *
 * Shell geometry is a pure function of the viewport, [OnlineHubState] and a body
 * height the panel measured ([measure]); the fitted rects come from the last
 * paint. [OnlineHubComponent] publishes the tab/close/rejoin hit regions
 * from `layout()` and this shell paints the exact rects it published. The shell
 * owns the card chrome, the header and the two panels ([LiveTabPanel],
 * [ShadowRankedTabPanel]); a panel owns its own body, controls and gesture
 * machine, and is handed the card's viewport only to clip and scroll what it
 * draws.
 *
 * The card scrolls as **one column**: the header, the rejoin band, the tab strip
 * and the body all share the tab's offset, so the chrome travels with the
 * content it belongs to. Only the rim and the scrollbar stay put — the bar is
 * the card's, and [measure] shifts every band by the same amount it maps.
 *
 * **Rejoin** is the shell's own, above the tab strip rather than in a body: the
 * table it returns to belongs to neither tab, so neither body can own the way
 * back to it. See [OnlineHubView.rejoinAvailable].
 *
 * Nothing here is authoritative: the tab and both scroll offsets come from the
 * route state handed in each frame.
 */
class OnlineHubOverlay(
    val livePanel: LiveTabPanel = LiveTabPanel(),
    val shadowPanel: ShadowRankedTabPanel = ShadowRankedTabPanel(),
) : Disposable {
    private val closeButton = CloseButton()
    private val glyphLayout = GlyphLayout()
    private val clipBounds = Rectangle()
    private val scissors = Rectangle()

    /** Geometry the last paint fitted to the two bodies' taller content. */
    private var measured: OnlineHubGeometry? = null
    private var measuredW = Float.NaN
    private var measuredH = Float.NaN

    private fun whitePixel(): Texture = WhitePixel.get()

    /**
     * The fitted geometry of the last paint, for the component's input layout.
     *
     * Null when no frame has painted at this viewport yet (the component falls
     * back to the viewport-filling [measure]). Geometry depends on the two
     * bodies' font-measured content heights, so it belongs to paint; this is the
     * same last-frame seam every other overlay card uses.
     */
    fun measureGeometry(screenW: Float, screenH: Float): OnlineHubGeometry? =
        measured?.takeIf { measuredW == screenW && measuredH == screenH }

    /** The gesture surface on top for [tab]; the body's controls live in it. */
    fun panelFor(tab: OnlineTab): GestureSurface<HubVerdict> = when (tab) {
        OnlineTab.Live -> livePanel
        OnlineTab.ShadowRanked -> shadowPanel
    }

    /** The active body's current scroll offset, or null when it keeps none. */
    fun scrollOffset(tab: OnlineTab): Float? = panelFor(tab).scrollOffset()

    fun scrollCoasting(tab: OnlineTab): Boolean = panelFor(tab).scrollCoasting()

    /** Advance the active body's fling; [deltaSec] comes from the host's frame. */
    fun advance(tab: OnlineTab, deltaSec: Float): Float = when (tab) {
        OnlineTab.Live -> livePanel.advance(deltaSec)
        OnlineTab.ShadowRanked -> shadowPanel.advance(deltaSec)
    }

    fun cancelGesture(tab: OnlineTab) {
        panelFor(tab).cancelGesture()
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        state: OnlineHubState,
        view: OnlineHubView = OnlineHubView(),
        shadowView: ShadowRankedView = ShadowRankedView(),
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        Fonts.ensureCreated()
        // The card hugs the taller of the two bodies, so it needs the fonts both
        // will paint with.
        val geometry = geometryFor(screenW, screenH, state, view, shadowView, state.scrollFor(state.tab))
        measured = geometry
        measuredW = screenW
        measuredH = screenH
        val px = whitePixel()

        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)
        OverlayChrome.drawPanel(
            batch, px,
            geometry.panel.left, geometry.panel.bottom, geometry.panel.width, geometry.panel.height,
            AppPalette.panel,
        )

        // The chrome is part of the scrolled column: it clips to the card's inner
        // box like the body does, so a band the offset pushed past the rim cannot
        // paint over it.
        clipTo(geometry.viewport, batch, camera) {
            renderChrome(
                batch = batch,
                px = px,
                geometry = geometry,
                state = state,
                view = view,
                pointerX = pointerWorldX,
                pointerY = pointerWorldY,
            )
        }

        when (state.tab) {
            OnlineTab.Live -> livePanel.render(
                batch = batch,
                camera = camera,
                bodyRect = geometry.body,
                viewport = geometry.viewport,
                contentHeight = geometry.contentHeight,
                scrollY = geometry.scrollY,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
                view = view,
            )
            OnlineTab.ShadowRanked -> shadowPanel.render(
                batch = batch,
                camera = camera,
                bodyRect = geometry.body,
                viewport = geometry.viewport,
                contentHeight = geometry.contentHeight,
                scrollY = geometry.scrollY,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
                view = shadowView,
                draft = state.draft,
            )
        }
    }

    /**
     * The card's one scrolled column: the header, the rejoin band, the tab strip
     * and the search dot, all placed at the offset [OnlineHubGeometry.measure]
     * shifted them to. Called inside the card's clip; the body is painted by its
     * panel, which clips itself.
     */
    private fun renderChrome(
        batch: SpriteBatch,
        px: Texture,
        geometry: OnlineHubGeometry,
        state: OnlineHubState,
        view: OnlineHubView,
        pointerX: Float,
        pointerY: Float,
    ) {
        renderHeader(
            batch = batch,
            px = px,
            identityRow = geometry.identityRow,
            rankedRow = geometry.rankedRow,
            close = geometry.close,
            leaderboard = geometry.leaderboard,
            rating = geometry.rating,
            view = view,
            pointerX = pointerX,
            pointerY = pointerY,
        )

        geometry.rejoin?.let { rejoin ->
            // Bold, and in the hub's go-green: Rejoin is the one control that leaves
            // the hub for something the player already owns, so it reads as the
            // affirmative rather than as another destination. The ink and the face
            // both come from the shell's own primitives ([HubDraw], [Fonts]) — the
            // label is the same "Rejoin" the Live primary used to swap to, so the
            // affordance is one the player has already read, just finally in a place
            // it can be found from either tab.
            HubDraw.drawButtonRow(
                batch = batch,
                px = px,
                font = Fonts.buttonBold,
                label = L10n.get("online.live.rejoin"),
                left = rejoin.left,
                bottom = rejoin.bottom,
                w = rejoin.width,
                h = rejoin.height,
                border = HubDraw.borderPx(),
                pointerX = pointerX,
                pointerY = pointerY,
                accent = HubDraw.goGreenInk,
            )
        }

        val labelFont = Fonts.button
        drawTab(
            batch, px, labelFont, geometry.liveTab,
            "online.hub.tab.live", state.tab == OnlineTab.Live, pointerX, pointerY,
        )
        drawTab(
            batch, px, labelFont, geometry.shadowTab,
            "online.hub.tab.shadowRanked", state.tab == OnlineTab.ShadowRanked, pointerX, pointerY,
        )
        geometry.liveSearchIndicator?.let { dot ->
            batch.setColor(AppPalette.tint(AppPalette.accent, 0.95f))
            batch.draw(px, dot.left, dot.bottom, dot.width, dot.height)
            batch.setColor(Color.WHITE)
        }

        batch.setColor(Color.WHITE)
        closeButton.layoutAt(geometry.close.left, geometry.close.bottom, geometry.close.width)
        closeButton.render(batch, px, pointerX, pointerY)
    }

    /**
     * Draw [block] clipped to [rect]. The chrome and the panel each clip their own
     * paint; the rim is already on the batch, so the clip only ever trims content.
     */
    private fun clipTo(rect: Rect, batch: SpriteBatch, camera: Camera, block: () -> Unit) {
        batch.flush()
        clipBounds.set(rect.left, rect.bottom, rect.width, rect.height)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val on = ScissorStack.pushScissors(scissors)
        try {
            block()
        } finally {
            batch.flush()
            if (on) ScissorStack.popScissors()
        }
    }

    /**
     * The shell fitted to the taller of the two bodies: width is independent of
     * height, so measure once to get the body's measure, ask both tabs how tall
     * their rows are at that width, then measure the card around the taller.
     */
    private fun geometryFor(
        screenW: Float,
        screenH: Float,
        state: OnlineHubState,
        view: OnlineHubView,
        shadowView: ShadowRankedView,
        scrollY: Float,
    ): OnlineHubGeometry {
        val base = measure(
            screenW, screenH, state, view.searching,
            rejoinAvailable = view.rejoinAvailable,
        )
        val bodyW = base.body.width
        // Both tabs share one card height: the taller body wins, so switching
        // tabs never resizes the card or moves the tab strip under the pointer.
        // Both are measured at the same width, so the two heights are comparable.
        // The offset, though, belongs to the tab on screen — its own content is
        // what the card can scroll through.
        val liveH = livePanel.contentHeight(bodyW, view)
        val shadowH = shadowPanel.contentHeight(bodyW, shadowView, state.draft)
        val activeH = if (state.tab == OnlineTab.Live) liveH else shadowH
        return measure(
            screenW, screenH, state, view.searching,
            rejoinAvailable = view.rejoinAvailable,
            bodyContentH = max(liveH, shadowH),
            activeBodyH = activeH,
            scrollY = scrollY,
        )
    }

    private fun renderHeader(
        batch: SpriteBatch,
        px: Texture,
        identityRow: Rect,
        rankedRow: Rect,
        close: Rect,
        leaderboard: Rect,
        rating: Rect,
        view: OnlineHubView,
        pointerX: Float,
        pointerY: Float,
    ) {
        val nameFont = Fonts.label
        val smallFont = Fonts.small

        // The **Leaderboard** link is common to both headers: [measure] put it on
        // the row beside the header's own content, so it reads the same on both
        // tabs. Set in [Fonts.button] like every other tappable control;
        // [Fonts.small] made it the smallest text here.
        drawHeaderLink(
            batch = batch,
            px = px,
            rect = leaderboard,
            label = L10n.get("leaderboard.open"),
            pointerX = pointerX,
            pointerY = pointerY,
        )

        // The hub is a signed-in surface, so the header is the identity: alias,
        // rating and ping. There is no signed-out branch — a signed-out player is
        // on the main menu, where Log in lives.
        val identityText = view.signedInLabel
            ?.let { L10n.format("online.live.signed_in_as", it) }
            .orEmpty()
        val ratingText = ratingLine(view.eloRating)
        // A dash when there is no fresh sample, so the slot never disappears.
        val latencyText = LatencyLabel.text(view.latencyMs)
        val rightEdge = close.left - Fonts.px(10f)

        // The ping is measured, not reserved: its label is translated and may be
        // the no-sample dash, so the alias is fitted to the ink actually there.
        glyphLayout.setText(smallFont, latencyText)
        val latencyWidth = glyphLayout.width

        if (identityText.isNotEmpty()) {
            val budget = (rightEdge - latencyWidth - Fonts.px(12f) - identityRow.left)
                .coerceAtLeast(1f)
            val identityY = identityRow.centerY + nameFont.capHeight * 0.5f
            nameFont.color = AppPalette.inkMuted
            nameFont.draw(
                batch,
                TextFit.ellipsize(nameFont, identityText, budget),
                identityRow.left,
                identityY,
            )
            nameFont.color = Color.WHITE
        }

        // The rating is the viewer's own history link: the number's cell is the
        // published target, so the header's second destination is the value
        // itself instead of a separate label.
        if (ratingText.isNotEmpty()) {
            drawRatingLink(
                batch = batch,
                px = px,
                rect = rating,
                label = ratingText,
                pointerX = pointerX,
                pointerY = pointerY,
            )
        }

        val pingY = identityRow.centerY + smallFont.capHeight * 0.5f
        val latencyX = rightEdge - latencyWidth
        smallFont.color = LatencyLabel.color(view.latencyMs)
        smallFont.draw(batch, latencyText, latencyX, pingY)
        smallFont.color = Color.WHITE
    }

    private fun drawTab(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        rect: Rect,
        labelKey: String,
        active: Boolean,
        pointerX: Float,
        pointerY: Float,
    ) {
        HubDraw.drawButtonRow(
            batch = batch,
            px = px,
            font = font,
            label = L10n.get(labelKey),
            left = rect.left,
            bottom = rect.bottom,
            w = rect.width,
            h = rect.height,
            border = HubDraw.borderPx(),
            pointerX = pointerX,
            pointerY = pointerY,
            selected = active,
        )
    }

    /**
     * The header's **Leaderboard** link: a [Hyperlink] centred in [rect] — which
     * is also the published hit region. The face, ink, hover lift, rule and fit
     * to the target all belong to the widget, so this link and the hub bodies'
     * **Past games** / **Past attempts** links cannot drift apart.
     */
    private fun drawHeaderLink(
        batch: SpriteBatch,
        px: Texture,
        rect: Rect,
        label: String,
        pointerX: Float,
        pointerY: Float,
    ) {
        Hyperlink.drawCentered(
            batch = batch,
            whitePixel = px,
            label = label,
            rect = rect,
            hovered = hovered(rect, pointerX, pointerY),
        )
    }

    /**
     * The header's **rating-as-link**: the viewer's own rating printed at the head
     * of the ranked row, inside [rect] — which is also its published touch target
     * — opening the viewer's rating history. Left-aligned with the alias above,
     * because a number is a datum rather than a centred label, and fitted to its
     * own cell so a longer value shrinks instead of running into the Leaderboard
     * link. The ink, rule and hover lift are [Hyperlink]'s, so a number that is a
     * way out reads like every other link.
     */
    private fun drawRatingLink(
        batch: SpriteBatch,
        px: Texture,
        rect: Rect,
        label: String,
        pointerX: Float,
        pointerY: Float,
    ) {
        val font = Fonts.button
        Fonts.withFaceScale(font, Fonts.fitScale(Hyperlink.width(label), rect.width)) {
            Hyperlink.draw(
                batch = batch,
                whitePixel = px,
                label = label,
                x = rect.left,
                baseline = rect.centerY - font.capHeight * 0.5f,
                hovered = hovered(rect, pointerX, pointerY),
            )
        }
    }

    /** Whether the pointer is on [rect]; [Rect.contains] rejects a NaN pointer. */
    private fun hovered(rect: Rect, pointerX: Float, pointerY: Float): Boolean =
        rect.contains(pointerX, pointerY)

    override fun dispose() {
        livePanel.dispose()
        shadowPanel.dispose()
    }

    companion object {
        /**
         * Rating number: the same rounded number the main menu shows. The raw
         * server value is fractional (`1199.75`), so formatting it as `toInt()`
         * here would show `1199` under a menu that says `1200`.
         */
        fun ratingLine(eloRating: Double?): String =
            MatchHistoryFormat.ratingLabel(eloRating).orEmpty()

        /**
         * Pure shell geometry: the card hugs the taller of the two bodies up to
         * the viewport margins, and every band inside it — header, rejoin band,
         * tab strip, body — sits in one column, shifted by [scrollY]. The rim and
         * the bar do not move. No fonts and no GL, so `layout()` and `draw()` can
         * derive the same rects from a content height the caller measured.
         *
         * [rejoinAvailable] is model-derived, so layout and paint read the same
         * answer and the published hit region and the painted band cannot disagree.
         *
         * [bodyContentH] is the taller body's unconstrained content height: it
         * sizes the card so switching tabs never resizes it. [activeBodyH] is the
         * body actually on screen; its content — chrome included — is what the
         * offset can travel through, so the shorter tab cannot scroll past its own
         * last row. It defaults to [bodyContentH] for the pre-paint seed.
         *
         * [scrollY] is the tab's offset, clamped here to the card's range so the
         * painted bands, the published regions and the panel's own rows share one
         * number.
         */
        fun measure(
            screenW: Float,
            screenH: Float,
            state: OnlineHubState,
            searching: Boolean = false,
            rejoinAvailable: Boolean = false,
            bodyContentH: Float = Float.POSITIVE_INFINITY,
            activeBodyH: Float = bodyContentH,
            scrollY: Float = 0f,
        ): OnlineHubGeometry {
            val portrait = screenH > screenW
            val marginX = if (portrait) screenW * 0.04f else screenW * 0.10f
            val marginY = if (portrait) screenH * 0.04f else screenH * 0.08f
            // A landscape card is capped and centred so the hub reads as a card
            // rather than a full-width panel; portrait keeps the near-full width.
            val panelW = if (portrait) {
                (screenW - marginX * 2f).coerceAtLeast(1f)
            } else {
                (screenW - marginX * 2f).coerceIn(1f, Fonts.px(560f))
            }
            val pad = Fonts.px(if (portrait) 16f else 22f)
            // The header is sized by its rows: the link's target is the ranked
            // row, so it gets the 40dp the Rejoin band gets.
            val identityRowH = Fonts.px(18f)
            val linkTargetH = Fonts.px(40f)
            val headerH = identityRowH + linkTargetH
            val headerGap = Fonts.px(6f)
            val tabH = Fonts.px(46f)
            val rejoinH = Fonts.px(40f)
            val bodyGap = Fonts.px(16f)
            val chromeH = pad + headerH + headerGap +
                (if (rejoinAvailable) rejoinH + headerGap else 0f) +
                tabH + bodyGap + pad
            // The shared hug-height owner: the card is chrome plus body content,
            // capped to the margin box and centered in it. The rim is outside the
            // scroll: every band clips to the inner box, so a row passing the edge
            // cannot paint over the border.
            val available = Rect(
                left = 0f,
                bottom = marginY,
                width = screenW.coerceAtLeast(1f),
                height = (screenH - marginY * 2f).coerceAtLeast(1f),
            )
            val whole = OverlayViewport.fitWholeCard(available, panelW, chromeH, bodyContentH)
            val panel = whole.panel
            val viewport = whole.inner
            // One offset for every band. The state carries the offset the player
            // left the tab at; the card clamps it to the active tab's own content,
            // and paint, hit regions and the panel's rows all read the clamped
            // value back from the geometry.
            val contentHeight = if (activeBodyH.isFinite()) chromeH + activeBodyH else viewport.height
            val maxScroll = (contentHeight - viewport.height).coerceAtLeast(0f)
            val s = scrollY.coerceIn(0f, maxScroll)
            val header = Rect(
                left = panel.left + pad,
                bottom = panel.top - pad - headerH,
                width = (panel.width - pad * 2f).coerceAtLeast(1f),
                height = headerH,
            )
            val closeSide = Fonts.px(36f)
            val close = Rect(
                left = header.right - closeSide,
                bottom = header.bottom + (header.height - closeSide) * 0.5f,
                width = closeSide,
                height = closeSide,
            )
            // The identity row keeps the alias the whole width of the top row.
            val identityRow = Rect(
                left = header.left,
                bottom = header.top - identityRowH,
                width = header.width,
                height = identityRowH,
            )
            val rankedRow = Rect(
                left = header.left,
                bottom = header.bottom,
                width = header.width,
                height = (header.height - identityRowH).coerceAtLeast(1f),
            )
            // The ranked row's right-anchored zone is the **Leaderboard** link;
            // the rating at the row's head is the viewer's own history link. Their
            // widths are fixed because `measure` is font-free; the painter fits
            // each label. The links do not share a zone: the Leaderboard link keeps
            // its own 128dp and the rating keeps the slot reserved for it, so
            // neither can squeeze the other to nothing on a narrow screen.
            val linkGap = Fonts.px(12f)
            val ratingSlot = Fonts.px(52f)
            val linkW = Fonts.px(128f)
            val linkRight = close.left - Fonts.px(10f)
            val linkLeft = max(linkRight - linkW, header.left + ratingSlot + linkGap)
            val leaderboard = Rect(
                left = linkLeft,
                bottom = rankedRow.bottom,
                width = (linkRight - linkLeft).coerceAtLeast(1f),
                height = rankedRow.height,
            )
            // The rating is the viewer's own history link, at the head of the
            // ranked row.
            val rating = Rect(
                left = rankedRow.left,
                bottom = rankedRow.bottom,
                width = ratingSlot,
                height = rankedRow.height,
            )
            // The strip is inset by the body pad, so the tabs align with the body
            // content and clear the header instead of hugging it. The rejoin band
            // is a peer of the strip, not part of it: it belongs to the hub, so it
            // sits above both tabs and takes the same inset, and only when there is
            // a table to return to. Taking its space out of the body rather than
            // squashing the strip keeps the tab hit targets at one size.
            val rejoin = if (rejoinAvailable) {
                Rect(
                    left = panel.left + pad,
                    bottom = header.bottom - headerGap - rejoinH,
                    width = (panel.width - pad * 2f).coerceAtLeast(1f),
                    height = rejoinH,
                )
            } else {
                null
            }
            val tabStrip = Rect(
                left = panel.left + pad,
                bottom = (rejoin?.bottom ?: header.bottom) - headerGap - tabH,
                width = (panel.width - pad * 2f).coerceAtLeast(1f),
                height = tabH,
            )

            // Two tabs share the strip evenly with a gap between them.
            val gap = Fonts.px(8f)
            val tabW = ((tabStrip.width - gap) / 2f).coerceAtLeast(1f)
            val liveTab = Rect(tabStrip.left, tabStrip.bottom, tabW, tabH)
            val shadowTab = Rect(liveTab.right + gap, tabStrip.bottom, tabW, tabH)

            val indicatorSide = Fonts.px(10f)
            val indicatorInset = Fonts.px(8f)
            val liveSearchIndicator = if (searching) {
                Rect(
                    left = liveTab.right - indicatorInset - indicatorSide,
                    bottom = liveTab.top - indicatorInset - indicatorSide,
                    width = indicatorSide,
                    height = indicatorSide,
                )
            } else {
                null
            }

            // The tab strip and the body are separate surfaces, so the space between
            // them is breathing room, not a row gap: the body's first line (a
            // summary or a description) reads as belonging to the tab it was
            // switched to, not as a continuation of the strip's border.
            val bodyTop = tabStrip.bottom - bodyGap
            val bodyBottom = panel.bottom + pad
            val body = Rect(
                left = panel.left + pad,
                bottom = bodyBottom,
                width = (panel.width - pad * 2f).coerceAtLeast(1f),
                height = (bodyTop - bodyBottom).coerceAtLeast(1f),
            )
            // Every band in the column travels with the offset; [body] stays the
            // unscrolled measure the panel lays its rows into.
            fun scrolled(rect: Rect): Rect =
                if (s == 0f) rect else Rect(rect.left, rect.bottom + s, rect.width, rect.height)
            return OnlineHubGeometry(
                panel = panel,
                header = scrolled(header),
                identityRow = scrolled(identityRow),
                rankedRow = scrolled(rankedRow),
                rejoin = rejoin?.let(::scrolled),
                tabStrip = scrolled(tabStrip),
                liveTab = scrolled(liveTab),
                shadowTab = scrolled(shadowTab),
                leaderboard = scrolled(leaderboard),
                rating = scrolled(rating),
                close = scrolled(close),
                body = body,
                viewport = viewport,
                contentHeight = contentHeight,
                scrollY = s,
                activeTab = state.tab,
                liveSearchIndicator = liveSearchIndicator?.let(::scrolled),
            )
        }
    }
}
