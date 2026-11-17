package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Cross-tab wake-ups for the stored session.
 * <p>
 * A {@code storage} event fires in every tab that did not make the write, which
 * is exactly the audience that has to adopt a rotation or a sign-out. It is
 * only a poke, never a payload: the handler re-reads the session and decides.
 * {@code visibilitychange} and a bfcache {@code pageshow} cover a tab that was
 * frozen while the write happened — frozen tabs miss storage events and do not
 * receive them on wake.
 */
public final class NativeSessionStorage {
    private NativeSessionStorage() {}

    @JSFunctor
    public interface Callback extends JSObject {
        void call();
    }

    @JSBody(params = {"key", "onChange"}, script = """
        window.addEventListener('storage', function(event) {
          if (!event) return;
          if (event.storageArea && event.storageArea !== window.localStorage) return;
          if (event.key === null || event.key === key) onChange();
        });
        document.addEventListener('visibilitychange', function() {
          if (document.visibilityState === 'visible') onChange();
        });
        window.addEventListener('pageshow', function(event) {
          if (event && event.persisted) onChange();
        });
        """)
    public static native void watch(String key, Callback onChange);
}
