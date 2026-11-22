package inego.zungjunggame.android;

import android.graphics.Insets;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.core.splashscreen.SplashScreen;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidGraphics;
import com.badlogic.gdx.backends.android.surfaceview.FillResolutionStrategy;
import com.badlogic.gdx.backends.android.surfaceview.ResolutionStrategy;

import inego.zungjunggame.BuildConfig;
import inego.zungjunggame.Main;
import inego.zungjunggame.net.ApiConfig;
import inego.zungjunggame.platform.AndroidGpuContexts;
import inego.zungjunggame.platform.GoogleAuthProvider;
import inego.zungjunggame.platform.GpuContextPolicy;
import inego.zungjunggame.platform.OverlayTextPrompt;
import inego.zungjunggame.platform.PresenceScheduler;
import inego.zungjunggame.platform.WebSocketClient;
import inego.zungjunggame.platform.ColdStartGate;
import inego.zungjunggame.i18n.PlatformLanguages;
import inego.zungjunggame.platform.PlatformTimeZone;
import inego.zungjunggame.platform.SafeArea;
import inego.zungjunggame.state.PlatformSecretBox;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Launches the Android application. */
public class AndroidLauncher extends AndroidApplication {
    private static final String TAG = "ZJLauncher";
    private static final String BOOT_TAG = "ZjBoot";
    private static final long PROCESS_T0_MS = System.currentTimeMillis();

