@file:JvmName("Lwjgl3Launcher")

package inego.zungjunggame.lwjgl3

import com.badlogic.gdx.Files
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import inego.zungjunggame.Main
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.PlatformLanguages
import inego.zungjunggame.platform.PlatformTimeZone
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.FileStateBackend
import inego.zungjunggame.state.PlatformSecretBox
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.platform.DesktopCrashHook
import inego.zungjunggame.platform.DesktopGoogleAuthProvider
import inego.zungjunggame.platform.DesktopPresenceScheduler
import inego.zungjunggame.platform.GoogleAuthProvider
import inego.zungjunggame.platform.JvmWebSocketClient
import inego.zungjunggame.platform.McHints
import inego.zungjunggame.platform.WebSocketClient
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Launches the desktop (LWJGL3) application. */
fun main(args: Array<String>) {
    // Choose the data folder before anything touches it: the crash-JVM relaunch,
    // the window geometry read, logs, and the core file store all resolve to it.
    DesktopDataDir.install(args)
    if (DesktopCrashJvm.relaunchIfNeeded()) return
    // This handles macOS support and helps on Windows.
    if (StartupHelper.startNewJvmIfRequired())
      return

    // The core file store resolves its root through `Gdx.files.external`, which
    // cannot be moved; hand it the override so its desktop files follow the rest.
    if (DesktopDataDir.isOverridden) {
        FileStateBackend.rootOverride = DesktopDataDir.root.absolutePath
    }

    DesktopLog.install()
    DesktopCrashHook.onThrowable = { thread, error, where ->
        DesktopLog.crash(thread, error, where)
    }
    // Must run before Gdx bootstraps: online restore reads the base URL during
    // the first frame. Bakes the build's server, or keeps the local default.
    DesktopApiConfig.install()
    WebSocketClient.factory = { JvmWebSocketClient() }
    GoogleAuthProvider.factory = { DesktopGoogleAuthProvider() }
    val secretBox = DesktopOsSecretBox.create()
    PlatformSecretBox.provider = { secretBox }
    // Display language, not the regional format locale. Installed before Main
    // resolves the first-run ui.locale.
    PlatformLanguages.provider = {
        listOf(AppLocale.localeTag(Locale.getDefault(Locale.Category.DISPLAY)))
    }
    // The OS zone, not a zone the player picks in the app. Installed before a
    // signed-in refresh reports it for email reports.
    PlatformTimeZone.provider = { ZoneId.systemDefault().id }
    // The same zone as the offset in force at an instant, for the rating
    // chart's popup: the zone's rules decide the offset, so it is asked per
    // instant rather than cached once.
    PlatformTimeZone.offsetProvider = { millis ->
        ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(millis)).totalSeconds / 60
    }
    inego.zungjunggame.platform.PresenceScheduler.factory = { DesktopPresenceScheduler() }
    // B1: desktop's GL context is durable. The window is double-buffered by the
    // OS, so backgrounding it cannot discard texture or glyph-atlas names — the
    // thing that actually changes while minimized is the framebuffer size, and
    // that is not a resource loss. Declared durable, this policy suppresses the
    // gap-heuristic rebuild (measured 186-203 ms on every minimize) and the
    // resume rebuild (161-175 ms), neither of which bought anything.
    //
    // It stays inert w.r.t. onContextRecreated: a driver reset still surfaces
    // through Lwjgl3's own context-recreated callback once Stage D1 wires it,
    // and until then desktop has no loss signal to offer. The plan keeps
    // DurableGpuContexts rather than a desktop-specific class because the only
    // difference is a listener that does not exist yet.
    inego.zungjunggame.platform.GpuContextPolicy.factory = {
        inego.zungjunggame.platform.DurableGpuContexts
    }
    // HINT gets UCB MC playouts on desktop only: they need a preemptive worker
    // thread, and a playout is fast enough here to be worth showing ([McHints]).
    McHints.enabled = true

    // Restore last window size / maximize / fullscreen before the window opens.
    // `platform.window` is read before libGDX exists, so it goes through the
    // desktop's Gdx-free store over the same folder the core file store uses.
    val windowStore = DefaultStateStore(DesktopFileStateBackend())
    val windowController = DesktopWindowController(StateSlot(windowStore, WindowStateKey))

    try {
        Lwjgl3Application(
            DesktopLog.guard(windowController.wrap(Main())),
            Lwjgl3ApplicationConfiguration().apply {
        setTitle("Zung Jung Mahjong")
        // Absolute so the `zj-state` preferences file sits in the same folder as
        // the rest of the desktop state, default or `--data-dir` override.
        setPreferencesConfig(DesktopDataDir.root.absolutePath, Files.FileType.Absolute)
        // The swap interval is the only present pacer. A software cap beside it
        // drifts against the blank and shows up as a periodic hitch.
        useVsync(true)
        setForegroundFPS(0)
        // How often the loop polls while nothing asked to draw.
        setIdleFPS(30)

        windowController.applyTo(this)

        //// Multisample antialiasing (MSAA). Last arg is sample count: 0 = off,
        //// 2/4/8 are common. 4x is a good quality/performance tradeoff.
        //// MSAA smooths geometry edges; textures already use linear filtering.
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
        //// Window / taskbar icons (lwjgl3/src/main/resources/icon{16,32,64,128}.png).
        //// Packaged .exe / .app icons live under lwjgl3/icons/ (see build.gradle construo).
        setWindowIcon(*(arrayOf(128, 64, 32, 16).map { "icon$it.png" }.toTypedArray()))
        enableGLDebugOutput(true, System.err)
        // Session is auto-saved on pause/dispose; no Exit confirm on window close.
        // Window geometry is auto-saved the same way (see DesktopWindowController).

        //// This could improve compatibility with Windows machines with buggy OpenGL drivers, Macs
        //// with Apple Silicon that have to emulate compatibility with OpenGL anyway, and more.
        //// This uses the dependency `com.badlogicgames.gdx:gdx-lwjgl3-angle` to function.
        //// You would need to add this line to lwjgl3/build.gradle , below the dependency on `gdx-backend-lwjgl3`:
        ////     implementation "com.badlogicgames.gdx:gdx-lwjgl3-angle:$gdxVersion"
        //// You can choose to add the following line and the mentioned dependency if you want; they
        //// are not intended for games that use GL30 (which is compatibility with OpenGL ES 3.0).
        //// Know that it might not work well in some cases.
//        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 0, 0)

        },
        )
    } catch (t: Throwable) {
        DesktopLog.crash(Thread.currentThread(), t, "Lwjgl3Application")
        throw t
    }
}
