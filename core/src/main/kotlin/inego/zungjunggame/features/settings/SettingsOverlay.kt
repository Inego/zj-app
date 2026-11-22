package inego.zungjunggame.features.settings

import com.badlogic.gdx.graphics.Camera
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
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * What a completed press on the Settings card resolved, in the card's own
 * vocabulary. A checkbox row reports its stable row id; the volume slider reports
 * its value when a horizontal drag claims it and on each step-crossing drag, so the
 * value travels the action channel instead of being smuggled through a row payload.
 */
sealed interface SettingsVerdict {
    /** A checkbox row, by its stable row id ([SettingsOverlay.TILE_LABELS_ROW], …). */
    data class Row(val id: String) : SettingsVerdict

    /** The volume slider's value, reported when a horizontal drag claims it and each step-crossing drag. */
    data class Volume(val value: Int) : SettingsVerdict

    /**
     * The digest-interval slider's step, reported once on release. Unlike the
     * volume slider it does not stream each drag step: the step is a server write,
     * so the card commits one value per gesture.
     */
    data class DigestInterval(val step: Int) : SettingsVerdict

    /** The top-right close control was tapped. */
    data object Close : SettingsVerdict

    /**
     * The card's offset: reported on each drag step and once more when a drag
     * settles ([settled] true), so the owner stores where the player left the
     * card.
     */
    data class BodyScroll(val offset: Float, val settled: Boolean = false) : SettingsVerdict

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
 * value is the model's, handed in each frame, and a horizontal drag reports a
 * [SettingsVerdict.Volume]. The preference itself is model state (`ui.prefs`),
 * read through [SettingsComponent], so a control and the value it commits cannot
 * disagree — the defect the old read-back draft allowed.
 *
 * The whole card scrolls as one column ([OverlayBodyScroll.fitWholeCard]): the
 * title, the close control and the rows share one offset and one bar, so in a
 * short landscape window — or once the signed-in notification section is shown —
 * the column moves under the card's rim rather than pushing a row off-screen.
 * The title and the close therefore scroll out of view; the rim and the bar are
 * the only fixed parts. The offset is the owner's: [render] is handed it and a
 * drag reports where it moved.
 * The gesture is **directional** and shared ([GestureAxis]): a vertical pan
 * anywhere on the card scrolls it, including over a slider, and only a
 * sideways-dominant drag takes a slider's value — otherwise a pan that happens
 * to start on the volume row would be dead.
 *
 * Opened from the root menu row; Esc / outside tap dismisses back to the menu
 * (routed by the platform input processor).
 */
class SettingsOverlay : Disposable, GestureSurface<SettingsVerdict> {
    private val glyphLayout = GlyphLayout()
    private val form = OverlayForm()
    private val body = form.body

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

    /** Which slider a press that landed on one may still turn out to be. */
    private enum class SliderTarget { Volume, Digest }

    /**
     * The press's direction: a vertical pan over a slider belongs to the body,
     * and only a sideways-dominant drag takes the slider. The rule is shared
     * ([GestureAxis]); this card only maps its answer to its own controls.
     */
    private val axis = GestureAxis()

    /** Press began on a checkbox row: a release there is a tap, not a drag. */
    private var pressRow: String? = null

    /** Press began on the top-right close control. */
    private var pressClose = false

    /**
     * The slider under the press. It does **not** take the value until the drag
     * proves sideways: a vertical pan over a slider belongs to the body.
     */
    private var pressSlider: Slider? = null
    private var pressSliderTarget: SliderTarget? = null

    /** The press began on the scrolling card, not on a control. */
    private var pressBody = false

