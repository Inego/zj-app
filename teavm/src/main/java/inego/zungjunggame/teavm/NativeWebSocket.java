package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.MessageEvent;

/**
 * Browser {@code WebSocket} JSO wrapper. TeaVM has no {@code java.net.http.WebSocket}.
 */
public abstract class NativeWebSocket implements JSObject {
    public static final int OPEN = 1;

    @JSBody(params = { "url" }, script = "return new WebSocket(url);")
    public static native NativeWebSocket create(String url);

    public abstract void send(String data);

    public abstract void close();

    public abstract void close(int code, String reason);

    @JSProperty
    public abstract int getReadyState();

    @JSProperty
    public abstract void setOnopen(EventListener<Event> listener);

    @JSProperty
    public abstract void setOnclose(EventListener<NativeCloseEvent> listener);

    @JSProperty
    public abstract void setOnmessage(EventListener<MessageEvent> listener);

    @JSProperty
    public abstract void setOnerror(EventListener<Event> listener);

    @JSBody(params = { "event" }, script = "return (typeof event.data === 'string') ? event.data : '';")
    public static native String textData(MessageEvent event);
}
