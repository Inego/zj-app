package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;

/**
 * The browser's preferred languages. TeaVM's {@code Locale.getDefault} is not
 * {@code navigator.language}, so the web launcher reads this and installs it
 * as {@code PlatformLanguages}.
 *
 * <p>Comma-joined. A BCP 47 tag does not contain a comma.
 */
public final class NativeBrowserLanguages {
    private NativeBrowserLanguages() {}

    @JSBody(script = """
        try {
            var list = (navigator.languages && navigator.languages.length)
                ? navigator.languages
                : (navigator.language ? [navigator.language] : []);
            var out = [];
            for (var i = 0; i < list.length; i++) {
                var tag = list[i] ? String(list[i]) : '';
                if (tag) out.push(tag);
            }
            return out.join(',');
        } catch (e) {
            return '';
        }
    """)
    public static native String preferredTagsCsv();
}
