package inego.zungjunggame.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Browser Google Identity Services bridge. Overlay, script load, and GIS
 * calls live in JS so the canvas never talks to Google APIs directly.
 * <p>
 * zj-server game credentials are the game's, not this bridge's: the refresh
 * token is persisted by the game through Gdx {@code Preferences} (browser
 * {@code localStorage}), never a Google token. GIS may use its own cookies for
 * the Google account picker; that is Google identity, not a zj-server session.
 */
public final class NativeGis {
    private NativeGis() {}

    @JSFunctor
    public interface Callback extends JSObject {
        void accept(String value);
    }

    public static void ensureInstalled() {
        install();
    }

    @JSBody(script = """
            if (window.__zjGis) return;
            window.__zjGis = {
              gen: 0,
              loadWaiters: null,
              resolveClientId: function(fallback) {
                try {
                  var params = new URLSearchParams(window.location.search || '');
                  var q = params.get('googleClientId');
                  if (q && String(q).trim()) return String(q).trim();
                } catch (e) {}
                try {
                  if (window.__ZJ_GOOGLE_CLIENT_ID__) {
                    var w = String(window.__ZJ_GOOGLE_CLIENT_ID__).trim();
                    if (w) return w;
                  }
                } catch (e2) {}
                return (fallback || '').trim();
              },
              isSecureContext: function() {
                return window.isSecureContext === true;
              },
              loadClient: function(onReady, onError) {
                if (window.google && google.accounts && google.accounts.id) {
                  onReady('');
                  return;
                }
                if (this.loadWaiters) {
                  this.loadWaiters.push({onReady: onReady, onError: onError});
                  return;
                }
                this.loadWaiters = [{onReady: onReady, onError: onError}];
                var self = this;
                var done = false;
                function finishOk() {
                  if (done) return;
                  done = true;
                  var waiters = self.loadWaiters || [];
                  self.loadWaiters = null;
                  for (var i = 0; i < waiters.length; i++) waiters[i].onReady('');
                }
                function finishErr(code) {
                  if (done) return;
                  done = true;
                  var waiters = self.loadWaiters || [];
                  self.loadWaiters = null;
                  for (var i = 0; i < waiters.length; i++) waiters[i].onError(code);
                }
                var s = document.createElement('script');
                s.src = 'https://accounts.google.com/gsi/client';
                s.async = true;
                s.onload = function() {
                  if (window.google && google.accounts && google.accounts.id) finishOk();
                  else finishErr('script_load');
                };
                s.onerror = function() { finishErr('script_load'); };
                setTimeout(function() {
                  if (!(window.google && google.accounts && google.accounts.id)) finishErr('timeout');
                }, 15000);
                document.head.appendChild(s);
              },
              ensureOverlay: function() {
                if (document.getElementById('zj-gis-overlay')) return;
                if (!document.getElementById('zj-gis-style')) {
                  var style = document.createElement('style');
                  style.id = 'zj-gis-style';
                  style.textContent =
                    '#zj-gis-overlay{position:fixed;inset:0;z-index:2147483646;display:none;' +
                    'align-items:center;justify-content:center;background:rgba(0,0,0,.55);' +
                    'font-family:system-ui,sans-serif;}' +
                    '#zj-gis-overlay .zj-gis-card{background:#101a17;color:#eee;padding:24px 28px;' +
                    'border-radius:12px;border:1px solid rgba(237,230,204,.45);min-width:300px;' +
                    'text-align:center;}' +
                    '#zj-gis-overlay .zj-gis-title{margin:0 0 16px;font-size:18px;font-weight:600;}' +
                    '#zj-gis-overlay .zj-gis-status{margin:12px 0 0;font-size:13px;color:#d8d2b8;min-height:1.2em;}' +
                    '#zj-gis-overlay .zj-gis-cancel{margin-top:16px;background:transparent;color:#f5e8c8;' +
                    'border:1px solid rgba(237,230,204,.45);padding:8px 18px;border-radius:8px;' +
                    'cursor:pointer;font-size:14px;}' +
                    '#zj-gis-button{display:flex;justify-content:center;min-height:44px;}';
                  document.head.appendChild(style);
                }
                var el = document.createElement('div');
                el.id = 'zj-gis-overlay';
                el.innerHTML =
                  '<div class="zj-gis-card">' +
                  '<p class="zj-gis-title"></p>' +
                  '<div id="zj-gis-button"></div>' +
                  '<p class="zj-gis-status"></p>' +
                  '<button type="button" class="zj-gis-cancel"></button>' +
                  '</div>';
                document.body.appendChild(el);
              },
              hideOverlay: function() {
                var el = document.getElementById('zj-gis-overlay');
                if (el) el.style.display = 'none';
              },
              cancel: function() {
                this.gen += 1;
                this.hideOverlay();
                try { google.accounts.id.cancel(); } catch (e) {}
              },
              disableAutoSelect: function() {
                try { google.accounts.id.disableAutoSelect(); } catch (e) {}
              },
              startSignIn: function(clientId, nonce, title, cancelLabel, onCredential, onError, onCancel) {
                this.gen += 1;
                var gen = this.gen;
                var self = this;
                function stale() { return gen !== self.gen; }
                this.ensureOverlay();
                var overlay = document.getElementById('zj-gis-overlay');
                overlay.querySelector('.zj-gis-title').textContent = title || 'Sign in with Google';
                overlay.querySelector('.zj-gis-cancel').textContent = cancelLabel || 'Cancel';
                overlay.querySelector('.zj-gis-status').textContent = '';
                overlay.style.display = 'flex';
                overlay.onclick = function(ev) {
                  if (ev.target !== overlay) return;
                  if (stale()) return;
                  self.cancel();
                  onCancel('');
                };
                overlay.querySelector('.zj-gis-card').onclick = function(ev) { ev.stopPropagation(); };
                overlay.querySelector('.zj-gis-cancel').onclick = function(ev) {
                  ev.preventDefault();
                  ev.stopPropagation();
                  if (stale()) return;
                  self.cancel();
                  onCancel('');
                };
                var btn = overlay.querySelector('#zj-gis-button');
                btn.innerHTML = '';
                try {
                  google.accounts.id.initialize({
                    client_id: clientId,
                    nonce: nonce,
                    callback: function(resp) {
                      if (stale()) return;
                      self.hideOverlay();
                      self.gen += 1;
                      var cred = resp && resp.credential ? String(resp.credential) : '';
                      if (!cred) { onError('empty_credential'); return; }
                      onCredential(cred);
                    },
                    auto_select: false,
                    cancel_on_tap_outside: true,
                    use_fedcm_for_prompt: true,
                    use_fedcm_for_button: true,
                    ux_mode: 'popup',
                    context: 'signin'
                  });
                } catch (e) {
                  this.hideOverlay();
                  onError('init_failed');
                  return;
                }
                try {
                  google.accounts.id.renderButton(btn, {
                    type: 'standard',
                    theme: 'outline',
                    size: 'large',
                    text: 'signin_with',
                    shape: 'rectangular',
                    width: 280
                  });
                } catch (e2) {
                  this.hideOverlay();
                  onError('button_failed');
                  return;
                }
                // One Tap / FedCM prompt is best-effort. Do not pass a moment
                // listener: isNotDisplayed / getNotDisplayedReason are removed
                // under mandatory FedCM. HTTPS is already gated in Kotlin.
                try { google.accounts.id.prompt(); } catch (e3) {}
              }
            };
            """)
    private static native void install();

