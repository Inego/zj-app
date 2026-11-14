package inego.zungjunggame.teavm;

import inego.zungjunggame.net.ApiConfig;
import org.teavm.jso.JSBody;

/**
 * Browser API configuration bridge. Extracts query params, global variables,
 * and page origin from the browser environment, delegating resolution logic
 * to {@link ApiConfig#resolveWebBaseUrl}.
 */
public final class NativeApiConfig {
    private NativeApiConfig() {}

    @JSBody(script = """
        try {
            var params = new URLSearchParams(window.location.search || '');
            return params.get('apiBase') || '';
        } catch (e) {
            return '';
        }
    """)
    private static native String readQueryParam();

    @JSBody(script = """
        try {
            return window.__ZJ_API_BASE__ ? String(window.__ZJ_API_BASE__) : '';
        } catch (e) {
            return '';
        }
    """)
    private static native String readGlobalConfig();

    @JSBody(script = """
        try {
            return window.location && window.location.origin ? String(window.location.origin) : '';
        } catch (e) {
            return '';
        }
    """)
    private static native String readOrigin();

    public static String resolveApiBase(String fallback) {
        return ApiConfig.INSTANCE.resolveWebBaseUrl(
            readQueryParam(),
            readGlobalConfig(),
            readOrigin(),
            fallback != null ? fallback : ApiConfig.DEFAULT_BASE_URL
        );
    }

    @JSBody(script = """
        try {
            return window.location && window.location.hash ? String(window.location.hash) : '';
        } catch (e) {
            return '';
        }
    """)
    private static native String readHash();

    @JSBody(script = """
        try {
            if (window.history && window.history.replaceState) {
                window.history.replaceState(
                    null, '', window.location.pathname + window.location.search);
            }
        } catch (e) {
        }
    """)
    private static native void clearHash();

    /**
     * Read the page's URL fragment (the mail deep link, e.g. `#my_challenges`) and
     * clear it, so a reload does not re-open a screen the player has already seen.
     * The fragment's meaning is resolved in Kotlin ({@code DeepLink}).
     */
    public static String consumeDeepLinkFragment() {
        String hash = readHash();
        clearHash();
        return hash;
    }
}
