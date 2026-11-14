package inego.zungjunggame.features.settings

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.net.NotificationPrefs
import inego.zungjunggame.net.NotificationPrefsScale
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.*
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * What a completed press on the Settings card resolved, in the card's own
 * vocabulary. A checkbox row reports its stable row id; the volume slider reports
 * its value on the press and on each drag step that crosses to a new step, so the
 * value travels the action channel instead of being smuggled through a row payload.
 */
sealed interface SettingsVerdict {
    /** A checkbox row, by its stable row id ([SettingsOverlay.TILE_LABELS_ROW], …). */
    data class Row(val id: String) : SettingsVerdict

    /** The volume slider's value, reported by the press and each step-crossing drag. */
    data class Volume(val value: Int) : SettingsVerdict

    /**
     * The digest-interval slider's step, reported once on release. Unlike the
     * volume slider it does not stream each drag step: the step is a server write,
     * so the card commits one value per gesture.
     */
    data class DigestInterval(val step: Int) : SettingsVerdict

    /** The top-right close control was tapped. */
    data object Close : SettingsVerdict

    /** A press that resolved no control. */
    data object None : SettingsVerdict
}

/** GL-free snapshot of the settings card's bounds and controls. */
data class SettingsGeometry(
    val panel: Rect?,
    val tileLabelsRow: Rect? = null,
    val soundsRow: Rect? = null,
    val volumeTrack: Rect? = null,
    val emailRow: Rect? = null,
    val digestTrack: Rect? = null,
    val closeButton: Rect? = null,
)

/**
 * Settings panel: app preferences that are **not** game rules.
 *
 * Rules (rounds, chicken hands) live in [inego.zungjunggame.features.local.NewGameSetupOverlay] and are committed
 * per game; this panel holds display choices that apply everywhere — online
 * tables and tutorial lessons included — and must therefore be reachable from
 * the root menu with no game in progress.
 *
 * It carries a checkbox row per boolean display option (**Show tile labels**,
 * **Sounds**) and a **Volume** slider. The row set is data, not chrome: adding
 * the next display option means adding one [drawCheckRow] cell, not another
 * modal.
 *
 * Neither control owns its value. The checkbox renders whatever [render] is
 * handed and a row release reports its id as a [SettingsVerdict.Row]; the slider's
 * value is the model's, handed in each frame, and its press/drag steps report a
 * [SettingsVerdict.Volume]. The preference itself is model state (`ui.prefs`),
 * read through [SettingsComponent], so a control and the value it commits cannot
 * disagree — the defect the old read-back draft allowed.
 *
 * Opened from the root menu row; Esc / outside tap dismisses back to the menu
 * (routed by the platform input processor).
 */
class SettingsOverlay : Disposable, GestureSurface<SettingsVerdict> {
    private val glyphLayout = GlyphLayout()

    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var rowLeft = 0f
    private var rowW = 0f
    private var rowH = 0f
    private var tileRowH = 0f
    private var soundsRowH = 0f
    private var emailRowH = 0f
    private var tileRowBottom = 0f
    private var soundsRowBottom = 0f
    private var emailRowBottom = 0f

    /** The notification section (email opt-in + digest interval) is on this frame. */
    private var notificationsVisible = false

    /** The volume control. Its own geometry, value, and drag state live in the widget. */
    val slider = Slider(
        min = 0,
        max = UiPreferences.MAX_SOUND_VOLUME,
        initialValue = UiPreferences.DEFAULT_SOUND_VOLUME,
    )

    /** The digest-interval control; each step is one allowed cadence. */
    val digestSlider = Slider(
        min = 0,
        max = NotificationPrefsScale.MAX_STEP,
        initialValue = NotificationPrefsScale.stepFor(NotificationPrefs.DEFAULT.digestIntervalHours),
    )

    private val closeButton = CloseButton()

    /** Press began on a checkbox row: a release there is a tap, not a drag. */
    private var pressRow: String? = null

    /** Press began on the top-right close control. */
    private var pressClose = false

    /** The slider value the press set, or null when the press did not claim the slider. */
    private var pressVolumeValue: Int? = null

