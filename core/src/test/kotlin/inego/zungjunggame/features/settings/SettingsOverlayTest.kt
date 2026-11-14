package inego.zungjunggame.features.settings

import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileLabels
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The panel's hit-testing and gesture contract, with no GL frame: no font atlas,
 * no `SpriteBatch`. Geometry comes from [SettingsOverlay.seedLayoutForTest], so
 * "which rect answers the pointer" is provable in a plain JVM test — the same
 * seam the other overlays expose.
 *
 * The control **values** are not tested here: the overlay does not own them. It
 * renders whatever [SettingsOverlay.render] is handed and only reports the row or
 * slider value a gesture resolved — see `SettingsFeatureTest` for the model read.
 *
 * Seeded layout: panel y 12..432, tile-label row y 32..92, sounds row y 102..162,
 * volume slider y 172..230, email opt-in row y 240..300, digest-interval slider
 * y 310..368 (see [SettingsOverlay.seedLayoutForTest]).
 */
class SettingsOverlayTest {

    @AfterTest
    fun tearDown() {
        // [TileLabels] and [Fonts.uiScale] are process-global; leave the suite as it was found.
        TileLabels.enabled = false
        Fonts.uiScale = 1f
    }

    private fun shown(): SettingsOverlay =
        SettingsOverlay().apply { seedLayoutForTest() }

    private val rowCentreX = 240f
    private val tileRowCentreY = 60f
    private val soundsRowCentreY = 132f
    private val emailRowCentreY = 270f
    private val limitCentreY = 339f
    private val chromeY = 400f

    @Test
    fun rowHitAnswersInsideTheRowOnly() {
        val overlay = shown()
        assertTrue(overlay.isOnTileLabelRow(rowCentreX, tileRowCentreY))
        assertFalse(overlay.isOnTileLabelRow(rowCentreX, 10f), "below the rows")
        assertFalse(overlay.isOnTileLabelRow(rowCentreX, chromeY), "title band")
        assertFalse(overlay.isOnTileLabelRow(900f, tileRowCentreY), "outside the card")
        assertTrue(overlay.containsPanel(rowCentreX, chromeY), "title band is still panel")
    }

    @Test
    fun eachCheckboxRowHitAnswersOnlyItsOwnBand() {
        val overlay = shown()
        assertTrue(overlay.isOnSoundsRow(rowCentreX, soundsRowCentreY))
        assertFalse(overlay.isOnSoundsRow(rowCentreX, tileRowCentreY), "tile-label band")
        assertFalse(overlay.isOnTileLabelRow(rowCentreX, soundsRowCentreY), "sounds band")
    }

    @Test
    fun tapOnTheTileRowReadsAsThatRow() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, tileRowCentreY)
        assertEquals(
            SettingsVerdict.Row(SettingsOverlay.TILE_LABELS_ROW),
            overlay.onPointerUp(rowCentreX, tileRowCentreY),
        )
        // The release resets the press, so a second one resolves nothing.
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(rowCentreX, tileRowCentreY))
    }

    @Test
    fun tapOnTheSoundsRowReadsAsThatRow() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, soundsRowCentreY)
        assertEquals(
            SettingsVerdict.Row(SettingsOverlay.SOUNDS_ROW),
            overlay.onPointerUp(rowCentreX, soundsRowCentreY),
        )
    }

    @Test
    fun tapOnTheEmailRowReadsAsThatRow() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, emailRowCentreY)
        assertEquals(
            SettingsVerdict.Row(SettingsOverlay.EMAIL_OPT_IN_ROW),
            overlay.onPointerUp(rowCentreX, emailRowCentreY),
        )
    }

    @Test
    fun theNotificationRowsAreInertWhileTheSectionIsHidden() {
        val overlay = SettingsOverlay().apply { seedLayoutForTest(notificationsVisible = false) }
        assertFalse(overlay.isOnEmailRow(rowCentreX, emailRowCentreY))
        assertFalse(overlay.isOnDigestSlider(246f, limitCentreY))
        overlay.onPointerDown(rowCentreX, emailRowCentreY)
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(rowCentreX, emailRowCentreY))
    }

    @Test
    fun tapOnTheCloseButtonResolvesClose() {
        val overlay = shown()
        // closeButton is seeded at left 550, bottom 382, side 36 -> centre 568, 400
        val closeX = 568f
        val closeY = 400f
        assertTrue(overlay.isOnCloseButton(closeX, closeY))
        assertTrue(overlay.onPointerDown(closeX, closeY))
        assertEquals(SettingsVerdict.Close, overlay.onPointerUp(closeX, closeY))
    }

    @Test
    fun dragOffTheCloseButtonCancelsClose() {
        val overlay = shown()
        val closeX = 568f
        val closeY = 400f
        assertTrue(overlay.onPointerDown(closeX, closeY))
        // Release far away outside close button
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(200f, 200f))
    }

    @Test
    fun tapOnPanelChromeSelectsNothingAndStaysOpen() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, chromeY)
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(rowCentreX, chromeY))
        // The router only dismisses an outside release, so an in-panel miss is inert.
        assertTrue(overlay.containsPanel(rowCentreX, chromeY))
        assertFalse(overlay.isOnCloseButton(rowCentreX, chromeY))
    }

    @Test
    fun pressStartedOutsideTheRowCannotToggleOnRelease() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, chromeY)
        overlay.onPointerDrag(rowCentreX, tileRowCentreY)
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(rowCentreX, tileRowCentreY))
    }

    @Test
    fun dragAlongTheRowDoesNotToggle() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, tileRowCentreY)
        overlay.onPointerDrag(rowCentreX, tileRowCentreY + 100f)
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(rowCentreX, tileRowCentreY))
    }

    @Test
    fun fingerRollWithinScaledSlopStillToggles() {
        // Scale 3 is a dense phone. 20 screen px is past a raw 12 and inside 12 design px.
        Fonts.uiScale = 3f
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, tileRowCentreY)
        overlay.onPointerDrag(rowCentreX, tileRowCentreY + 20f)
        assertEquals(
            SettingsVerdict.Row(SettingsOverlay.TILE_LABELS_ROW),
            overlay.onPointerUp(rowCentreX, tileRowCentreY + 20f),
        )

        overlay.onPointerDown(rowCentreX, tileRowCentreY)
        overlay.onPointerDrag(rowCentreX, tileRowCentreY + 40f)
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(rowCentreX, tileRowCentreY + 40f))
    }

    @Test
    fun digestSliderCommitsOnceOnReleaseAndNotPerDragStep() {
        val overlay = shown()
        // The seeded digest track runs x 246 (step 0) .. 554 (step 7, once a day).
        assertTrue(overlay.onPointerDown(246f, limitCentreY))
        // Unlike the volume slider, the digest interval only reports on release: a server write.
        assertNull(overlay.pressVerdict())
        assertNull(overlay.onPointerDrag(554f, limitCentreY))
        assertEquals(SettingsVerdict.DigestInterval(7), overlay.onPointerUp(554f, limitCentreY))
    }

    @Test
    fun aRowPressReportsNoVolume() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, tileRowCentreY)
        assertNull(overlay.pressVerdict())
        overlay.onPointerUp(rowCentreX, tileRowCentreY)
    }

    @Test
    fun cancelGestureDropsThePendingPress() {
        val overlay = shown()
        overlay.onPointerDown(rowCentreX, tileRowCentreY)
        overlay.cancelGesture()
        assertEquals(SettingsVerdict.None, overlay.onPointerUp(rowCentreX, tileRowCentreY))
    }
}
