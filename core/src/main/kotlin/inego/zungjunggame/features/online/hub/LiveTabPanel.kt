package inego.zungjunggame.features.online.hub

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.PatternlessRule
import inego.zungjunggame.net.PresenceActivity
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.net.QueueRounds
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.richtext.RichAlign
import inego.zungjunggame.render.richtext.RichColorPalette
import inego.zungjunggame.render.richtext.RichTextBlock
import inego.zungjunggame.render.richtext.TextRole
import inego.zungjunggame.render.online.PopulationLabel
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.Slider
import inego.zungjunggame.render.ui.tapSlopPx
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * The hub's **Live** tab: the matchmaking surface, rendered inside the body
 * rect the [OnlineHubOverlay] shell hands down. It is the matchmaking body with
 * the card chrome removed — the shell owns the card, the tab strip and the close
 * button; this panel owns the scrollable body only.
 *
 * Primary action reflects the state:
 * - Signed out → **no body**; the hub is a signed-in surface, so this is only
 *   the cold-start window where a restored hub outruns the session
 * - Idle → **Find game**
 * - Searching/reserved → **Cancel search**
 * - Seated, or a draining server → **disabled**; a new game cannot be started
 *
  * **Rejoin is not a primary state.** It belongs to neither tab, so the shell
  * offers it in its own band above the tab strip
  * ([OnlineHubView.rejoinAvailable]); a seated player finds it there from either
  * tab instead of hunting for it inside Live. That is also what lets a Shadow
  * Ranked attempt — whose home is the other tab — be picked up again.
  *
  * Pure view: every value arrives in [OnlineHubView] and the scroll offset is
  * handed in each frame; the panel keeps only its own gesture state ([body],
  * [slider], the press machine) and emits [HubVerdict]s to its bridge.
  */
class LiveTabPanel : Disposable, GestureSurface<HubVerdict> {
    /** The hourly-convention note's ink; the promo below it is drawn in this too. */
    private val promoColor = AppPalette.inkMuted

    /**
     * The Shadow Ranked escape under the convention note.
     *
     * A [RichTextBlock] at [TextRole.Small] so the line is the same size and ink
     * as the convention note above it — the same pipeline as every other markup
     * surface, with `**…**` in the bundle supplying the one bold run.
     */
    private val promoBlock = RichTextBlock(
        palette = RichColorPalette(named = emptyMap(), default = promoColor),
        bodyRole = TextRole.Small,
    )
    private var lastPromoMarkup: String? = null

    private fun whitePixel(): Texture = WhitePixel.get()

    /** The body viewport the shell installed for this frame. */
    private val body = OverlayBodyScroll()

    /** Rect→verdict map for the frame just painted; rebuilt by [render]. */
    private val regions = HitRegions<HubVerdict>()

    val slider = Slider(min = 0, max = 3, initialValue = 1)

    private var pressTarget: HubVerdict? = null
    private var pressSliderValue: Int? = null
    private var pressBody = false
    private var pressY = Float.NaN
    private var dragged = false

    enum class PrimaryAction { FindGame, CancelSearch, Disabled }

    companion object {
            /**
             * The Live primary's state.
             *
             * A search or a reserved table owns the button (Cancel search). A seat,
             * or a server on its way down, cannot start a *new* game, so the button is
             * disabled — but the way back to the table the player is already sitting at
             * is the shell's Rejoin band, so disabling this costs the player nothing.
             *
             * [storedTableId] no longer takes part: an offline player holding a
             * reservation is rejoinable, not findable, and Rejoin is what they are
             * offered. It is kept only so callers that already hold the value do not
             * have to change shape.
             */
            @Suppress("UNUSED_PARAMETER")
            fun resolvePrimaryAction(
                searching: Boolean = false,
                seated: Boolean = false,
                reserved: Boolean = false,
                draining: Boolean = false,
                storedTableId: String? = null,
            ): PrimaryAction = when {
                searching || reserved -> PrimaryAction.CancelSearch
                draining || seated -> PrimaryAction.Disabled
                else -> PrimaryAction.FindGame
            }

        /**
         * Rating cell: the same rounded number the main menu shows. The raw
         * server value is fractional (`1199.75`), so formatting it as `toInt()`
         * here would show `1199` under a menu that says `1200`.
         */
        fun ratingLine(eloRating: Double?): String = OnlineHubOverlay.ratingLine(eloRating)

        fun otherWaitingCount(searching: Boolean, snapshot: PresenceSnapshot?): Int {
            val totalWaiting = snapshot?.waiting ?: 0
            val weAreWaiting = searching || snapshot?.self?.activity == PresenceActivity.Waiting
            return if (weAreWaiting) (totalWaiting - 1).coerceAtLeast(0) else totalWaiting
        }

        fun isHourlyConventionBannerVisible(nowMs: Long, otherWaiting: Int): Boolean {
            if (otherWaiting > 0) return false
            val totalMinutes = if (nowMs >= 0L) nowMs / 60_000L else (nowMs - 59_999L) / 60_000L
            val minuteOfHour = ((totalMinutes % 60L + 60L) % 60L).toInt()
            return minuteOfHour !in 0..1
        }
    }

