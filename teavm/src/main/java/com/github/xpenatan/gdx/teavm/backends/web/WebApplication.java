/*
 * Derived from gdx-teavm 1.6.2 (https://github.com/xpenatan/gdx-teavm),
 * licensed under the Apache License, Version 2.0. Modified for this project;
 * see NOTICE and third_party/gdx-teavm/.
 */
package com.github.xpenatan.gdx.teavm.backends.web;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.ObjectMap;
import com.github.xpenatan.gdx.teavm.backends.shared.SharedApplicationLogger;
import com.github.xpenatan.gdx.teavm.backends.web.agent.WebAgentInfo;
import com.github.xpenatan.gdx.teavm.backends.web.agent.WebWebAgent;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetDownloadImpl;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetInstance;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoadImpl;
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoaderListener;
import com.github.xpenatan.gdx.teavm.backends.web.dom.impl.WebWindow;
import com.github.xpenatan.gdx.teavm.backends.web.utils.WebNavigator;
import com.github.xpenatan.gdx.teavm.backends.web.webaudio.howler.HowlTeaAudio;
import com.github.xpenatan.jmultiplatform.core.JMultiplatform;
import com.github.xpenatan.jmultiplatform.core.JPlatformMap;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSExceptions;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Storage;
import org.teavm.jso.dom.events.EventListener;

/**
 * Same package/name as gdx-teavm's {@code WebApplication} so this class shadows it.
 *
 * <p>The {@code pagehide}, {@code visibilitychange} and {@code resize} listeners are
 * browser callbacks: JavaScript invokes their {@code handleEvent} directly, so TeaVM
 * exports it, and an export that is a coroutine reads {@code Fiber.current()} in its
 * prologue and traps once {@code main} has returned. The {@code visibilitychange}
 * body is one because of its {@code synchronized (lifecycleListeners)} blocks, which
 * is the {@code WebApplication$2.handleEvent$exported$0} /
 * {@code org.teavm.runtime.Fiber::isResuming} null-pointer crash on tab switch.
 *
 * <p>Each {@code handleEvent} here only calls {@link BrowserFiber#enter}; the real
 * body runs on that daemon fiber, where {@code Fiber.current} is set. See
 * {@link BrowserFiber}.
 *
 * @author xpenatan
 */
public class WebApplication implements Application {

    private static WebAgentInfo agentInfo;

    private int initQueue;

    public static WebAgentInfo getAgentInfo() {
        return agentInfo;
    }

    public static WebApplication get() {
        return (WebApplication)Gdx.app;
    }

    private WebGraphics graphics;
    private WebInput input;
    private WebFiles files;
    private WebNet net;
    private final WebApplicationConfiguration config;
    private ApplicationListener appListener;
    private ApplicationListener curListener;
    private final Array<LifecycleListener> lifecycleListeners = new Array<>(4);
    private final WebWindow window;

    private AppState initState = AppState.INIT;
    private boolean isPreloadReady = false;

    private int lastWidth = -1;
    private int lastHeight = 1;

    private ApplicationLogger logger;
    private int logLevel = LOG_INFO;

    private AssetLoadImpl assetLoader;

    private final ObjectMap<String, Preferences> prefs = new ObjectMap<>();

    private WebClipboard clipboard;

    private final Array<Runnable> runnables = new Array<>();
    private final Array<Runnable> runnablesHelper = new Array<>();

    private boolean stepError;

    /** The live present loop, once the constructor has built it. */
    private Runnable appLoop;

    /**
     * Preload frames. This is the only callback that installs {@link #appListener}.
     * A wake must not replace it: {@link WebWindow} keeps a single animation slot.
     */
    private Runnable bootLoop;

    /** True while [step] is on the stack, so a wake does not nest a frame. */
    private boolean inFrame;

    /** An animation frame is already queued. */
    private boolean frameScheduled;

    /** Something asked for a present that this frame has not yet scheduled. */
    private boolean renderRequested;

    /**
     * The backend draws nothing until {@code frameId} passes this, then calls
     * {@code render}. The loop has to keep scheduling through that preamble or
     * the first real frame never arrives.
     */
    private static final long FIRST_RENDER_FRAME = 60;

    public WebApplication(ApplicationListener appListener, WebApplicationConfiguration config) {
        this(appListener, null, config);
    }

    public WebApplication(ApplicationListener appListener, ApplicationListener preloadAppListener, WebApplicationConfiguration config) {
        this.window = WebWindow.get();
        this.config = config;
        this.appListener = appListener;
        if(preloadAppListener == null) {
            preloadAppListener = createDefaultPreloadAppListener();
        }
        curListener = preloadAppListener;
        init();
    }

    protected ApplicationListener createDefaultPreloadAppListener() {
        return new WebPreloadApplicationListener();
    }

