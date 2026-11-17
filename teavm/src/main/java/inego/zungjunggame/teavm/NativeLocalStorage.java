package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;

/**
 * Browser {@code localStorage} bridge for the per-key state backend.
 * <p>
 * Every call goes to the live store: nothing is cached in this process, so a
 * write another tab made is visible on the next read. A disabled store, a
 * private-mode rejection or a quota failure is swallowed here and surfaces as a
 * missing value, which the state store already treats as absent.
 */
public final class NativeLocalStorage {
    private NativeLocalStorage() {}

    @JSBody(script = "try { window.localStorage; return true; } catch (e) { return false; }")
    public static native boolean isAvailable();

    @JSBody(params = {"key"}, script = """
        try {
          var value = window.localStorage.getItem(key);
          return value === null ? null : String(value);
        } catch (e) {
          return null;
        }
        """)
    public static native String getItem(String key);

    @JSBody(params = {"key", "value"}, script =
        "try { window.localStorage.setItem(key, value); } catch (e) {}")
    public static native void setItem(String key, String value);

    @JSBody(params = {"key"}, script =
        "try { window.localStorage.removeItem(key); } catch (e) {}")
    public static native void removeItem(String key);

    /**
     * The remainder of every key that starts with {@code prefix}, one per line.
     * State names never contain a newline; an empty string means no key matched.
     */
    @JSBody(params = {"prefix"}, script = """
        try {
          var out = [];
          var length = window.localStorage.length;
          for (var i = 0; i < length; i++) {
            var key = window.localStorage.key(i);
            if (key && key.indexOf(prefix) === 0) {
              out.push(key.substring(prefix.length));
            }
          }
          return out.join('\\n');
        } catch (e) {
          return '';
        }
        """)
    public static native String keysWithPrefix(String prefix);
}
