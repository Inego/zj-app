package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Browser window timer and visibility bridge for TeaVM.
 */
public final class NativeScheduler {
    private NativeScheduler() {}

    @JSFunctor
    public interface Callback extends JSObject {
        void call();
    }

    @JSBody(params = {"callback", "delay"}, script =
        "return window.setInterval(function() { callback(); }, delay);"
    )
    public static native int setInterval(Callback callback, int delay);

    @JSBody(params = {"id"}, script = "window.clearInterval(id);")
    public static native void clearInterval(int id);

    @JSBody(params = {"callback", "delay"}, script =
        "return window.setTimeout(function() { callback(); }, delay);"
    )
    public static native int setTimeout(Callback callback, int delay);

    @JSBody(params = {"id"}, script = "window.clearTimeout(id);")
    public static native void clearTimeout(int id);

    @JSBody(params = {"onVisible", "onHidden"}, script =
            """
                    var listener = function() {
                        if (document.visibilityState === 'visible') {
                            onVisible();
                        } else if (document.visibilityState === 'hidden') {
                            onHidden();
                        }
                    };
                    document.addEventListener('visibilitychange', listener);
                    return listener;"""
    )
    public static native JSObject onVisibilityChange(Callback onVisible, Callback onHidden);

    @JSBody(params = {"listener"}, script =
        "document.removeEventListener('visibilitychange', listener);"
    )
    public static native void removeVisibilityListener(JSObject listener);

    @JSBody(script = "return document.visibilityState === 'visible';")
    public static native boolean isVisible();

    /**
     * Create a reusable cooperative macro-task channel backed by {@code MessageChannel}
     * (with {@code setTimeout(0)} fallback). Each call to {@link #scheduleSlice(JSObject)}
     * invokes {@code onTick} once on a fresh browser macro-task without the 4 ms nested
     * timer clamp or redundant {@code requestAnimationFrame} WebGL redraws.
     */
    @JSBody(params = {"onTick"}, script =
        """
        var ch = (typeof MessageChannel !== 'undefined') ? new MessageChannel() : null;
        var state = { ch: ch, timer: 0, closed: false, onTick: onTick };
        var dispatch = function() {
            state.timer = 0;
            if (!state.closed) state.onTick();
        };
        state.dispatch = dispatch;
        if (ch) {
            ch.port1.onmessage = dispatch;
        }
        return state;
        """
    )
    public static native JSObject createSliceChannel(Callback onTick);

    /**
     * Schedule one slice tick on {@code handle}. When the tab is hidden, throttles to
     * a 250 ms timer so an unfocused tab does not spin CPU.
     */
    @JSBody(params = {"handle"}, script =
        """
        if (!handle || handle.closed) return;
        if (document.visibilityState === 'hidden') {
            handle.timer = window.setTimeout(handle.dispatch, 250);
        } else if (handle.ch) {
            handle.ch.port2.postMessage(null);
        } else {
            handle.timer = window.setTimeout(handle.dispatch, 0);
        }
        """
    )
    public static native void scheduleSlice(JSObject handle);

    @JSBody(params = {"handle"}, script =
        """
        if (!handle || handle.closed) return;
        handle.closed = true;
        if (handle.timer) {
            window.clearTimeout(handle.timer);
            handle.timer = 0;
        }
        if (handle.ch) {
            handle.ch.port1.onmessage = null;
            handle.ch.port1.close();
            handle.ch.port2.close();
            handle.ch = null;
        }
        """
    )
    public static native void closeSliceChannel(JSObject handle);
}