    protected void init() {
        updateBrowserErrorStack();
        WebApplication.agentInfo = WebWebAgent.computeAgentInfo();
        System.setProperty("java.runtime.name", "");
        System.setProperty("userAgent", WebApplication.agentInfo.getUserAgent());
        if(agentInfo.isWindows())
            System.setProperty("os.name", "Windows");
        else if(agentInfo.isMacOS())
            System.setProperty("os.name", "OS X");
        else if(agentInfo.isLinux())
            System.setProperty("os.name", "Linux");
        else
            System.setProperty("os.name", "no OS");

        AssetDownloadImpl assetDownload = new AssetDownloadImpl(config.showDownloadLogs);
        AssetInstance.setInstance(assetDownload);
        String hostPageBaseURL = config.baseUrlProvider.getBaseUrl();
        assetLoader = new AssetLoadImpl(hostPageBaseURL, this, assetDownload);
        AssetInstance.setInstance(assetLoader);

        JMultiplatform instance = JMultiplatform.getInstance();
        JPlatformMap map = instance.getMap();
        String WEB_SCRIPT_PATH = "WEB_SCRIPT_PATH";
        map.put(WEB_SCRIPT_PATH, assetLoader.getScriptUrl());
        String WEB_ASSET_PATH = "WEB_ASSET_PATH";
        map.put(WEB_ASSET_PATH, assetLoader.getAssetUrl());

        graphics = createGraphics(config);
        assetLoader.setupFileDrop(graphics.canvas, this);

        input = new WebInput(this, graphics.canvas);
        files = new WebFiles(config, this);
        net = new WebNet();
        logger = new SharedApplicationLogger();
        clipboard = new WebClipboard();

        initNativeLibraries();

        if(config.preloadListener != null) {
            config.preloadListener.onPreload(assetLoader);
        }

        Gdx.app = this;
        Gdx.graphics = graphics;
        Gdx.gl = graphics.getGL20();
        Gdx.gl20 = graphics.getGL20();
        Gdx.gl30 = graphics.getGL30();
        Gdx.input = input;
        Gdx.files = files;
        Gdx.net = net;

        window.addEventListener("pagehide", (EventListener) evt -> {
            // Browser callback. Only the hop; see BrowserFiber.
            BrowserFiber.enter(() -> {
                if(curListener != null) {
                    curListener.pause();
                    curListener.dispose();
                    curListener = null;
                }
            });
        });

        window.getDocument().addEventListener("visibilitychange", evt -> {
            // Browser callback. Only the hop; see BrowserFiber. Without it this
            // export is a coroutine (the synchronized blocks below) and traps.
            BrowserFiber.enter(() -> {
                // notify of state change
                if(initState == AppState.APP_LOOP) {
                    String state = window.getDocument().getVisibilityState();
                    if (state.equals("hidden")) {
                        // hidden: i.e. we are paused
                        synchronized (lifecycleListeners) {
                            for (LifecycleListener listener : lifecycleListeners) {
                                listener.pause();
                            }
                        }
                        curListener.pause();
                    }
                    else if(state.equals("visible")){
                        // visible: i.e. we resume
                        synchronized (lifecycleListeners) {
                            for (LifecycleListener listener : lifecycleListeners) {
                                listener.resume();
                            }
                        }
                        curListener.resume();
                        wakeFrame();
                    }
                }
            });
        });

        // The authoritative WebGL context-loss signal, which
        // nothing in this backend listened for before 2026-09-29. It started as the
        // diagnostic that made `GpuContextPolicy.contextsAreDurable` measurable and
        // is now load-bearing: the web installs durable, which switches off the
        // frame-gap rebuild behind the reported ~1.2 s freeze, and this is what
        // repairs a real loss (a GPU driver reset, an OS-reclaimed tab).
        //
        // `graphics.canvas` is the same element `setupFileDrop` already uses
        // (`protected`, same package), so no DOM query is needed.
        WebGpuContextSignal.attach(graphics.canvas);

        if(config.isAutoSizeApplication()) {
            window.addEventListener("resize", (EventListener) evt -> {
                // Browser callback. Only the hop; see BrowserFiber.
                BrowserFiber.enter(() -> {
                    int width = window.getClientWidth() - config.padHorizontal;
                    int height = window.getClientHeight() - config.padVertical;

                    if(width <= 0 || height <= 0) {
                        return;
                    }

                    if(graphics != null) {
                        graphics.setCanvasSize(width, height, config.usePhysicalPixels);
                        wakeFrame();
                    }
                });
            });
        }

        appLoop = new Runnable() {
            @Override
            public void run() {
                frameScheduled = false;
                // Resize and pointer events call wakeFrame between preload frames.
                // That used to overwrite bootLoop, so setPreloadReady never installed Main.
                if(appListener != null) {
                    window.requestAnimationFrame(bootLoop);
                    return;
                }
                inFrame = true;
                try {
                    step(curListener);
                } finally {
                    inFrame = false;
                }
                if(stepError) {
                    return;
                }
                // Stock WebGraphics skips listener.render until frameId passes
                // FIRST_RENDER_FRAME. Keep the chain alive through that, then
                // only while continuous or someone asked for another present.
                if(graphics.isContinuousRendering()
                    || renderRequested
                    || graphics.frameId <= FIRST_RENDER_FRAME) {
                    renderRequested = false;
                    scheduleFrame(this);
                }
            }
        };

        bootLoop = new Runnable() {
            @Override
            public void run() {
                AppState state = initState;
                switch(state) {
                    case APP_LOOP:
                        step(curListener);
                        break;
                    case INIT:
                        if(initQueue == 0) {
                            step(curListener);
                        }
                        break;
                }
                if(stepError) {
                    return;
                }
                if(isPreloadReady) {
                    initState = AppState.INIT;
                    curListener.dispose();
                    curListener = appListener;
                    appListener = null;
                    window.requestAnimationFrame(appLoop);
                }
                else {
                    window.requestAnimationFrame(this);
                }
            }
        };
        window.requestAnimationFrame(bootLoop);
    }

