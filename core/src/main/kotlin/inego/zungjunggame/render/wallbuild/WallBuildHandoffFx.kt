package inego.zungjunggame.render.wallbuild

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.tiles.TileEasing
import inego.zungjunggame.render.tiles.TilePose

/**
 * Residual wall tiles from the wall-build diagram that collapse / fade during
 * table handoff (not matched into engine layout). Dead wall stays put and
 * fades; live residual eases toward the live-wall marker then fades.
 *
 * Hands themselves are seeded on [inego.zungjunggame.render.tiles.TileBoard]; this
 * object only owns leftover closed tiles plus the marker fade-in.
 */
class WallBuildHandoffFx {
    /**
     * Residual wall tiles from the wall-build diagram that collapse / fade
     * during handoff (not matched into engine layout).
     */
    private data class Tile(
        val from: TilePose,
        val to: TilePose,
        val tileType: Int,
        /** Dead wall stays put and fades; live residual eases toward the marker. */
        val collapseToCenter: Boolean,
    )

    private var tiles: List<Tile> = emptyList()
    /** 0 = just started, 1 = finished (or idle). */
    private var progress: Float = 1f
    /** First table render retargets residual collapse to true wall center. */
    private var needsCenter: Boolean = false

    /** Live-wall marker opacity during handoff (0 → 1 as residual collapses). */
    var markerAlpha: Float = 1f
        private set

    fun clear() {
        tiles = emptyList()
        progress = 1f
        markerAlpha = 1f
        needsCenter = false
    }

    /**
     * Begin residual FX. [wallCx]/[wallCy] may be NaN; live collapse then aims
     * at the average hand center until [retargetToCenter] on the first table
     * render.
     */
    fun start(
        snapshot: WallBuildHandoffSnapshot,
        wallCx: Float = Float.NaN,
        wallCy: Float = Float.NaN,
    ) {
        val hands = snapshot.hands
        val cx = if (wallCx.isFinite()) wallCx else averageHandCenterX(hands)
        val cy = if (wallCy.isFinite()) wallCy else averageHandCenterY(hands)
        tiles = buildTiles(snapshot, cx, cy)
        progress = 0f
        markerAlpha = 0f
        needsCenter = true
    }

    /** Point residual live collapse at the real table center (content coords). */
    fun retargetToCenter(wallCx: Float, wallCy: Float) {
        if (!needsCenter || tiles.isEmpty()) return
        needsCenter = false
        var liveI = 0
        tiles = tiles.map { fx ->
            if (!fx.collapseToCenter) {
                fx
            } else {
                val depth = (liveI % 3).toFloat()
                liveI++
                fx.copy(to = collapseTarget(fx.from, wallCx, wallCy, depth))
            }
        }
    }

    /**
     * Advance residual motion and marker fade.
     *
     * @return true when this tick completed the morph (caller restores board
     *         flight duration).
     */
    /** Residual tiles are still collapsing or fading in. */
    fun isRunning(): Boolean = progress < 1f && tiles.isNotEmpty()

    fun advance(deltaSec: Float): Boolean {
        if (progress >= 1f || deltaSec <= 0f) return false
        progress = (progress + deltaSec / DURATION_SEC).coerceAtMost(1f)
        // Marker appears as residual live finishes collapsing (~mid → end).
        val markerT = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
        markerAlpha = TileEasing.easeOutCubic(markerT)
        if (progress >= 1f) {
            tiles = emptyList()
            markerAlpha = 1f
            return true
        }
        return false
    }

    fun draw(batch: SpriteBatch, drawer: TileDrawer) {
        if (tiles.isEmpty() || progress >= 1f) return
        val u = TileEasing.easeOutCubic(progress)
        // Dead fades early; live stays more opaque while collapsing then fades.
        for ((from, to, _, collapseToCenter) in tiles) {
            val alpha = if (!collapseToCenter) {
                // Dead wall: fade through the first ~70% of the morph.
                (1f - progress / 0.70f).coerceIn(0f, 1f)
            } else {
                // Live: full while moving, fade in the last 40%.
                if (progress < 0.60f) {
                    1f
                } else {
                    (1f - (progress - 0.60f) / 0.40f).coerceIn(0f, 1f)
                }
            }
            if (alpha < 0.01f) continue
            val p = if (collapseToCenter) {
                interpolate(from, to, u)
            } else {
                from
            }
            batch.setColor(AppPalette.tint(AppPalette.headerInk, alpha))
            drawer.drawClosed(
                batch,
                p.x,
                p.y,
                p.w,
                p.h,
                p.rotationDeg,
            )
            batch.setColor(Color.WHITE)
        }
    }

    private fun buildTiles(
        snapshot: WallBuildHandoffSnapshot,
        wallCx: Float,
        wallCy: Float,
    ): List<Tile> {
        val out = ArrayList<Tile>(
            snapshot.residualLive.size + snapshot.residualDead.size,
        )
        // Dead wall: stay put and fade out (no engine visual).
        for (p in snapshot.residualDead) {
            val pose = poseOf(p)
            out += Tile(
                from = pose,
                to = pose,
                tileType = p.tileType,
                collapseToCenter = false,
            )
        }
        // Live remainder: collapse toward the center marker, then fade.
        for ((i, p) in snapshot.residualLive.withIndex()) {
            val from = poseOf(p)
            // Slight stack depth so many tiles don't occupy one pixel.
            val depth = (i % 3).toFloat()
            out += Tile(
                from = from,
                to = collapseTarget(from, wallCx, wallCy, depth),
                tileType = p.tileType,
                collapseToCenter = true,
            )
        }
        return out
    }

    companion object {
        /** Diagram → table morph duration (hands + residual wall FX). */
        const val DURATION_SEC = 0.55f

        private fun poseOf(p: WallBuildHandoffPose): TilePose = TilePose(
            x = p.cx,
            y = p.cy,
            rotationDeg = p.rot,
            w = p.w,
            h = p.h,
            open = false,
        )

        private fun collapseTarget(
            from: TilePose,
            wallCx: Float,
            wallCy: Float,
            depth: Float,
        ): TilePose = TilePose(
            x = wallCx - depth * from.w * 0.08f,
            y = wallCy + depth * from.h * 0.07f,
            rotationDeg = 0f,
            w = from.w,
            h = from.h,
            open = false,
        )

        private fun interpolate(from: TilePose, to: TilePose, u: Float): TilePose {
            if (u <= 0f) return from
            if (u >= 1f) return to
            return TilePose(
                x = from.x + (to.x - from.x) * u,
                y = from.y + (to.y - from.y) * u,
                rotationDeg = from.rotationDeg + (to.rotationDeg - from.rotationDeg) * u,
                w = from.w + (to.w - from.w) * u,
                h = from.h + (to.h - from.h) * u,
                open = false,
            )
        }

        private fun averageHandCenterX(hands: List<WallBuildHandoffTile>): Float {
            if (hands.isEmpty()) return 0f
            var s = 0f
            for (t in hands) s += t.cx
            return s / hands.size
        }

        private fun averageHandCenterY(hands: List<WallBuildHandoffTile>): Float {
            if (hands.isEmpty()) return 0f
            var s = 0f
            for (t in hands) s += t.cy
            return s / hands.size
        }
    }
}
