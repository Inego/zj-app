package inego.zungjunggame.teavm;

import com.badlogic.gdx.Gdx;
import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber;
import inego.zungjunggame.platform.WebSocketClient;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Browser WebSocket implementation for TeaVM (JS and WasmGC).
 * Custom HTTP headers are ignored: the browser API cannot set them.
 */
public final class TeaWebSocketClient implements WebSocketClient {

    private NativeWebSocket socket;
    private volatile boolean connected;

    @Override
    public boolean isConnected() {
        return connected && socket != null && socket.getReadyState() == NativeWebSocket.OPEN;
    }

    @Override
    public void connect(@NotNull String url, @NotNull Map<String, String> headers, @NotNull Listener listener) {
        close(1000, "normal");
        connected = false;
        final NativeWebSocket ws;
        try {
            ws = NativeWebSocket.create(url);
        } catch (Throwable t) {
            post(() -> listener.onError(t));
            return;
        }
        socket = ws;

        ws.setOnopen(event -> BrowserFiber.enter(() -> {
            connected = true;
            post(listener::onOpen);
        }));
        ws.setOnmessage(event -> {
            String text = NativeWebSocket.textData(event);
            BrowserFiber.enter(() -> post(() -> listener.onMessage(text)));
        });
        ws.setOnclose(event -> {
            String reason = event.getReason() != null ? event.getReason() : "";
            int code = event.getCode();
            BrowserFiber.enter(() -> {
                connected = false;
                socket = null;
                post(() -> listener.onClose(code, reason));
            });
        });
        ws.setOnerror(event -> BrowserFiber.enter(() -> {
            connected = false;
            post(() -> listener.onError(new RuntimeException("WebSocket error")));
        }));
    }

    @Override
    public boolean send(@NotNull String text) {
        NativeWebSocket ws = socket;
        if (ws == null || !connected || ws.getReadyState() != NativeWebSocket.OPEN) {
            return false;
        }
        try {
            ws.send(text);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void close(int code, @NotNull String reason) {
        connected = false;
        NativeWebSocket ws = socket;
        socket = null;
        if (ws == null) {
            return;
        }
        try {
            ws.close(code, reason);
        } catch (Throwable ignored) {
            try {
                ws.close();
            } catch (Throwable ignored2) {
                // already closed
            }
        }
    }

    /**
     * Deliver {@code action} on the next application frame.
     *
     * <p>An idle page schedules no animation frame
     * ({@code setContinuousRendering(false)}). Running the listener inline
     * updated the sequencer and then left that batch frozen until a pointer
     * event asked for a present. Desktop and Android already post through
     * {@code Gdx.app}, and that post is what schedules the frame. The listener
     * still has to be wrapped: an exception inside {@code step}'s runnable
     * drain sets {@code stepError} and stops the loop for good.
     */
    private static void post(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (Throwable t) {
                if (Gdx.app != null) {
                    Gdx.app.error("TeaWebSocketClient", "Callback error", t);
                } else {
                    t.printStackTrace();
                }
            }
        };
        if (Gdx.app != null) {
            Gdx.app.postRunnable(safe);
        } else {
            safe.run();
        }
    }
}