    @JSBody(params = { "fallback" }, script = "window.__zjGis || {}; return window.__zjGis.resolveClientId(fallback);")
    private static native String resolveClientIdRaw(String fallback);

    @JSBody(script = "return window.__zjGis.isSecureContext();")
    private static native boolean isSecureContextRaw();

    @JSBody(params = { "onReady", "onError" }, script = "window.__zjGis.loadClient(onReady, onError);")
    private static native void loadClientRaw(Callback onReady, Callback onError);

    @JSBody(
            params = { "clientId", "nonce", "title", "cancelLabel", "onCredential", "onError", "onCancel" },
            script = "window.__zjGis.startSignIn(clientId, nonce, title, cancelLabel, onCredential, onError, onCancel);"
    )
    private static native void startSignInRaw(
            String clientId,
            String nonce,
            String title,
            String cancelLabel,
            Callback onCredential,
            Callback onError,
            Callback onCancel
    );

    @JSBody(script = "window.__zjGis.cancel();")
    private static native void cancelRaw();

    @JSBody(script = "window.__zjGis.disableAutoSelect();")
    private static native void disableAutoSelectRaw();

    public static String resolveClientId(String fallback) {
        install();
        return resolveClientIdRaw(fallback != null ? fallback : "");
    }

    public static boolean isSecureContext() {
        install();
        return isSecureContextRaw();
    }

    public static void loadClient(Callback onReady, Callback onError) {
        install();
        loadClientRaw(onReady, onError);
    }

    public static void startSignIn(
            String clientId,
            String nonce,
            String title,
            String cancelLabel,
            Callback onCredential,
            Callback onError,
            Callback onCancel
    ) {
        install();
        startSignInRaw(clientId, nonce, title, cancelLabel, onCredential, onError, onCancel);
    }

    public static void cancel() {
        install();
        cancelRaw();
    }

    public static void disableAutoSelect() {
        install();
        disableAutoSelectRaw();
    }
}
