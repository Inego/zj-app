package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.tutorial.QuizBin

/** Bottom bin targets for [inego.zungjunggame.tutorial.QuizKind.BinAssign]. */
object QuizBinBar {
    fun draw(
        batch: SpriteBatch,
        px: Texture,
        bins: List<QuizBin>,
        left: Float,
        bottom: Float,
        contentW: Float,
        btnH: Float,
        pointerX: Float,
        pointerY: Float,
        alpha: Float,
        hits: QuizHitRegistrar,
    ) {
        if (bins.isEmpty()) return
        val gap = Fonts.px(10f)
        val n = bins.size
        val btnW = (contentW - gap * (n - 1)) / n
        var x = left
        for ((id, labelKey) in bins) {
            hits.add(QuizControlKind.Bin, id, x, bottom, btnW, btnH)
            TableChip.draw(
                batch, px, L10n.get(labelKey), x, bottom, btnW, btnH,
                pointerX, pointerY, alpha = alpha,
            )
            x += btnW + gap
        }
    }
}
