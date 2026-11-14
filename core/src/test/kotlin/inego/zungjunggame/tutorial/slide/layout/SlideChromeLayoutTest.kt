package inego.zungjunggame.tutorial.slide.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideChromeLayoutTest {

    private fun planFor(
        w: Float,
        h: Float,
        hasDiagram: Boolean,
        touch: Boolean = false,
        titleLines: Int = 1,
        bodyLines: Int = 2,
    ): SlideChromeLayout.Plan {
        val vp = SlideViewport(w, h, touch, uiScale = 1f)
        val titleLh = 28f
        val bodyLh = if (hasDiagram) 22f else 18f
        val btnLh = 18f
        return SlideChromeLayout.plan(
            vp = vp,
            hasTileDiagram = hasDiagram,
            titleEmpty = false,
            bodyEmpty = false,
            titleLineHeight = titleLh,
            bodyLineHeight = bodyLh,
            buttonLineHeight = btnLh,
            measureTitleH = { scale, _ -> titleLh * scale * titleLines },
            measureBodyH = { scale, _ -> bodyLh * scale * bodyLines },
            measurePrimaryLabelW = { 80f },
        )
    }

    @Test
    fun diagramGetsSubstantialShareOnPortraitPhone() {
        val plan = planFor(360f, 640f, hasDiagram = true, touch = true)
        val contentH = plan.titleTop - (plan.primaryBottom + plan.primaryH)
        assertTrue("diagramH=${plan.diagramH} contentH=$contentH", plan.diagramH >= contentH * 0.40f)
        assertTrue(plan.compactBodyFace)
        assertTrue(plan.fixedCopyBand)
        // No old 1.4× title inflation
        assertTrue(plan.titleScale <= 1.05f)
        assertTrue(plan.bodyScale <= 1.05f)
    }

    @Test
    fun nonDiagramAllowsMoreCopy() {
        val with = planFor(800f, 600f, hasDiagram = true)
        val without = planFor(800f, 600f, hasDiagram = false)
        assertTrue(without.diagramH <= with.diagramH + 1f || !without.fixedCopyBand)
        assertEquals(false, without.compactBodyFace)
    }

    @Test
    fun buttonBandCentersTheRow() {
        val vp = SlideViewport(400f, 700f, touch = true, uiScale = 2f)
        val buttonLine = 18f
        val plan = SlideChromeLayout.plan(
            vp = vp,
            hasTileDiagram = false,
            titleEmpty = false,
            bodyEmpty = false,
            titleLineHeight = 28f,
            bodyLineHeight = 18f,
            buttonLineHeight = buttonLine,
            measureTitleH = { scale, _ -> 28f * scale },
            measureBodyH = { scale, _ -> 18f * scale * 2f },
            measurePrimaryLabelW = { 80f },
        )
        val bandTop = SlideChromeLayout.buttonBandTop(vp, buttonLine)
        // The table reserves the viewport form; the coach paints the plan form.
        assertEquals(SlideChromeLayout.buttonBandTop(plan), bandTop, 0.01f)
        val above = bandTop - (plan.primaryBottom + plan.primaryH)
        assertEquals(plan.primaryBottom, above, 0.01f)
        assertEquals(plan.padY, above, 0.01f)
    }

    @Test
    fun primaryButtonInsideViewport() {
        val plan = planFor(400f, 700f, hasDiagram = true, touch = true)
        assertTrue(plan.primaryLeft >= 0f)
        assertTrue(plan.primaryLeft + plan.primaryW <= 400f + 0.5f)
        assertTrue(plan.primaryBottom >= 0f)
    }
}
