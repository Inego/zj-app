package com.github.xpenatan.gdx.teavm.backends.web;

import java.util.ArrayList;
import java.util.List;

import org.teavm.jso.dom.html.HTMLCanvasElement;

/**
 * The browser's own answer to "was the GL context thrown away?", delivered to
 * whoever asks.
 *
 * <p>This started as a line inside {@link WebResumeDiagnostics}, and was promoted
 * to its own class once it became load-bearing: the web installs
 * {@code contextsAreDurable = true} (six measured away/return cycles
 * up to 155 s with **zero** loss events), which switches off the frame-gap rebuild
 * that produced the reported ~1.2 s freeze. Switching that off is only safe while
 * something still repairs a *real* loss, and this is that something. A GPU driver
 * reset, or an OS reclaiming a backgrounded tab, produces
 * {@code webglcontextrestored} and this class forwards it as the one event the
 * platform can report.
 *
 * <p>It lives in the backend package for the same reason {@code setupFileDrop}
 * does: only {@link WebApplication} has the {@code canvas}, and it is the one
 * caller of {@link #attach}.
 *
 * <p><b>Do not add a second listener for these two events.</b> {@code
 * webglcontextlost} is only followed by {@code webglcontextrestored} if the lost
 * event is cancelled; a second un-cancelled handler anywhere on the page would
 * make the restore optional and silence the signal this class exists to carry.
 * Reporting is delegated to {@link WebResumeDiagnostics} so the loss/restore lines
 * keep landing in the same console/title timeline as the rest of the plan's
 * evidence.
 */
public final class WebGpuContextSignal {
    private static final List<Runnable> RESTORED = new ArrayList<>();

    private static volatile boolean installed = false;

    private WebGpuContextSignal() {
    }

    /**
     * Installs the two browser listeners. Idempotent; safe to call once per
     * application construction.
     *
     * @param canvas the same element {@code setupFileDrop} already uses, so no DOM
     *     query is needed.
     */
    public static void attach(HTMLCanvasElement canvas) {
        if (canvas == null || installed) {
            return;
        }
        installed = true;

        canvas.addEventListener("webglcontextlost", evt -> {
            // Browser callback. Only the hop; see BrowserFiber.
            BrowserFiber.enter(() -> {
                // Required by the spec, or `webglcontextrestored` never fires:
                // the browser treats a lost context as permanently gone. This
                // is the single most important line in the file — without it
                // the durable install has no repair path at all.
                evt.preventDefault();
                WebResumeDiagnostics.log("contextlost", WebResumeDiagnostics.describe(evt));
            });
        });

        canvas.addEventListener("webglcontextrestored", evt -> {
            // Browser callback. Only the hop; see BrowserFiber.
            BrowserFiber.enter(() -> {
                WebResumeDiagnostics.log("contextrestored", WebResumeDiagnostics.describe(evt));
                // Everything GL-side is gone: every glyph atlas and every
                // texture face has to be re-uploaded. Reported, never acted on
                // here — the rebuild runs at most once per frame from the render
                // loop, so several losses in one frame collapse into one.
                for (Runnable cb : RESTORED) {
                    try {
                        cb.run();
                    } catch (Throwable t) {
                        // One bad subscriber must not strand the others, and a
                        // restore must never be able to kill the fiber.
                        WebResumeDiagnostics.log("contextrestored:error", String.valueOf(t));
                    }
                }
            });
        });

        // The control line: if this is present and no `contextlost` follows,
        // a stall was ours rather than the browser's.
        WebResumeDiagnostics.log("listeners", "attached canvas=ok");
    }

    /**
     * Subscribe to a real context recreation.
     *
     * <p>The callback runs on a {@link BrowserFiber} daemon fiber — the same hop
     * every browser callback in this backend uses, because a method JavaScript
     * invokes must not itself be a TeaVM coroutine. That is a different fiber from
     * the render loop's, so a subscriber must only *request* recovery, never
     * rebuild: the single pending flag and the one-rebuild-per-frame guarantee live
     * in the render loop, and a subscriber that rebuilt directly would take that
     * guarantee with it.
     *
     * <p>One subscription per runtime, made while the app is constructing it. A
     * restore that arrives before the first subscriber is not replayed: the context
     * does not exist yet at that point, so there is nothing to have lost.
     *
     * @return a handle that removes this subscriber.
     */
    public static AutoCloseable onContextRestored(Runnable onRestored) {
        RESTORED.add(onRestored);
        return () -> RESTORED.remove(onRestored);
    }
}