    /**
     * Pre-Gdx boot trail. Honor often redacts logcat; also mirrors to
     * {@code files/zj_boot_android.log} (pull via run-as).
     */
    private void bootLog(String step) {
        long dt = System.currentTimeMillis() - PROCESS_T0_MS;
        String line = "android +" + dt + "ms " + step;
        Log.i(BOOT_TAG, line);
        try {
            File dir = getFilesDir();
            if (dir == null) return;
            File out = new File(dir, "zj_boot_android.log");
            try (FileOutputStream fos = new FileOutputStream(out, true)) {
                fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            File dir = getFilesDir();
            if (dir != null) {
                File out = new File(dir, "zj_boot_android.log");
                try (FileOutputStream fos = new FileOutputStream(out, false)) {
                    fos.write(("--- android cold start " + PROCESS_T0_MS + " ---\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        bootLog("onCreate:enter savedInstance=" + (savedInstanceState != null)
                + " sdk=" + Build.VERSION.SDK_INT);
        // Some OEM builds redact logcat; mirror uncaught exceptions to app files.
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final File crashFile = new File(getFilesDir(), "last-crash.txt");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String body = "thread=" + thread.getName() + "\n" + sw;
                try (FileOutputStream out = new FileOutputStream(crashFile, false)) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                Log.e(TAG, body, error);
            } catch (Throwable ignored) {
                // best-effort
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });

        // Must run before super.onCreate. Keeps icon-on-felt splash until Main
        // finishes bootstrap and draws the first UI frame (ColdStartGate).
        ColdStartGate.reset();
        final boolean[] loggedSplashExit = { false };
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setKeepOnScreenCondition(() -> {
            boolean ready = ColdStartGate.isContentReady();
            if (ready && !loggedSplashExit[0]) {
                loggedSplashExit[0] = true;
                bootLog("splash:release contentReady=true");
            }
            return !ready;
        });
        bootLog("onCreate:SplashScreen installed keepUntil=ColdStartGate");

        super.onCreate(savedInstanceState);
        // Stage C. Built here, before any policy is handed out, because
        // `createGraphics` runs from inside `initialize` and the graphics subclass
        // has to report into the *same* instance the factory returns — two objects
        // would mean the notify lands on a policy nobody is subscribed to.
        gpuContexts = new AndroidGpuContexts();
        GpuContextPolicy.Companion.setFactory(() -> gpuContexts);
        WebSocketClient.Companion.setFactory(AndroidWebSocketClient::new);
        GoogleAuthProvider.Companion.setFactory(() -> new AndroidGoogleAuthProvider(this));
        OverlayTextPrompt.Companion.setFactory(() -> new AndroidOverlayTextPrompt(this));
        // Continuous rendering is off, so a settled screen presents no frame. The
        // runtime's one-second presence tick therefore needs a platform clock on
        // Android as it has on desktop and Wasm; without it a still signed-in
        // screen stops heartbeating and the server expires its presence.
        PresenceScheduler.Companion.setFactory(AndroidPresenceScheduler::new);
        // Stage C: Android is durable, and this is the web's shape rather than a
        // third guess. `AndroidGpuContexts` declares `contextsAreDurable = true`
        // because libGDX calls `view.setPreserveEGLContextOnPause(true)` in the
        // `AndroidGraphics` constructor, so a Home press suspends the GL thread
        // without destroying the EGL context — confirmed on the attached Honor,
        // where PID and `ActivityRecord` both survive a background/return and
        // `always_finish_activities=1` does not change that.
        // A rotation is not a loss either: the manifest lists
        // `orientation|screenSize` on `configChanges`, so it arrives as
        // `onSurfaceChanged` on the same graphics object.
        //
        // That makes the A5 measurement — 438–493 ms of glyph regeneration on every
        // background/return — pure cost, and it is now gone.
        //
        // Durable alone would be blind, so the *same change* installs the real
        // signal: the graphics subclass in `createGraphics` reports a second
        // `onSurfaceCreated` on the same graphics object. Never the first one —
        // `AppRuntime` subscribes from `create()`, which libGDX calls from
        // `onSurfaceChanged` *after* that first surface, so an early notify would
        // race the coordinator's first-writer and run loss recovery against a
        // batch that had just been created (the felt-only boot Stage G gated off).
        //
        // See `gpuContexts` above for the install, which must precede this one.
        // One box for the process: the state store seals the auth session with it.
        final AndroidKeystoreSecretBox secretBox = AndroidKeystoreSecretBox.create();
        PlatformSecretBox.INSTANCE.setProvider(() -> secretBox);
        // First launch only. Sampled here, on the main thread, before the GL
        // boot asks PlatformLanguages. The configuration list already includes
        // a per-app language.
        final java.util.List<String> languageTags =
                AndroidSystemLanguages.preferredTags(getResources().getConfiguration());
        PlatformLanguages.INSTANCE.setProvider(() -> languageTags);
        // The device zone. A per-app language does not change it. Installed
        // before a signed-in refresh reports it for email reports.
        PlatformTimeZone.INSTANCE.setProvider(() -> java.time.ZoneId.systemDefault().getId());
        // The same zone, as the offset in force at an instant. The rating
        // chart's popup places a candle in the player's own clock, and a zone's
        // offset follows its own rules, so this is asked per instant.
        PlatformTimeZone.INSTANCE.setOffsetProvider(millis -> {
            try {
                return java.time.ZoneId.systemDefault().getRules()
                        .getOffset(java.time.Instant.ofEpochMilli(millis))
                        .getTotalSeconds() / 60;
            } catch (Throwable t) {
                return null;
            }
        });
        // HINT stays rank-only here on purpose: platform.McHints.enabled defaults to
        // false, because the MC worker thread competes with the GL thread and the
        // search is far slower on a phone than on desktop.
        applyApiBaseUrl();
        bootLog("onCreate:after super api=" + ApiConfig.INSTANCE.getBaseUrl());
        AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
        // Must be set explicitly: libGDX defaults this to *true*, and the flag hides
        // both bars — the old `= true` here was redundant with that default. The
        // navigation bar is the user's only way off the screen, so the app turns the
        // switch off and `installWindowChrome` owns the policy instead.
        configuration.useImmersiveMode = false;
        configuration.useWakelock = true; // Keep the screen on while a table is in the foreground.
        // Choreographer presents on the display blank. setForegroundFPS is a no-op
        // on this backend, so there is no second cap to fight that clock.
        // Main turns continuous rendering off; a still picture is not redrawn.
        bootLog("onCreate:initialize(Main) begin");
        initialize(new Main(), configuration);
        bootLog("onCreate:initialize(Main) returned (GL create/render may still be pending)");
        installWindowChrome();
        installSafeAreaInsets();
        bootLog("onCreate:exit");
    }

    /**
     * Install the app's own {@link AndroidGraphics} so a real context re-entry is
     * reported, and (in a debuggable build) optionally stop the platform from
     * preserving the context across a pause.
     *
     * <p>libGDX calls this from {@code init(...)} during {@code onCreate}, before
     * {@link #initialize}, so the policy installed above is already in place and
     * this graphics object is the one {@code AppRuntime} will find through
     * {@code GpuContextPolicy.create()}.
     */
    @Override
    protected AndroidGraphics createGraphics(AndroidApplicationConfiguration config) {
        // The null branch mirrors libGDX's own `createGraphics` default, so the
        // resolution strategy is chosen the same way it would have been without
        // this override. (The launcher never nulls it; the branch is not the point.)
        ResolutionStrategy strategy = config.resolutionStrategy != null
                ? config.resolutionStrategy
                : new FillResolutionStrategy();
        return new LossReportingGraphics(this, config, strategy, gpuContexts);
    }

    /**
     * The one place this process's {@link AndroidGpuContexts} lives.
     *
     * <p>Written in {@code onCreate} before {@code initialize(...)}, because
     * {@code createGraphics} is called from inside it and needs the object the
     * {@code GpuContextPolicy} factory is also handing out — the two must be the
     * same instance or the notify lands on a policy nobody is subscribed to.
     */
    private AndroidGpuContexts gpuContexts;

    /**
     * One-shot guard for the chrome-policy boot line. {@link #installWindowChrome} is
     * re-applied on every focus change; the policy only needs saying once.
     */
    private boolean loggedChromePolicy;

    /**
     * {@link AndroidGraphics} that reports a second {@code onSurfaceCreated}.
     *
     * <p><b>Why a subclass, and why here.</b> {@code AndroidGraphics.created} is
     * package-private, so the "this is the second surface" fact is not readable
     * from a launcher in another package. The subclass keeps its own flag instead
     * of reaching for it: it is the same fact, and reading libGDX's field would
     * couple the launcher to an internal that carries no promise.
     *
     * <p><b>Why {@code super} runs first.</b> libGDX's own
     * {@code onSurfaceCreated} calls {@code setupGL} and invalidates every
     * managed mesh, texture, cubemap, texture array, shader program and frame
     * buffer. That is most of the repair, and it must be in place before the
     * notify asks for the rest. The notify itself only <em>requests</em>
     * recovery; the rebuild is spent on the next frame, by which point {@code super}
     * has long returned.
     *
     * <p><b>Why the first surface is silent.</b> {@code create()} — and with it
     * {@code AppRuntime}'s subscription — is called from libGDX's
     * {@code onSurfaceChanged}, which the GL thread runs <em>after</em> the first
     * {@code onSurfaceCreated}. A notify on that first call could win the
     * coordinator's first-writer race ahead of {@code new runtime}, and would run
     * loss recovery against a batch that had just been created: the felt-only boot
     * Stage G explicitly gated off.
     */
    private static final class LossReportingGraphics extends AndroidGraphics {
        /** The launcher, for the boot trail only (a static nested class). */
        private final AndroidLauncher launcher;
        private final AndroidGpuContexts contexts;
        /** False until this graphics object has seen its first surface. */
        private boolean seenSurface = false;

        LossReportingGraphics(AndroidLauncher application, AndroidApplicationConfiguration config,
                              ResolutionStrategy strategy, AndroidGpuContexts contexts) {
            super(application, config, strategy);
            this.launcher = application;
            this.contexts = contexts;
            maybeForceContextLoss();
        }

        /**
         * Stop {@code GLSurfaceView} from preserving the EGL context, so a Home
         * press really does destroy it — the only way to reach the loss path this
         * class exists to report, because the platform will not lose it on its own.
         *
         * <p><b>Debuggable builds only</b>, and behind one property read once at
         * startup: {@code debug.zj.force_egl_context_loss=1}. Release builds
         * ignore it, and a user cannot reach it, so a shipped app cannot be talked
         * into dropping every GL object on its first Home press.
         *
         * <p>This is {@code GLSurfaceView}'s own contract rather than a trick:
         * {@code setPreserveEGLContextOnPause(false)} is documented to release the
         * context on pause and recreate it on resume, which is exactly the
         * {@code onSurfaceCreated} re-entry being tested. It is the *opposite* of
         * what {@link com.badlogic.gdx.backends.android.AndroidGraphics}'s
         * constructor sets, so it has to be set after {@code super} — which is
         * also why the property is read here rather than before.
         *
         * <p>Not to be confused with {@code adb shell setprop
         * debug.egl.force_context_loss 1}: that is not an AOSP EGL property, so a
         * green run of it would not mean anything. And {@code adb shell am kill} is
         * process death, which runs the cold-start path instead.
         */
        private void maybeForceContextLoss() {
            if (!BuildConfig.DEBUG) return;
            if (!"1".equals(systemProperty("debug.zj.force_egl_context_loss"))) return;
            if (getView() instanceof GLSurfaceView) {
                ((GLSurfaceView) getView()).setPreserveEGLContextOnPause(false);
                Log.i(TAG, "egl-loss: preserve OFF (debug.zj.force_egl_context_loss=1)");
            }
        }

        /**
         * Read a system property without naming a hidden API.
         *
         * <p>{@code android.os.SystemProperties} is not in {@code android.jar}, so
         * it cannot be referenced directly, and on API 28+ it is subject to non-SDK
         * interface restrictions. Both are fine for a debug-only, best-effort knob:
         * reflection degrades to "property unavailable", which is the correct
         * outcome for tooling that is off by default.
         */
        private static String systemProperty(String key) {
            try {
                Class<?> clazz = Class.forName("android.os.SystemProperties");
                Method get = clazz.getMethod("get", String.class, String.class);
                Object value = get.invoke(null, key, "");
                return value == null ? "" : value.toString();
            } catch (Throwable t) {
                Log.i(TAG, "egl-loss: property '" + key + "' unavailable (" + t.getMessage() + ")");
                return "";
            }
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            super.onSurfaceCreated(gl, config);
            if (seenSurface) {
                launcher.bootLog("surface:recreated — EGL context is new; requesting recovery");
                contexts.onSurfaceRecreated();
            } else {
                seenSurface = true;
                launcher.bootLog("surface:first — initial creation, not a loss");
            }
        }
    }

    /**
     * Resolve the API base URL before {@link #initialize} runs.
     * <p>
     * A build with {@code -PapiBaseUrl=...} carries a fixed address
     * ({@link BuildConfig#API_BASE_URL}); it wins and the LAN dev file is
     * ignored. Dev builds fall back to {@code files/dev-api-url.txt}, written
     * by the Gradle {@code writeDevApiUrl} task.
     */
    private void applyApiBaseUrl() {
        if (ApiConfig.INSTANCE.bakeBaseUrl(BuildConfig.API_BASE_URL)) {
            bootLog("api-base-url: baked " + ApiConfig.INSTANCE.getBaseUrl());
            return;
        }
        try {
            File dir = getFilesDir();
            if (dir == null) return;
            File file = new File(dir, ApiConfig.DEV_URL_FILE);
            if (!file.isFile()) {
                bootLog("dev-api-url: missing, using " + ApiConfig.INSTANCE.getBaseUrl());
                return;
            }
            String text;
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[(int) file.length()];
                int n = in.read(buf);
                text = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            }
            ApiConfig.INSTANCE.applyDevUrlFile(text, localLanIpv4());
            bootLog("dev-api-url: applied " + ApiConfig.INSTANCE.getBaseUrl());
        } catch (Throwable t) {
            bootLog("dev-api-url: " + t.getMessage());
        }
    }

    private static String localLanIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip != null && !ip.startsWith("169.254.")) return ip;
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return null;
    }

