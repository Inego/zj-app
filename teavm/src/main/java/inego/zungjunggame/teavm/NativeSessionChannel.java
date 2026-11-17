package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Live access-JWT handoff between the tabs of one origin.
 * <p>
 * A rotation consumes the session row every sibling tab's access JWT names, so
 * the sibling's next request is refused and it exchanges the token it just
 * adopted — which consumes that row too. Two visible tabs then rotate each
 * other's tokens at heartbeat cadence. The tab that won the rotation posts the
 * JWT it received here, and the siblings keep working with it.
 * <p>
 * {@code BroadcastChannel} delivers to the other contexts of the origin, never
 * back to the sender, which is exactly the audience. This carries a credential
 * in memory only: nothing here touches {@code localStorage}, so the access JWT
 * stays off disk on every platform. A browser without {@code BroadcastChannel}
 * degrades to the old behaviour — a sibling exchange per rotation, still
 * guarded by the store reload and the compare-and-swap.
 */
public final class NativeSessionChannel {
    private NativeSessionChannel() {}

    /** Receives the announced session's three fields. */
    @JSFunctor
    public interface Callback extends JSObject {
        void call(String refreshToken, String accessToken, String userId);
    }

    // Plain statements, never an IIFE: TeaVM re-emits these bodies for the Wasm
    // build and drops the grouping parentheses of a statement-level
    // `(function() { ... })()`, so the module would not parse. See
    // NativeSessionLock for the same constraint.
    @JSBody(params = {"name"}, script = """
        if (typeof BroadcastChannel === 'undefined') return null;
        window.__zjAuthChannels = window.__zjAuthChannels || {};
        if (!window.__zjAuthChannels[name]) {
          window.__zjAuthChannels[name] = new BroadcastChannel(name);
        }
        return window.__zjAuthChannels[name];
        """)
    public static native JSObject open(String name);

    @JSBody(params = {"channel", "refreshToken", "accessToken", "userId"}, script = """
        if (!channel) return;
        try {
          channel.postMessage({
            refreshToken: refreshToken,
            accessToken: accessToken,
            userId: userId
          });
        } catch (e) {}
        """)
    public static native void post(JSObject channel, String refreshToken, String accessToken, String userId);

    @JSBody(params = {"channel", "onMessage"}, script = """
        if (!channel) return;
        channel.onmessage = function(event) {
          var data = event && event.data;
          if (!data) return;
          if (typeof data.refreshToken !== 'string') return;
          if (typeof data.accessToken !== 'string') return;
          if (typeof data.userId !== 'string') return;
          onMessage(data.refreshToken, data.accessToken, data.userId);
        };
        """)
    public static native void watch(JSObject channel, Callback onMessage);
}
