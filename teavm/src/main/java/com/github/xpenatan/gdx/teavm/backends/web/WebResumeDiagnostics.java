package com.github.xpenatan.gdx.teavm.backends.web;

import com.badlogic.gdx.Gdx;
import org.teavm.jso.browser.Performance;
import org.teavm.jso.dom.events.Event;

/**
 * The web's context-loss reporter.
 *
 * <p>Once carried the whole resume timeline and mirrored it into
 * {@code document.title}; both were removed when the stage closed. What is
 * left is the part that is **not** removable: the {@code contextlost} /
 * {@code contextrestored} lines have to reach the console, because they are the
 * only evidence that {@link WebGpuContextSignal}'s listeners attached and that a
 * loss actually fired. §9.1's measured negative — "the listener was live and
 * nothing fired", which is what proved the web's stall was ours and not the
 * browser's — is unreproducible from a build that does not print them.
 *
 * <p>So this is a guarded logger, not instrumentation. It was deliberately never
 * extended to wrap `console`: an earlier revision did, on the theory that patching
 * a global the preload pipeline logs through was what stopped the client booting
 * (black canvas, empty console — the picture {@code docs/guides/wasm-startup.md}
 * documents). That was <b>falsified</b> — a build with no diagnostic changes at
 * all is equally black in the same browser — and a global patch on the boot path
 * is a side effect for no gain, since {@code Gdx.app.log} already reaches the
 * console for anyone with DevTools open.
 */
public class WebResumeDiagnostics {

    private static final String TAG = "ZjCtxLoss";

    private WebResumeDiagnostics() {
    }

    /** Package-private so {@link WebGpuContextSignal} reports through one logger. */
    static String describe(Event evt) {
        try {
            // `Event` has no `getDefaultPrevented` in TeaVM's JSO bindings, so
            // report the cancelable flag instead — it is what makes preventDefault
            // meaningful on `webglcontextlost`.
            return "cancelable=" + evt.isCancelable()
                + " phase=" + evt.getEventPhase()
                + " now=" + Math.round(Performance.now()) + "ms";
        } catch (Throwable t) {
            return "describe failed: " + t.getClass().getSimpleName();
        }
    }

    /** Package-private so {@link WebGpuContextSignal} reports through one logger. */
    static void log(String step, String detail) {
        String tail = step + (detail.isEmpty() ? "" : " " + detail);
        try {
            // Diagnostics must never break a frame.
            if (Gdx.app != null) {
                Gdx.app.log(TAG, tail);
            } else {
                System.out.println(TAG + ": " + tail);
            }
        } catch (Throwable ignored) {
            // See above.
        }
    }
}
