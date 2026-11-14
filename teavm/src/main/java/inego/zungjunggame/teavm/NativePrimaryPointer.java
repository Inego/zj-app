package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;

/**
 * The browser's primary pointing device. A phone reports {@code pointer: coarse};
 * a mouse reports {@code pointer: fine}, including a desktop that also has a
 * touchscreen ({@code any-pointer} is deliberately not consulted).
 */
public final class NativePrimaryPointer {
    private NativePrimaryPointer() {}

    @JSBody(script = """
        try {
            return !!(window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
        } catch (e) {
            return false;
        }
    """)
    public static native boolean isCoarse();
}
