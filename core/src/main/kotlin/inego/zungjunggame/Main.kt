package inego.zungjunggame

import inego.zungjunggame.ui.stack.SessionKind
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import inego.zungjunggame.app.AppGraph
import inego.zungjunggame.app.AppRuntime
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LanguagePack
import inego.zungjunggame.persist.AppLocaleKey
import inego.zungjunggame.persist.resolveStartupLocale
import inego.zungjunggame.platform.ColdStartGate
import inego.zungjunggame.render.TableFelt
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.PlatformStateBackend
import inego.zungjunggame.ui.Scope

/**
 * Renders a live game from the ported core.
 *
 * Computer seats play on their own; the game only waits for the human.
 * Each AI decision waits until tile animations from the previous action finish.
 *
 * A **game** is a configured sequence of hands (default 4 rounds × 4 hands = 16,
 * or unlimited). With no restored session, the main menu opens first; hands are
 * dealt only after Menu → New game → setup Start.
 *
 * Runtime construction, input, session orchestration, and rendering live in
 * [AppRuntime]; this class owns only the LibGDX boot shell and splash timing.
 *
 * Shared targets (desktop click / touch release):
 * - Hand tile: discard (your ActOnHand turn)
 * - Claim buttons: pass / hu / pung / kong / each chow (your react turn)
 * - Hu / Declare Concealed Kong (sequential per four-of-a-kind) / Keep, then discard;
 *   Promote Kong when offered with discards
 * - HINT button: classic co-best highlight; on desktop UCB flat MC playouts refine
 *   it (labels avgΔ (n)); unique best → second press plays
 * - Menu button (top-right: local / online board, tutorial shell): open the game
 *   menu (same as Esc / empty-felt tap)
 * - Hand end (win): inline score breakdown + Scores / Next hand (or New game)
 * - Hand end (draw): Exhaustive draw + Scores / Next hand (or New game)
 *
 * Touch ([InputProfile.Touch]) — Android, iOS, and a web page whose primary
 * pointer is a finger:
 * - Press and drag: hover highlight follows the finger
 * - Release on a target: commit that action
 * - Press+release on empty felt: open game menu (free-play board or tutorial coach/quiz)
 * - Press on a control, release off it: cancel only (no menu)
 * - Release outside an open menu / score table: dismiss it
 *
 * Desktop only ([InputProfile.Desktop]):
 * - Continuous mouse hover highlights hand tiles and claim buttons
 * - Click (press) acts immediately
 * - Esc: open game menu (Score table / New game / Exit);
 *   or close menu / scores when one of those is open
 * - Exit (menu or window close): leaves immediately; session is auto-saved
 * - Ctrl+Space: AI assist
 * - H: same as HINT button · T: score table · N: next hand
 * - Ctrl+R: open Zung Jung reference (full-screen; × or Esc exits;
 *   history ← / Backspace goes back inside the browser)
 * - Hand end: Enter presses Next hand when that button is available
 * - Confirm dialog (abandon hand / new game mid-run): Yes/No click, or Y/N/Enter/Esc
 * - New Game setup: Enter starts, Esc cancels · F11: fullscreen
 * - Scroll wheel: score table / reference mode
 *
 * Session: free-play and tutorial each own a **live board and a save** — entering
 * one leaves the other alone (no confirm, nothing cleared).
 * - Free-play: the `session.freeplay` key on pause/dispose while [SessionKind.FreePlay].
 * - Mid-lesson: [TutorialHost] + the `session.tutorial` key
 *   on pause/dispose while Tutorial; the file is **kept** when starting
 *   free-play so reopening Tutorial restores that beat and board.
 * - Campaign progress ([inego.zungjunggame.tutorial.TutorialProgress]
 *   completed lessons) survives free-play, and a local game survives the tutorial.
 * Cold start restores whichever context the stack names, and a save the other
 * context holds is left on disk for its own next entry.
 * Desktop also restores window size / maximize / fullscreen across launches
 * (see lwjgl3 [DesktopWindowController]).
 */
