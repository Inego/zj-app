package inego.zungjunggame.features.settings

import inego.zungjunggame.i18n.AppLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagePickerOverlayTest {

    @Test
    fun overflowKeepsLastLocaleHittableAfterScroll() {
        val overlay = LanguagePickerOverlay()
        val locales = List(12) { AppLocale.English }
        val itemH = 144f
        val itemGap = 24f
        val bodyBottom = 32f
        val bodyH = 400f
        overlay.seedLayoutForTest(
            itemH = itemH,
            itemGap = itemGap,
            bodyBottom = bodyBottom,
            bodyH = bodyH,
            locales = locales,
        )
        assertTrue(overlay.overflowingForTest)
        val cx = 400f
        val firstY = bodyBottom + bodyH - itemH * 0.5f
        assertEquals(AppLocale.English, overlay.localeAt(cx, firstY))

        overlay.onPointerDown(cx, firstY)
        overlay.onPointerDrag(cx, firstY + 120f)
        val up = overlay.onPointerUp(cx, firstY + 120f)
        // The drag scrolled the body instead of selecting the row under the lift.
        assertNull(up.selectedId)
        assertTrue(overlay.maxScrollYForTest > 0f)
        assertFalse(up.close)
    }

    @Test
    fun revealingAnOffscreenLocaleReturnsTheOffsetThatShowsItsWholeRow() {
        val overlay = LanguagePickerOverlay()
        val locales = AppLocale.entries.toList()
        val itemH = 50f
        val itemGap = 10f
        val bodyH = 200f
        overlay.seedLayoutForTest(
            itemH = itemH,
            itemGap = itemGap,
            bodyBottom = 0f,
            bodyH = bodyH,
            locales = locales,
            scrollY = 0f,
        )
        assertTrue(overlay.overflowingForTest)

        // zh-HK is last, well below the initial viewport [0, 200].
        val last = AppLocale.ChineseTraditionalHK
        val offset = overlay.revealLocaleOffset(last, 0f)
        assertTrue(offset > 0f, "the selected locale sits below the view")

        val index = locales.indexOf(last)
        val rowTop = index * (itemH + itemGap)
        val rowBottom = rowTop + itemH
        assertTrue(rowTop >= offset, "the row's top is inside the view")
        assertTrue(rowBottom <= offset + bodyH, "the row's bottom is inside the view")

        // Revealing the row again from its revealed position moves nothing.
        assertEquals(offset, overlay.revealLocaleOffset(last, offset))
    }
}
