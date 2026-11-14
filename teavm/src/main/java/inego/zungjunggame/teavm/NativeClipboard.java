package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;

/**
 * Browser clipboard write for overlay copy. Paste is the document {@code paste}
 * event in {@code WasmOverlayClipboard}, not {@code navigator.clipboard.readText()}
 * (that prompts for clipboard-read and then drops the text).
 */
public final class NativeClipboard {
    private NativeClipboard() {}

    @JSBody(
            params = { "text" },
            script = "try { if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text); } catch (e) {}"
    )
    public static native void write(String text);
}