class Main : ApplicationAdapter() {
    /** The process lifetime: it owns the GL shell and, through [AppGraph], every screen. */
    private val scope = Scope.root("app")
    private val camera = OrthographicCamera()
    private val batch by lazy { scope.own("batch") { SpriteBatch() } }
    // A viewport owns no GL resource and is not Disposable (gdx 1.14.2), so it is
    // plain launcher state, not scope-owned.
    private val viewport = ScreenViewport(camera)

    /** Last non-fullscreen size for F11 restore (updated in [resize]). */
    private var windowedWidth = 1280
    private var windowedHeight = 800

    private var runtime: AppRuntime? = null
    private var bootstrapFace: Fonts.BootstrapFace? = null

    /**
     * Cold-start boot order (process start only; [create] is not called on resume):
     * 1. First GL frame: clear felt and return so a green frame is presented
     *    (desktop has no system splash; without this, bootstrap leaves black).
     * 2. Next frame: [bootstrapApp] while the felt frame stays on screen; then
     *    normal UI draw. On Android the system splash still covers until
     *    [ColdStartGate] after that first UI frame.
     * 3. Normal gameplay loop.
     */
    private enum class BootPhase {
        PresentFelt,
        Bootstrap,
        Ready,
    }

    private var bootPhase = BootPhase.PresentFelt

    /**
     * After bootstrap, release the Android system splash only once this render
     * has drawn the first real UI frame (avoids splash → empty felt).
     */
    private var releaseSplashAfterThisFrame: Boolean = false

    /**
     * libGDX calls this once per process with a live GL context, but buffer
     * swap happens after [render]. Keep heavy work out of here so Android can
     * install the splash keep-condition before bootstrap blocks the GL thread.
     */
    override fun create() {
        ColdStartGate.reset()
        BootDiagnostics.resetFile()
        // The display blank paces presents. A settled picture does not ask for
        // another one; input, posted work, and in-flight motion do.
        Gdx.graphics.isContinuousRendering = false
        Gdx.graphics.requestRendering()
        BootDiagnostics.mark(
            "create()",
            "screen=${Gdx.graphics.width}x${Gdx.graphics.height} " +
                "density=${Gdx.graphics.density} " +
                "type=${Gdx.app.type} continuous=${Gdx.graphics.isContinuousRendering} " +
                "bootPhase=$bootPhase (felt then Bootstrap on first renders)",
        )
    }

    private fun readPersistedLocale(): AppLocale {
        return try {
            val store = DefaultStateStore(PlatformStateBackend.create())
            val seeded = !store.contains(AppLocaleKey)
            val locale = store.resolveStartupLocale()
            BootDiagnostics.mark(
                "readPersistedLocale",
                "locale=${locale.tag}" + if (seeded) " seeded" else "",
            )
            locale
        } catch (t: Throwable) {
            BootDiagnostics.mark("readPersistedLocale:failed", "${t.javaClass.simpleName}: ${t.message}")
            AppLocale.DEFAULT
        }
    }

    /** Solid felt fill + localized bootstrap mark — first presented frame while assets load on the next. */
    private fun clearToFelt() {
        val w = Gdx.graphics.width.coerceAtLeast(1)
        val h = Gdx.graphics.height.coerceAtLeast(1)
        viewport.update(w, h, true)
        ScreenUtils.clear(TableFelt.R, TableFelt.G, TableFelt.B, TableFelt.A)

        try {
            val locale = readPersistedLocale()
            // Make the persisted pack readable before L10n reads it. Idempotent:
            // synchronous for desktop/Android internal zips, already open on Wasm
            // (the preload screen fetched it).
            LanguagePack.ensure(locale.tag)
            L10n.bootstrap(locale.javaLocale)
            val text = L10n.get("boot.initializing")
            BootDiagnostics.mark("clearToFelt", "text=$text locale=${locale.tag}")
            val face = Fonts.createBootstrapFace(locale, text)
            bootstrapFace = face

            batch.projectionMatrix = camera.combined
            batch.begin()
            val layout = GlyphLayout(face.font, text)
            val x = (w - layout.width) * 0.5f
            val y = (h + layout.height) * 0.5f
            face.font.draw(batch, layout, x, y)
            batch.end()
        } catch (t: Throwable) {
            BootDiagnostics.mark("clearToFelt:textFailed", "${t.javaClass.simpleName}: ${t.message}")
            Gdx.app?.error("Main", "Failed to render bootstrap initializing label", t)
        }
    }