    @Override
    protected void onResume() {
        bootLog("onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        bootLog("onPause");
        super.onPause();
    }

    /**
     * Window policy: draw edge-to-edge, hide the status bar, never the navigation bar.
     *
     * <p>The status bar is information — a swipe from the top reveals it as a transient
     * overlay, and nothing is lost while it is gone. The navigation bar is the only way
     * off the screen, so hiding it strands a three-button user. On a gesture-navigation
     * phone (where the Android layout was tuned) the system draws no permanent row, so
     * the cost of hiding the bar is invisible; that is why the defect shipped.
     * libGDX's {@code useImmersiveMode} is deliberately unused: it is one switch for
     * both bars, which is exactly the coupling this policy has to break.
     *
     * <p>{@code setDecorFitsSystemWindows(false)} keeps the GL surface behind the bars.
     * Without it the window would shrink by the bar height and the game would stop
     * being edge-to-edge. Android 15+ enforces that layout for this target SDK anyway,
     * so the app must draw behind the bars and reserve them itself — see
     * {@link #applySafeAreaFromInsets}.
     *
     * <p>Idempotent, and re-applied on window focus: a legacy flag set can be cleared
     * by the system around dialogs and focus loss.
     */
    private void installWindowChrome() {
        if (!loggedChromePolicy) {
            loggedChromePolicy = true;
            bootLog("windowChrome: edge-to-edge, status hidden, navigation kept (sdk="
                    + Build.VERSION.SDK_INT + ")");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window window = getWindow();
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            // A top swipe shows the status bar; it hides itself again.
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.statusBars());
            return;
        }
        // API 28-29: no WindowInsetsController and no setDecorFitsSystemWindows.
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) installWindowChrome();
    }

    /**
     * Report the permanently occupied screen area into [SafeArea] so core UI can keep
     * interactive chrome out of the front camera / notches (especially landscape, when
     * the punch-hole sits on a left or right edge) and above the navigation bar — see
     * {@link #applySafeAreaFromInsets}.
     */
    private void installSafeAreaInsets() {
        allowLayoutIntoDisplayCutout();

        final AndroidGraphics g = (AndroidGraphics) getGraphics();
        final View view = g.getView();
        if (view == null) {
            Log.w(TAG, "SafeArea: graphics view is null; insets will stay zero");
            return;
        }

        view.setOnApplyWindowInsetsListener((v, insets) -> {
            applySafeAreaFromInsets(v, insets);
            // Do not consume — libGDX / system may still need them.
            return insets;
        });

        // First apply (rotation / multi-window will re-fire the listener).
        view.requestApplyInsets();
        WindowInsets current = view.getRootWindowInsets();
        if (current != null) {
            applySafeAreaFromInsets(view, current);
        }
    }

    /**
     * Draw under the display cutout on every supported version.
     *
     * <p>{@link WindowManager.LayoutParams#LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS} is the
     * value Android 15 requires of a target-35 non-floating window, and it is the one this
     * app wants anyway: the felt reaches every edge while {@link SafeArea} keeps chrome out
     * of the camera hole. {@code ALWAYS} only exists from API 30, so API 28-29 keeps the
     * short-edge mode — the closest value that level defines, and exactly the fallback
     * {@code WindowCompat.enableEdgeToEdge(window)} uses there.
     */
    private void allowLayoutIntoDisplayCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
    }

    /**
     * Publish the layout safe area into {@link SafeArea}: the display cutout, the navigation
     * bar and the window caption bar, per side.
     *
     * <p>Both the cutout and the navigation bar are permanent under the policy in
     * {@link #installWindowChrome}: a rotating cutout re-delivers insets, and a kept-visible
     * navigation bar reports the same value for as long as the device's navigation mode
     * holds. That matters because the layout reads this every frame.
     *
     * <p>The navigation bar belongs here for two reasons. On a three-button device the
     * buttons are the user's navigation, so bottom chrome must sit above them rather than
     * beneath them. On gesture navigation the handle is drawn by the system over the app,
     * and a tap or drag in that strip is the system's, not a widget's — reserving it keeps
     * the bottom row out of the conflict either way. The left/right back-gesture strips are
     * deliberately not reserved: they are swipe-priority, not occupied space, and taking
     * them would inset the board on both sides.
     *
     * <p>The caption bar is the desktop-windowing title bar Android 16 draws over the app
     * even in immersive mode. It is zero on a phone and on every window that does not have
     * one, so including it changes nothing there; omitting it is what would let the top
     * chrome slide under the window controls on a freeform/desktop window.
     *
     * <p>The status bar is deliberately excluded: it is the transient one. It is hidden by
     * policy and a top swipe reveals it as an overlay, so the window-insets listener fires
     * with the bar present and again when it hides. Publishing that would give two
     * different layout values inside one transition, and a frame laid out from the first
     * would be presented and then jump — the menu card by half the delta (it re-centers in
     * the safe rect) and the bottom player row by the whole delta (it is anchored to the
     * edge). A cutout, a kept-visible bar and a caption bar cannot do that.
     *
     * <p>The top-right corner is published separately, through
     * {@link #publishTopRightCorner}: an edge inset says how deep the furniture reaches
     * <em>somewhere</em> along that edge, which is what a row crossing the whole edge
     * needs, but a corner button needs to know whether that particular corner is free.
     */
    private static void applySafeAreaFromInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            Insets bars = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets caption = insets.getInsets(WindowInsets.Type.captionBar());
            SafeArea.setPixels(
                    Math.max(cutout.left, Math.max(bars.left, caption.left)),
                    Math.max(cutout.top, Math.max(bars.top, caption.top)),
                    Math.max(cutout.right, Math.max(bars.right, caption.right)),
                    Math.max(cutout.bottom, Math.max(bars.bottom, caption.bottom)));
            publishTopRightCorner(
                    view,
                    insets.getDisplayCutout(),
                    Math.max(bars.right, caption.right),
                    Math.max(bars.top, caption.top));
        } else {
            applySafeAreaFromInsetsPreR(insets);
            // Pre-R has no per-type insets: the stable ones are the closest
            // equivalent, and over-reserving is the safe direction there.
            publishTopRightCorner(
                    view,
                    insets.getDisplayCutout(),
                    insets.getStableInsetRight(),
                    insets.getStableInsetTop());
        }
    }

    /**
     * Publish how far the window's top-right corner is occupied, in screen pixels, so
     * corner chrome can sit flush when that corner is open.
     *
     * <p>The per-side max published above cannot answer this: a punch-hole in the middle
     * of a long side makes {@code right} a full notch deep while leaving the corner free.
     * The cutout's bounding rects can — they are in window coordinates in the current
     * rotation, with the origin at the window's top-left. A rect that touches both the
     * right and the top edge of the view is a corner hole.
     *
     * <p>A permanent system strip is blocking wherever it is, so {@code barRight} and
     * {@code barTop} (the navigation bar and the caption bar, which cannot move while the
     * app runs) are part of the corner depth whatever the cutout reports: a landscape
     * three-button navigation bar on the right edge blocks the whole right edge, corner
     * included. The deepest of the bar and any corner hole wins per axis, and both depths
     * stay zero when nothing occupies the corner (a mid-edge hole, a top-centre notch, no
     * cutout).
     *
     * <p>The depths are measured from the edges a caller places chrome against: {@code
     * right} is the occupied width in from the right edge, {@code top} its height down
     * from the top edge.
     *
     * <p>Before the first layout the view has no bounds and the corner is cleared; the next
     * insets pass, which the layout triggers, publishes the real value.
     */
    private static void publishTopRightCorner(
            View view, DisplayCutout cutout, int barRight, int barTop) {
        final int w = view.getWidth();
        final int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            SafeArea.setTopRightCorner(0, 0);
            return;
        }
        int right = Math.max(barRight, 0);
        int top = Math.max(barTop, 0);
        if (cutout != null) {
            for (Rect hole : cutout.getBoundingRects()) {
                if (hole.right >= w && hole.top <= 0) {
                    right = Math.max(right, w - hole.left);
                    top = Math.max(top, hole.bottom);
                }
            }
        }
        SafeArea.setTopRightCorner(right, top);
    }

    /**
     * Pre-API 30: {@link WindowInsets#getInsets(int)} does not exist, so the per-side
     * numbers come from the {@code getStableInset*} getters. Stable is the right word for
     * the same reason the navigation bar is published: it is kept visible, so its inset
     * cannot change while the app runs. The hidden status bar contributes zero, and the
     * per-side max keeps the cutout's punch-hole side.
     */
    private static void applySafeAreaFromInsetsPreR(WindowInsets insets) {
        final DisplayCutout cutout = insets.getDisplayCutout(); // API 28 = minSdk
        final int cutoutLeft = cutout == null ? 0 : cutout.getSafeInsetLeft();
        final int cutoutTop = cutout == null ? 0 : cutout.getSafeInsetTop();
        final int cutoutRight = cutout == null ? 0 : cutout.getSafeInsetRight();
        final int cutoutBottom = cutout == null ? 0 : cutout.getSafeInsetBottom();
        SafeArea.setPixels(
                Math.max(cutoutLeft, insets.getStableInsetLeft()),
                Math.max(cutoutTop, insets.getStableInsetTop()),
                Math.max(cutoutRight, insets.getStableInsetRight()),
                Math.max(cutoutBottom, insets.getStableInsetBottom()));
    }
}
