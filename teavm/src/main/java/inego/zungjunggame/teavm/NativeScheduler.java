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
}
