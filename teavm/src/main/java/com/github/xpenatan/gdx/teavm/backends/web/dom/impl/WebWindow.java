/*
 * Derived from gdx-teavm 1.6.2 (https://github.com/xpenatan/gdx-teavm),
 * licensed under the Apache License, Version 2.0. Modified for this project;
 * see NOTICE and third_party/gdx-teavm/.
 */
package com.github.xpenatan.gdx.teavm.backends.web.dom.impl;

import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber;
import com.github.xpenatan.gdx.teavm.backends.web.dom.HTMLDocumentExt;
import org.teavm.jso.browser.AnimationFrameCallback;
import org.teavm.jso.browser.Location;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;

/**
 * Same package/name as gdx-teavm's {@code WebWindow} so this class shadows it.
 *
 * <p>The animation-frame functor only calls {@link BrowserFiber#enter}. The
 * frame itself runs on that daemon fiber. See {@link BrowserFiber}.
 */
public class WebWindow implements AnimationFrameCallback {

    private static final WebWindow TEA_WINDOW = new WebWindow();

    public static WebWindow get() {
        return TEA_WINDOW;
    }

    private final Window window;
    private Runnable runnable;
    /** A browser callback is already registered. Another one would fire after dispatch cleared the slot and drop the next frame. */
    private boolean frameQueued;

    public WebWindow() {
        this.window = Window.current();
    }

    public HTMLDocumentExt getDocument() {
        return (HTMLDocumentExt) window.getDocument();
    }

    public void requestAnimationFrame(Runnable runnable) {
        this.runnable = runnable;
        if (frameQueued) {
            return;
        }
        frameQueued = true;
        Window.requestAnimationFrame(this);
    }

    public void onAnimationFrame(double arg0) {
        BrowserFiber.enter(this::dispatch);
    }

    private void dispatch() {
        frameQueued = false;
        Runnable toRun = runnable;
        runnable = null;
        if (toRun != null) {
            toRun.run();
        }
    }

    public Location getLocation() {
        return window.getLocation();
    }

    public int getClientWidth() {
        return window.getInnerWidth();
    }

    public int getClientHeight() {
        return window.getInnerHeight();
    }

    public void addEventListener(String type, EventListener<Event> listener) {
        window.addEventListener(type, listener);
    }
}
