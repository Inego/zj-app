package inego.zungjunggame.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.math.Vector3
import inego.zungjunggame.input.RawInput
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.ui.FramePointers
import inego.zungjunggame.ui.PointerSample

/**
 * Thin [RawInput] over `Gdx.input`, unprojecting pointer coordinates through
 * [camera] into world space.
 *
 * No gesture state machine: drag/caret/scroll ownership lives in
 * [inego.zungjunggame.input.GestureArbiter]. [snapshot] must be called once per
 * frame before the properties are read, because `justReleased` is derived from
 * the previous sample and Gdx exposes no such flag. A settled picture presents
 * once per gesture, so a short tap's down and up are both applied before that
 * sample: `justTouched` is true while `isTouched` is already false.
 *
 * Scroll and OS touch-cancel arrive out of band as Gdx callbacks; the platform
 * input processor forwards them through [recordScroll] / [markTouchCancelled].
 */
class GdxRawInput(private val camera: Camera) : RawInput {
    private val point = Vector3()

    private var touched = false
    private var released = false
    private var wasTouched = false
    private var x = 0f
    private var y = 0f
    private var ctrlDown = false
    private var shiftDown = false
    private var keys: List<Int> = emptyList()
    private var back = false
    private var held = false
    private var pointerSamples: FramePointers = FramePointers.EMPTY
    private var cancelled = false
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var scrollDeltaX = 0f
    private var scrollDeltaY = 0f

    /** Records a wheel sample delivered by the Gdx input processor, both axes. */
    fun recordScroll(amountX: Float, amountY: Float) {
        pendingScrollX += amountX
        pendingScrollY += amountY
    }

    /** Records an OS touch-cancel delivered by the Gdx input processor. */
    fun markTouchCancelled() {
        cancelled = true
    }

    /** Samples one frame of Gdx state; call at the top of the frame loop. */
    fun snapshot() {
        touched = Gdx.input.justTouched()
        val nowTouched = Gdx.input.isTouched
        released = (wasTouched || touched) && !nowTouched
        wasTouched = nowTouched
        held = nowTouched

        // Every pointer the frame reports down, not just the primary: a touch
        // platform reports each finger through its own slot, and the frame's
        // multi-pointer facts have to come from the same sample the primary
        // does. Desktop's `maxPointers` is 1, so its one mouse is slot 0.
        val down = ArrayList<PointerSample>(4)
        val maxPointers = Gdx.input.maxPointers
        for (i in 0 until maxPointers) {
            if (!Gdx.input.isTouched(i)) continue
            point.set(Gdx.input.getX(i).toFloat(), Gdx.input.getY(i).toFloat(), 0f)
            camera.unproject(point)
            down += PointerSample(i, point.x, point.y)
        }
        pointerSamples = if (down.isEmpty()) FramePointers.EMPTY else FramePointers(down)

        point.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f)
        camera.unproject(point)
        x = point.x
        y = point.y

        ctrlDown = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) ||
            Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
        shiftDown = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) ||
            Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)

        val pressed = ArrayList<Int>(4)
        for (keycode in 0..255) {
            if (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) continue
            if (Gdx.input.isKeyJustPressed(keycode)) pressed += keycode
        }
        keys = pressed
        back = Gdx.input.isKeyJustPressed(Input.Keys.BACK) ||
            Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)

        // Capture the out-of-band samples so the properties are pure frame
        // snapshots; callbacks that arrive after this snapshot land next frame.
        scrollDeltaX = pendingScrollX
        scrollDeltaY = pendingScrollY
        pendingScrollX = 0f
        pendingScrollY = 0f

        if (touched || released || cancelled) {
            TouchDiagnostics.log(
                "raw down=$touched up=$released held=$held was=$wasTouched pointers=${pointerSamples.count} " +
                    "screen=${Gdx.input.x},${Gdx.input.y} " +
                    "world=${TouchDiagnostics.fmt(x)},${TouchDiagnostics.fmt(y)}",
            )
        }
    }

    override val justTouched: Boolean get() = touched
    override val justReleased: Boolean get() = released
    override val isTouched: Boolean get() = held
    override val touchCancelled: Boolean get() = cancelled.also { cancelled = false }
    override val pointerCount: Int get() = pointerSamples.count
    override val pointers: FramePointers get() = pointerSamples
    override fun touchX(): Float = x
    override fun touchY(): Float = y

    override val scrollAmount: Float get() = scrollDeltaY
    override val scrollAmountX: Float get() = scrollDeltaX
    override val scrollX: Float get() = x
    override val scrollY: Float get() = y

    override fun keysJustPressed(): List<Int> = keys
    override val backPressed: Boolean get() = back
    override val ctrl: Boolean get() = ctrlDown
    override val shift: Boolean get() = shiftDown
}