    private void step(ApplicationListener appListener) {
        int width = Gdx.graphics.getWidth();
        int height = Gdx.graphics.getHeight();

        boolean resizeBypass = false;

        try {
            graphics.begin();

            if(initState == AppState.INIT) {
                initState = AppState.APP_LOOP;
                input.setInputProcessor(null);
                input.reset();
                runnables.clear();
                graphics.frameId = 0;
                appListener.create();
                resizeBypass = true;
            }

            if((width != lastWidth || height != lastHeight) || resizeBypass) {
                lastWidth = width;
                lastHeight = height;
                graphics.resize(appListener, width, height);
            }

            runnablesHelper.addAll(runnables);
            runnables.clear();
            for(int i = 0; i < runnablesHelper.size; i++) {
                runnablesHelper.get(i).run();
            }
            runnablesHelper.clear();
            graphics.frameId++;
            if(graphics.frameId > 60) { // A bit of delay before rendering so fps don't start with 0
                graphics.render(appListener);
            }
            input.reset();

        } catch(Throwable t) {
            stepError = true;
            onError(t);
        } finally {
            graphics.end();
        }
    }

    protected void onError(Throwable error) {
        printErrorStack(error);
    }

    public static void printErrorStack(Object obj) {
        Throwable error = (Throwable)obj;
        ArrayList<JSObject> errors = new ArrayList<>();
        ArrayList<String> throwables = new ArrayList<>();
        Throwable root = error;
        while(root != null) {
            JSObject jsException = JSExceptions.getJSException(root);
            errors.addFirst(jsException);
            String msg = root.getMessage();
            if(msg == null) msg = "";
            throwables.addFirst(root.getClass().getSimpleName() + " " + msg);
            root = root.getCause();
        }
        JSObject[] errorsJS = new JSObject[errors.size()];
        String[] exceptions = new String[errors.size()];
        errors.toArray(errorsJS);
        throwables.toArray(exceptions);
        groupCollapsed("%cFatal Error" + ": " + exceptions[0], "color: #FF0000");

        for(int i = 0; i < errorsJS.length; i++) {
            int count = i + 1;
            JSObject errorJS = errorsJS[i];
            if(i > 0) { // Already logged the first one
                consoleLog("%cException " + count + ": " + exceptions[i], "color: #FF0000");
            }
            consoleLogError(errorJS);
        }
        groupEnd();
    }

    @JSBody(params = { "error" }, script = "console.log(error);")
    private static native void consoleLogError(JSObject error);

    @JSBody(script = """
        if(typeof Error.stackTraceLimit !== 'undefined') {
          Error.stackTraceLimit = Infinity;
        }""")
    private static native void updateBrowserErrorStack();

    @JSBody(params = { "msg" , "param"}, script = "console.log(msg, param);")
    private static native void consoleLog(String msg, String param);

    @JSBody(params = { "text", "param" }, script = "console.groupCollapsed(text, param);")
    private static native void groupCollapsed(String text, String param);

    @JSBody(script = "console.groupEnd();")
    private static native void groupEnd();

    public WebApplicationConfiguration getConfig() {
        return config;
    }

    @Override
    public ApplicationListener getApplicationListener() {
        return curListener;
    }

    @Override
    public Graphics getGraphics() {
        return graphics;
    }

    @Deprecated
    @Override
    public Audio getAudio() {
        return null;
    }

    @Override
    public Input getInput() {
        return input;
    }

    @Override
    public Files getFiles() {
        return files;
    }

    @Override
    public Net getNet() {
        return net;
    }

