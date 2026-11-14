package inego.zungjunggame.lwjgl3

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter
import inego.zungjunggame.state.StateSlot
import org.lwjgl.glfw.GLFW

/**
 * Desktop window restore + auto-save for the LWJGL3 backend.
 *
 * Applies the last `platform.window` geometry ([WindowState]) to the launch
 * config (fullscreen / maximized / windowed size + position), then tracks size
 * and maximize flag while running so the next cold start matches how the app was
 * closed. The key is read when the controller is built — before libGDX exists —
 * through the Gdx-free [DesktopFileStateBackend].
 *
 * Windowed restore size is only updated while the window is neither fullscreen
 * nor maximized — otherwise a maximized frame would overwrite the true
 * restore dimensions.
 */
class DesktopWindowController(
    /** The desktop's `platform.window` key; written on close, pause, and dispose. */
    private val windowState: StateSlot<WindowState>,
) {
    private val initial: WindowState = windowState.requireValue().sanitized()
    private var windowedWidth: Int =
        initial.width.coerceAtLeast(WindowState.MIN_WIDTH)
    private var windowedHeight: Int =
        initial.height.coerceAtLeast(WindowState.MIN_HEIGHT)
    private var windowedX: Int? = initial.x
    private var windowedY: Int? = initial.y
    private var maximized: Boolean = initial.maximized && !initial.fullscreen
    private var window: Lwjgl3Window? = null

    /** True once the window has held focus; stops the startup foreground retries. */
    private var focusAchieved = false

    /** Apply geometry and install a listener that tracks maximize / close. */
    fun applyTo(config: Lwjgl3ApplicationConfiguration) {
        if (initial.fullscreen) {
            config.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode())
        } else {
            config.setWindowedMode(windowedWidth, windowedHeight)
            val x = windowedX
            val y = windowedY
            if (x != null && y != null && isPositionPlausible(x, y)) {
                config.setWindowPosition(x, y)
            }
            if (maximized) {
                config.setMaximized(true)
            }
        }
        config.setWindowListener(object : Lwjgl3WindowAdapter() {
            override fun created(w: Lwjgl3Window) {
                window = w
                // Launching from a terminal/IDE (`.gradlew :lwjgl3:run`) does not
                // always make the new window the foreground window on Windows: it is
                // a child of the Gradle daemon, not of the foreground process, so
                // Windows' foreground lock denies the grab. [`created`] also runs
                // *before* libGDX shows the window (`window.setVisible` follows this
                // callback), and GLFW shows with SW_SHOWNA ("show without
                // activating"). Defer to the window's own queue, which runs after it
                // is mapped, then retry a few frames while it has never been focused.
                w.postRunnable { focusAfterShow(w, FOCUS_ATTEMPTS) }
            }

            override fun maximized(isMaximized: Boolean) {
                maximized = isMaximized
                // Leaving maximize: next resize will refresh windowed size.
                if (!isMaximized) {
                    captureWindowedGeometryIfSafe()
                }
                persist()
            }

            override fun closeRequested(): Boolean {
                persist()
                return true
            }
        })
    }

    /**
     * Wrap the game listener so resize / pause / dispose keep the store in sync.
     */
    fun wrap(listener: ApplicationListener): ApplicationListener =
        object : ApplicationListener by listener {
            override fun resize(width: Int, height: Int) {
                if (!isFullscreenSafe() && !maximized &&
                    width >= WindowState.MIN_WIDTH &&
                    height >= WindowState.MIN_HEIGHT
                ) {
                    windowedWidth = width
                    windowedHeight = height
                    capturePositionIfSafe()
                }
                listener.resize(width, height)
            }

            override fun pause() {
                persist()
                listener.pause()
            }

            override fun resume() {
                listener.resume()
            }

            override fun dispose() {
                persist()
                listener.dispose()
            }
        }

    private fun captureWindowedGeometryIfSafe() {
        if (isFullscreenSafe() || maximized) return
        try {
            val w = Gdx.graphics.width
            val h = Gdx.graphics.height
            if (w >= WindowState.MIN_WIDTH && h >= WindowState.MIN_HEIGHT) {
                windowedWidth = w
                windowedHeight = h
            }
            capturePositionIfSafe()
        } catch (_: Exception) {
            // Gdx not ready yet
        }
    }

    private fun capturePositionIfSafe() {
        if (isFullscreenSafe() || maximized) return
        val w = window ?: return
        windowedX = w.positionX
        windowedY = w.positionY
    }

    /**
     * Write the current geometry to `platform.window`.
     *
     * Internal so the geometry round trip is testable without a window; the
     * window listener calls it on maximize / close / pause / dispose.
     */
    internal fun persist() {
        val fullscreen = isFullscreenSafe()
        if (!fullscreen && !maximized) {
            captureWindowedGeometryIfSafe()
        } else if (!fullscreen) {
            capturePositionIfSafe()
        }
        windowState.set(
            WindowState(
                width = windowedWidth,
                height = windowedHeight,
                x = windowedX,
                y = windowedY,
                fullscreen = fullscreen,
                maximized = maximized && !fullscreen,
            ).sanitized(),
        )
    }

    /**
     * Best-effort foreground grab, deferred until after the window is shown (see
     * [applyTo]'s `created`). Runs from the window's own queue, so GLFW calls are
     * safe; retries for [attemptsLeft] frames while the window has never been
     * focused, then stops so it can never fight the player for focus.
     */
    private fun focusAfterShow(window: Lwjgl3Window, attemptsLeft: Int) {
        if (focusAchieved) return
        val handle = window.windowHandle
        if (handle == 0L) return
        val attempt = FOCUS_ATTEMPTS - attemptsLeft + 1
        // Do not gate on GLFW's GLFW_FOCUSED: at the first frame it is not yet
        // synced with the real OS foreground, so it can report focused while the
        // window is still behind the launcher. Win32's own answer is authoritative.
        GLFW.glfwShowWindow(handle)
        GLFW.glfwFocusWindow(handle)
        if (attempt == 1) GLFW.glfwRequestWindowAttention(handle)
        val grab = WindowsForeground.grab(handle)
        if (grab?.success == true) {
            focusAchieved = true
            return
        }
        if (attemptsLeft > 0) {
            window.postRunnable { focusAfterShow(window, attemptsLeft - 1) }
        } else {
            println(
                "[ZjFocus] could not take the foreground after $attempt attempt(s): " +
                    (grab ?: "no win32 result"),
            )
        }
    }

    private fun isFullscreenSafe(): Boolean =
        try {
            Gdx.graphics.isFullscreen
        } catch (_: Exception) {
            false
        }

    /**
     * Reject positions that no longer sit on any connected monitor
     * (e.g. undocked laptop after a multi-monitor session).
     */
    private fun isPositionPlausible(x: Int, y: Int): Boolean {
        val monitors = Lwjgl3ApplicationConfiguration.getMonitors()
        if (monitors.isEmpty()) return true
        val margin = 100
        for (monitor in monitors) {
            val mode = Lwjgl3ApplicationConfiguration.getDisplayMode(monitor)
            if (x >= monitor.virtualX - margin &&
                x < monitor.virtualX + mode.width &&
                y >= monitor.virtualY - margin &&
                y < monitor.virtualY + mode.height
            ) {
                return true
            }
        }
        return false
    }

    private companion object {
        /**
         * Frames to keep asking for the foreground before giving up: enough to ride
         * out a slow first frame, short enough never to fight a player who switches
         * away before focus is first granted.
         */
        const val FOCUS_ATTEMPTS = 30
    }
}
