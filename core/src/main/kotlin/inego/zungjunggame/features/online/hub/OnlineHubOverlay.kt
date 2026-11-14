package inego.zungjunggame.features.online.hub

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
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

/** GL-free shell geometry for one laid-out hub frame. */
data class OnlineHubGeometry(
    val panel: Rect,
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
    val body: Rect,
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
 * machine and never sees the card.
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
        val geometry = geometryFor(screenW, screenH, state, view, shadowView)
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

        renderHeader(
            batch = batch,
            px = px,
            identityRow = geometry.identityRow,
            rankedRow = geometry.rankedRow,
            close = geometry.close,
            leaderboard = geometry.leaderboard,
            rating = geometry.rating,
            view = view,
            pointerX = pointerWorldX,
            pointerY = pointerWorldY,
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
                pointerX = pointerWorldX,
                pointerY = pointerWorldY,
                accent = HubDraw.goGreenInk,
            )
        }

        val labelFont = Fonts.button
        drawTab(
            batch, px, labelFont, geometry.liveTab,
            "online.hub.tab.live", state.tab == OnlineTab.Live, pointerWorldX, pointerWorldY,
        )
        drawTab(
            batch, px, labelFont, geometry.shadowTab,
            "online.hub.tab.shadowRanked", state.tab == OnlineTab.ShadowRanked, pointerWorldX, pointerWorldY,
        )
        geometry.liveSearchIndicator?.let { dot ->
            batch.setColor(AppPalette.tint(AppPalette.accent, 0.95f))
            batch.draw(px, dot.left, dot.bottom, dot.width, dot.height)
            batch.setColor(Color.WHITE)
        }

        batch.setColor(Color.WHITE)
        closeButton.layoutAt(geometry.close.left, geometry.close.bottom, geometry.close.width)
        closeButton.render(batch, px, pointerWorldX, pointerWorldY)

        when (state.tab) {
            OnlineTab.Live -> livePanel.render(
                batch = batch,
                camera = camera,
                bodyRect = geometry.body,
                scrollY = state.liveScroll,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
                view = view,
            )
            OnlineTab.ShadowRanked -> shadowPanel.render(
                batch = batch,
                camera = camera,
                bodyRect = geometry.body,
                scrollY = state.shadowScroll,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
                view = shadowView,
                draft = state.draft,
            )
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
    ): OnlineHubGeometry {
        val base = measure(
            screenW, screenH, state, view.searching,
            rejoinAvailable = view.rejoinAvailable,
        )
        val bodyW = base.body.width
        // Both tabs share one card height: the taller body wins, so switching
        // tabs never resizes the card or moves the tab strip under the pointer.
        // Both are measured at the same width, so the two heights are comparable.
        val contentH = max(
            livePanel.contentHeight(bodyW, view),
            shadowPanel.contentHeight(bodyW, shadowView, state.draft),
        )
        return measure(
            screenW, screenH, state, view.searching,
            rejoinAvailable = view.rejoinAvailable,
            bodyContentH = contentH,
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
        val latencyText = view.latencyMs?.let { LatencyLabel.text(it) }.orEmpty()
        val rightEdge = close.left - Fonts.px(10f)

        // The ping is measured, not reserved: its label is translated, so the
        // alias is fitted to the ink actually there.
        var latencyWidth = 0f
        if (latencyText.isNotEmpty()) {
            glyphLayout.setText(smallFont, latencyText)
            latencyWidth = glyphLayout.width
        }

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

        if (latencyText.isNotEmpty()) {
            val pingY = identityRow.centerY + smallFont.capHeight * 0.5f
            val latencyX = rightEdge - latencyWidth
            val latencyInk = view.latencyMs?.let { LatencyLabel.color(it) }
                ?: AppPalette.inkWarmMuted
            smallFont.color = latencyInk
            smallFont.draw(batch, latencyText, latencyX, pingY)
            smallFont.color = Color.WHITE
        }
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
         * the viewport margins, the tab strip is its pinned top band, and the body
         * is everything below it. The rejoin band, when present, is a pinned band
         * above the strip. No fonts and no GL, so `layout()` and `draw()` can
         * derive the same rects from a content height the caller measured.
         *
         * [rejoinAvailable] is model-derived, so layout and paint read the same
         * answer and the published hit region and the painted band cannot disagree.
         *
         * [bodyContentH] is the taller body's unconstrained content height. The
         * default [Float.POSITIVE_INFINITY] keeps the card viewport-filling, which
         * is the seed the component uses before its first fitted paint.
         */
        fun measure(
            screenW: Float,
            screenH: Float,
            state: OnlineHubState,
            searching: Boolean = false,
            rejoinAvailable: Boolean = false,
            bodyContentH: Float = Float.POSITIVE_INFINITY,
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
            // capped to the margin box and centered in it.
            val available = OverlayViewport.Rect(
                left = 0f,
                bottom = marginY,
                width = screenW.coerceAtLeast(1f),
                height = (screenH - marginY * 2f).coerceAtLeast(1f),
            )
            val fit = OverlayViewport.fitSticky(available.height, chromeH, bodyContentH)
            val placed = OverlayViewport.placeCentered(panelW, fit.cardH, available)
            val panel = Rect(
                left = placed.first,
                bottom = placed.second,
                width = panelW,
                height = fit.cardH,
            )
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
            return OnlineHubGeometry(
                panel = panel,
                header = header,
                identityRow = identityRow,
                rankedRow = rankedRow,
                rejoin = rejoin,
                tabStrip = tabStrip,
                liveTab = liveTab,
                shadowTab = shadowTab,
                leaderboard = leaderboard,
                rating = rating,
                close = close,
                body = body,
                activeTab = state.tab,
                liveSearchIndicator = liveSearchIndicator,
            )
        }
    }
}
