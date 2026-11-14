package inego.zungjunggame.teavm;

import org.teavm.jso.JSProperty;
import org.teavm.jso.dom.events.Event;

/** Browser {@code CloseEvent} for [NativeWebSocket]. */
public abstract class NativeCloseEvent implements Event {
    @JSProperty
    public abstract int getCode();

    @JSProperty
    public abstract String getReason();
}