    /** Pixels the body is scrolled down (0 = top); the component emits it. */
    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Advance the body's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    /**
     * Slider drag acquisition. Consults the same [regions] map as the press
     * machine, so a slider that was not drawn cannot capture a press.
     */
    fun onSliderPointerDown(worldX: Float, worldY: Float): Int? {
        if (regions.hitAt(worldX, worldY) !is HubVerdict.SetMinOtherHumans) return null
        return slider.onPointerDown(worldX, worldY)
    }

    fun onSliderPointerDrag(worldX: Float): Int? = slider.onPointerDrag(worldX)

    fun onSliderPointerUp(): Int? {
        val finalVal = if (slider.isDragging) slider.value else null
        slider.onPointerUp()
        return finalVal
    }

    fun onSliderCancelDrag() {
        slider.clearDrag()
    }

    /** @return false when the press is outside the body or nothing can scroll. */
    fun beginBodyScroll(worldX: Float, worldY: Float): Boolean {
        return body.overflowing && body.beginPointer(worldX, worldY)
    }

    fun dragBodyScroll(worldY: Float): Float = body.dragTo(worldY)

    /** @return true when the gesture scrolled, so it must not also act as a tap. */
    fun endBodyScroll(): Boolean = body.endPointer()

    fun cancelBodyScroll() {
        body.cancel()
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        body.wheel(amountY)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        if (!body.contains(x, y)) return false
        val sliderValue = onSliderPointerDown(x, y)
        pressSliderValue = sliderValue
        val hit = when {
            sliderValue != null -> HubVerdict.SetMinOtherHumans(sliderValue)
            else -> regions.hitAt(x, y)
        }
        pressTarget = hit
        pressBody = hit == null && beginBodyScroll(x, y)
        pressY = y
        dragged = false
        return true
    }

    override fun pressVerdict(): HubVerdict? =
        pressSliderValue?.let { HubVerdict.SetMinOtherHumans(it) }

    override fun onPointerDrag(x: Float, y: Float): HubVerdict? {
        if (abs(y - pressY) > tapSlopPx()) dragged = true
        if (pressBody) return HubVerdict.BodyScroll(dragBodyScroll(y))
        if (pressSliderValue == null) return null
        return onSliderPointerDrag(x)?.let { HubVerdict.SetMinOtherHumans(it) }
    }

    override fun onPointerUp(x: Float, y: Float): HubVerdict {
        if (pressSliderValue != null) {
            onSliderPointerUp()
            clearPress()
            return HubVerdict.None
        }
        if (pressBody) {
            val wasDrag = endBodyScroll()
            val settled = if (wasDrag) body.scrollY else null
            clearPress()
            return settled?.let(HubVerdict::BodyScroll) ?: HubVerdict.None
        }
        val resolved = pressTarget?.takeIf { it != HubVerdict.None && !dragged }
        clearPress()
        return resolved ?: HubVerdict.None
    }

    override fun cancelGesture() {
        onSliderCancelDrag()
        cancelBodyScroll()
        clearPress()
    }

    private fun clearPress() {
        pressTarget = null
        pressSliderValue = null
        pressBody = false
        pressY = Float.NaN
        dragged = false
    }

