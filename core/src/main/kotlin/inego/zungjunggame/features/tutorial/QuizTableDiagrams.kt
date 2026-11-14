package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.tutorial.TutorialDiagram
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/**
 * Soft felt inlays used by orientation quizzes (wall / seats sketches).
 * Coach-primary tile diagrams are drawn elsewhere.
 */
object QuizTableDiagrams {
    private val glyphLayout = GlyphLayout()
    private val ivory = AppPalette.ink
    private val accent = AppPalette.accent

    fun draw(
        batch: SpriteBatch,
        px: Texture,
        diagram: TutorialDiagram,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        spotlight: String?,
    ) {
        // Soft inlay on the felt - not a dark card.
        UiPlate.drawPlate(
            batch, px, left, bottom, w, h,
            AppPalette.diagramFill,
            AppPalette.diagramRim,
            max(1f, Fonts.px(1.5f)),
        )

        when (diagram) {
            TutorialDiagram.None,
            TutorialDiagram.ThreeSuits,
            TutorialDiagram.Honors,
            TutorialDiagram.TileTypes,
            TutorialDiagram.SequenceExample,
            TutorialDiagram.NotASequenceRun,
            TutorialDiagram.WallBuild,
            TutorialDiagram.RegularHand,
            TutorialDiagram.SetKinds,
            TutorialDiagram.KongAsTriplet,
            TutorialDiagram.SeatWinds,
            TutorialDiagram.TileTags,
            -> Unit
            TutorialDiagram.WallDead -> drawWallDead(batch, px, left, bottom, w, h, spotlight)
            TutorialDiagram.SeatsTurns -> drawSeats(batch, px, left, bottom, w, h, spotlight)
            TutorialDiagram.TableOverview -> drawSeats(batch, px, left, bottom, w, h, spotlight)
        }
    }

    private fun drawWallDead(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        spotlight: String?,
    ) {
        val pad = Fonts.px(10f)
        val barH = h * 0.38f
        val barY = bottom + (h - barH) * 0.55f
        val liveW = w * 0.62f
        val deadW = w * 0.28f
        val gap = Fonts.px(8f)
        val total = liveW + gap + deadW
        val x0 = left + (w - total) * 0.5f

        batch.setColor(AppPalette.diagramPlate)
        batch.draw(px, x0, barY, liveW, barH)
        val deadHighlight = spotlight == "wall.dead" || spotlight == null
        batch.setColor(
            if (deadHighlight) AppPalette.diagramHighlight
            else AppPalette.diagramMuted,
        )
        batch.draw(px, x0 + liveW + gap, barY, deadW, barH)
        batch.setColor(Color.WHITE)

        val font = Fonts.small
        val liveLabel = L10n.get("tutorial.diagram.live_wall")
        val deadLabel = L10n.get("tutorial.diagram.dead_wall")
        glyphLayout.setText(font, liveLabel)
        font.color = ivory
        font.draw(batch, liveLabel, x0 + (liveW - glyphLayout.width) * 0.5f, barY - Fonts.px(4f))
        glyphLayout.setText(font, deadLabel)
        font.draw(
            batch, deadLabel,
            x0 + liveW + gap + (deadW - glyphLayout.width) * 0.5f,
            barY - Fonts.px(4f),
        )
        font.color = Color.WHITE

        val cap = L10n.get("tutorial.diagram.dead_wall_count")
        glyphLayout.setText(Fonts.button, cap)
        Fonts.button.color = accent
        Fonts.button.draw(
            batch, cap,
            left + (w - glyphLayout.width) * 0.5f,
            bottom + pad + glyphLayout.height,
        )
        Fonts.button.color = Color.WHITE
    }

    private fun drawSeats(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        spotlight: String?,
    ) {
        val cx = left + w * 0.5f
        val cy = bottom + h * 0.52f
        val boxW = min(Fonts.px(86f), w * 0.28f)
        val boxH = min(Fonts.px(34f), h * 0.18f)
        val reach = min(w, h) * 0.28f
        data class SeatBox(val id: String, val x: Float, val y: Float, val key: String)
        val seats = listOf(
            SeatBox("you", cx - boxW * 0.5f, cy - reach, "tutorial.diagram.seat_you"),
            SeatBox("right", cx + reach * 0.55f, cy - boxH * 0.5f, "tutorial.diagram.seat_right"),
            SeatBox("opposite", cx - boxW * 0.5f, cy + reach * 0.55f, "tutorial.diagram.seat_opposite"),
            SeatBox("left", cx - reach * 0.55f - boxW, cy - boxH * 0.5f, "tutorial.diagram.seat_left"),
        )
        for ((id, x, y, key) in seats) {
            val hot = spotlight == null || spotlight == "seat.$id" ||
                (spotlight == "seat.east" && id == "you") ||
                (spotlight == "turn.next" && id == "right")
            val label = L10n.get(key)
            OverlayChrome.drawButton(
                batch, px, Fonts.small, glyphLayout, label,
                x, y, boxW, boxH,
                if (hot) AppPalette.diagramHot else AppPalette.diagramCold,
                if (hot) accent else AppPalette.tint(AppPalette.rim, 0.45f),
                ivory,
            )
        }
        val arrow = L10n.get("tutorial.diagram.turn_ccw")
        glyphLayout.setText(Fonts.small, arrow)
        Fonts.small.color = accent
        Fonts.small.draw(
            batch, arrow,
            cx - glyphLayout.width * 0.5f,
            bottom + Fonts.px(14f) + glyphLayout.height,
        )
        Fonts.small.color = Color.WHITE
    }
}
