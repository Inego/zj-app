package inego.zungjunggame.lwjgl3

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import org.lwjgl.glfw.GLFWNativeWin32

/**
 * Best-effort Windows foreground grab for a freshly created GLFW window.
 *
 * Launching the app from a parent process (Gradle/IDE/terminal) means Windows
 * does not grant the new window foreground rights, so `glfwFocusWindow` alone is
 * often ignored and the window opens behind the launcher with only a taskbar
 * flash. This applies the conventional Win32 workarounds: attach the calling
 * thread's input to the current foreground thread, then `SetForegroundWindow`,
 * `SetActiveWindow`/`SetFocus`, and — when the foreground lock still refuses —
 * the long-lived `SwitchToThisWindow` fallback.
 *
 * The caller passes GLFW's window handle (`Lwjgl3Window.windowHandle`), which is
 * a `GLFWwindow*`, **not** a Win32 `HWND`; it is converted here before any
 * `user32` call. Passing the pointer straight to `user32` silently fails every
 * call. No-op on non-Windows platforms and never throws.
 *
 * Bringing a window forward must not change its display state: the size and
 * maximize flag belong to [DesktopWindowController], so the only `ShowWindow`
 * here is the guarded un-minimize below. `SW_RESTORE` restores a *maximized*
 * window to its original size too, which would silently undo a restored
 * maximize (and let the "restored" event overwrite the saved state).
 */
internal object WindowsForeground {
    private const val SW_RESTORE = 9

    /** Minimal `user32.dll` surface; avoids a `jna-platform` dependency. */
    private interface User32 : Library {
        fun GetForegroundWindow(): Long
        fun GetWindowThreadProcessId(hwnd: Long, processId: IntArray?): Int
        fun AttachThreadInput(idAttach: Int, idAttachTo: Int, attach: Boolean): Boolean
        fun SetForegroundWindow(hwnd: Long): Boolean
        fun BringWindowToTop(hwnd: Long): Boolean
        fun SetActiveWindow(hwnd: Long): Long
        fun SetFocus(hwnd: Long): Long
        fun IsIconic(hwnd: Long): Boolean
        fun ShowWindow(hwnd: Long, cmdShow: Int): Boolean
        fun SwitchToThisWindow(hwnd: Long, altTab: Boolean)
    }

    private interface Kernel32 : Library {
        fun GetCurrentThreadId(): Int
    }

    private val user32: User32 by lazy { Native.load("user32", User32::class.java) }
    private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

    /** One line per process is enough; a 30-frame retry must not spam the log. */
    private var failureLogged = false

    /** What one grab attempt did, for the session log. */
    data class Attempt(
        /** The real Win32 `HWND`, converted from GLFW's pointer. */
        val window: Long,
        val foregroundBefore: Long,
        val targetThread: Int,
        val currentThread: Int,
        val attached: Boolean,
        val setForeground: Boolean,
        val switched: Boolean,
        val foregroundAfter: Long,
    ) {
        val success: Boolean get() = foregroundAfter == window

        override fun toString(): String =
            "before=0x${foregroundBefore.toString(16)} target=0x${window.toString(16)} " +
                "fgThread=$targetThread curThread=$currentThread attached=$attached " +
                "setForeground=$setForeground switched=$switched " +
                "after=0x${foregroundAfter.toString(16)} success=$success"
    }

    /**
     * Try to make [glfwWindow] (a `GLFWwindow*`) the foreground window.
     *
     * @return what each step observed, or null when not on Windows / the window
     *   is gone / JNA is unavailable.
     */
    fun grab(glfwWindow: Long): Attempt? {
        if (!Platform.isWindows() || glfwWindow == 0L) return null
        return try {
            val hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow)
            if (hwnd == 0L) return null
            val before = user32.GetForegroundWindow()
            val targetThread = user32.GetWindowThreadProcessId(before, null)
            val currentThread = kernel32.GetCurrentThreadId()
            // Attach our input queue to the foreground thread's so Windows treats
            // this process as having received the last input event. Try both
            // directions: which one is accepted has varied across Windows builds.
            var attached = false
            if (targetThread != 0 && targetThread != currentThread) {
                attached = user32.AttachThreadInput(targetThread, currentThread, true) ||
                    user32.AttachThreadInput(currentThread, targetThread, true)
            }
            var switched = false
            try {
                // Only un-minimize. SW_RESTORE also un-maximizes, and a window
                // restored maximized must stay maximized; glfwShowWindow already
                // makes a hidden window visible without touching its state.
                if (user32.IsIconic(hwnd)) {
                    user32.ShowWindow(hwnd, SW_RESTORE)
                }
                user32.BringWindowToTop(hwnd)
                val setForeground = user32.SetForegroundWindow(hwnd)
                user32.SetActiveWindow(hwnd)
                user32.SetFocus(hwnd)
                if (user32.GetForegroundWindow() != hwnd) {
                    switched = runCatching {
                        user32.SwitchToThisWindow(hwnd, true)
                    }.isSuccess
                }
                Attempt(
                    window = hwnd,
                    foregroundBefore = before,
                    targetThread = targetThread,
                    currentThread = currentThread,
                    attached = attached,
                    setForeground = setForeground,
                    switched = switched,
                    foregroundAfter = user32.GetForegroundWindow(),
                )
            } finally {
                if (attached) {
                    user32.AttachThreadInput(targetThread, currentThread, false)
                    user32.AttachThreadInput(currentThread, targetThread, false)
                }
            }
        } catch (failure: Throwable) {
            // Focusing is a nicety; never block launch on it.
            if (!failureLogged) {
                failureLogged = true
                println("[ZjFocus] win32 grab failed: ${failure::class.java.name}: ${failure.message}")
            }
            null
        }
    }
}