    /** The digest slider's value the press set, or null when it did not claim the press. */
    private var pressDigestStep: Int? = null

    private var pressY = Float.NaN
    private var dragged = false

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /** True when the point is on the top-right close button. */
    fun isOnCloseButton(worldX: Float, worldY: Float): Boolean =
        closeButton.contains(worldX, worldY)

    /** True when the point is on the tile-label checkbox row. */
    fun isOnTileLabelRow(worldX: Float, worldY: Float): Boolean =
        isOnRow(worldX, worldY, tileRowBottom, tileRowH)

    /** True when the point is on the sounds checkbox row. */
    fun isOnSoundsRow(worldX: Float, worldY: Float): Boolean =
        isOnRow(worldX, worldY, soundsRowBottom, soundsRowH)

    /** True when the point is on the email opt-in row (only while the section is shown). */
    fun isOnEmailRow(worldX: Float, worldY: Float): Boolean =
        notificationsVisible && isOnRow(worldX, worldY, emailRowBottom, emailRowH)

    /** True when the point is on the digest-interval slider (only while the section is shown). */
    fun isOnDigestSlider(worldX: Float, worldY: Float): Boolean =
        notificationsVisible && digestSlider.contains(worldX, worldY)

    private fun isOnRow(worldX: Float, worldY: Float, bottom: Float, height: Float): Boolean {
        if (height <= 0f) return false
        return worldX >= rowLeft && worldX <= rowLeft + rowW &&
            worldY >= bottom && worldY <= bottom + height
    }