    /**
     * Heavy one-time init formerly in [create]: locale, fonts, tiles, hosts, restore.
     * Runs on the second render so a felt frame is already on screen (desktop).
     */
    private fun bootstrapApp() {
        runtime = AppGraph(
            batch = batch,
            camera = camera,
            viewport = viewport,
            onToggleFullscreen = { toggleFullscreen() },
            onExit = { Gdx.app.exit() },
            onExitRequest = { runtime?.exitApp() ?: Gdx.app.exit() },
            scope = scope,
        ).create()
    }

    /** Write runtime state when the app pauses or resumes. */
    override fun pause() {
        runtime?.pause()
    }

    override fun resume() {
        runtime?.resume()
        // Not a diagnostic — this call is
        // load-bearing. With `setContinuousRendering(false)` the only two other
        // callers cover the first frame and the not-yet-ready boot phase, and a
        // backgrounded app is by definition not animating, so removing it would
        // leave the window resumed and never repainting.
        Gdx.graphics.requestRendering()
    }

    override fun resize(width: Int, height: Int) {
        // Keep the last windowed size for F11; frame orchestration lives in AppRuntime.
        if (!Gdx.graphics.isFullscreen && width >= 640 && height >= 480) {
            windowedWidth = width
            windowedHeight = height
        }
        runtime?.resize(width, height) ?: viewport.update(width, height, true)
    }

    private fun toggleFullscreen() {
        if (Gdx.graphics.isFullscreen) {
            Gdx.graphics.setWindowedMode(windowedWidth, windowedHeight)
        } else {
            Gdx.graphics.setFullscreenMode(Gdx.graphics.displayMode)
        }
    }

    override fun render() {
        try {
            when (bootPhase) {
                BootPhase.PresentFelt -> {
                    // Present green before bootstrap blocks (visible on desktop;
                    // under system splash on Android).
                    BootDiagnostics.mark("render:PresentFelt")
                    clearToFelt()
                    bootPhase = BootPhase.Bootstrap
                    BootDiagnostics.mark("render:PresentFelt:return", "next=Bootstrap")
                    return
                }
                BootPhase.Bootstrap -> {
                    // Prior frame's felt stays visible while this blocks.
                    // Android system splash stays up via [ColdStartGate] until
                    // after this frame draws real UI (see finally below).
                    BootDiagnostics.mark("render:Bootstrap")
                    bootstrapApp()
                    bootPhase = BootPhase.Ready
                    releaseSplashAfterThisFrame = true
                    bootstrapFace?.dispose()
                    bootstrapFace = null
                    BootDiagnostics.mark("render:Bootstrap:done → Ready (drawing first real UI frame)")
                }
                BootPhase.Ready -> Unit
            }

            val currentRuntime = runtime
            if (currentRuntime == null) {
                BootDiagnostics.mark(
                    "render:earlyReturn",
                    "runtime=false phase=$bootPhase",
                )
                return
            }
            currentRuntime.renderFrame()
        } finally {
            // Drop system splash only after the first Ready frame has run (UI
            // draw attempted). Avoids icon → empty felt while fonts/tiles load.
            if (releaseSplashAfterThisFrame && bootPhase == BootPhase.Ready) {
                releaseSplashAfterThisFrame = false
                ColdStartGate.markContentReady()
                BootDiagnostics.mark("ColdStartGate:contentReady")
            }
            // Felt and bootstrap each owe one more present. After Ready, the
            // runtime asks only while something is still moving.
            if (bootPhase != BootPhase.Ready) {
                Gdx.graphics.requestRendering()
            }
        }
    }

    override fun dispose() {
        bootstrapFace?.dispose()
        bootstrapFace = null
        // The runtime closes the app scope (every screen it built and the GL shell);
        // before bootstrap there is nothing but the shell this launcher owns.
        runtime?.dispose() ?: scope.dispose()
    }
}
