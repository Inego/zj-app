package inego.zungjunggame.render.claim

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.hints.McHintArmSnapshot
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.hint.McHintLabels
import inego.zungjunggame.render.table.ConnectorCurveDrawer
import inego.zungjunggame.render.table.TurnStatusBanner
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.ParkableChrome
import inego.zungjunggame.render.ui.ParkDrag
import inego.zungjunggame.render.ui.ParkGeometry
import inego.zungjunggame.render.ui.ParkRelease
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.ui.Rect
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/**
 * Bottom-center action buttons for the human seat: claims on a discard (pass / hu /
 * pung / kong / each chow shape) and extra ActOnHand actions (hu / kongs / Keep).
 *
 * Each chow option is its own button with the full 3-tile meld preview so multiple
 * legal chows are distinguishable.
 *
 * On a discard reaction, an optional half-transparent curve links the button group
 * to the pending discarded tile so the call target stays obvious.
 *
 * The row is **parkable** ([ParkableChrome]): on a small phone it sits right over
 * the hand, so pressing it and dragging moves it out of the way — the same
 * press → slop → park gesture as the hand-end score card, sharing
 * [ParkDrag]. A press that never becomes a drag is
 * still an ordinary tap on the button under the finger. A row wider than the
 * screen may be dragged sideways to reach the far buttons (see
 * [ParkGeometry]).
 */