    /** Paints the body into [bodyRect]; the offset is the owner's. */
    fun render(
        batch: SpriteBatch,
        camera: Camera,
        bodyRect: Rect,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        view: OnlineHubView = OnlineHubView(),
    ) {
        Fonts.ensureCreated()
        val hoverPointerX = body.hoverX(pointerWorldX)
        val hoverPointerY = body.hoverY(pointerWorldY)
        regions.clear()

        val px = whitePixel()
        val labelFont = Fonts.label
        // The primary is the body's one filled plate: [Fonts.buttonBold] so the
        // verb reads as the action, and a width that hugs its label instead of
        // spanning the gutter — a full-width band would read as the panel's
        // footer rather than as one choice.
        val actionFont = Fonts.buttonBold

        val model = bodyFor(view, bodyRect.width)
        val m = model.m
        body.layout(
            bodyRect.left,
            bodyRect.bottom,
            bodyRect.width,
            bodyRect.height,
            model.rows.height,
            scrollY,
        )

        val geo = lobbyGeometry(bodyRect)
        val rowPlaced = model.rows.place(body.contentTop)
        for ((row, top) in rowPlaced) registerRegions(row, top, geo)

        fun hovered(rect: Rect?): Boolean =
            rect != null && rect.contains(hoverPointerX, hoverPointerY)

        fun rowRegion(row: LobbyRow, top: Float): Rect? =
            row.regions.firstOrNull()?.resolve(top, geo)

        body.drawBody(batch, camera, px, hoverPointerX, hoverPointerY) {
            for ((row, top) in rowPlaced) {
                when (row.kind) {
                    LobbyRowKind.Primary -> HubDraw.drawButtonRow(
                        batch, px, actionFont, model.primaryLabel,
                        geo.left + (geo.width - model.primaryW) * 0.5f, top - m.itemH,
                        model.primaryW, m.itemH, HubDraw.borderPx(),
                        hoverPointerX, hoverPointerY,
                        enabled = model.primaryAction != PrimaryAction.Disabled,
                    )

                    LobbyRowKind.Status -> HubDraw.drawWrapped(
                        batch, labelFont, model.statusLine, geo.left, top, model.wrapW,
                        statusColor(
                            searching = view.searching,
                            stale = view.stale,
                            interrupted = view.searchInterrupted,
                            joining = view.seatedAt != null,
                            notice = model.noticeLine.isNotEmpty(),
                            draining = view.snapshot?.serverShutdown != null,
                        ),
                    )

                    LobbyRowKind.Population -> HubDraw.drawWrapped(
                        batch, labelFont, model.populationLine, geo.left, top, model.wrapW,
                        PopulationLabel.defaultColor,
                    )

                    LobbyRowKind.HourlyConvention -> HubDraw.drawWrapped(
                        batch, Fonts.small, model.hourlyConventionText, geo.left, top,
                        model.wrapW, promoColor,
                    )

                    LobbyRowKind.ShadowRankedPromo -> promoBlock.draw(
                        batch = batch,
                        left = geo.left,
                        top = top,
                        maxWidth = model.wrapW,
                    )

                    LobbyRowKind.SettingsSlider -> {
                        layOutSlider(
                            geo.left, top - m.cycleGap - m.sliderH,
                            geo.width, m.sliderH, view.minOtherHumans,
                        )
                        slider.render(
                            batch, px, font = labelFont, smallFont = Fonts.small,
                            pointerWorldX = hoverPointerX, pointerWorldY = hoverPointerY,
                            muted = model.minOtherFrozen,
                        )
                    }

                    LobbyRowKind.Rounds -> {
                        val roundsLabelKey = when (view.rounds) {
                            QueueRounds.One -> "presence.rounds_one"
                            QueueRounds.Two -> "presence.rounds_two"
                            QueueRounds.Either -> "presence.rounds_either"
                        }
                        HubDraw.drawCycleRow(
                            batch, px, labelFont,
                            geo.left, top - m.cycleGap - m.cycleH,
                            geo.width, m.cycleH, HubDraw.borderPx(),
                            label = L10n.get("presence.rounds"),
                            value = L10n.get(roundsLabelKey),
                            hovered = hovered(rowRegion(row, top)),
                            muted = view.prefsFrozen,
                        )
                    }

                    LobbyRowKind.Chicken -> {
                        HubDraw.drawCycleRow(
                            batch, px, labelFont,
                            geo.left, top - m.cycleGap - m.cycleH,
                            geo.width, m.cycleH, HubDraw.borderPx(),
                            label = PatternlessRule.caption(),
                            value = PatternlessRule.settingValue(view.chicken),
                            hovered = hovered(rowRegion(row, top)),
                            muted = view.prefsFrozen,
                        )
                    }

                    LobbyRowKind.PastGames -> {
                        val rect = rowRegion(row, top)
                        if (rect != null) {
                            HubDraw.drawLink(
                                batch = batch,
                                px = px,
                                label = model.pastGamesLabel,
                                rect = rect,
                                hovered = hovered(rect),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Height of the body's rows at [width]; the hub card hugs this. */
    internal fun contentHeight(width: Float, view: OnlineHubView): Float =
        bodyFor(view, width).rows.height

    /** Everything one frame's body layout derives, before it is painted. */
    private class LobbyBody(
        val m: LobbyMetrics,
        val rows: HubStack<LobbyRow>,
        val wrapW: Float,
        val primaryAction: PrimaryAction,
        val primaryLabel: String,
        val primaryW: Float,
        val noticeLine: String,
        val statusLine: String,
        val populationLine: String,
        val hourlyConventionText: String,
        val minOtherFrozen: Boolean,
        val pastGamesLabel: String,
    )

    private fun bodyFor(view: OnlineHubView, width: Float): LobbyBody {
        Fonts.ensureCreated()
        val labelFont = Fonts.label
        // The one filled plate's face: bold (see the paint arm), and the face its
        // hugging width is measured on.
        val actionFont = Fonts.buttonBold
        val signedIn = view.signedInLabel != null
        val serverShutdown = view.snapshot?.serverShutdown
        val primaryAction = resolvePrimaryAction(
            searching = view.searching,
            seated = view.seatedAt != null,
            reserved = view.reserved,
            draining = serverShutdown != null,
            storedTableId = view.storedTableId,
        )
        val primaryLabel = when (primaryAction) {
            PrimaryAction.FindGame, PrimaryAction.Disabled -> L10n.get("online.live.find_game")
            PrimaryAction.CancelSearch -> L10n.get("online.live.cancel_search")
        }
        val noticeLine = view.notice?.trim().orEmpty()
        val statusLine = when {
            // A signed-out body has no button for the status line to caption, so
            // it carries only a home notice — a cold start can restore the hub
            // before the session does, and the notice explains why nothing is
            // live. See the signed-out early return in [buildRows].
            !signedIn -> noticeLine
            view.seatedAt != null && view.reserved -> L10n.get("online.live.status_joining")
            view.seatedAt != null -> L10n.get("online.live.status_in_progress")
            view.searchInterrupted -> L10n.get("online.live.status_interrupted")
            view.searching -> {
                val elapsed = view.searchElapsedSec?.let { sec ->
                    val mm = sec / 60
                    val ss = sec % 60
                    L10n.format("online.live.elapsed", "%02d:%02d".format(mm, ss))
                }.orEmpty()
                (L10n.get("online.live.status_searching") + " " + elapsed).trim()
            }
            serverShutdown != null -> {
                val msg = serverShutdown.message
                if (msg.isNullOrBlank()) {
                    L10n.get("online.live.status_shutdown")
                } else {
                    L10n.format("online.live.status_shutdown_reason", msg)
                }
            }
            noticeLine.isNotEmpty() -> noticeLine
            view.stale -> L10n.get("presence.error_stale")
            else -> ""
        }
        val populationFresh = view.signedInLabel != null &&
            !view.stale &&
            view.seatedAt == null &&
            !view.searchInterrupted
        val populationLine = view.snapshot
            ?.takeIf { populationFresh }
            ?.let { PopulationLabel.text(it) }
            .orEmpty()

        val pastGamesLabel = L10n.get("history.past_games")
        val minOtherFrozen = view.seatedAt != null || (view.prefsFrozen && !view.searching)
        val otherWaiting = otherWaitingCount(view.searching, view.snapshot)
        // The banner is about playing *now*, and the promo below it invites the
        // Shadow Ranked tab.
        val hourlyConventionShown = isHourlyConventionBannerVisible(view.nowMs, otherWaiting)
        val hourlyConventionText =
            if (hourlyConventionShown) L10n.get("online.live.hourly_convention") else ""
        // The escape to Shadow Ranked rides the same empty-lobby condition as the
        // convention banner, on its own row so it never runs on from that
        // sentence. It goes through [RichTextBlock] at [TextRole.Small] and the
        // same ink, so it reads as a continuation of the banner — same size,
        // same colour — with only the tab name bold. The name is written into the
        // string rather than substituted from the tab label: a language that
        // declines nouns inflects it to the sentence's own case, which an
        // injected nominative label cannot survive.
        val promoMarkup = if (hourlyConventionShown) {
            L10n.get("online.live.shadow_ranked_promo")
        } else {
            ""
        }
        if (promoMarkup != lastPromoMarkup) {
            lastPromoMarkup = promoMarkup
            promoBlock.setInlinesMarkup(promoMarkup, RichAlign.Left)
        }

        val m = LobbyMetrics(
            tightGap = Fonts.px(3f),
            lineGap = Fonts.px(6f),
            sectionGap = Fonts.px(14f),
            blockGap = Fonts.px(20f),
            itemH = max(Fonts.px(48f), actionFont.lineHeight + Fonts.px(22f)),
            cycleH = max(Fonts.px(38f), labelFont.lineHeight + Fonts.px(16f)),
            sliderH = max(Fonts.px(58f), labelFont.lineHeight + Fonts.px(38f)),
            linkRowH = HubDraw.linkRowHeight(actionFont),
            cycleGap = Fonts.px(6f),
            labelLineH = labelFont.lineHeight,
            smallLineH = Fonts.small.lineHeight,
        )

        // Two measures, and they are not interchangeable. [width] is the body's
        // full gutter: the settings plates span it, exactly as the tabs above do,
        // and a scrollbar strip overlays its right edge just as it overlays
        // theirs. The primary is the exception — it is centred in the gutter at
        // the width its label asks for, because it is one choice rather than a
        // band of the panel. [wrapW] is what text gets — short of that strip,
        // whether or not the strip is showing, so a line cannot run under the
        // bar. It is fixed for the same reason: a wrap width that narrowed when
        // the body tipped into scrolling re-wrapped the prose, changed the
        // content's height, and pushed the whole column — the button just pressed
        // included — down by a line. Prose is laid out at [wrapW] *and* painted at
        // [wrapW] for the same reason: measuring at one width and drawing at
        // another re-breaks its lines between the passes and leaves the bottom of
        // every prose row blank.
        val wrapW = body.measureWidth(width, 0f)
        val primaryW = HubDraw.buttonWidth(actionFont, primaryLabel, width)
        val pastGamesW = minOf(HubDraw.linkWidth(pastGamesLabel), width)
        val wrapped = HashMap<LobbyRowKind, Float>()
        if (statusLine.isNotEmpty()) {
            wrapped[LobbyRowKind.Status] = HubDraw.measureWrapped(labelFont, statusLine, wrapW)
        }
        if (populationLine.isNotEmpty()) {
            wrapped[LobbyRowKind.Population] =
                HubDraw.measureWrapped(labelFont, populationLine, wrapW)
        }
        if (hourlyConventionShown) {
            wrapped[LobbyRowKind.HourlyConvention] =
                HubDraw.measureWrapped(Fonts.small, hourlyConventionText, wrapW)
            wrapped[LobbyRowKind.ShadowRankedPromo] = promoBlock.measure(wrapW)
        }
        val content = LobbyContent(
            signedIn = signedIn,
            statusShown = statusLine.isNotEmpty(),
            searching = view.searching,
            seated = view.seatedAt != null,
            populationShown = populationLine.isNotEmpty(),
            hourlyConventionShown = hourlyConventionShown,
            wrappedHeights = wrapped,
            prefsFrozen = view.prefsFrozen,
            minOtherFrozen = minOtherFrozen,
            pastGamesW = pastGamesW,
            primaryW = primaryW,
            primaryDisabled = primaryAction == PrimaryAction.Disabled,
        )
        return LobbyBody(
            m = m,
            rows = buildRows(m, content),
            wrapW = wrapW,
            primaryAction = primaryAction,
            primaryLabel = primaryLabel,
            primaryW = primaryW,
            noticeLine = noticeLine,
            statusLine = statusLine,
            populationLine = populationLine,
            hourlyConventionText = hourlyConventionText,
            minOtherFrozen = minOtherFrozen,
            pastGamesLabel = pastGamesLabel,
        )
    }

    private fun layOutSlider(left: Float, bottom: Float, width: Float, height: Float, value: Int) {
        if (!slider.isDragging) slider.setValue(value)
        slider.label = L10n.get("presence.preset")
        slider.valueText = ""
        slider.stepLabels = listOf("0", "1", "2", "3")
        slider.layout(left, bottom, width, height)
    }

    private fun statusColor(
        searching: Boolean,
        stale: Boolean,
        interrupted: Boolean,
        joining: Boolean,
        notice: Boolean,
        draining: Boolean = false,
    ): Color = when {
        draining || stale || interrupted || notice -> AppPalette.danger
        searching || joining -> AppPalette.accent
        else -> HubDraw.bodyInk
    }

    override fun dispose() {
        promoBlock.dispose()
        lastPromoMarkup = null
    }

    // ── Layout model ────────────────────────────────────────────────────────
    //
    // One copy of the row sequence paints the body, sizes the panel, and answers
    // the pointer; nothing here touches a batch or a font the paint arms do not.

    /** Explicit hit precedence; member order is the spec, lowest wins. */
    internal enum class RegionRank {
        Primary,
        PastGames,
        Slider,
        Rounds,
        Chicken,
    }

    internal enum class LobbyRowKind {
        Primary,
        Status,
        Population,
        HourlyConvention,
        SettingsSlider,
        Rounds,
        Chicken,
        ShadowRankedPromo,
        PastGames,
    }

    /**
     * Where a region's horizontal extent is anchored: the body's full measure, its
     * right edge, or centred over its measure at the region's own width. There is
     * no per-kind inset — every band of the body shares one gutter, the same one
     * the tab strip above it uses.
     */
    internal enum class LobbyAnchor {
        Full,
        Right,
        Center,
    }

    internal class LobbyRegion(
        val action: HubVerdict,
        val rank: RegionRank,
        val anchor: LobbyAnchor,
        val height: Float,
        val topOffset: Float = 0f,
        val width: Float = 0f,
        val leftInset: Float = 0f,
    ) {
        fun resolve(bandTop: Float, geo: LobbyGeo): Rect {
            val top = bandTop - topOffset
            val bottom = top - height
            val w = if (anchor == LobbyAnchor.Full) geo.width else width
            val left = when (anchor) {
                LobbyAnchor.Full -> geo.left
                LobbyAnchor.Right -> geo.left + geo.width - leftInset - w
                LobbyAnchor.Center -> geo.left + (geo.width - w) * 0.5f
            }
            return Rect(left, bottom, w, height)
        }
    }

    /**
     * One band of the panel body.
     *
     * [height] is the band's own painted extent. The air below it is not this
     * row's to decide: it belongs to the boundary with whatever comes next, so
     * [HubStack] owns it. A hit region resolves from the top the stack placed
     * the row at, which is why nothing here needs to know about the gap.
     */
    internal class LobbyRow(
        val kind: LobbyRowKind,
        val height: Float,
        val regions: List<LobbyRegion> = emptyList(),
    )

    /**
     * The single horizontal space every band of the body occupies.
     *
     * The shell sizes [body] to exactly the tab strip's left and width, so taking
     * it verbatim is what lines the controls and prose up with the tabs above
     * them. There is deliberately no per-kind inset: prose, rows and settings all
     * take the same measure, because a body that indents some of its bands and
     * not others reads as a mistake rather than as rhythm. The one control that
     * is not a band — the primary — is centred in this measure at its own width.
     */
    internal class LobbyGeo(
        val left: Float,
        val width: Float,
    )

    internal class LobbyContent(
        val signedIn: Boolean,
        val statusShown: Boolean,
        val searching: Boolean,
        val seated: Boolean,
        val populationShown: Boolean,
        val hourlyConventionShown: Boolean = false,
        val wrappedHeights: Map<LobbyRowKind, Float> = emptyMap(),
        val prefsFrozen: Boolean,
        val minOtherFrozen: Boolean,
        val pastGamesW: Float,
        val primaryW: Float,
        val primaryDisabled: Boolean = false,
    )

    /**
     * Layout metrics for the Live body.
     *
     * [tightGap] keeps a dense list reading as one column, [lineGap] continues
     * the rhythm inside a group, [sectionGap] ends one block of content and
     * opens the next, and [blockGap] marks a change of kind — prose to
     * controls, settings to the action they start.
     *
     * The settings rows carry their own tight [cycleGap] internally and stay on
     * the line rhythm against each other: they are one control group, and
     * holding them apart from the surrounding text is what makes them read as
     * settings rather than another run of lines.
     */
    internal class LobbyMetrics(
        val tightGap: Float,
        val lineGap: Float,
        val sectionGap: Float,
        val blockGap: Float,
        val itemH: Float,
        val cycleH: Float,
        val sliderH: Float,
        val linkRowH: Float,
        val cycleGap: Float,
        val labelLineH: Float,
        val smallLineH: Float,
    )

    private val sliderRegionAction: HubVerdict.SetMinOtherHumans
        get() = HubVerdict.SetMinOtherHumans(slider.min)

    private fun buildRows(m: LobbyMetrics, c: LobbyContent): HubStack<LobbyRow> {
        val rows = HubStack<LobbyRow>(m.tightGap, m.lineGap, m.sectionGap, m.blockGap)

        fun wrapped(kind: LobbyRowKind, fallback: Float): Float =
            c.wrappedHeights[kind] ?: fallback

        // The banner and its Shadow Ranked escape are one block of prose; the
        // settings below start a new kind of thing, so the boundary between them
        // is the block gap however the two are split into rows.
        fun hourlyConventionRows() {
            if (!c.hourlyConventionShown) return
            rows.add(
                LobbyRow(
                    kind = LobbyRowKind.HourlyConvention,
                    height = wrapped(LobbyRowKind.HourlyConvention, m.smallLineH),
                ),
                height = wrapped(LobbyRowKind.HourlyConvention, m.smallLineH),
            )
            // Its own row, so the promo is never run on from the convention's
            // last line by the wrapper.
            rows.add(
                LobbyRow(
                    kind = LobbyRowKind.ShadowRankedPromo,
                    height = wrapped(LobbyRowKind.ShadowRankedPromo, m.smallLineH),
                ),
                height = wrapped(LobbyRowKind.ShadowRankedPromo, m.smallLineH),
                trail = HubGap.Block,
            )
        }

        // The status line reports what the primary button is doing — a search
        // under way with its elapsed time, a table being joined, a server
        // draining — so it is the button's caption and sits directly beneath it.
        // At the top of the body it was a line the reader had to scroll back up
        // to find, and the one line whose whole job is to explain the control
        // nearest the thumb is the one place distance actively hurts.
        //
        // Signed out there is no button to caption, so the row carries only a home
        // notice (see the signed-out early return below).
        fun statusRow(trail: HubGap) {
            if (!c.statusShown) return
            val h = wrapped(LobbyRowKind.Status, m.labelLineH)
            rows.add(LobbyRow(LobbyRowKind.Status, h), height = h, trail = trail)
        }

        // Signed out there is no body to draw: the hub is a signed-in surface, so
        // this branch only exists for the cold-start window where a persisted hub
        // is restored before the session is. It paints the notice (if one
        // explains the state) and nothing else — there is no control here that
        // could sign in, because Log in lives on the main menu.
        if (!c.signedIn) {
            statusRow(HubGap.Line)
            return rows
        }

        hourlyConventionRows()
        // The settings block is always on screen, exactly as Shadow Ranked's start
        // controls are: the slider and the two cycle rows are part of the body,
        // not a drawer that a toggle reveals. Frozen prefs still gate the hit
        // regions, so an in-flight queue cannot be re-pointed mid-search.
        //
        // The three rows are one control group and stay on the tight rhythm;
        // [HubGap.Block] on the last one opens the boundary to whatever follows.
        rows.add(
            LobbyRow(
                kind = LobbyRowKind.SettingsSlider,
                height = m.cycleGap + m.sliderH,
                regions = if (c.minOtherFrozen) {
                    emptyList()
                } else {
                    listOf(
                        LobbyRegion(
                            action = sliderRegionAction,
                            rank = RegionRank.Slider,
                            anchor = LobbyAnchor.Full,
                            height = m.sliderH,
                            topOffset = m.cycleGap,
                        ),
                    )
                },
            ),
            height = m.cycleGap + m.sliderH,
        )
        for (kind in listOf(LobbyRowKind.Rounds, LobbyRowKind.Chicken)) {
            val action = if (kind == LobbyRowKind.Rounds) {
                HubVerdict.CycleRounds
            } else {
                HubVerdict.CycleChicken
            }
            val rank = if (kind == LobbyRowKind.Rounds) RegionRank.Rounds else RegionRank.Chicken
            rows.add(
                LobbyRow(
                    kind = kind,
                    height = m.cycleGap + m.cycleH,
                    regions = if (c.prefsFrozen) {
                        emptyList()
                    } else {
                        listOf(
                            LobbyRegion(
                                action = action,
                                rank = rank,
                                anchor = LobbyAnchor.Full,
                                height = m.cycleH,
                                topOffset = m.cycleGap,
                            ),
                        )
                    },
                ),
                height = m.cycleGap + m.cycleH,
                trail = if (kind == LobbyRowKind.Chicken) HubGap.Block else HubGap.Line,
            )
        }

        // The counts describe the queue the settings above configure, so they read
        // after them: what you set, then what there is to match it against. They
        // stay on the line rhythm — a line of information, not a control group —
        // but the [HubGap.Block] below them is what separates them from the
        // primary, which is a different kind of control again.
        if (c.populationShown) {
            rows.add(
                LobbyRow(
                    kind = LobbyRowKind.Population,
                    height = wrapped(LobbyRowKind.Population, m.labelLineH),
                ),
                height = wrapped(LobbyRowKind.Population, m.labelLineH),
                trail = HubGap.Block,
            )
        }

        // The primary follows the settings it starts a search with, as Shadow
        // Ranked's start button follows its draft. Reading the prefs and then
        // acting on them is one gesture; a button above them splits the two.
        //
        // Its plate is the width its own label asks for, centred in the gutter:
        // a plate that spanned the gutter would read as the panel's footer rather
        // than as the one choice it is.
        //
        // Its own trail hands the section boundary to the status line when there
        // is one, so the button and its caption close together and the boundary
        // lands once, below the pair.
        rows.add(
            LobbyRow(
                kind = LobbyRowKind.Primary,
                height = m.itemH,
                regions = if (c.primaryDisabled) emptyList() else listOf(
                    LobbyRegion(
                        action = HubVerdict.Primary,
                        rank = RegionRank.Primary,
                        anchor = LobbyAnchor.Center,
                        height = m.itemH,
                        width = c.primaryW,
                    ),
                ),
            ),
            height = m.itemH,
            trail = if (c.statusShown) HubGap.Tight else HubGap.Section,
        )

        // Immediately below the button it describes, and left with the section
        // gap so it is the last thing in this section.
        statusRow(HubGap.Section)

        // One destination closes the body: the full history, which is a card of
        // its own. It is a standalone link, so it needs no section title — the
        // title would only repeat the tab the player is already on.
        rows.add(
            LobbyRow(
                kind = LobbyRowKind.PastGames,
                height = m.linkRowH,
                regions = listOf(
                    LobbyRegion(
                        action = HubVerdict.PastGames,
                        rank = RegionRank.PastGames,
                        anchor = LobbyAnchor.Right,
                        height = m.linkRowH,
                        width = c.pastGamesW,
                    ),
                ),
            ),
            height = m.linkRowH,
            lead = HubGap.Section,
        )
        return rows
    }

    private fun registerRegions(row: LobbyRow, topY: Float, geo: LobbyGeo) {
        for (region in row.regions) {
            regions.add(region.resolve(topY, geo), region.action, region.rank.ordinal)
        }
    }

    /**
     * The one gutter the body uses: the shell's body rect, which already matches
     * the tab strip exactly.
     */
    private fun lobbyGeometry(body: Rect): LobbyGeo = LobbyGeo(left = body.left, width = body.width)
}
