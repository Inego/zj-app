/*
 * Derived from gdx-teavm 1.6.2 (https://github.com/xpenatan/gdx-teavm),
 * licensed under the Apache License, Version 2.0. Modified for this project;
 * see NOTICE and third_party/gdx-teavm/.
 */
package com.github.xpenatan.gdx.teavm.backends.web.assetloader;

import com.badlogic.gdx.utils.GdxRuntimeException;
import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber;
import com.github.xpenatan.gdx.teavm.backends.web.dom.typedarray.TypedArrays;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLScriptElement;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Same class as gdx-teavm 1.6.2 so this one shadows it.
 *
 * <p>Each browser callback ({@code setTimeout}, XHR, script load/error) only
 * calls {@link BrowserFiber#enter}. The library bodies call back into the
 * loader and into {@code LanguagePack}, which TeaVM compiles as coroutines.
 * An export that is itself a coroutine reads {@code Fiber.current()} and traps
 * with {@code dereferencing a null pointer} once {@code main} has returned.
 */
public class AssetDownloadImpl implements AssetDownloader {

    private static final int MAX_DOWNLOAD_ATTEMPT = 3;
    private static final boolean DEBUG_ASSET_DOWNLOAD = false;

    private final boolean showLogs;

    public AssetDownloadImpl(boolean showDownloadLogs) {
        showLogs = showDownloadLogs;
        log("<init>", "showLogs=" + showDownloadLogs);
    }

    @Override
    public void load(boolean async, String url, AssetType type, AssetLoaderListener<WebBlob> listener) {
        log("load", "async=" + async + ", type=" + type + ", url=" + url + ", listener=" + (listener != null));
        AssetLoaderListener<WebBlob> internalListener = new AssetLoaderListener<>() {
            @Override
            public void onSuccess(String url, WebBlob result) {
                log("load.internalListener.onSuccess", "url=" + url + ", hasResult=" + (result != null));
                if(showLogs) {
                    System.out.println("Asset download success: " + url);
                }
                if(listener != null) {
                    listener.onSuccess(url, result);
                }
            }

            @Override
            public void onFailure(String url) {
                log("load.internalListener.onFailure", "url=" + url);
                if(showLogs) {
                    System.err.println("Asset download failed: " + url);
                }
                if(listener != null) {
                    listener.onFailure(url);
                }
            }

            @Override
            public void onProgress(int total, int loaded) {
                log("load.internalListener.onProgress", "url=" + url + ", loaded=" + loaded + ", total=" + total);
                if(listener != null) {
                    listener.onProgress(total, loaded);
                }
            }
        };

        if(showLogs) {
            System.out.println("Loading asset: " + url);
        }

        switch(type) {
            case Binary:
                log("load", "dispatch Binary -> loadBinary");
                loadBinary(async, url, internalListener, 0);
                break;
            case Directory:
                log("load", "dispatch Directory -> immediate success");
                internalListener.onSuccess(url, null);
                break;
            default:
                log("load", "unsupported type=" + type);
                throw new GdxRuntimeException("Unsupported asset type " + type);
        }
    }

    @Override
    public void loadScript(boolean async, String url, AssetLoaderListener<String> listener) {
        log("loadScript", "async=" + async + ", url=" + url + ", listener=" + (listener != null));
        if(showLogs) {
            System.out.println("Loading script: " + url);
        }
        Window current = Window.current();
        HTMLDocument document = current.getDocument();
        HTMLScriptElement scriptElement = (HTMLScriptElement)document.createElement("script");

        scriptElement.addEventListener("load", event -> BrowserFiber.enter(() -> {
            log("loadScript.onLoad", "url=" + url);
            if(showLogs) {
                System.out.println("Script download success: " + url);
            }
            if(listener != null) {
                listener.onSuccess(url, "");
            }
        }));
        scriptElement.addEventListener("error", (error) -> BrowserFiber.enter(() -> {
            log("loadScript.onError", "url=" + url);
            if(showLogs) {
                System.err.println("Script download failed: " + url);
            }
            if(listener != null) {
                listener.onFailure(url);
            }
        }));
        scriptElement.setSrc(url);
        document.getBody().appendChild(scriptElement);
        log("loadScript", "script appended url=" + url);
    }

    private void loadBinary(boolean async, final String url, final AssetLoaderListener<WebBlob> listener, int count) {
        log("loadBinary", "async=" + async + ", attempt=" + count + ", url=" + url);
        if(count == MAX_DOWNLOAD_ATTEMPT) {
            log("loadBinary", "max attempts reached -> failure url=" + url);
            if(listener != null) {
                listener.onFailure(url);
            }
            return;
        }

        // don't load on main thread
        if(async) {
            log("loadBinary", "queue async dispatch via Window.setTimeout url=" + url + ", attempt=" + count);
            Window.setTimeout(() -> BrowserFiber.enter(() -> loadBinaryInternally(true, url, listener, count)), 0);
        }
        else {
            log("loadBinary", "run sync loadBinaryInternally url=" + url + ", attempt=" + count);
            loadBinaryInternally(false, url, listener, count);
        }
    }

    private void loadBinaryInternally(boolean async, final String url, final AssetLoaderListener<WebBlob> listener, int count) {
        log("loadBinaryInternally", "start async=" + async + ", attempt=" + count + ", url=" + url);
        XMLHttpRequest request = XMLHttpRequest.create();
        final boolean[] settled = {false};

        request.onError(evt -> BrowserFiber.enter(() -> {
            log("loadBinaryInternally.onError", "url=" + url + ", attempt=" + count);
            if(!trySettle(settled)) {
                return;
            }
            retryOrFail(async, url, listener, count);
        }));
        request.onAbort(evt -> BrowserFiber.enter(() -> {
            log("loadBinaryInternally.onAbort", "url=" + url + ", attempt=" + count);
            if(!trySettle(settled)) {
                return;
            }
            retryOrFail(async, url, listener, count);
        }));
        request.onTimeout(evt -> BrowserFiber.enter(() -> {
            log("loadBinaryInternally.onTimeout", "url=" + url + ", attempt=" + count);
            if(!trySettle(settled)) {
                return;
            }
            retryOrFail(async, url, listener, count);
        }));

        request.onComplete(() -> BrowserFiber.enter(() -> {
            log("loadBinaryInternally.onComplete", "url=" + url + ", readyState=" + request.getReadyState() + ", status=" + request.getStatus() + ", attempt=" + count);
            if(!trySettle(settled)) {
                return;
            }
            handleTerminalStatus(async, url, listener, count, request);
        }));

        request.setOnReadyStateChange(evt -> BrowserFiber.enter(() -> {
            log("loadBinaryInternally.onReadyStateChange", "url=" + url + ", readyState=" + request.getReadyState() + ", attempt=" + count);
            if(request.getReadyState() != XMLHttpRequest.DONE || !trySettle(settled)) {
                if(request.getReadyState() == XMLHttpRequest.DONE) {
                    log("loadBinaryInternally.onReadyStateChange", "DONE ignored because already settled url=" + url + ", attempt=" + count);
                }
                return;
            }

            handleTerminalStatus(async, url, listener, count, request);
        }));

        setOnProgress(request, url, listener);
        try {
            request.open("GET", url, async);
            if(async) {
                request.setResponseType("arraybuffer");
            }
            log("loadBinaryInternally", "request.send url=" + url + ", attempt=" + count);
            request.send();
        }
        catch(Throwable t) {
            log("loadBinaryInternally", "open/send threw url=" + url + ", attempt=" + count + ", error=" + t);
            if(trySettle(settled)) {
                retryOrFail(async, url, listener, count);
            }
        }
    }

    private void handleTerminalStatus(boolean async, String url, AssetLoaderListener<WebBlob> listener, int count, XMLHttpRequest request) {
        int status = request.getStatus();
        log("handleTerminalStatus", "status=" + status + ", url=" + url + ", attempt=" + count + ", readyState=" + request.getReadyState());
        if(status == 200) {
            JSObject jsResponse = request.getResponse();

            Int8Array data;
            ArrayBuffer arrayBuffer;
            if(isString(jsResponse)) {
                // sync downloading is always string
                log("handleTerminalStatus", "response is string url=" + url + ", attempt=" + count);
                String responseStr = toString(jsResponse);
                data = TypedArrays.getInt8Array(responseStr.getBytes());
                arrayBuffer = data.getBuffer();
            }
            else {
                log("handleTerminalStatus", "response is arraybuffer url=" + url + ", attempt=" + count);
                ArrayBuffer response = (ArrayBuffer)jsResponse;
                data = new Int8Array(response);
                arrayBuffer = response;
            }

            if(listener != null) {
                log("handleTerminalStatus", "notify success url=" + url + ", bytes=" + data.getLength() + ", attempt=" + count);
                listener.onSuccess(url, new WebBlob(arrayBuffer, data));
            }
            return;
        }

        if(status == 404 || status == 403) {
            log("handleTerminalStatus", "notify failure terminal status=" + status + ", url=" + url + ", attempt=" + count);
            if(listener != null) {
                listener.onFailure(url);
            }
            return;
        }

        // Retry transient statuses (including 0) a few times before failing.
        log("handleTerminalStatus", "retry transient status=" + status + ", url=" + url + ", attempt=" + count);
        retryOrFail(async, url, listener, count);
    }

    private void retryOrFail(boolean async, final String url, final AssetLoaderListener<WebBlob> listener, int count) {
        int newCount = count + 1;
        log("retryOrFail", "url=" + url + ", prevAttempt=" + count + ", nextAttempt=" + newCount + ", async=" + async);
        if(newCount < MAX_DOWNLOAD_ATTEMPT) {
            log("retryOrFail", "schedule retry url=" + url + ", attempt=" + newCount);
            Window.setTimeout(() -> BrowserFiber.enter(() -> loadBinary(async, url, listener, newCount)), 100);
            return;
        }
        log("retryOrFail", "notify final failure url=" + url + ", attempt=" + count);
        if(listener != null) {
            listener.onFailure(url);
        }
    }

    private boolean trySettle(boolean[] settled) {
        log("trySettle", "before=" + settled[0]);
        if(settled[0]) {
            log("trySettle", "already settled");
            return false;
        }
        settled[0] = true;
        log("trySettle", "set settled=true");
        return true;
    }

    private void setOnProgress(XMLHttpRequest req, String url, final AssetLoaderListener<?> listener) {
        log("setOnProgress", "register progress listener for url=" + url + ", listener=" + (listener != null));
        req.onProgress(evt -> BrowserFiber.enter(() -> {
            int loaded = evt.getLoaded();
            int total = evt.getTotal();
            double percent = total > 0 ? (double)loaded / total : -1;
            log("onProgress", "url=" + url + ", loaded=" + loaded + ", total=" + total + ", percent=" + percent);
            if(listener != null) {
                listener.onProgress(total, loaded);
            }
        }));
    }

    private void log(String method, String message) {
        if(DEBUG_ASSET_DOWNLOAD) {
            System.out.println("[AssetDownloadImpl] " + method + " | " + message);
        }
    }

    @JSBody(params = "jsObject", script = "return typeof jsObject == 'string';")
    private static native boolean isString(JSObject jsObject);

    @JSBody(params = "jsObject", script = "return jsObject;")
    private static native String toString(JSObject jsObject);

    @JSBody(params = "object", script = "return URL.createObjectURL(object);")
    private static native String createObjectURL(JSObject object);
}