class ClaimActionPanel(
    private val drawer: TileDrawer,
) : Disposable, ParkableChrome {
    private val font get() = Fonts.button
    private val glyphLayout = GlyphLayout()
    // A getter, not a field: [WhitePixel.invalidate] drops the shared instance on
    // context recovery, so a cached reference would draw through a dead GL name.
    private val whitePixel: Texture get() = WhitePixel.get()
    private val curveDrawer = ConnectorCurveDrawer()

    /** Park gesture for the whole row (all buttons move together). */
    private val park = ParkDrag()

    private val hits = ArrayList<ButtonHit>(12)
    private var hoveredIndex: Int = -1

    /**
     * Row hull from the last render, i.e. where the parked row is **drawn**.
     * A press there (including the gaps between buttons) grabs the row; the
     * buttons' own hit boxes stay authoritative for acting on a claim.
     */
    private var rowLeft: Float = Float.NaN
    private var rowBottom: Float = Float.NaN
    private var rowW: Float = 0f
    private var rowH: Float = 0f

    /** False while the row only paints (lecture snapshot) — then it cannot be grabbed. */
    private var rowInteractive: Boolean = false

    /** Applied park offset, in world px (the host draws the discard connector from it). */
    val parkX: Float get() = park.parkX
    val parkY: Float get() = park.parkY

    /**
     * Seed the player's remembered place, an offset from the row's centered rest
     * pose. The board hands it in when it appears; the next render clamps it through
     * the same [ParkGeometry] the drag uses.
     */
    fun setPark(x: Float, y: Float) {
        park.setPark(x, y)
    }

    data class ButtonHit(
        val choice: Choice,
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val label: String,
        val tiles: IntArray,
        /** Index into [tiles] that is the claimed / focus tile, or -1. */
        val focusTileIndex: Int,
    ) {
        fun contains(px: Float, py: Float): Boolean {
            val hw = w * 0.5f
            val hh = h * 0.5f
            return px >= cx - hw && px <= cx + hw && py >= cy - hh && py <= cy + hh
        }
    }

    fun clear() {
        hits.clear()
        hoveredIndex = -1
        // The row is gone until the next render proves otherwise; the park
        // itself stays (a place the player chose is not re-learned each turn).
        rowW = 0f
        rowH = 0f
        rowInteractive = false
    }

    /** Current button boxes for [choices] at this geometry; no prior render needed. */
    fun buttons(
        claimedTile: Int,
        choices: ChoiceList,
        screenW: Float,
        screenH: Float,
        anchorY: Float,
        tileH: Float,
        tileAspect: Float,
    ): List<ButtonHit> =
        layoutRow(claimedTile, choices, screenW, screenH, anchorY, tileH, tileAspect)?.buttons ?: emptyList()

    // ── ParkableChrome ───────────────────────────────────────────────────────
    // The whole row parks as one block: the drop target is the felt, not a
    // button, so the gaps between buttons grab it too. Acting on a claim is
    // still the buttons' own hit test ([actionAt]) on the ordinary tap path.

    override val parkChromeLabel: String get() = PARK_LABEL

    override fun hitsParkChrome(worldX: Float, worldY: Float): Boolean {
        return !(!rowInteractive || rowW <= 1f || rowH <= 1f || rowLeft.isNaN() || rowBottom.isNaN()) && worldX >= rowLeft && worldX <= rowLeft + rowW &&
                worldY >= rowBottom && worldY <= rowBottom + rowH
    }

    override fun onParkPointerDown(worldX: Float, worldY: Float): Boolean {
        if (!hitsParkChrome(worldX, worldY)) return false
        park.begin(worldX, worldY)
        return true
    }

    override fun onParkPointerDrag(worldX: Float, worldY: Float) {
        park.drag(worldX, worldY)
    }

    /**
     * A drag was a park (the host must not also act on a claim); a press that
     * never moved is an ordinary tap, which the host dispatches.
     */
    override fun onParkPointerUp(worldX: Float, worldY: Float): ParkRelease {
        if (!park.release().moved) return ParkRelease.Tap
        return ParkRelease(
            consumed = true,
            parkChromeLabel = PARK_LABEL,
            parkX = park.parkX,
            parkY = park.parkY,
        )
    }

    override fun cancelPark() = park.cancel()

    /**
     * Rebuild hit boxes and draw. [ReadOnly][inego.zungjunggame.render.table.TableInteract.ReadOnly] callers
     * pass [interactive] false so buttons paint but do not hover or hit-test.
     *
     * @param anchorY world Y of the row center (above the human hand)
     * @param screenH world height: the park clamp for a row the player moved off
     *   its rest pose
     * @param recommended engine co-best choices to outline (empty = none)
     * @param mcHintArms UCB-MC stats; labels drawn above matching buttons
     * @param discardedTileX world X of the pending discard center (NaN = no connector)
     * @param discardedTileY world Y of the pending discard center
     * @param interactive when false, skip hover and drop hits after drawing
     */
    fun render(
        batch: SpriteBatch,
        view: TableViewState,
        choices: ChoiceList,
        screenW: Float,
        screenH: Float,
        anchorY: Float,
        tileH: Float,
        tileAspect: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        recommended: Collection<Choice> = emptyList(),
        mcHintArms: List<McHintArmSnapshot> = emptyList(),
        discardedTileX: Float = Float.NaN,
        discardedTileY: Float = Float.NaN,
        interactive: Boolean = true,
        decisionTimeLeftMs: Long? = null,
        decisionSlackMs: Long? = null,
        decisionBaseMs: Long? = null,
    ) {
        val claimed = if (view.isPendingDiscard) view.lastDiscarded else -1
        render(
            batch = batch,
            claimedTile = claimed,
            choices = choices,
            screenW = screenW,
            screenH = screenH,
            anchorY = anchorY,
            tileH = tileH,
            tileAspect = tileAspect,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
            recommended = recommended,
            mcHintArms = mcHintArms,
            discardedTileX = discardedTileX,
            discardedTileY = discardedTileY,
            interactive = interactive,
            decisionTimeLeftMs = decisionTimeLeftMs,
            decisionSlackMs = decisionSlackMs,
            decisionBaseMs = decisionBaseMs,
        )
    }

    fun render(
        batch: SpriteBatch,
        state: GameState,
        choices: ChoiceList,
        screenW: Float,
        screenH: Float,
        anchorY: Float,
        tileH: Float,
        tileAspect: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        recommended: Collection<Choice> = emptyList(),
        mcHintArms: List<McHintArmSnapshot> = emptyList(),
        discardedTileX: Float = Float.NaN,
        discardedTileY: Float = Float.NaN,
        interactive: Boolean = true,
        decisionTimeLeftMs: Long? = null,
        decisionSlackMs: Long? = null,
        decisionBaseMs: Long? = null,
    ) {
        val claimed =
            if (state.decisionType == DecisionType.ReactToDiscard &&
                state.lastDiscarded in 0 until NUM_TILE_TYPES
            ) {
                state.lastDiscarded
            } else {
                -1
            }
        render(
            batch = batch,
            claimedTile = claimed,
            choices = choices,
            screenW = screenW,
            screenH = screenH,
            anchorY = anchorY,
            tileH = tileH,
            tileAspect = tileAspect,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
            recommended = recommended,
            mcHintArms = mcHintArms,
            discardedTileX = discardedTileX,
            discardedTileY = discardedTileY,
            interactive = interactive,
            decisionTimeLeftMs = decisionTimeLeftMs,
            decisionSlackMs = decisionSlackMs,
            decisionBaseMs = decisionBaseMs,
        )
    }

    /**
     * One frame's pure row geometry: the placed row and its button boxes, with
     * the derived sizes [render] needs. Computed from the panel's inputs and its
     * park state; it neither paints nor mutates.
     */
    data class RowLayout(
        val left: Float,
        val bottom: Float,
        val width: Float,
        val height: Float,
        val restLeft: Float,
        val restBottom: Float,
        val parkX: Float,
        val parkY: Float,
        val bounds: Rect,
        val tileW: Float,
        val tileGap: Float,
        val padY: Float,
        val buttons: List<ButtonHit>,
    ) {
        val rowCy: Float get() = bottom + height * 0.5f
    }

    /**
     * Measure the claim / action row for [choices] without painting. The park is
     * read (never advanced) so a row the player moved keeps its place; the
     * caller feeds [RowLayout.parkX]/[parkY] back through [ParkDrag] when it
     * draws.
     */
    fun layoutRow(
        claimedTile: Int,
        choices: ChoiceList,
        screenW: Float,
        screenH: Float,
        anchorY: Float,
        tileH: Float,
        tileAspect: Float,
    ): RowLayout? {
        val specs = buildSpecs(claimedTile, choices)
        if (specs.isEmpty()) return null

        val tileW = tileH * tileAspect
        val tileGap = tileW * 0.08f
        val padX = tileW * 0.35f
        val padY = tileH * 0.22f
        // Focus preview is 1.05× and nudged up; reserve a little extra height.
        val tilesBlockH = tileH * 1.12f
        val labelTileGap = max(padY * 0.55f, Fonts.px(8f))
        val buttonGap = tileW * 0.45f

        // Measure each button from real font metrics (density-scaled fonts can
        // exceed a tile-fraction label band and otherwise sit under the tiles).
        data class Sized(val spec: Spec, val w: Float, val h: Float)

        val sized = specs.map { spec ->
            glyphLayout.setText(font, spec.label)
            val textH = glyphLayout.height
            val isHu = spec.choice.kind == ChoiceKind.Hu
            val contentW = when {
                isHu -> {
                    val scoreStr = spec.choice.huScore().toString()
                    val scoreFont = huScoreFont(tilesBlockH)
                    glyphLayout.setText(scoreFont, scoreStr)
                    val scoreW = glyphLayout.width
                    glyphLayout.setText(font, spec.label)
                    val labelW = glyphLayout.width
                    maxOf(labelW, scoreW, tileW * 1.8f)
                }
                spec.tiles.isNotEmpty() -> {
                    val tilesW = spec.tiles.size * tileW + (spec.tiles.size - 1) * tileGap
                    max(glyphLayout.width, tilesW)
                }
                else -> glyphLayout.width
            }
            val w = contentW + padX * 2f
            val h =
                if (spec.tiles.isEmpty() && !isHu) {
                    padY * 2f + textH
                } else {
                    padY * 2f + textH + labelTileGap + tilesBlockH
                }
            Sized(spec, w, h)
        }

        val totalW = sized.sumOf { it.w.toDouble() }.toFloat() +
            buttonGap * (sized.size - 1).coerceAtLeast(0)
        val maxH = sized.maxOf { it.h }

        // Rest pose: the row centered on screen with its middle at [anchorY]
        // (above the human hand). The park moves it from there, clamped so the
        // row stays reachable — a row wider than the screen slides sideways
        // instead of pinning one end off-screen.
        val restLeft = screenW * 0.5f - totalW * 0.5f
        val restBottom = anchorY - maxH * 0.5f
        val bounds = OverlayViewport.available(screenW, screenH)
        val placed = ParkGeometry.place(
            restLeft = restLeft,
            restBottom = restBottom,
            blockW = totalW,
            blockH = maxH,
            parkX = park.parkX,
            parkY = park.parkY,
            bounds = bounds,
        )
        var xLeft = placed.left
        val rowCy = placed.bottom + maxH * 0.5f
        val buttons = ArrayList<ButtonHit>(sized.size)
        for ((spec, w) in sized) {
            val cx = xLeft + w * 0.5f
            buttons += ButtonHit(
                choice = spec.choice,
                cx = cx,
                cy = rowCy,
                w = w,
                h = maxH,
                label = spec.label,
                tiles = spec.tiles,
                focusTileIndex = spec.focusTileIndex,
            )
            xLeft += w + buttonGap
        }
        return RowLayout(
            left = placed.left,
            bottom = placed.bottom,
            width = totalW,
            height = maxH,
            restLeft = restLeft,
            restBottom = restBottom,
            parkX = placed.parkX,
            parkY = placed.parkY,
            bounds = bounds,
            tileW = tileW,
            tileGap = tileGap,
            padY = padY,
            buttons = buttons,
        )
    }

    fun render(
        batch: SpriteBatch,
        claimedTile: Int,
        choices: ChoiceList,
        screenW: Float,
        screenH: Float,
        anchorY: Float,
        tileH: Float,
        tileAspect: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        recommended: Collection<Choice> = emptyList(),
        mcHintArms: List<McHintArmSnapshot> = emptyList(),
        discardedTileX: Float = Float.NaN,
        discardedTileY: Float = Float.NaN,
        interactive: Boolean = true,
        decisionTimeLeftMs: Long? = null,
        decisionSlackMs: Long? = null,
        decisionBaseMs: Long? = null,
    ) {
        hits.clear()
        hoveredIndex = -1

        val row = layoutRow(claimedTile, choices, screenW, screenH, anchorY, tileH, tileAspect)
            ?: return
        hits.addAll(row.buttons)
        park.setPark(row.parkX, row.parkY)
        park.setGeometry(row.restLeft, row.restBottom, row.width, row.height, row.bounds)
        rowLeft = row.left
        rowBottom = row.bottom
        rowW = row.width
        rowH = row.height
        rowInteractive = interactive

        if (decisionTimeLeftMs != null && interactive) {
            val seconds = ceil(decisionTimeLeftMs.coerceAtLeast(0L) / 1000.0).toInt()
            val timerText = TurnStatusBanner.formatClock(decisionTimeLeftMs, decisionSlackMs, decisionBaseMs)
            val timerFont = Fonts.small
            val previousColor = timerFont.color.cpy()
            timerFont.color = when (seconds) {
                in 1..3 -> {
                    AppPalette.dangerInkStrong
                }
                in 4..5 -> {
                    AppPalette.warningInk
                }
                else -> {
                    Color.WHITE
                }
            }
            glyphLayout.setText(timerFont, timerText)
            timerFont.draw(batch, timerText, row.left + row.width * 0.5f - glyphLayout.width * 0.5f, row.rowCy + row.height * 0.5f + Fonts.px(18f))
            timerFont.color = previousColor
        }

        if (interactive && !pointerWorldX.isNaN() && !pointerWorldY.isNaN()) {
            for (i in hits.indices) {
                if (hits[i].contains(pointerWorldX, pointerWorldY)) {
                    hoveredIndex = i
                    break
                }
            }
        }

        // Connector under the buttons so labels/tiles stay crisp on top.
        if (!discardedTileX.isNaN() && !discardedTileY.isNaN() && hits.isNotEmpty()) {
            drawDiscardConnector(
                batch = batch,
                discardedX = discardedTileX,
                discardedY = discardedTileY,
                screenW = screenW,
                thickness = max(2f, tileH * 0.07f),
            )
        }

        val armByChoice = HashMap<Long, McHintArmSnapshot>(mcHintArms.size.coerceAtLeast(1))
        for (arm in mcHintArms) {
            if (arm.n <= 0) continue
            armByChoice[choiceKey(arm.choice)] = arm
        }

        for ((i, hit) in hits.withIndex()) {
            val hovered = i == hoveredIndex
            val choice = hit.choice
            val isRecommended =
                recommended.any {
                    it.kind == choice.kind && it.payload == choice.payload
                }
            drawButton(
                batch, hit, row.tileW, tileH, row.tileGap, row.padY, row.height,
                hovered = hovered,
                recommended = isRecommended,
            )
            val arm = armByChoice[choiceKey(choice)]
            if (arm != null) {
                drawMcLabel(
                    batch,
                    hit.cx,
                    choiceTopY = hit.cy + row.height * 0.5f,
                    arm.avgDelta,
                    arm.n,
                    maxWidth = hit.w * 0.96f,
                )
            }
        }
        // Grab chrome: while the row is being dragged, frame it so the move
        // reads as deliberate (the buttons underneath keep their own states).
        if (park.moved) {
            drawGrabFrame(batch, row.width, row.height)
        }
        if (!interactive) {
            hits.clear()
            hoveredIndex = -1
        }
    }

    /** Accent frame around the parked row while the player is moving it. */
    private fun drawGrabFrame(batch: SpriteBatch, totalW: Float, maxH: Float) {
        val pad = Fonts.px(5f)
        val t = max(2f, Fonts.px(1.5f))
        val left = rowLeft - pad
        val bottom = rowBottom - pad
        val w = totalW + pad * 2f
        val h = maxH + pad * 2f
        UiPlate.drawFrame(batch, whitePixel, left, bottom, w, h, GRAB_FRAME_COLOR, t)
    }

    /** Two-line MC label above a button: avg Δ, then playout count. */
    private fun drawMcLabel(
        batch: SpriteBatch,
        cx: Float,
        choiceTopY: Float,
        avgDelta: Double,
        n: Int,
        maxWidth: Float,
    ) {
        val small = Fonts.small
        val prev = small.color.cpy()
        small.color = AppPalette.tint(AppPalette.accentBright, 0.95f)
        McHintLabels.drawTwoLine(
            batch = batch,
            font = small,
            cx = cx,
            choiceTopY = choiceTopY,
            gapAboveChoice = Fonts.px(2f),
            avgDelta = avgDelta,
            n = n,
            lineGap = Fonts.px(2f),
            maxWidth = maxWidth,
            glyphLayout = glyphLayout,
        )
        small.color = prev
    }

    private fun choiceKey(c: Choice): Long =
        (c.kind.ordinal.toLong() shl 32) or (c.payload.toLong() and 0xFFFF_FFFFL)

    /**
     * Half-transparent quadratic curve from the claim button group center to the
     * pending discarded tile center.
     */
    private fun drawDiscardConnector(
        batch: SpriteBatch,
        discardedX: Float,
        discardedY: Float,
        screenW: Float,
        thickness: Float,
    ) {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.POSITIVE_INFINITY
        var top = Float.NEGATIVE_INFINITY
        for ((_, cx, cy, w, h) in hits) {
            left = min(left, cx - w * 0.5f)
            right = max(right, cx + w * 0.5f)
            bottom = min(bottom, cy - h * 0.5f)
            top = max(top, cy + h * 0.5f)
        }

        val startX = (left + right) * 0.5f
        val startY = (bottom + top) * 0.5f
        val chordLen = hypot(discardedX - startX, discardedY - startY)

        val tableCx = screenW * 0.5f
        val tableCy = (top + bottom) * 0.5f + chordLen * 0.5f

        curveDrawer.drawArc(
            batch = batch,
            x0 = startX,
            y0 = startY,
            x1 = discardedX,
            y1 = discardedY,
            centerRefX = tableCx,
            centerRefY = tableCy,
            style = ConnectorCurveDrawer.CurveStyle.CLAIM_DISCARD,
            overrideThickness = thickness,
        )
    }

    private fun drawButton(
        batch: SpriteBatch,
        hit: ButtonHit,
        tileW: Float,
        tileH: Float,
        tileGap: Float,
        padY: Float,
        buttonH: Float,
        hovered: Boolean,
        recommended: Boolean,
    ) {
        val left = hit.cx - hit.w * 0.5f
        val bottom = hit.cy - buttonH * 0.5f
        val isHu = hit.choice.kind == ChoiceKind.Hu

        if (isHu) {
            // Soft green glow highlight blooming around the Hu button
            drawer.drawGlow(
                batch = batch,
                cx = hit.cx,
                cy = hit.cy,
                w = hit.w,
                h = buttonH,
                color = if (hovered) AppPalette.successGlowHover else AppPalette.successGlow,
                expand = 2.15f,
            )
            drawer.drawGlow(
                batch = batch,
                cx = hit.cx,
                cy = hit.cy,
                w = hit.w,
                h = buttonH,
                color = if (hovered) AppPalette.successGlowStrongHover else AppPalette.successGlowStrong,
                expand = 1.70f,
            )
        }

        // Panel background: Hu emerald vs hover sage vs recommendation cyan vs default dark
        val bg = when {
            isHu && hovered -> AppPalette.successFillHover
            isHu -> AppPalette.successFill
            hovered -> AppPalette.tint(AppPalette.plateHover, 0.94f)
            recommended -> AppPalette.infoFill
            else -> AppPalette.tint(AppPalette.plate, 0.90f)
        }
        val border = when {
            isHu && hovered -> AppPalette.successRimHover
            isHu -> AppPalette.successRim
            hovered -> AppPalette.hoverRim
            recommended -> AppPalette.infoRim
            else -> AppPalette.tint(AppPalette.rim, 0.55f)
        }
        val t = max(1.5f, tileH * 0.04f)
        val borderT = when {
            isHu -> t * 1.35f
            recommended && !hovered -> t * 1.6f
            else -> t
        }
        UiPlate.drawPlate(batch, whitePixel, left, bottom, hit.w, buttonH, bg, border, borderT)

        val tilesBlockH = tileH * 1.12f

        // Content area: meld tiles or Hu score in large font
        if (hit.tiles.isNotEmpty()) {
            val tilesW = hit.tiles.size * tileW + (hit.tiles.size - 1) * tileGap
            var tx = hit.cx - tilesW * 0.5f + tileW * 0.5f
            // Sit on the bottom pad; focus scale/lift stays inside the reserved tiles block.
            val ty = bottom + padY + tileH * 0.5f
            for ((ti, tile) in hit.tiles.withIndex()) {
                val focus = ti == hit.focusTileIndex
                // Claim previews are the tiles a learner must recognise to decide,
                // so they carry the same optional tag as the play surface.
                val tag = TileLabels.tagOrNull(tile)
                if (focus) {
                    batch.setColor(AppPalette.focusWash)
                    drawer.drawOpen(
                        batch, tile, tx, ty + tileH * 0.06f, tileW * 1.05f, tileH * 1.05f,
                        label = tag,
                    )
                    batch.setColor(Color.WHITE)
                } else {
                    drawer.drawOpen(batch, tile, tx, ty, tileW, tileH, label = tag)
                }
                tx += tileW + tileGap
            }
        } else if (isHu) {
            val scoreStr = hit.choice.huScore().toString()
            val scoreFont = huScoreFont(tilesBlockH)
            glyphLayout.setText(scoreFont, scoreStr)
            val scoreW = glyphLayout.width
            val scoreH = glyphLayout.height
            val contentCenterY = bottom + padY + tilesBlockH * 0.5f
            val prevScoreColor = scoreFont.color.cpy()
            scoreFont.color = if (hovered) AppPalette.successScoreHover else AppPalette.successScore
            scoreFont.draw(
                batch,
                scoreStr,
                hit.cx - scoreW * 0.5f,
                contentCenterY + scoreH * 0.5f,
            )
            scoreFont.color = prevScoreColor
        }

        // Label top-aligned across all buttons (button height was sized for this).
        glyphLayout.setText(font, hit.label)
        val textH = glyphLayout.height
        val textTop = bottom + buttonH - padY
        val textCenterY = textTop - textH * 0.5f
        val prevFontColor = font.color.cpy()
        if (isHu) {
            font.color = if (hovered) AppPalette.successInkBright else AppPalette.successInk
        }
        font.draw(
            batch,
            hit.label,
            hit.cx - glyphLayout.width * 0.5f,
            textCenterY + textH * 0.5f,
        )
        if (isHu) {
            font.color = prevFontColor
        }
    }

    private fun huScoreFont(availableH: Float): BitmapFont {
        glyphLayout.setText(Fonts.display, "0")
        if (glyphLayout.height <= availableH * 0.82f) return Fonts.display
        glyphLayout.setText(Fonts.wallCount, "0")
        if (glyphLayout.height <= availableH * 0.85f) return Fonts.wallCount
        return Fonts.button
    }

    companion object {
        /** Stable identity of this block for [ParkableChrome.parkChromeLabel]. */
        const val PARK_LABEL = "claim-row"

        /** Accent grab frame drawn around the row while it is being parked. */
        private val GRAB_FRAME_COLOR = AppPalette.tint(AppPalette.accent, 0.85f)

        internal data class Spec(
            val choice: Choice,
            val label: String,
            val tiles: IntArray,
            val focusTileIndex: Int,
        )

        internal fun buildSpecs(claimedTile: Int, choices: ChoiceList): List<Spec> {
            val list = ArrayList<Spec>(choices.size + 1)
            var pass: Spec? = null
            var keep: Spec? = null

            for (element in choices) {
                when (element.kind) {
                    ChoiceKind.Pass -> {
                        pass = Spec(element, L10n.get("claim.pass"), intArrayOf(), -1)
                    }
                    ChoiceKind.Keep -> {
                        // Engine Keep for the current sequential concealed-kong gate.
                        keep = Spec(element, L10n.get("claim.keep"), intArrayOf(), -1)
                    }
                    ChoiceKind.Hu -> {
                        list += Spec(element, L10n.get("claim.hu"), intArrayOf(), -1)
                    }
                    ChoiceKind.Pung -> {
                        val t = element.tile()
                        list += Spec(element, L10n.get("claim.pung"), intArrayOf(t, t, t), focusTileIndex = 2)
                    }
                    ChoiceKind.Kong -> {
                        val t = element.tile()
                        list += Spec(element, L10n.get("claim.kong"), intArrayOf(t, t, t, t), focusTileIndex = 3)
                    }
                    ChoiceKind.Chow -> {
                        val first = element.chowFirstTile()
                        val tiles = intArrayOf(first, first + 1, first + 2)
                        val focus = if (claimedTile in tiles) tiles.indexOf(claimedTile) else -1
                        list += Spec(element, L10n.get("claim.chow"), tiles, focus)
                    }
                    ChoiceKind.ConcealedKong -> {
                        val t = element.tile()
                        list += Spec(
                            element,
                            L10n.get("claim.declare_concealed_kong"),
                            intArrayOf(t, t, t, t),
                            focusTileIndex = -1,
                        )
                    }
                    ChoiceKind.PromoteKong -> {
                        val t = element.tile()
                        list += Spec(
                            element,
                            L10n.get("claim.promote_kong"),
                            intArrayOf(t, t, t, t),
                            focusTileIndex = 3,
                        )
                    }
                    ChoiceKind.Discard -> {
                        // Discard is via hand click, not this panel.
                    }
                }
            }

            // Prefer Hu → Kong → Pung → Chows (by first tile) → Pass / Keep.
            list.sortWith { a, b ->
                val ca = a.choice
                val cb = b.choice
                val byKind = kindRank(ca.kind).compareTo(kindRank(cb.kind))
                if (byKind != 0) return@sortWith byKind
                val byChow =
                    if (ca.kind == ChoiceKind.Chow && cb.kind == ChoiceKind.Chow) {
                        ca.chowFirstTile().compareTo(cb.chowFirstTile())
                    } else {
                        0
                    }
                if (byChow != 0) return@sortWith byChow
                ca.payload.compareTo(cb.payload)
            }
            if (pass != null) list += pass
            if (keep != null) list += keep
            return list
        }

        internal fun kindRank(kind: ChoiceKind): Int = when (kind) {
            ChoiceKind.Hu -> 0
            ChoiceKind.Kong, ChoiceKind.ConcealedKong, ChoiceKind.PromoteKong -> 1
            ChoiceKind.Pung -> 2
            ChoiceKind.Chow -> 3
            ChoiceKind.Keep, ChoiceKind.Pass -> 8
            else -> 9
        }
    }

    override fun dispose() {
        whitePixel.dispose()
    }
}