    /** Offset the card held when that press began, so a no-op drag reports nothing. */
    private var pressScrollStart = 0f

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /** The offset the card is scrolled down (0 = top), read by the gesture bridge. */
    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Advance the card's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

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

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        body.wheel(amountY)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        // A new gesture owns the card, not the one before it.
        body.cancel()
        clearPress()
        // The card owns gestures only within its own panel; a press outside it
        // falls through to the layer's outside-tap path.
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressClose = true
            TouchDiagnostics.log("set down close x=${TouchDiagnostics.fmt(x)} y=${TouchDiagnostics.fmt(y)}")
            return true
        }
        axis.begin(x, y)
        // Arm the card's viewport on every press that lands in it, so a
        // scrollbar grab follows the finger at once. A content drag stays still
        // until the finger commits to the vertical axis — see [onPointerDrag].
        pressBody = body.contains(x, y)
        if (pressBody) {
            val before = body.scrollY
            body.beginPointer(x, y)
            pressScrollStart = before
        }
        when {
            slider.contains(x, y) -> {
                pressSlider = slider
                pressSliderTarget = SliderTarget.Volume
            }
            notificationsVisible && digestSlider.contains(x, y) -> {
                pressSlider = digestSlider
                pressSliderTarget = SliderTarget.Digest
            }
            else -> pressRow = when {
                isOnTileLabelRow(x, y) -> TILE_LABELS_ROW
                isOnSoundsRow(x, y) -> SOUNDS_ROW
                isOnEmailRow(x, y) -> EMAIL_OPT_IN_ROW
                else -> null
            }
        }
        TouchDiagnostics.log(
            "set down x=${TouchDiagnostics.fmt(x)} y=${TouchDiagnostics.fmt(y)} " +
                "row=${pressRow ?: "none"} slider=${pressSliderTarget ?: "none"} " +
                "body=$pressBody slop=${TouchDiagnostics.fmt(tapSlopPx())} " +
                "uiScale=${TouchDiagnostics.fmt(Fonts.uiScale)}",
        )
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): SettingsVerdict? {
        if (!axis.started) return null
        val previous = axis.owner
        val owner = axis.resolve(
            x = x,
            y = y,
            sideAvailable = pressSlider != null,
            scrollAvailable = pressBody,
            scrollGrabbed = body.isScrollbarDrag,
        )
        if (owner != previous && owner != GestureAxis.Owner.Undecided) {
            // A slider owns this one; the body's armed press is not a scroll.
            if (owner == GestureAxis.Owner.Side) body.cancel()
            TouchDiagnostics.log(
                "set drag start target=$owner dx=${TouchDiagnostics.fmt(axis.dx)} " +
                    "dy=${TouchDiagnostics.fmt(axis.dy)} slop=${TouchDiagnostics.fmt(tapSlopPx())}",
            )
        }
        return when (owner) {
            GestureAxis.Owner.Side -> {
                val slider = pressSlider ?: return null
                // The sliding gesture begins where the sideways intent is
                // proved: the value under the finger when the lock happens.
                val next = if (slider.isDragging) {
                    slider.onPointerDrag(x)
                } else {
                    slider.onPointerDown(x, y)
                } ?: return null
                if (pressSliderTarget == SliderTarget.Volume) {
                    SettingsVerdict.Volume(next)
                } else {
                    // The digest interval is a server write; it commits on release.
                    null
                }
            }
            GestureAxis.Owner.Scroll -> {
                val offset = body.dragTo(y)
                // A thumb drag owns the offset without being a content drag, so
                // both must stream it or the card would jump on release.
                if (body.isDrag || body.isScrollbarDrag) SettingsVerdict.BodyScroll(offset) else null
            }
            GestureAxis.Owner.Undecided -> null
        }
    }

    /**
     * The card carries checkbox rows and sliders. The overlay owns the press state
     * machine (direction, slop, press origin); the caller owns the *values*, which
     * are model state. A completed row tap reports the row it selected; a
     * horizontal slider drag streams its value, and the digest interval commits
     * once on release.
     */
    override fun onPointerUp(x: Float, y: Float): SettingsVerdict {
        if (pressClose) {
            val hit = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y)
            clearPress()
            return if (hit) SettingsVerdict.Close else SettingsVerdict.None
        }
        if (axis.owner == GestureAxis.Owner.Side) {
            val target = pressSliderTarget
            val value = pressSlider?.value
            pressSlider?.onPointerUp()
            clearPress()
            return when {
                value == null -> SettingsVerdict.None
                // The volume already travelled the drag channel; only the server
                // write the digest interval carries is committed once, here.
                target == SliderTarget.Digest -> SettingsVerdict.DigestInterval(value)
                else -> SettingsVerdict.None
            }
        }
        // A press that never passed the slop is still the tap it started: on a
        // slider it jumps to the release point, on a row it selects the row.
        val slider = pressSlider
        if (axis.owner == GestureAxis.Owner.Undecided && slider != null) {
            val target = pressSliderTarget
            val value = if (x.isNaN() || y.isNaN()) null else slider.onPointerDown(x, y)
            slider.onPointerUp()
            body.cancel()
            clearPress()
            return when {
                value == null -> SettingsVerdict.None
                target == SliderTarget.Digest -> SettingsVerdict.DigestInterval(value)
                else -> SettingsVerdict.Volume(value)
            }
        }
        if (pressBody) {
            // Ends a content drag or the scrollbar grab. A bar tap that jumped the
            // thumb is a scroll too, even though the finger never travelled.
            body.endPointer()
            val settled = body.scrollY
            val start = pressScrollStart
            val resolvedRow = pressRow?.takeIf { !axis.moved }
            clearPress()
            return when {
                settled != start -> SettingsVerdict.BodyScroll(settled, settled = true)
                resolvedRow != null -> SettingsVerdict.Row(resolvedRow)
                else -> SettingsVerdict.None
            }
        }
        val resolvedRow = pressRow?.takeIf { !axis.moved }
        TouchDiagnostics.log(
            "set up x=${TouchDiagnostics.fmt(x)} y=${TouchDiagnostics.fmt(y)} " +
                "row=${resolvedRow ?: "none"} moved=${axis.moved}",
        )
        clearPress()
        return resolvedRow?.let(SettingsVerdict::Row) ?: SettingsVerdict.None
    }

    override fun cancelGesture() {
        slider.clearDrag()
        digestSlider.clearDrag()
        body.cancel()
        clearPress()
    }

    private fun clearPress() {
        pressRow = null
        pressClose = false
        pressSlider = null
        pressSliderTarget = null
        axis.clear()
        pressBody = false
        pressScrollStart = 0f
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        /** The card's offset the owner stored; 0 = top. */
        scrollY: Float = 0f,
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
        val contentW = max(Fonts.textWidth(titleFont, title), max(rowContentW, sliderContentW))
        // Keep each slider's header fitted to the widest value it can show, so
        // the scale does not breathe as the digest cadence changes.
        slider.headerFitWidth = sliderHeaderRunW(volumeLabel, "")
        digestSlider.headerFitWidth = digestHeaderRunW

        val closeInset = CloseButton.cornerInset()
        val closeSide = CloseButton.defaultSide()
        val spec = OverlayForm.Spec(
            title = title,
            minWidth = Fonts.px(280f),
            chrome = OverlayForm.Chrome.WholeCard,
            closeWidth = closeInset + closeSide + Fonts.px(10f),
            closeHeight = closeSide,
            sections = { rhythm, rowWidth ->
                val labelInset = Fonts.px(4f) + checkBox + checkGap
                val labelAvail = (rowWidth - labelInset).coerceAtLeast(1f)
                fun wrappedRowH(label: String): Float {
                    glyphLayout.setText(rowFont, label, Color.WHITE, labelAvail, Align.left, true)
                    return max(rhythm.rowH, glyphLayout.height + Fonts.px(18f))
                }
                val rows = ArrayList<Float>(5)
                rows += wrappedRowH(tileLabelsLabel)
                rows += wrappedRowH(soundsLabel)
                rows += sliderH
                if (notificationsVisible) {
                    rows += wrappedRowH(emailLabel)
                    rows += sliderH
                }
                listOf(
                    OverlayForm.Section(
                        label = null,
                        contentWidth = contentW,
                        rows = rows,
                        groupPad = 0f,
                        rowGap = rowGap,
                    ),
                )
            },
        )
        val geometry = form.layout(screenW, screenH, spec, scrollY)
        val panel = geometry.panel
        panelLeft = panel.left
        panelBottom = panel.bottom
        panelW = panel.width
        panelH = panel.height

        // Row rects are the form's; the hit-test predicates read them, so a press
        // and the pixel it sees can never disagree.
        val rows = geometry.sections.firstOrNull()?.rows.orEmpty()
        val tileRect = rows.getOrNull(0)
        val soundsRect = rows.getOrNull(1)
        val volumeRect = rows.getOrNull(2)
        val emailRect = if (notificationsVisible) rows.getOrNull(3) else null
        val digestRect = if (notificationsVisible) rows.getOrNull(4) else null
        if (tileRect != null) {
            rowLeft = tileRect.left
            rowW = tileRect.width
            rowH = tileRect.height
            tileRowH = tileRect.height
            tileRowBottom = tileRect.bottom
        }
        if (soundsRect != null) {
            soundsRowH = soundsRect.height
            soundsRowBottom = soundsRect.bottom
        }
        emailRowH = emailRect?.height ?: 0f
        emailRowBottom = emailRect?.bottom ?: 0f

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        // Rows read top-down under the title: tile labels, sounds, volume, then
        // the signed-in notification section. They live in the scroll column with
        // the title, so a short window scrolls instead of pushing a row off-screen.
        val hoverX = body.hoverX(pointerWorldX)
        val hoverY = body.hoverY(pointerWorldY)
        body.drawBody(batch, camera, px, pointerWorldX, pointerWorldY) {
            // Chrome belongs to the column: it sits at the content's top and is
            // clipped with the rows by the card's inner box.
            glyphLayout.setText(titleFont, title)
            titleFont.draw(
                batch,
                title,
                geometry.title.left + (geometry.title.width - glyphLayout.width) * 0.5f,
                geometry.header.top,
            )

            geometry.trailing?.let { trailing ->
                closeButton.layoutAt(trailing.left, trailing.bottom, closeSide)
                closeButton.render(
                    batch = batch,
                    whitePixel = px,
                    pointerWorldX = pointerWorldX,
                    pointerWorldY = pointerWorldY,
                )
            }

            if (tileRect != null) {
                drawCheckRow(
                    batch, px, tileLabelsLabel, showTileLabels, tileRect.bottom,
                    isOnTileLabelRow(hoverX, hoverY),
                    rowHeight = tileRect.height,
                )
            }

            if (soundsRect != null) {
                drawCheckRow(
                    batch, px, soundsLabel, soundsEnabled, soundsRect.bottom,
                    isOnSoundsRow(hoverX, hoverY),
                    rowHeight = soundsRect.height,
                )
            }

            if (volumeRect != null) {
                slider.label = volumeLabel
                slider.valueText = ""
                slider.stepLabels = emptyList()
                if (!slider.isDragging) slider.setValue(soundVolume)
                slider.layout(volumeRect.left, volumeRect.bottom, volumeRect.width, volumeRect.height)
                slider.render(
                    batch,
                    px,
                    pointerWorldX = hoverX,
                    pointerWorldY = hoverY,
                    muted = !soundsEnabled,
                )
            }

            if (emailRect != null) {
                drawCheckRow(
                    batch, px, emailLabel, emailOptIn, emailRect.bottom,
                    isOnEmailRow(hoverX, hoverY),
                    rowHeight = emailRect.height,
                )
            }

            if (digestRect != null) {
                digestSlider.label = digestLabel
                digestSlider.stepLabels = emptyList()
                if (!digestSlider.isDragging) {
                    digestSlider.setValue(NotificationPrefsScale.stepFor(digestIntervalHours))
                }
                digestSlider.valueText = digestIntervalText(digestSlider.value)
                digestSlider.layout(digestRect.left, digestRect.bottom, digestRect.width, digestRect.height)
                digestSlider.render(
                    batch,
                    px,
                    pointerWorldX = hoverX,
                    pointerWorldY = hoverY,
                )
            }
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
        // The rows live in the card's scroll column in [render]; mirror that so a
        // press on chrome takes the body branch and a control press keeps its own
        // gesture.
        body.layout(panelLeft, panelBottom, panelW, panelH, contentHeight = panelH, scrollY = 0f)
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
