package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.tutorial.QuizContentFactories
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.tutorial.slide.SlideChannel
import inego.zungjunggame.tutorial.slide.SlideEase
import inego.zungjunggame.tutorial.slide.SlideFrame
import inego.zungjunggame.tutorial.slide.layout.DiagramLayout
import inego.zungjunggame.tutorial.slide.layout.SlideViewport
import kotlin.random.Random

/**
 * The coach's tile-diagram layer: plan → draw for every diagram the panel owns,
 * and the anchors it publishes for the focus chrome.
 *
 * The overlay owns *which* diagram a beat shows and the motion channel it eases
 * on; this layer owns how that diagram is composed and where its tiles landed.
 * A drawer registers each tile face it paints by
 * [inego.zungjunggame.tutorial.TutorialTargets] id, so [CoachFocusChrome] can
 * highlight "the 3 of characters" without knowing the diagram, and repeated
 * faces of one type union into a single target.
 *
 * The not-a-sequence peel (phase 0 static, 1/2 peeling spares aside) is beat
 * state, so it arrives as [NotASequencePeel] rather than living here.
 */
internal class CoachDiagramTiles(
    private val glyphLayout: GlyphLayout = GlyphLayout(),
    private val sharedTileAssets: (() -> TileAssets)? = null,
) : Disposable {

    /** Whether tile faces carry a character/honor label. */
    enum class TagMode { Off, FollowSetting, ForceOn }

    /** One painted face and the tag policy it was drawn under, for [redrawFaces]. */
    private data class DiagramFace(
        val type: Int,
        val rect: AnchorRect,
        val tagMode: TagMode,
    )

    /**
     * Not-a-sequence peel animation: 0 = static four-run, 1 = peel 3 left,
     * 2 = keep peel-3 + peel 6 right on a second row.
     */
    data class NotASequencePeel(val phase: Int = 0, val t: Float = 1f)

    private val anchors = HashMap<String, AnchorRect>(48)

    /** Individual diagram tile faces; same type is unioned into [anchors]. */
    private val diagramTileFaces = ArrayList<DiagramFace>(48)

    private var ownedTileAssets: TileAssets? = null
    private var tileDrawer: TileDrawer? = null

    /** Running index for [SlideFrame.itemWeight] while drawing diagram tiles. */
    private var itemIndex = 0

        /**
         * Motion sample for the frame being drawn, held for the duration of [draw].
         * Every drawer eases on it: the diagram alpha, the per-tile stagger and the
         * peel. [SlideFrame.SETTLED] is the neutral sample when nothing is moving.
         */
        private var slideFrame: SlideFrame = SlideFrame.SETTLED

    private val compareTypes: List<Int> = listOf(
        Tiles.CHAR_5, Tiles.DOT_3, Tiles.BAMBOO_1, Tiles.EAST, Tiles.WHITE,
    )
    private val sampleHand: IntArray = QuizContentFactories.drawHandWithMinHonors(Random(11))

    /** Anchors from the diagram drawn last frame, for the focus chrome. */
    fun anchorFor(targetId: String): AnchorRect? = anchors[targetId]

        /** The tile painter the other diagram views draw with. */
        fun drawer(): TileDrawer = tileDrawerOrCreate()

        /** Forget the anchors from the last diagram; the panel is no longer a diagram beat. */
        fun clearAnchors() {
            anchors.clear()
            diagramTileFaces.clear()
            itemIndex = 0
        }

        /**
         * Publish [anchor] as the target [targetId].
         *
         * A board-overlay beat has no diagram of its own, but its focus still needs
         * an anchor — the live tile's rect, which the presenter resolved from the
         * board's published layout.
         */
        fun publishAnchor(targetId: String, anchor: AnchorRect) {
            anchors[targetId] = anchor
        }

        /**
         * Repaint every face of [type] from the frame just drawn.
         *
         * The focus chrome lays an accent wash over the target; a set (kong,
         * triplet, …) must stay untinted, so its faces come back on top.
         * Each face replays the [TagMode] it was drawn under, so a forced tag
         * survives the redraw. Faces are already registered, so no anchor is
         * published here.
         */
        fun redrawFaces(batch: SpriteBatch, type: Int) {
            for (face in diagramTileFaces) {
                if (face.type == type) {
                    tileDrawerOrCreate().drawOpen(
                        batch, face.type, face.rect.cx, face.rect.cy, face.rect.w, face.rect.h,
                        label = labelFor(face.type, face.tagMode),
                        forceLabel = face.tagMode == TagMode.ForceOn,
                    )
                }
            }
        }

    /** Draw [diagram] into the rect, publishing anchors. Does nothing for [skip]. */
    fun draw(
        batch: SpriteBatch,
            diagram: TutorialDiagram,
            vp: SlideViewport,
            left: Float,
            bottom: Float,
            areaW: Float,
            areaH: Float,
            frame: SlideFrame,
            peel: NotASequencePeel,
            bodyKey: String,
        ) {
            anchors.clear()
            diagramTileFaces.clear()
            itemIndex = 0
            slideFrame = frame
        when (diagram) {
            TutorialDiagram.ThreeSuits ->
                    drawThreeSuits(batch, vp, left, bottom, areaW, areaH)
            TutorialDiagram.Honors ->
                    drawHonors(batch, vp, left, bottom, areaW, areaH)
            TutorialDiagram.TileTypes ->
                drawTileTypes(batch, vp, left, bottom, areaW, areaH)
            TutorialDiagram.SequenceExample ->
                drawSequenceExample(batch, vp, left, bottom, areaW, areaH)
            TutorialDiagram.NotASequenceRun ->
                    drawNotASequenceRun(batch, vp, left, bottom, areaW, areaH, peel)
            TutorialDiagram.RegularHand ->
                drawRegularHand(batch, vp, left, bottom, areaW, areaH, bodyKey)
            TutorialDiagram.SetKinds ->
                drawSetKinds(batch, vp, left, bottom, areaW, areaH)
            TutorialDiagram.KongAsTriplet ->
                drawKongAsTriplet(batch, vp, left, bottom, areaW, areaH)
            TutorialDiagram.TileTags ->
                    drawTileTags(batch, vp, left, bottom, areaW, areaH, bodyKey)
            else -> Unit
                    }
                }

                /** Alpha of the diagram channel for the frame being drawn. */
                private fun diagramAlpha(): Float = slideFrame.alphaOf(SlideChannel.Diagram)

    override fun dispose() {
        tileDrawer = null
        ownedTileAssets?.dispose()
        ownedTileAssets = null
        anchors.clear()
        diagramTileFaces.clear()
    }

    private fun tileDrawerOrCreate(): TileDrawer {
        tileDrawer?.let { return it }
        val assets = sharedTileAssets?.invoke()
            ?: TileAssets().also { ownedTileAssets = it }
        val d = TileDrawer(assets)
        tileDrawer = d
        return d
    }

    // ── Tile and label primitives ───────────────────────────────────────────

    /** Draw a diagram tile with stagger weight; always registers the settled anchor. */
    private fun drawTile(
        batch: SpriteBatch,
        type: Int,
        cx: Float,
        cy: Float,
        drawW: Float,
        drawH: Float,
        tagMode: TagMode = TagMode.FollowSetting,
    ) {
        val idx = itemIndex++
        val w = slideFrame.itemWeight(idx).coerceIn(0f, 1f)
        putTileAnchor(type, cx - drawW * 0.5f, cy - drawH * 0.5f, drawW, drawH, tagMode)
        if (w < 0.02f) return
        val s = 0.88f + 0.12f * w
        val prev = batch.color.cpy()
        batch.setColor(prev.r, prev.g, prev.b, prev.a * w)
        tileDrawerOrCreate().drawOpen(
            batch, type, cx, cy, drawW * s, drawH * s,
            label = labelFor(type, tagMode),
            forceLabel = tagMode == TagMode.ForceOn,
        )
        batch.color = prev
    }

    /** Resolve a face's tag under [tagMode]; the one place the policy is applied. */
    private fun labelFor(type: Int, tagMode: TagMode): String? = when (tagMode) {
        TagMode.Off -> null
        TagMode.FollowSetting -> TileLabels.tagOrNull(type)
        TagMode.ForceOn -> TileLabels.label(type).ifEmpty { null }
    }

    private fun drawTileRect(batch: SpriteBatch, tile: DiagramLayout.TileRect) {
        drawTile(batch, tile.type, tile.cx, tile.cy, tile.w, tile.h)
    }

    private fun putTileAnchor(
        type: Int,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        tagMode: TagMode,
    ) {
        val rect = AnchorRect(left, bottom, w, h)
        diagramTileFaces += DiagramFace(type, rect, tagMode)
        val key = TutorialTargets.tile(type)
        val existing = anchors[key]
        anchors[key] = existing?.union(rect) ?: rect
    }

    private fun drawPlanLabel(
        batch: SpriteBatch,
        font: BitmapFont,
        spec: DiagramLayout.LabelSpec,
        color: Color,
        alpha: Float,
    ) {
        withFontScale(font, spec.scale) {
            font.color = Color(color.r, color.g, color.b, alpha)
            if (spec.maxWidth > 0f) {
                font.draw(batch, spec.text, spec.x, spec.baselineY, spec.maxWidth, Align.center, true)
            } else {
                font.draw(batch, spec.text, spec.x, spec.baselineY)
            }
            font.color = Color.WHITE
        }
    }

    /** Measure single-line width at [scale] relative to the current base scale of [font]. */
    private fun measureLineW(font: BitmapFont, text: String, scale: Float): Float {
        val prevSx = font.data.scaleX
        val prevSy = font.data.scaleY
        font.data.setScale(prevSx * scale, prevSy * scale)
        try {
            glyphLayout.setText(font, text)
            return glyphLayout.width
        } finally {
            font.data.setScale(prevSx, prevSy)
        }
    }

    private inline fun withFontScale(font: BitmapFont, scale: Float, block: () -> Unit) {
        val prevSx = font.data.scaleX
        val prevSy = font.data.scaleY
        font.data.setScale(prevSx * scale, prevSy * scale)
        try {
            block()
        } finally {
            font.data.setScale(prevSx, prevSy)
        }
    }

    // ── Diagram drawers (plan → draw) ──────────────────────────────────────

    private fun drawThreeSuits(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
    ) {
        val labelFont = Fonts.labelActive
        val suitTexts = listOf(
            L10n.get("tutorial.bin.characters"),
            L10n.get("tutorial.bin.dots"),
            L10n.get("tutorial.bin.bamboos"),
        )
        val firstTypes = listOf(Tiles.CHAR_1, Tiles.DOT_1, Tiles.BAMBOO_1)
        val plan = DiagramLayout.planThreeSuits(
            vp = vp,
            left = left,
            bottom = bottom,
            areaW = areaW,
            areaH = areaH,
            suitLabelTexts = suitTexts,
            firstTypes = firstTypes,
            measureLabelW = { text, scale -> measureLineW(labelFont, text, scale) },
            labelLineHeight = labelFont.lineHeight,
        )
        val diagA = diagramAlpha()
        for (tile in plan.tiles) drawTileRect(batch, tile)
        for (lab in plan.suitLabels) drawPlanLabel(batch, labelFont, lab, AppPalette.accent, diagA)
        for (lab in plan.rankLabels) drawPlanLabel(batch, labelFont, lab, AppPalette.ink, diagA)
    }

    private fun drawHonors(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
    ) {
        val labelFont = Fonts.labelActive
        val rows = listOf(
            DiagramLayout.HonorRowModel(
                groupLabel = L10n.get("tutorial.diagram.honors_winds"),
                tiles = listOf(
                    Tiles.EAST to L10n.get("wind.east"),
                    Tiles.SOUTH to L10n.get("wind.south"),
                    Tiles.WEST to L10n.get("wind.west"),
                    Tiles.NORTH to L10n.get("wind.north"),
                ),
            ),
            DiagramLayout.HonorRowModel(
                groupLabel = L10n.get("tutorial.diagram.honors_dragons"),
                tiles = listOf(
                    Tiles.RED to L10n.get("dragon.red"),
                    Tiles.GREEN to L10n.get("dragon.green"),
                    Tiles.WHITE to L10n.get("dragon.white"),
                ),
            ),
        )
        val plan = DiagramLayout.planHonors(
            vp = vp,
            left = left,
            bottom = bottom,
            areaW = areaW,
            areaH = areaH,
            rows = rows,
            measureLabelW = { text, scale -> measureLineW(labelFont, text, scale) },
            labelLineHeight = labelFont.lineHeight,
        )
        val diagA = diagramAlpha()
        for (tile in plan.tiles) drawTileRect(batch, tile)
        for (lab in plan.groupLabels) drawPlanLabel(batch, labelFont, lab, AppPalette.accent, diagA)
        for (lab in plan.nameLabels) drawPlanLabel(batch, labelFont, lab, AppPalette.ink, diagA)
    }

    private fun drawTileTypes(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
    ) {
        val rows = listOf(
            DiagramLayout.LabeledRowModel(
                label = L10n.get("tutorial.diagram.number_tiles"),
                groups = listOf(
                    DiagramLayout.TileGroupModel(
                        listOf(Tiles.CHAR_1, Tiles.CHAR_5, Tiles.DOT_3, Tiles.BAMBOO_2, Tiles.BAMBOO_9),
                    ),
                ),
            ),
            DiagramLayout.LabeledRowModel(
                label = L10n.get("tutorial.diagram.honor_tiles"),
                groups = listOf(
                    DiagramLayout.TileGroupModel(
                        listOf(Tiles.EAST, Tiles.SOUTH, Tiles.RED, Tiles.WHITE),
                    ),
                ),
            ),
        )
        drawLabeledRowsPlan(batch, vp, left, bottom, areaW, areaH, rows)
    }

    private fun drawTileTags(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        bodyKey: String,
    ) {
        val compare = compareTypes
        val sample = sampleHand.toList()
        val showSample = bodyKey == "tutorial.orient.tiles.tags_try"
        val extraGap = vp.px(
            when {
                vp.isCompact && vp.touch -> 18f
                vp.touch -> 22f
                vp.isCompact -> 20f
                else -> 32f
            },
        )
        val labelFont = Fonts.labelActive
        // Plan the compare rows on their own: the hidden thirteen-tile sample
        // must not shrink them to a thirteenth of the width. When the sample
        // appears it gets its own band below, so the compare faces keep the
        // same width-limited size across both beats.
        val compareRows = listOf(
            DiagramLayout.LabeledRowModel(
                label = L10n.get("tutorial.diagram.tags_on"),
                groups = listOf(DiagramLayout.TileGroupModel(compare)),
            ),
            DiagramLayout.LabeledRowModel(
                label = L10n.get("tutorial.diagram.tags_off"),
                groups = listOf(DiagramLayout.TileGroupModel(compare)),
            ),
        )
        val sampleTileH = if (showSample) {
            DiagramLayout.fitTileHeight(
                areaW = areaW,
                areaH = areaH,
                cols = sample.size,
                rows = 1,
                gapX = DiagramLayout.defaultTileGap(vp),
                gapY = 0f,
            )
        } else {
            0f
        }
        val sampleReserve = if (showSample) {
            (sampleTileH + extraGap).coerceAtMost(areaH * 0.45f)
        } else {
            0f
        }
        val comparePlan = DiagramLayout.planLabeledRows(
            vp = vp,
            left = left,
            // Keep the compare block's top fixed and take the sample band out
            // of the bottom, so the compare faces do not shift between beats.
            bottom = bottom + sampleReserve,
            areaW = areaW,
            areaH = (areaH - sampleReserve).coerceAtLeast(1f),
            rows = compareRows,
            measureLabelW = { text, scale -> measureLineW(labelFont, text, scale) },
            labelLineHeight = labelFont.lineHeight,
        )
        val diagA = diagramAlpha()
        for ((i, tile) in comparePlan.tiles.withIndex()) {
            val mode = if (i < compare.size) TagMode.ForceOn else TagMode.Off
            drawTile(batch, tile.type, tile.cx, tile.cy, tile.w, tile.h, mode)
        }
        for (lab in comparePlan.rowLabels) drawPlanLabel(batch, labelFont, lab, AppPalette.accent, diagA)
        for (lab in comparePlan.captions) drawPlanLabel(batch, labelFont, lab, AppPalette.ink, diagA)

        if (!showSample) return
        val compareBottom = comparePlan.tiles.minOfOrNull { it.bottom } ?: return
        val sampleH = compareBottom - extraGap - bottom
        if (sampleH <= 0f) return
        val samplePlan = DiagramLayout.planLabeledRows(
            vp = vp,
            left = left,
            bottom = bottom,
            areaW = areaW,
            areaH = sampleH,
            rows = listOf(
                DiagramLayout.LabeledRowModel(
                    label = "",
                    groups = listOf(DiagramLayout.TileGroupModel(sample)),
                ),
            ),
            measureLabelW = { text, scale -> measureLineW(labelFont, text, scale) },
            labelLineHeight = labelFont.lineHeight,
        )
        for (tile in samplePlan.tiles) {
            drawTile(batch, tile.type, tile.cx, tile.cy, tile.w, tile.h, TagMode.FollowSetting)
        }
    }

    private fun drawSequenceExample(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
    ) {
        val sequence = L10n.get("tutorial.diagram.sequence")
        val rows = listOf(
            DiagramLayout.LabeledRowModel(
                label = sequence,
                groups = listOf(DiagramLayout.TileGroupModel(listOf(Tiles.CHAR_2, Tiles.CHAR_3, Tiles.CHAR_4))),
            ),
            DiagramLayout.LabeledRowModel(
                label = sequence,
                groups = listOf(DiagramLayout.TileGroupModel(listOf(Tiles.DOT_5, Tiles.DOT_6, Tiles.DOT_7))),
            ),
            DiagramLayout.LabeledRowModel(
                label = sequence,
                groups = listOf(DiagramLayout.TileGroupModel(listOf(Tiles.BAMBOO_1, Tiles.BAMBOO_2, Tiles.BAMBOO_3))),
            ),
        )
        drawLabeledRowsPlan(batch, vp, left, bottom, areaW, areaH, rows)
    }

    /**
     * Regular winning shape: four sets (triplet / sequence / triplet / sequence)
     * + a pair — always one row, modest gaps between groups.
     * `tutorial.hands.win.kong_size` swaps the second triplet for a kong.
     */
    private fun drawRegularHand(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        bodyKey: String,
    ) {
        val triplet = L10n.get("tutorial.diagram.triplet")
        val sequence = L10n.get("tutorial.diagram.sequence")
        val pair = L10n.get("tutorial.diagram.pair")
        val kong = L10n.get("tutorial.diagram.kong")
        val kongBeat = bodyKey == "tutorial.hands.win.kong_size"
        val bamboo8 = if (kongBeat) {
            DiagramLayout.TileGroupModel(
                tiles = listOf(Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8),
                caption = kong,
            )
        } else {
            DiagramLayout.TileGroupModel(
                tiles = listOf(Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8),
                caption = triplet,
            )
        }
        val rows = listOf(
            DiagramLayout.LabeledRowModel(
                label = "",
                groups = listOf(
                    DiagramLayout.TileGroupModel(
                        tiles = listOf(Tiles.CHAR_2, Tiles.CHAR_2, Tiles.CHAR_2),
                        caption = triplet,
                    ),
                    DiagramLayout.TileGroupModel(
                        tiles = listOf(Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6),
                        caption = sequence,
                    ),
                    bamboo8,
                    DiagramLayout.TileGroupModel(
                        tiles = listOf(Tiles.CHAR_7, Tiles.CHAR_8, Tiles.CHAR_9),
                        caption = sequence,
                    ),
                    DiagramLayout.TileGroupModel(
                        tiles = listOf(Tiles.RED, Tiles.RED),
                        caption = pair,
                    ),
                ),
            ),
        )
        drawLabeledRowsPlan(batch, vp, left, bottom, areaW, areaH, rows)
    }

    /**
     * Set taxonomy for the winning-hand intro: sequence, triplet, or kong.
     */
    private fun drawSetKinds(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
    ) {
        val sequence = L10n.get("tutorial.diagram.sequence")
        val triplet = L10n.get("tutorial.diagram.triplet")
        val kong = L10n.get("tutorial.diagram.kong")
        val rows = listOf(
            DiagramLayout.LabeledRowModel(
                label = sequence,
                groups = listOf(
                    DiagramLayout.TileGroupModel(
                        listOf(Tiles.CHAR_2, Tiles.CHAR_3, Tiles.CHAR_4),
                    ),
                ),
            ),
            DiagramLayout.LabeledRowModel(
                label = triplet,
                groups = listOf(
                    DiagramLayout.TileGroupModel(
                        listOf(Tiles.CHAR_2, Tiles.CHAR_2, Tiles.CHAR_2),
                    ),
                ),
            ),
            DiagramLayout.LabeledRowModel(
                label = kong,
                groups = listOf(
                    DiagramLayout.TileGroupModel(
                        listOf(Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8),
                    ),
                ),
            ),
        )
        drawLabeledRowsPlan(batch, vp, left, bottom, areaW, areaH, rows)
    }

    /**
     * Two regular hands that match except one set is a pung vs a kong of 8s —
     * same other melds and pair so the only difference is three vs four of a kind.
     * Captions sit under the differing group only (not as left-of-row labels).
     */
    private fun drawKongAsTriplet(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
    ) {
        val pung = L10n.get("tutorial.diagram.pung")
        val kong = L10n.get("tutorial.diagram.kong")
        // Shared structure: 222m, 456p, (888s vs 8888s), 789m, RR.
        fun hand(middle: DiagramLayout.TileGroupModel) = DiagramLayout.LabeledRowModel(
            label = "",
            groups = listOf(
                DiagramLayout.TileGroupModel(listOf(Tiles.CHAR_2, Tiles.CHAR_2, Tiles.CHAR_2)),
                DiagramLayout.TileGroupModel(listOf(Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6)),
                middle,
                DiagramLayout.TileGroupModel(listOf(Tiles.CHAR_7, Tiles.CHAR_8, Tiles.CHAR_9)),
                DiagramLayout.TileGroupModel(listOf(Tiles.RED, Tiles.RED)),
            ),
        )
        val rows = listOf(
            hand(
                DiagramLayout.TileGroupModel(
                    tiles = listOf(Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8),
                    caption = pung,
                ),
            ),
            hand(
                DiagramLayout.TileGroupModel(
                    tiles = listOf(Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8, Tiles.BAMBOO_8),
                    caption = kong,
                ),
            ),
        )
        drawLabeledRowsPlan(batch, vp, left, bottom, areaW, areaH, rows)
    }

    private fun drawNotASequenceRun(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        peel: NotASequencePeel,
    ) {
        if (peel.phase == 0) {
            val rows = listOf(
                DiagramLayout.LabeledRowModel(
                    label = L10n.get("tutorial.diagram.not_a_sequence"),
                    groups = listOf(
                        DiagramLayout.TileGroupModel(
                            listOf(Tiles.CHAR_3, Tiles.CHAR_4, Tiles.CHAR_5, Tiles.CHAR_6),
                        ),
                    ),
                ),
            )
            drawLabeledRowsPlan(batch, vp, left, bottom, areaW, areaH, rows)
        } else {
            drawNotASequencePeels(batch, vp, left, bottom, areaW, areaH, peel)
        }
    }

    private fun drawNotASequencePeels(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        peel: NotASequencePeel,
    ) {
        val labelFont = Fonts.labelActive
        val plan = DiagramLayout.planNotASequencePeels(
            vp = vp,
            left = left,
            bottom = bottom,
            areaW = areaW,
            areaH = areaH,
            phase = peel.phase,
            peelT = SlideEase.OutCubic.apply(peel.t.coerceIn(0f, 1f)),
            types = intArrayOf(Tiles.CHAR_3, Tiles.CHAR_4, Tiles.CHAR_5, Tiles.CHAR_6),
            spareCaption = L10n.get("tutorial.diagram.spare"),
            sequenceCaption = L10n.get("tutorial.diagram.sequence"),
            measureLabelW = { text, scale -> measureLineW(labelFont, text, scale) },
            labelLineHeight = labelFont.lineHeight,
        )
        val diagA = diagramAlpha()
        for ((tileRects, spareCaption, sequenceCaption, peelT) in plan.rows) {
            for (tile in tileRects) drawTileRect(batch, tile)
            // Captions fade in as this row's spare opens.
            val capA = diagA * ((peelT - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (capA > 0.02f) {
                spareCaption?.let { drawPlanLabel(batch, labelFont, it, AppPalette.ink, capA) }
                sequenceCaption?.let { drawPlanLabel(batch, labelFont, it, AppPalette.ink, capA) }
            }
        }
    }

    private fun drawLabeledRowsPlan(
        batch: SpriteBatch,
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        rows: List<DiagramLayout.LabeledRowModel>,
    ) {
        val labelFont = Fonts.labelActive
        val plan = DiagramLayout.planLabeledRows(
            vp = vp,
            left = left,
            bottom = bottom,
            areaW = areaW,
            areaH = areaH,
            rows = rows,
            measureLabelW = { text, scale -> measureLineW(labelFont, text, scale) },
            labelLineHeight = labelFont.lineHeight,
        )
        val diagA = diagramAlpha()
        for (tile in plan.tiles) drawTileRect(batch, tile)
        for (lab in plan.rowLabels) drawPlanLabel(batch, labelFont, lab, AppPalette.accent, diagA)
        for (lab in plan.captions) drawPlanLabel(batch, labelFont, lab, AppPalette.ink, diagA)
    }

}