    /**
     * Pure snapshot of the card the last [render] laid out (or the last
     * [seedLayoutForTest] installed). No GL / fonts.
     */
    fun measureGeometry(): SettingsGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        val tile = if (tileRowH > 0f && rowW > 0f) {
            Rect(rowLeft, tileRowBottom, rowW, tileRowH)
        } else {
            null
        }
        val sounds = if (soundsRowH > 0f && rowW > 0f) {
            Rect(rowLeft, soundsRowBottom, rowW, soundsRowH)
        } else {
            null
        }
        val track = if (slider.width > 0f && slider.height > 0f) {
            Rect(slider.left, slider.bottom, slider.width, slider.height)
        } else {
            null
        }
        val email = if (notificationsVisible && emailRowH > 0f && rowW > 0f) {
            Rect(rowLeft, emailRowBottom, rowW, emailRowH)
        } else {
            null
        }
        val digest = if (notificationsVisible && digestSlider.width > 0f && digestSlider.height > 0f) {
            Rect(digestSlider.left, digestSlider.bottom, digestSlider.width, digestSlider.height)
        } else {
            null
        }
        val close = if (closeButton.side > 0f) {
            Rect(closeButton.leftX, closeButton.bottomY, closeButton.side, closeButton.side)
        } else {
            null
        }
        return SettingsGeometry(panel, tile, sounds, track, email, digest, close)
    }

    /** No scroll region: the panel is sized to its rows. */
    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean = false

    override fun onPointerDown(x: Float, y: Float): Boolean {
        // The card owns gestures only within its own panel; a press outside it
        // falls through to the layer's outside-tap path.
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressClose = true
            pressY = y
            dragged = false
            TouchDiagnostics.log("set down close x=${TouchDiagnostics.fmt(x)} y=${TouchDiagnostics.fmt(y)}")
            return true
        }
        val sliderValue = onSliderPointerDown(x, y)
        pressVolumeValue = sliderValue
        val digestValue = if (sliderValue == null) onDigestSliderPointerDown(x, y) else null
        pressDigestStep = digestValue
        pressRow = when {
            sliderValue != null -> null
            digestValue != null -> null
            isOnTileLabelRow(x, y) -> TILE_LABELS_ROW
            isOnSoundsRow(x, y) -> SOUNDS_ROW
            isOnEmailRow(x, y) -> EMAIL_OPT_IN_ROW
            else -> null
        }
        pressY = y
        dragged = false
        TouchDiagnostics.log(
            "set down x=${TouchDiagnostics.fmt(x)} y=${TouchDiagnostics.fmt(y)} " +
                "row=${pressRow ?: "none"} slider=${sliderValue ?: "none"} " +
                "digest=${digestValue ?: "none"} " +
                "slop=${TouchDiagnostics.fmt(tapSlopPx())} uiScale=${TouchDiagnostics.fmt(Fonts.uiScale)}",
        )
        return true
    }

    /** A slider press already set a value; emit it before the drag steps that follow. */
    override fun pressVerdict(): SettingsVerdict? =
        pressVolumeValue?.let { SettingsVerdict.Volume(it) }

    override fun onPointerDrag(x: Float, y: Float): SettingsVerdict? {
        if (pressY.isNaN()) return null
        val dy = abs(y - pressY)
        val wasDragged = dragged
        if (dy > tapSlopPx()) dragged = true
        if (dragged && !wasDragged) {
            TouchDiagnostics.log(
                "set drag-cancel dy=${TouchDiagnostics.fmt(dy)} y=${TouchDiagnostics.fmt(y)} " +
                    "slop=${TouchDiagnostics.fmt(tapSlopPx())}",
            )
        }
        if (pressDigestStep != null) {
            // The digest interval is a server write; it commits on release, not per drag step.
            digestSlider.onPointerDrag(x)
            return null
        }
        if (pressVolumeValue == null) return null
        return onSliderPointerDrag(x)?.let { SettingsVerdict.Volume(it) }
    }

    /**
     * The card carries checkbox rows and the volume slider. The overlay owns the
     * press state machine (slop, press origin); the caller owns the *values*, which
     * are model state. A completed row tap reports the row it selected; the slider
     * only ends its drag here — its value already travelled the press/drag channels.
     */
    override fun onPointerUp(x: Float, y: Float): SettingsVerdict {
        if (pressClose) {
            val hit = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y)
            clearPress()
            return if (hit) SettingsVerdict.Close else SettingsVerdict.None
        }
        if (pressVolumeValue != null) {
            onSliderPointerUp()
            clearPress()
            return SettingsVerdict.None
        }
        if (pressDigestStep != null) {
            // A tap on the track and a drag both commit the value under the finger.
            val step = digestSlider.value
            digestSlider.onPointerUp()
            clearPress()
            return SettingsVerdict.DigestInterval(step)
        }
        val resolvedRow = pressRow?.takeIf { !dragged }
        TouchDiagnostics.log(
            "set up x=${TouchDiagnostics.fmt(x)} y=${TouchDiagnostics.fmt(y)} " +
                "row=${resolvedRow ?: "none"} dragged=$dragged",
        )
        clearPress()
        return resolvedRow?.let(SettingsVerdict::Row) ?: SettingsVerdict.None
    }

    override fun cancelGesture() {
        onSliderCancelDrag()
        digestSlider.clearDrag()
        clearPress()
    }

    // --- Volume slider delegation (the widget owns its press machine) ---

    private fun onSliderPointerDown(worldX: Float, worldY: Float): Int? {
        if (!slider.contains(worldX, worldY)) return null
        return slider.onPointerDown(worldX, worldY)
    }

    private fun onSliderPointerDrag(worldX: Float): Int? =
        slider.onPointerDrag(worldX)

    private fun onSliderPointerUp(): Int? {
        val finalVal = if (slider.isDragging) slider.value else null
        slider.onPointerUp()
        return finalVal
    }

    private fun onSliderCancelDrag() {
        slider.clearDrag()
    }

    private fun onDigestSliderPointerDown(worldX: Float, worldY: Float): Int? {
        if (!notificationsVisible || !digestSlider.contains(worldX, worldY)) return null
        return digestSlider.onPointerDown(worldX, worldY)
    }

    private fun clearPress() {
        pressRow = null
        pressClose = false
        pressVolumeValue = null
        pressDigestStep = null
        pressY = Float.NaN
        dragged = false
    }

    fun render(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        /** Checkbox value for this frame; the caller reads it from the model. */
        showTileLabels: Boolean = UiPreferences.DEFAULT.showTileLabels,
        /** Master sound switch; drives the sounds checkbox and the slider's muted look. */
        soundsEnabled: Boolean = UiPreferences.DEFAULT.soundsEnabled,
        /** Sound level for this frame; the caller reads it from the model. */
        soundVolume: Int = UiPreferences.DEFAULT.soundVolume,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        /** The account's notification settings are shown (the player is signed in). */
        notificationsVisible: Boolean = false,
        /** Email opt-in for this frame; the caller reads it from the notification model. */
        emailOptIn: Boolean = NotificationPrefs.DEFAULT.emailOptIn,
        /** Hours between result digests for this frame; the caller reads it from the model. */
        digestIntervalHours: Int = NotificationPrefs.DEFAULT.digestIntervalHours,
    ) {
        this.notificationsVisible = notificationsVisible
        Fonts.ensureCreated()

        val px = whitePixel()
        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)

        val titleFont = Fonts.labelActive
        val rowFont = Fonts.button
        val title = L10n.get("settings.title")
        val tileLabelsLabel = L10n.get("settings.tile_labels")
        val soundsLabel = L10n.get("settings.sounds")
        val volumeLabel = L10n.get("settings.volume")
        val emailLabel = L10n.get("settings.email_opt_in")
        val digestLabel = L10n.get("settings.digest_interval")

        val padX = Fonts.px(28f)
        val padY = Fonts.px(20f)
        val titleGap = Fonts.px(16f)
        rowH = max(Fonts.px(44f), rowFont.lineHeight + Fonts.px(18f))
        val sliderH = max(Fonts.px(58f), rowFont.lineHeight + Fonts.px(38f))
        val rowGap = Fonts.px(10f)

        // Row ink: checkbox (see [drawCheckRow]) + gap + label.
        val checkBox = Fonts.px(22f)
        val checkGap = Fonts.px(14f)
        fun checkRowWidth(label: String): Float {
            glyphLayout.setText(rowFont, label)
            return Fonts.px(4f) + checkBox + checkGap + glyphLayout.width
        }
        val rowContentW = max(
            checkRowWidth(tileLabelsLabel),
            max(
                checkRowWidth(soundsLabel),
                checkRowWidth(emailLabel),
            ),
        )

        // A slider header is the label on the left and the value text on the right,
        // each inset by the widget's own padding. Both share one line, so the panel
        // must be wide enough for the widest header it draws or the two collide.
        fun sliderHeaderRunW(label: String, value: String): Float {
            glyphLayout.setText(rowFont, label)
            val labelW = glyphLayout.width
            glyphLayout.setText(rowFont, value)
            val valueW = glyphLayout.width
            return labelW + (if (value.isEmpty()) 0f else Fonts.px(16f)) + valueW
        }
        fun sliderHeaderWidth(label: String, value: String): Float =
            Fonts.px(14f) * 2f + sliderHeaderRunW(label, value)
        // The digest row is only drawn while the notification section is shown, so
        // size for it only then; the value text changes with the step, so measure the
        // widest cadence to keep the panel from resizing as the slider moves.
        val digestHeaderRunW = if (notificationsVisible) {
            (digestSlider.min..digestSlider.max).maxOf { step ->
                sliderHeaderRunW(digestLabel, digestIntervalText(step))
            }
        } else {
            0f
        }
        val digestHeaderW = if (notificationsVisible) {
            Fonts.px(14f) * 2f + digestHeaderRunW
        } else {
            0f
        }
        val sliderContentW = max(sliderHeaderWidth(volumeLabel, ""), digestHeaderW)

        glyphLayout.setText(titleFont, title)
        val contentW = max(glyphLayout.width, max(rowContentW, sliderContentW))

        val avail = OverlayViewport.available(screenW, screenH)
        val portrait = screenH > screenW
        val maxPanelW = OverlayViewport.maxPanelWidth(
            screenW * (if (portrait) 0.88f else 0.72f),
            avail,
            screenW,
            Fonts.px(24f),
        )
        panelW = max(Fonts.px(280f), contentW + padX * 2f)
            .coerceAtMost(maxPanelW)
            .coerceAtMost(avail.width)
        // Row labels wrap to the row's text width: a translation longer than the
        // panel grows its row instead of running past the rim.
        val rowLabelInset = Fonts.px(4f) + checkBox + checkGap
        val rowLabelAvail = (panelW - padX * 2f - rowLabelInset).coerceAtLeast(1f)
        fun wrappedRowH(label: String): Float {
            glyphLayout.setText(rowFont, label, Color.WHITE, rowLabelAvail, Align.left, true)
            return max(rowH, glyphLayout.height + Fonts.px(18f))
        }
        tileRowH = wrappedRowH(tileLabelsLabel)
        soundsRowH = wrappedRowH(soundsLabel)
        emailRowH = if (notificationsVisible) wrappedRowH(emailLabel) else 0f
        // Keep each slider's header fitted to the widest value it can show, so
        // the scale does not breathe as the digest cadence changes.
        slider.headerFitWidth = sliderHeaderRunW(volumeLabel, "")
        digestSlider.headerFitWidth = digestHeaderRunW
        val rowsH = tileRowH + rowGap + soundsRowH + rowGap + sliderH
        val notifH = if (notificationsVisible) {
            rowGap + emailRowH + rowGap + sliderH
        } else {
            0f
        }
        panelH = padY * 2f + titleFont.lineHeight + titleGap + rowsH + notifH
        val placed = OverlayViewport.placeCentered(panelW, panelH, avail)
        panelLeft = placed.first
        panelBottom = placed.second

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH)
        closeButton.render(
            batch = batch,
            whitePixel = px,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
        )

        glyphLayout.setText(titleFont, title)
        titleFont.draw(
            batch,
            title,
            panelLeft + (panelW - glyphLayout.width) * 0.5f,
            panelBottom + panelH - padY,
        )

        // Rows read top-down after the title: tile labels, sounds, then volume.
        rowLeft = panelLeft + padX
        this.rowW = (panelW - padX * 2f).coerceAtLeast(Fonts.px(24f))
        var top = panelBottom + panelH - padY - titleFont.lineHeight - titleGap

        tileRowBottom = top - tileRowH
        drawCheckRow(
            batch, px, tileLabelsLabel, showTileLabels, tileRowBottom,
            isOnTileLabelRow(pointerWorldX, pointerWorldY),
            rowHeight = tileRowH,
        )
        top = tileRowBottom - rowGap

        soundsRowBottom = top - soundsRowH
        drawCheckRow(
            batch, px, soundsLabel, soundsEnabled, soundsRowBottom,
            isOnSoundsRow(pointerWorldX, pointerWorldY),
            rowHeight = soundsRowH,
        )
        top = soundsRowBottom - rowGap

        slider.label = volumeLabel
        slider.valueText = ""
        slider.stepLabels = emptyList()
        if (!slider.isDragging) slider.setValue(soundVolume)
        slider.layout(rowLeft, top - sliderH, this.rowW, sliderH)
        slider.render(
            batch,
            px,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
            muted = !soundsEnabled,
        )

        if (notificationsVisible) {
            top = top - sliderH - rowGap
            emailRowBottom = top - emailRowH
            drawCheckRow(
                batch, px, emailLabel, emailOptIn, emailRowBottom,
                isOnEmailRow(pointerWorldX, pointerWorldY),
                rowHeight = emailRowH,
            )
            top = emailRowBottom - rowGap

            digestSlider.label = digestLabel
            digestSlider.stepLabels = emptyList()
            if (!digestSlider.isDragging) {
                digestSlider.setValue(NotificationPrefsScale.stepFor(digestIntervalHours))
            }
            digestSlider.valueText = digestIntervalText(digestSlider.value)
            digestSlider.layout(rowLeft, top - sliderH, this.rowW, sliderH)
            digestSlider.render(
                batch,
                px,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
            )
        }
    }

    /**
     * The digest slider's right-hand description for [step]: the cadence the step
     * selects, with the daily and twice-daily steps named rather than counted.
     */
    private fun digestIntervalText(step: Int): String = when (NotificationPrefsScale.hoursFor(step)) {
        1 -> L10n.get("settings.digest_interval_hourly")
        12 -> L10n.get("settings.digest_interval_twice_daily")
        24 -> L10n.get("settings.digest_interval_daily")
        else -> L10n.format("settings.digest_interval_hours", NotificationPrefsScale.hoursFor(step))
    }

    /**
     * Square checkbox + label, drawn like the chicken-hand row in
     * [inego.zungjunggame.features.local.NewGameSetupOverlay] so a checkbox reads the same in both panels. The row
     * rect is tracked beside it for hit-testing — the rect exists only on frames
     * the row is painted.
     */
    private fun drawCheckRow(
        batch: SpriteBatch,
        px: Texture,
        label: String,
        checked: Boolean,
        rowBottom: Float,
        hovered: Boolean,
        rowHeight: Float,
    ) {
        if (hovered) {
            batch.setColor(AppPalette.plateRaised)
            batch.draw(px, rowLeft, rowBottom, rowW, rowHeight)
            batch.setColor(Color.WHITE)
        }

        val box = Fonts.px(22f)
        val boxLeft = rowLeft + Fonts.px(4f)
        val boxBottom = rowBottom + (rowHeight - box) * 0.5f
        val outline = max(1.5f, Fonts.px(2f))
        UiPlate.drawFrame(
            batch, px, boxLeft, boxBottom, box, box,
            0.90f, 0.88f, 0.78f, 0.95f, outline,
        )
        if (checked) {
            val inset = Fonts.px(5f)
            batch.setColor(AppPalette.success)
            batch.draw(px, boxLeft + inset, boxBottom + inset, box - inset * 2f, box - inset * 2f)
        }
        batch.setColor(Color.WHITE)

        val font = Fonts.button
        val labelLeft = boxLeft + box + Fonts.px(14f)
        val labelAvail = (rowLeft + rowW - labelLeft).coerceAtLeast(1f)
        val labelColor =
            if (hovered) AppPalette.inkStrong else AppPalette.ink
        glyphLayout.setText(font, label, labelColor, labelAvail, Align.left, true)
        font.draw(
            batch,
            glyphLayout,
            labelLeft,
            rowBottom + (rowHeight + glyphLayout.height) * 0.5f,
        )
    }

    /**
     * Geometry seam for plain JVM tests: hit-testing must be provable without a
     * GL frame (see `SettingsOverlayTest`).
     */
    internal fun seedLayoutForTest(
        panelLeft: Float = 200f,
        panelBottom: Float = 12f,
        panelW: Float = 400f,
        panelH: Float = 530f,
        notificationsVisible: Boolean = true,
    ) {
        this.panelLeft = panelLeft
        this.panelBottom = panelBottom
        this.panelW = panelW
        this.panelH = panelH
        this.rowLeft = panelLeft + 28f
        this.rowW = panelW - 56f
        this.rowH = 60f
        this.tileRowH = rowH
        this.soundsRowH = rowH
        this.emailRowH = rowH
        this.notificationsVisible = notificationsVisible
        this.tileRowBottom = panelBottom + 20f
        this.soundsRowBottom = tileRowBottom + rowH + 10f
        val sliderBottom = soundsRowBottom + rowH + 10f
        slider.layout(rowLeft, sliderBottom, rowW, 58f)
        this.emailRowBottom = sliderBottom + 58f + 10f
        digestSlider.layout(rowLeft, emailRowBottom + rowH + 10f, rowW, 58f)
        closeButton.layoutAt(panelLeft + panelW - 50f, panelBottom + 420f - 50f, 36f)
    }

    override fun dispose() {
    }

    companion object {
        /**
         * The row ids this card carries, as the ids its releases report. The feature
         * maps them to actions, so the widget never names an action id.
         */
        const val TILE_LABELS_ROW = "tileLabels"
        const val SOUNDS_ROW = "sounds"
        /** The Score Breakdown email opt-in row (shown only while signed in). */
        const val EMAIL_OPT_IN_ROW = "emailOptIn"
    }
}
