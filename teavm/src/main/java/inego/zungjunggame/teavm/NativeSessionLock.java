package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Web Locks bridge for the origin-wide refresh critical section.
 * <p>
 * The lock is held from the store read to the write of the rotated session, so
 * two tabs of the origin cannot present the same refresh token. The wait is
 * bounded: if the holder is wedged (a frozen tab, a stalled exchange), the
 * request is aborted and the caller runs unlocked — the store reload plus the
 * compare-and-swap on the result make that a sibling rotation, not a replay.
 * <p>
 * Releases are addressed by handle, so a stale callback from an abandoned
 * acquisition can never release the lock a newer one holds. A handle of 0 means
 * "ran unlocked": its release is a no-op.
 */
public final class NativeSessionLock {
    private NativeSessionLock() {}

    /** Receives the acquisition's handle; 0 means the critical section runs unlocked. */
    @JSFunctor
    public interface Acquired extends JSObject {
        void accept(int handle);
    }

    @JSBody(script =
        "return !!(window.navigator && window.navigator.locks && " +
        "typeof window.navigator.locks.request === 'function');")
    public static native boolean isSupported();

    @JSBody(script = "return document.visibilityState === 'visible';")
    public static native boolean isDocumentVisible();

    // Plain statements, never an IIFE: TeaVM parses this body and prints it
    // again for the Wasm build, and that printer drops the grouping parentheses
    // of `(function() { ... })()`. The browser then sees `function() { ... }()`
    // at statement position and refuses the whole module with
    // `SyntaxError: Function statements require a function name`. The body here
    // is already a function, so declarations and `return` are legal.
    @JSBody(params = {"name", "onAcquired"}, script = """
        window.__zjLockReleases = window.__zjLockReleases || {};
        window.__zjLockSeq = window.__zjLockSeq || 0;
        var handedOff = false;
        function handOff(handle) {
          if (handedOff) return;
          handedOff = true;
          try {
            onAcquired(handle);
          } catch (e) {
            var release = handle ? window.__zjLockReleases[handle] : null;
            if (release) release();
          }
        }
        function runUnlocked() { handOff(0); }
        try {
          var locks = window.navigator && window.navigator.locks;
          if (!locks || typeof locks.request !== 'function') { runUnlocked(); return; }
          var signal = (typeof AbortSignal !== 'undefined' && AbortSignal.timeout)
            ? AbortSignal.timeout(12000)
            : undefined;
          locks.request(name, { mode: 'exclusive', signal: signal }, function() {
            return new Promise(function(resolve) {
              var id = ++window.__zjLockSeq;
              window.__zjLockReleases[id] = function() {
                if (!window.__zjLockReleases[id]) return;
                delete window.__zjLockReleases[id];
                resolve();
              };
              handOff(id);
            });
          }).catch(function() {
            // The wait was aborted or the document is going away. Running
            // unlocked is safe: the caller reloads the store and writes only
            // if the token it presented is still there.
            runUnlocked();
          });
        } catch (e) {
          runUnlocked();
        }
        """)
    public static native void requestExclusive(String name, Acquired onAcquired);

    /** Release the acquisition [handle]; a no-op for the unlocked handle 0. */
    @JSBody(params = {"handle"}, script = """
        try {
          var releases = window.__zjLockReleases;
          var release = releases && releases[handle];
          if (release) release();
        } catch (e) {}
        """)
    public static native void release(int handle);
}
