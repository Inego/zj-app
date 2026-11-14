package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;

/**
 * The browser's IANA time zone. TeaVM has no zone database of its own, so the
 * web launcher reads this and installs it as {@code PlatformTimeZone}.
 *
 * <p>An IANA id does not need escaping. A failure reports an empty string, and
 * the client then leaves whatever zone the server already stored.
 */
public final class NativeTimeZone {
    private NativeTimeZone() {}

    @JSBody(script = """
        try {
            var zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
            return zone ? String(zone) : '';
        } catch (e) {
            return '';
        }
    """)
    public static native String ianaId();
}
