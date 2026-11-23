package inego.zungjunggame.teavm

import com.badlogic.gdx.Files
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration
import com.github.xpenatan.gdx.teavm.backends.web.WebAssetPreloadListener
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetLoaderListener
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.AssetType
import com.github.xpenatan.gdx.teavm.backends.web.assetloader.WebBlob
import inego.zungjunggame.Main
import inego.zungjunggame.app.DeepLink
import inego.zungjunggame.i18n.LanguagePack
import inego.zungjunggame.i18n.PlatformLanguages
import inego.zungjunggame.persist.AuthPersistedSessionKey
import inego.zungjunggame.platform.PlatformTimeZone
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.net.ApiConfig
import inego.zungjunggame.platform.GoogleAuthProvider
import inego.zungjunggame.platform.OverlayClipboard
import inego.zungjunggame.platform.SessionCoordination
import inego.zungjunggame.platform.SliceScheduler
import inego.zungjunggame.platform.WebSocketClient
import inego.zungjunggame.state.PlatformStateBackend
import inego.zungjunggame.state.PreferencesStateBackend

/** Launches the Web (TeaVM) application. */
class TeaLauncher {
    companion object {
        /**
         * `localStorage` namespace libGDX's preferences and the per-key state
         * backend share. Changing it abandons every stored session and setting.
         */
        private const val STORAGE_PREFIX = "zj_game"

        @JvmStatic
        fun main(args: Array<String>) {
            ApiConfig.baseUrl = NativeApiConfig.resolveApiBase(ApiConfig.DEFAULT_BASE_URL)
            // Sampled once, before Main reads InputProfile. A finger gets the
            // touch profile (tile magnifier, empty-felt menu). A mouse stays desktop.
            InputProfile.webCoarsePointer = NativePrimaryPointer.isCoarse()
            // The mail deep link, if the page opened with one.
            DeepLink.publish(NativeApiConfig.consumeDeepLinkFragment())

            val config = WebApplicationConfiguration("canvas").apply {
                width = 0
                height = 0
                useGL30 = true
                antialiasing = true
                usePhysicalPixels = true
                storagePrefix = STORAGE_PREFIX
                showDownloadLogs = false
                // gdx-freetype-web is old-style Emscripten (`var Module=...` in scripts/freetype.js).
                // Pixmap uses window.Gdx from gdx.wasm.js; FreeType JNI glue needs this script.
                preloadListener = WebAssetPreloadListener { loader ->
                    loader.loadScript("freetype.js")
                    // The web side of "make a pack readable": LanguagePack.ensure
                    // defers here, this fetches the one zip, and onReady re-reads it
                    // from the virtual filesystem. The zips are copied into
                    // assets/lang/ after the preload manifest is baked.
                    LanguagePack.fetcher = LanguagePack.Fetcher { tag, onReady ->
                        loader.loadAsset(
                            "lang/$tag.zip",
                            AssetType.Binary,
                            Files.FileType.Internal,
                            object : AssetLoaderListener<WebBlob> {
                                override fun onSuccess(url: String, result: WebBlob?) {
                                    onReady()
                                }
                            },
                        )
                    }
                }
            }
            WebSocketClient.factory = { TeaWebSocketClient() }
            GoogleAuthProvider.factory = { WasmGoogleAuthProvider() }
            OverlayClipboard.factory = { WasmOverlayClipboard.shared }
            // One live localStorage key per state key. libGDX's WebPreferences
            // caches the whole container per tab and rewrites every key on flush,
            // so two tabs would overwrite each other's writes — and the refresh
            // token is written by whichever tab is rotating.
            PlatformStateBackend.factory = {
                WasmStateBackend(STORAGE_PREFIX, PreferencesStateBackend.STATE_NAME)
            }
            // The origin-wide refresh lock, plus the wake-up when another tab
            // rotates the token, signs out, or signs in as a different account.
            SessionCoordination.factory = {
                WasmSessionCoordination(
                    webStateStorageKey(
                        STORAGE_PREFIX,
                        PreferencesStateBackend.STATE_NAME,
                        AuthPersistedSessionKey.fullName,
                    ),
                )
            }
            // navigator.languages. TeaVM Locale.getDefault is not the browser,
            // and the preload listener resolves ui.locale before Main starts.
            PlatformLanguages.provider = {
                NativeBrowserLanguages.preferredTagsCsv()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            // Intl's zone, not TeaVM's. Installed before a signed-in refresh
            // reports it for email reports.
            PlatformTimeZone.provider = { NativeTimeZone.ianaId().trim().ifEmpty { null } }
            // The same zone as the offset in force at an instant, for the rating
            // chart's popup. Intl knows the browser's daylight saving rules, so
            // the offset is asked per instant rather than cached once.
            PlatformTimeZone.offsetProvider = { millis ->
                NativeLocalTime.offsetMinutes(millis.toDouble())
            }
            inego.zungjunggame.platform.PresenceScheduler.factory = { WasmPresenceScheduler() }
            SliceScheduler.startLoop = WasmSliceScheduler::startLoop
            // The web's context is *measured* durable (six
            // away/return cycles to 155 s, listener live, zero loss events), and the
            // freeze the user actually reported was ours: a ~1.2 s
            // `Fonts.recoverAfterBackgrounding` against a 6–21 ms paint, triggered by
            // a frame gap that `setContinuousRendering(false)` makes it produce
            // *by design*. Installing volatile here paid that on every tab return
            // and on every idle gap over 1.5 s in the foreground.
            //
            // W2 is what makes this safe rather than reckless: `WasmGpuContexts`
            // wires the browser's own `webglcontextrestored` to the same request
            // path, so a genuine loss — a GPU driver reset, an OS-reclaimed tab —
            // still rebuilds. The two shipped together for that reason. Do not
            // "simplify" this to `DurableGpuContexts`: that object's
            // `onContextRecreated` is inert, which would trade the 1.2 s freeze for
            // a silent black canvas.
            inego.zungjunggame.platform.GpuContextPolicy.factory = { WasmGpuContexts() }
            // Deliberately no McHints.enabled = true: TeaVM `Thread.start` is a
            // cooperative fiber, and the MC playout loop has no suspension point, so
            // it would freeze the tab. Web HINT is the classic rank highlight.
            //
            // The custom preload listener keeps the progress screen up until the
            // active language pack is fetched, so Main never starts without it.
            WebApplication(Main(), WebLanguagePackPreloadListener(), config)
        }
    }
}
