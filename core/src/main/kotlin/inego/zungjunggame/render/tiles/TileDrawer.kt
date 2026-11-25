package inego.zungjunggame.render.tiles

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/**
 * Low-level tile drawing: open face (composited ivory + symbol) or closed back,
 * centered, with optional rotation in degrees (libGDX counter-clockwise).
 */
class TileDrawer(
    private val assets: TileAssets,
) {
    /**
     * Nine-patch over the **current** `assets.outlineGlow`.
     *
     * It cannot be built once and kept: [TileAssets] *replaces* that texture when
     * the driver's context is recovered ([inego.zungjunggame.render.assets.TextureRecovery]), and drawing a
     * disposed handle renders an opaque black quad — which is how a recovered
     * session turned every tutorial outline into the black box over the live-wall
     * marker. The cache is therefore keyed on the texture instance it was built
     * from, and re-derived when the asset hands over a new one.
     */
    private var outlineGlowTex: Texture = assets.outlineGlow
    private var outlinePatch: NinePatch = outlinePatchFor(outlineGlowTex)
    private val outlineClusters = ArrayList<TileHighlightCluster>(8)
    private val transformPrev = Matrix4()
    private val transform = Matrix4()

    private fun outlinePatchFor(texture: Texture): NinePatch = NinePatch(
        texture,
        TileAssets.OUTLINE_PATCH_PAD,
        TileAssets.OUTLINE_PATCH_PAD,
        TileAssets.OUTLINE_PATCH_PAD,
        TileAssets.OUTLINE_PATCH_PAD,
    )

    /** The patch for the asset's live texture, rebuilt when that texture changed. */
    private fun outlinePatch(): NinePatch {
        val current = assets.outlineGlow
        if (current !== outlineGlowTex) {
            outlineGlowTex = current
            outlinePatch = outlinePatchFor(current)
        }
        return outlinePatch
    }

    /** Reused label metrics — a fresh [GlyphLayout] per tile per frame is garbage. */
    private val labelLayout = GlyphLayout()

    /** Reused knockout colour — a labelled tile must not allocate per frame. */
    private val labelHalo = Color()

    /** Reused ink colour, so a tag fades with its tile's batch alpha. */
    private val labelInk = Color()

    /**
     * A label's natural ink box on the shadow-free [Fonts.tileLabel] face, keyed by
     * `<script profile>|<label text>`.
     *
     * The key covers both ways this could go stale: [Fonts] rebuilds its faces only when
     * the script profile changes, so a rebuild always lands on a new key, and a locale
     * switch changes the label text itself, so a tag can never be measured in one locale
     * and drawn in another. Entries are a pair of floats each, bounded by the 34 tile
     * faces times the shipped locales.
     *
     * The chrome faces are unusable for a tag: they bake a black drop shadow, and every
     * stamp of the ivory halo would drag its own shadow along as a dark fringe.
     */
    private val labelMetricCache = HashMap<String, LabelFace>()

    private fun labelFaceFor(label: String): LabelFace =
        labelMetricCache.getOrPut("${Fonts.activeProfile}|$label") {
            labelLayout.setText(Fonts.tileLabel, label)
            LabelFace(labelLayout.width, labelLayout.height)
        }

    /**
     * Soft halo *around* a tile (play engine). [w]/[h] are the tile size; the
     * glow sprite is larger so a quiet ring sits outside the opaque face.
     * Call before the tile.
     */
    fun drawGlow(
        batch: SpriteBatch,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        color: Color = FOCUS_GLOW_COLOR,
        expand: Float = 1.85f,
    ) {
        val prevR = batch.color.r
        val prevG = batch.color.g
        val prevB = batch.color.b
        val prevA = batch.color.a
        batch.setColor(color)
        drawTexture(
            batch,
            assets.glow,
            cx,
            cy,
            w * expand,
            h * expand,
            rotationDeg,
            scaleX,
            scaleY,
        )
        batch.setColor(prevR, prevG, prevB, prevA)
    }

    /**
     * Tutorial outline around one tile. [w]/[h] are the tile size; the stroke
     * sits just outside the opaque face (the face itself is not tinted).
     */
    fun drawGlowOutline(
        batch: SpriteBatch,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float = 0f,
        color: Color = PROMPT_GLOW_COLOR,
        intensity: Float = 1f,
    ) {
        if (w < 1f || h < 1f) return
        drawOutlineRect(batch, cx, cy, w, h, rotationDeg, color, intensity)
    }

    /**
     * Cluster [boxes] of the same style into groups of adjacent tiles and draw
     * one outline per group. Pass a single [color] — split the list (or use
     * [TileHighlightBox.groupKey] and call again) when styles differ.
     */
    fun drawGlowOutlines(
        batch: SpriteBatch,
        boxes: List<TileHighlightBox>,
        color: Color,
        intensity: Float = 1f,
    ) {
        if (boxes.isEmpty()) return
        for ((_, cx, cy, w, h, rotationDeg) in TileHighlight.cluster(boxes, outlineClusters)) {
            drawOutlineRect(batch, cx, cy, w, h, rotationDeg, color, intensity)
        }
    }

    private fun drawOutlineRect(
        batch: SpriteBatch,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float,
        color: Color,
        intensity: Float,
    ) {
        // The corner cell's inner corner is the arc center. Size the cell to the
        // outer radius, but grow the dest only by the stroke (the opaque share of
        // that cell). The center then lands inside the tile by the inner radius.
        // Growing the cell and the dest by the same pad pins the center on the vertex.
        val frame = outlineFrame(w, h) ?: return
        val outside = frame.outside
        val radius = frame.outerRadius
        val dw = w + outside * 2f
        val dh = h + outside * 2f
        val patch = outlinePatch()
        patch.leftWidth = radius
        patch.rightWidth = radius
        patch.topHeight = radius
        patch.bottomHeight = radius
        val a = (color.a * intensity.coerceIn(0f, 1.25f)).coerceIn(0f, 1f)
        val prevR = batch.color.r
        val prevG = batch.color.g
        val prevB = batch.color.b
        val prevA = batch.color.a
        batch.setColor(color.r, color.g, color.b, a)
        if (abs(rotationDeg) < 0.05f) {
            patch.draw(batch, cx - dw * 0.5f, cy - dh * 0.5f, dw, dh)
        } else {
            transformPrev.set(batch.transformMatrix)
            transform.set(transformPrev)
            transform.translate(cx, cy, 0f)
            transform.rotate(0f, 0f, 1f, rotationDeg)
            batch.transformMatrix = transform
            patch.draw(batch, -dw * 0.5f, -dh * 0.5f, dw, dh)
            batch.transformMatrix = transformPrev
        }
        batch.setColor(prevR, prevG, prevB, prevA)
    }

    /**
     * One open (face-up) tile.
     *
     * @param label optional learner tag ([TileLabels.tagOrNull]) painted in the tile's
     *   top-left corner. Opt-in and defaulted so every pre-existing call site draws
     *   exactly as before; a null/empty [label] — or [TileLabels.enabled] false —
     *   adds no draw call and no geometry. Face-down tiles have no tag: use
     *   [drawClosed], which never reads the flag.
     * @param forceLabel paint [label] even while [TileLabels.enabled] is off.
     *   Tutorial comparison beats use this to show tagged faces next to untagged
     *   ones regardless of the player's setting; the 13-tile "follow setting" row
     *   still goes through [TileLabels.tagOrNull] with [forceLabel] false.
     */
    fun drawOpen(
        batch: SpriteBatch,
        tileType: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        label: String? = null,
        forceLabel: Boolean = false,
    ) {
        val backBufferScale = Gdx.graphics?.backBufferScale ?: 1f
        val physicalH = max(h * abs(scaleY), w * abs(scaleX) * (4f / 3f)) * backBufferScale
        val region = assets.face(tileType, physicalH)
        batch.draw(
            region,
            cx - w / 2f,
            cy - h / 2f,
            w / 2f,
            h / 2f,
            w,
            h,
            scaleX,
            scaleY,
            rotationDeg,
        )
        if (!label.isNullOrEmpty() && (forceLabel || TileLabels.enabled)) {
            drawTileLabel(batch, label, cx, cy, w, h, rotationDeg, scaleX, scaleY)
        }
    }

    /**
     * Prints [label] into the top-left corner of the tile face, in the tile's own
     * space, so it turns and travels with the tile.
     *
     * Printed on the face, not overlaid on the screen: the tag is drawn in the
     * tile's **local** space, through the same centre → rotation → scale transform
     * `drawTexture` uses, so it rotates, squashes and travels with the tile
     * through every deal, discard and meld animation. An earlier version drew the
     * tag in world space to keep it screen-upright; that made it slide around the
     * tile whenever the tile turned (and hang off the corner), which reads as a
     * sticker floating over the table rather than a mark on the tile. The cost is
     * deliberate: on a far seat the tag is upside-down, exactly as the face is.
     *
     * Size: the ink fills a fixed share of the **face** — ≤28% wide, ≤15% tall — so the
     * tag scales with its tile: a larger window, a lifted tile, the drawn-tile emphasis
     * alike. An earlier version capped the size at the font's rasterised size, which left
     * a constant pixel size that only ever shrank, and the first proportional share (a
     * third of the tile's height) read as a badge rather than a corner mark. The tag is
     * drawn scaled to its tile from the shadow-free [Fonts.tileLabel] face, which is what
     * lets the outline exist without a dark fringe. Measuring on that primary is exact
     * for every shipped tag: digits and `Wh` are ASCII, and the Cyrillic tags ride the
     * same Roboto primary as the `ru` chrome; [Fonts.drawText] still picks the script
     * fallback per glyph, so a font gap cannot drop the tag. Below
     * [TileLabelGeometry.MIN_INK_HEIGHT_DESIGN_PX] of rendered ink the tag is skipped
     * rather than smeared over the face art.
     *
     * Ink: dark on the ivory face, knocked out of the art underneath. Dark ink
     * alone is unreadable where the suit art runs under the tag (a pin circle, a
     * man kanji stroke), so the strokes are preceded by a **glyph-shaped halo** in
     * the tile's own ivory. A backing *rectangle* was tried first and rejected: it
     * must guess the face's tone across a gradient, and at anything below full
     * alpha the art ghosts through inside it — which is exactly what makes it read
     * as a whitish box on the tile. A halo has no edges of its own, so the only
     * thing it changes is the few pixels the strokes need.
     */
    private fun drawTileLabel(
        batch: SpriteBatch,
        label: String,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        // Placement and size are pure arithmetic, pinned by `TileLabelGeometryTest`:
        // the ink fills a fixed share of the face, so the tag scales with its tile.
        // [LabelPlacement.topY] is a cap-height top — hand it to the font with no
        // further maths, or the tag sinks a line.
        val placement = TileLabelGeometry.place(
            tileW = w,
            tileH = h,
            face = labelFaceFor(label),
            minInkHeight = Fonts.px(TileLabelGeometry.MIN_INK_HEIGHT_DESIGN_PX),
        ) ?: return
        val face = Fonts.tileLabel

        // The halo is the face's ivory times the batch tint, so it also matches a
        // dimmed river tile or the brighter tint a focused claim tile is drawn
        // with — the tag knocks out of the tile as the tile is actually painted.
        labelHalo.set(
            batch.color.r * FACE_IVORY.r,
            batch.color.g * FACE_IVORY.g,
            batch.color.b * FACE_IVORY.b,
            batch.color.a,
        )
        val halo = max(1f, placement.inkH * TAG_HALO_FRACTION)

        // The tag is part of the tile, so it follows the batch alpha: a tile the
        // touch loupe fades under the finger must not leave an opaque tag on the
        // felt. Every other tile is drawn at alpha 1, so this is a no-op there.
        val ink = if (batch.color.a < 1f) {
            labelInk.set(
                LABEL_INK_COLOR.r,
                LABEL_INK_COLOR.g,
                LABEL_INK_COLOR.b,
                LABEL_INK_COLOR.a * batch.color.a,
            )
            labelInk
        } else {
            LABEL_INK_COLOR
        }

        // Ride the tile's own transform — centre, rotation, then scale, the same
        // order `drawTexture` composes — so the tag cannot drift from the face it
        // marks, in any seat rotation or mid-animation pose.
        transformPrev.set(batch.transformMatrix)
        transform.set(transformPrev)
        transform.translate(cx, cy, 0f)
        transform.rotate(0f, 0f, 1f, rotationDeg)
        transform.scale(scaleX, scaleY, 1f)
        batch.transformMatrix = transform
        try {
            // Sanctioned scale helper: keeps the chosen face and its script fallback
            // in lockstep, and restores both even if the draw throws.
            Fonts.withFaceScale(face, placement.scale) {
                for (i in HALO_OFFSETS.indices step 2) {
                    Fonts.drawText(
                        batch,
                        face,
                        label,
                        placement.leftX + HALO_OFFSETS[i] * halo,
                        placement.topY + HALO_OFFSETS[i + 1] * halo,
                        labelHalo,
                    )
                }
                Fonts.drawText(batch, face, label, placement.leftX, placement.topY, ink)
            }
        } finally {
            batch.transformMatrix = transformPrev
        }
    }

    fun drawClosed(
        batch: SpriteBatch,
        closed: Texture,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ) {
        drawTexture(batch, closed, cx, cy, w, h, rotationDeg, scaleX, scaleY)
    }

    fun drawClosed(
        batch: SpriteBatch,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ) {
        drawClosed(
            batch,
            assets.closedBlue,
            cx,
            cy,
            w,
            h,
            rotationDeg,
            scaleX,
            scaleY,
        )
    }

    private fun drawTexture(
        batch: SpriteBatch,
        texture: Texture,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotationDeg: Float,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ) {
        batch.draw(
            texture,
            cx - w / 2f,
            cy - h / 2f,
            w / 2f,
            h / 2f,
            w,
            h,
            scaleX,
            scaleY,
            rotationDeg,
            0,
            0,
            texture.width,
            texture.height,
            false,
            false,
        )
    }

    /**
     * Where [drawOutlineRect] places the outline ring. [outerRadius] is the
     * nine-patch border; [outside] is the stroke past the tile edge.
     */
    private class OutlineFrame(val outerRadius: Float, val outside: Float)

    companion object {
        /**
         * Screen placement of [TileAssets.outlineGlow] for a rect of size [w]×[h].
         * [OutlineFrame.outerRadius] is the nine-patch border (the outer corner
         * radius). [OutlineFrame.outside] is how far the dest extends past the
         * rect; it is the stroke, which is the opaque share of that border.
         */
        private fun outlineFrame(w: Float, h: Float): OutlineFrame? {
            if (w < 1f || h < 1f) return null
            val strokeShare = TileAssets.OUTLINE_STROKE / TileAssets.OUTLINE_PATCH_PAD.toFloat()
            val innerShare = 1f - strokeShare
            val cap = min(
                (w - 1f) / (2f * innerShare),
                (h - 1f) / (2f * innerShare),
            )
            if (cap < 1f) return null
            val outer = min(
                min(w, h) * TileAssets.OUTLINE_INNER_RADIUS_FRACTION / innerShare,
                cap,
            )
            return OutlineFrame(outer, outer * strokeShare)
        }

        /**
         * Flat ivory of `tiles/Front.png` (sampled: 245,240,235) — the label's
         * knockout tone. The face's own gradient stays within a few 1/255 of it
         * across the area a tag occupies (the brighter band is 249,246,243), so
         * the knockout does not read as a patch.
         */
        private val FACE_IVORY: Color = AppPalette.tileFaceInk

        /** Halo thickness as a fraction of the tag's height, floored at one pixel. */
        private const val TAG_HALO_FRACTION = 0.10f

        /**
         * Unit offsets the knockout is stamped at, as x,y pairs. Eight points, so
         * the ring closes at the diagonals too: a glyph stroke is 1-2px wide at
         * tag size, and a four-point halo leaves visible notches in the corners.
         */
        private val HALO_OFFSETS: FloatArray = floatArrayOf(
            -1f, 0f, 1f, 0f, 0f, -1f, 0f, 1f,
            -0.7f, -0.7f, 0.7f, -0.7f, -0.7f, 0.7f, 0.7f, 0.7f,
        )

        /** Warm near-black ink: maximum contrast on the ivory face. */
        private val LABEL_INK_COLOR: Color = AppPalette.tileInk

        /** Soft gold halo for the pending claim discard (play engine). */
        val FOCUS_GLOW_COLOR: Color = AppPalette.tint(AppPalette.accent, 0.42f)

        /** Orange tutorial outline for a prompted tile or adjacent group. */
        val PROMPT_GLOW_COLOR: Color = AppPalette.promptGlow

        /**
         * Ready-discard outline. Chartreuse: the hue midway between yellow
         * and green.
         */
        val READY_GLOW_COLOR: Color = AppPalette.readyGlow

        /** Cyan tutorial outline for secondary contrasts. */
        val SECONDARY_GLOW_COLOR: Color = AppPalette.secondaryGlow

        /**
         * Quiet cyan/teal halo for tiles matching the face under the pointer
         * (distinct from the gold focus / orange win).
         */
        val MATCH_GLOW_COLOR: Color = AppPalette.matchGlow

        /** Red tutorial outline for a pending discard that cannot be claimed. */
        val DENY_GLOW_COLOR: Color = AppPalette.denyGlow

        /** Bright orange halo for the discard that won the hand. */
        val WIN_DISCARD_GLOW_COLOR: Color = AppPalette.winDiscardGlow

        /** Soft gold halo for tiles in the winning hand (softer than WinDiscard). */
        val WIN_HAND_GLOW_COLOR: Color = AppPalette.tint(AppPalette.accent, 0.52f)
    }
}
