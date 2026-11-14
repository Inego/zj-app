package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Horizontal slider component for selecting discrete integer values (e.g. 0..3).
 *
 * Supports discrete click/tap anywhere on the track, continuous dragging,
 * tick marks with step labels, hover feedback, and disabled/muted state.
 */
class Slider(
    val min: Int = 0,
    val max: Int = 3,
    initialValue: Int = 1,
) {
    init {
        require(max > min) { "max must be strictly greater than min ($max <= $min)" }
    }

    var value: Int = initialValue.coerceIn(min, max)
        private set

    /** Optional label displayed on the top-left (e.g. "Humans"). */
    var label: String = ""

    /** Optional value description displayed on the top-right (e.g. "1+ other humans"). */
    var valueText: String = ""

    /** Optional step labels (e.g. listOf("0", "1", "2", "3")). */
    var stepLabels: List<String> = emptyList()

    /**
     * Natural width of the widest header ([label] + [valueText]) the caller will
     * ever set. The header fits itself to the plate's inner width; a caller whose
     * value text changes sets this so the scale stays put while the value does.
     * Zero means "measure the header being drawn".
     */
    var headerFitWidth: Float = 0f

    // Last layout geometry (world coordinates, y-up).
    var left: Float = 0f
        private set
    var bottom: Float = 0f
        private set
    var width: Float = 0f
        private set
    var height: Float = 0f
        private set

    var trackLeft: Float = 0f
        private set
    var trackBottom: Float = 0f
        private set
    var trackWidth: Float = 0f
        private set
    var trackHeight: Float = 0f
        private set

    var isDragging: Boolean = false
        private set

    private val glyphLayout = GlyphLayout()

    /**
     * Set the current value directly, clamped to [min]..[max].
     * @return true if the value changed.
     */
    fun setValue(newValue: Int): Boolean {
        val clamped = newValue.coerceIn(min, max)
        if (value == clamped) return false
        value = clamped
        return true
    }

    /**
     * Position the slider control within the specified rectangle.
     */
    fun layout(left: Float, bottom: Float, width: Float, height: Float) {
        this.left = left
        this.bottom = bottom
        this.width = width
        this.height = height

        val padX = Fonts.px(18f)
        val hasLabel = label.isNotEmpty()
        val hasSteps = stepLabels.isNotEmpty()

        val stepAreaH = if (hasSteps) Fonts.px(16f) else Fonts.px(4f)
        val labelAreaH = if (hasLabel) Fonts.px(22f) else Fonts.px(4f)

        this.trackLeft = left + padX
        this.trackWidth = (width - padX * 2f).coerceAtLeast(1f)
        this.trackHeight = max(2.5f, Fonts.px(4f))

        // Center track cleanly between step numbers at bottom and label at top
        val trackCenterY = bottom + stepAreaH + (height - stepAreaH - labelAreaH) * 0.5f
        this.trackBottom = trackCenterY - trackHeight * 0.5f
    }

    /**
     * Check if a world point falls within the interactive touch/click area.
     */
    fun contains(worldX: Float, worldY: Float): Boolean =
        width > 0f && height > 0f &&
            worldX >= left && worldX <= left + width &&
            worldY >= bottom && worldY <= bottom + height

    /**
     * Map a pointer world X coordinate to the nearest discrete step in [min]..[max].
     */
    fun valueForPosition(worldX: Float): Int {
        if (trackWidth <= 0f) return value
        val t = ((worldX - trackLeft) / trackWidth).coerceIn(0f, 1f)
        val steps = max - min
        val stepIndex = (t * steps).roundToInt()
        return (min + stepIndex).coerceIn(min, max)
    }

    /**
     * Compute the center X coordinate for a given step value.
     */
    fun xForValue(v: Int): Float {
        val steps = max - min
        if (steps <= 0 || trackWidth <= 0f) return trackLeft
        val fraction = (v.coerceIn(min, max) - min).toFloat() / steps
        return trackLeft + fraction * trackWidth
    }

    /**
     * Handle pointer down: if [contains], updates value and begins drag.
     * @return new value if pressed, or null if outside.
     */
    fun onPointerDown(worldX: Float, worldY: Float): Int? {
        if (!contains(worldX, worldY)) return null
        isDragging = true
        val target = valueForPosition(worldX)
        setValue(target)
        return target
    }

    /**
     * Handle pointer drag: updates value as the pointer moves.
     * @return the new value if the drag crossed a step, or null if it stayed
     *   within the current step or is not dragging.
     */
    fun onPointerDrag(worldX: Float): Int? {
        if (!isDragging) return null
        val target = valueForPosition(worldX)
        return if (setValue(target)) target else null
    }

    /**
     * Handle pointer release.
     */
    fun onPointerUp() {
        isDragging = false
    }

    /**
     * Clear drag state without side effects.
     */
    fun clearDrag() {
        isDragging = false
    }

    /**
     * Render the slider with two-tier layout and polished capsule thumb.
     */
    fun render(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont = Fonts.button,
        smallFont: BitmapFont = Fonts.small,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        muted: Boolean = false,
    ) {
        if (width <= 1f || height <= 1f) return

        val isHovered = !muted && !pointerWorldX.isNaN() && !pointerWorldY.isNaN() &&
            contains(pointerWorldX, pointerWorldY)
        val border = max(1.2f, Fonts.px(1.5f))

        // Outer container background and rim.
        val bg = when {
            muted -> AppPalette.plateMuted
            isHovered || isDragging -> AppPalette.controlHover
            else -> AppPalette.tint(AppPalette.plateDeep, 0.75f)
        }
        val rim = when {
            muted -> AppPalette.tint(AppPalette.inkInert, 0.22f)
            isHovered || isDragging -> AppPalette.tint(AppPalette.rim, 0.75f)
            else -> AppPalette.tint(AppPalette.rim, 0.30f)
        }
        UiPlate.drawPlate(batch, px, left, bottom, width, height, bg, rim, border)

        // Top line: label on the left, current value text on the right (if provided).
        // The pair is fitted to the plate's inner width, so a long translation
        // shrinks uniformly instead of running into the value.
        val headerY = bottom + height - Fonts.px(9f)
        val headerPad = Fonts.px(14f)
        val headerGap = Fonts.px(16f)
        val headerAvail = (width - headerPad * 2f).coerceAtLeast(1f)
        glyphLayout.setText(font, label)
        val labelW = glyphLayout.width
        glyphLayout.setText(font, valueText)
        val valueW = glyphLayout.width
        val drawnHeaderW = labelW +
            (if (label.isNotEmpty() && valueText.isNotEmpty()) headerGap else 0f) + valueW
        val headerScale = Fonts.fitScale(max(drawnHeaderW, headerFitWidth), headerAvail)
        Fonts.withFaceScale(font, headerScale) {
            if (label.isNotEmpty()) {
                font.color = if (muted) AppPalette.inkInert else AppPalette.controlLabel
                font.draw(batch, label, left + headerPad, headerY)
            }
            if (valueText.isNotEmpty()) {
                font.color = when {
                    muted -> AppPalette.inkMuted
                    isHovered || isDragging -> AppPalette.inkStrong
                    else -> AppPalette.inkSoft
                }
                glyphLayout.setText(font, valueText)
                font.draw(batch, valueText, left + width - headerPad - glyphLayout.width, headerY)
            }
            font.color = Color.WHITE
        }

        // Inactive track groove.
        UiPlate.drawPlate(
            batch, px, trackLeft, trackBottom, trackWidth, trackHeight,
            AppPalette.controlGroove,
            AppPalette.tint(AppPalette.controlRim, if (muted) 0.12f else 0.25f),
            border,
        )

        // Active track fill (from min position up to current thumb center).
        val thumbCenterX = xForValue(value)
        val activeWidth = (thumbCenterX - trackLeft).coerceAtLeast(0f)
        if (activeWidth > 0f) {
            val activeColor = when {
                muted -> AppPalette.tint(AppPalette.trackMuted, 0.50f)
                isHovered || isDragging -> AppPalette.trackHover
                else -> AppPalette.tint(AppPalette.track, 0.95f)
            }
            batch.setColor(activeColor)
            batch.draw(px, trackLeft, trackBottom, activeWidth, trackHeight)
        }

        val trackCenterY = trackBottom + trackHeight * 0.5f

        // Tick marks and step labels.
        val steps = max - min
        val tickH = Fonts.px(7f)
        val tickW = max(1.2f, Fonts.px(1.5f))
        val tickBottom = trackCenterY - tickH * 0.5f

        for (i in 0..steps) {
            val stepVal = min + i
            val tickX = xForValue(stepVal)
            val isActive = stepVal <= value

            val tickColor = when {
                muted -> AppPalette.tint(AppPalette.inkInert, 0.30f)
                isActive -> AppPalette.tint(AppPalette.accent, 0.90f)
                else -> AppPalette.tint(AppPalette.tick, 0.45f)
            }
            batch.setColor(tickColor)
            batch.draw(px, tickX - tickW * 0.5f, tickBottom, tickW, tickH)

            // Step label beneath the tick mark in the bottom tier.
            if (i < stepLabels.size) {
                val stepText = stepLabels[i]
                glyphLayout.setText(smallFont, stepText)
                smallFont.color = when {
                    muted -> AppPalette.tint(AppPalette.inkInert, 0.5f)
                    stepVal == value -> AppPalette.accent
                    else -> AppPalette.tint(AppPalette.tickLabel, 0.75f)
                }
                val labelX = tickX - glyphLayout.width * 0.5f
                val labelY = bottom + Fonts.px(5f)
                smallFont.draw(batch, stepText, labelX, labelY + glyphLayout.height)
                smallFont.color = Color.WHITE
            }
        }

        // Thumb handle knob (sleek capsule with subtle center notch and glow).
        val thumbW = Fonts.px(16f)
        val thumbH = Fonts.px(16f)
        val thumbLeft = thumbCenterX - thumbW * 0.5f
        val thumbBottom = trackCenterY - thumbH * 0.5f

        // Soft halo glow when hovered or dragging.
        if (!muted && (isHovered || isDragging)) {
            val glowPad = Fonts.px(3f)
            batch.setColor(AppPalette.tint(AppPalette.accent, 0.35f))
            batch.draw(
                px,
                thumbLeft - glowPad,
                thumbBottom - glowPad,
                thumbW + glowPad * 2f,
                thumbH + glowPad * 2f,
            )
        }

        // Thumb body. This was a cross of two bars, whose union is the same rect
        // but whose inset centre was painted twice — brighter whenever thumbFill
        // was translucent. It never chamfered anything.
        val thumbFill = when {
            muted -> AppPalette.tint(AppPalette.knobMuted, 0.75f)
            isDragging -> AppPalette.knobActive
            isHovered -> AppPalette.knobHover
            else -> AppPalette.knob
        }
        batch.setColor(thumbFill)
        batch.draw(px, thumbLeft, thumbBottom, thumbW, thumbH)

        // Subtle center grip notch:
        val notchW = max(1.5f, Fonts.px(2f))
        val notchH = max(4f, thumbH - Fonts.px(8f))
        val notchColor = when {
            muted -> AppPalette.tint(AppPalette.knobNotchMuted, 0.40f)
            isDragging -> AppPalette.tint(AppPalette.knobNotchActive, 0.75f)
            else -> AppPalette.tint(AppPalette.knobNotch, 0.65f)
        }
        batch.setColor(notchColor)
        batch.draw(px, thumbCenterX - notchW * 0.5f, trackCenterY - notchH * 0.5f, notchW, notchH)

        // Thumb border rim:
        val thumbRim = when {
            muted -> AppPalette.tint(AppPalette.inkInert, 0.35f)
            isDragging || isHovered -> AppPalette.tint(AppPalette.rim, 0.85f)
            else -> AppPalette.tint(AppPalette.knobRim, 0.85f)
        }
        UiPlate.drawFrame(batch, px, thumbLeft, thumbBottom, thumbW, thumbH, thumbRim, border)
    }
}