    @Override
    public void log(String tag, String message) {
        if(logLevel >= LOG_INFO) getApplicationLogger().log(tag, message);
    }

    @Override
    public void log(String tag, String message, Throwable exception) {
        if(logLevel >= LOG_INFO) getApplicationLogger().log(tag, message, exception);
    }

    @Override
    public void error(String tag, String message) {
        if(logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message);
    }

    @Override
    public void error(String tag, String message, Throwable exception) {
        if(logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message, exception);
    }

    @Override
    public void debug(String tag, String message) {
        if(logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message);
    }

    @Override
    public void debug(String tag, String message, Throwable exception) {
        if(logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message, exception);
    }

    @Override
    public void setLogLevel(int logLevel) {
        this.logLevel = logLevel;
    }

    @Override
    public int getLogLevel() {
        return logLevel;
    }

    @Override
    public void setApplicationLogger(ApplicationLogger applicationLogger) {
        this.logger = applicationLogger;
    }

    @Override
    public ApplicationLogger getApplicationLogger() {
        return logger;
    }

    @Override
    public ApplicationType getType() {
        return ApplicationType.WebGL;
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public long getJavaHeap() {
        return 0;
    }

    @Override
    public long getNativeHeap() {
        return 0;
    }

    @Override
    public Preferences getPreferences(String name) {
        Preferences pref = prefs.get(name);
        if(pref == null) {
            Storage storage = Storage.getLocalStorage();
            pref = new WebPreferences(storage, config.storagePrefix + ":" + name, config.shouldEncodePreference);
            prefs.put(name, pref);
        }
        return pref;
    }

    @Override
    public Clipboard getClipboard() {
        return clipboard;
    }

    @Override
    public void postRunnable(Runnable runnable) {
        runnables.add(runnable);
        wakeFrame();
    }

    /**
     * Queue one present. A request that arrives mid-frame is folded into the
     * decision at the end of that frame so two animation callbacks do not nest.
     */
    void wakeFrame() {
        renderRequested = true;
        if(inFrame || stepError || appLoop == null || initState != AppState.APP_LOOP) {
            return;
        }
        // appListener is still the game, waiting for the preload loop to install it.
        if(appListener != null) {
            return;
        }
        scheduleFrame(appLoop);
    }

    private void scheduleFrame(Runnable loop) {
        if(frameScheduled || loop == null || stepError) {
            return;
        }
        frameScheduled = true;
        window.requestAnimationFrame(loop);
    }

    @Override
    public void exit() {
    }

    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.add(listener);
        }
    }

    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        synchronized (lifecycleListeners) {
            lifecycleListeners.removeValue(listener, true);
        }
    }

    /** @return {@code true} if application runs on a mobile device */
    public static boolean isMobileDevice () {
        // RegEx pattern from detectmobilebrowsers.com (public domain)
        String pattern = "(android|bb\\d+|meego).+mobile|avantgo|bada\\/|blackberry|blazer|compal|elaine|fennec"
                + "|hiptop|iemobile|ip(hone|od)|iris|kindle|lge |maemo|midp|mmp|mobile.+firefox|netfront|opera m(ob|in)"
                + "i|palm( os)?|phone|p(ixi|re)\\/|plucker|pocket|psp|series(4|6)0|symbian|treo|up\\.(browser|link)"
                + "|vodafone|wap|windows ce|xda|xiino|android|ipad|playbook|silk";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(WebNavigator.getUserAgent().toLowerCase());
        return m.matches();
    }


    public void setPreloadReady() {
        isPreloadReady = true;
    }

    public int getInitQueue() {
        return initQueue;
    }

    public void addInitQueue() {
        initQueue++;
    }

    public void subtractInitQueue() {
        initQueue--;
    }

    public enum AppState {
        INIT,
        APP_LOOP
    }

    // Testing code only
    @JSBody(params = "text", script = "console.log(text);" )
    public static native void print(String text);

    // ##################### NATIVE CALLS #####################

    private void initNativeLibraries() {
        initGdxLibrary();
        initAudio();
    }

    protected void initGdxLibrary() {
        addInitQueue();
        assetLoader.loadScript("gdx.wasm.js", new AssetLoaderListener<>() {
            @Override
            public void onSuccess(String url, String result) {
                subtractInitQueue();
            }

            @Override
            public void onFailure(String url) {
                throw new RuntimeException("Gdx script failed to load");
            }
        });
    }

    protected void initAudio() {
        addInitQueue();
        assetLoader.loadScript("howler.js", new AssetLoaderListener<>() {
            @Override
            public void onSuccess(String url, String result) {
                subtractInitQueue();
                Gdx.audio = new HowlTeaAudio();
            }
        });
    }

    protected WebGraphics createGraphics (WebApplicationConfiguration config) {
        return new PacedWebGraphics(config, this);
    }
}
