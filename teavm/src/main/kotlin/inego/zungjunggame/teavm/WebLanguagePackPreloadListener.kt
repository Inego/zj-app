package inego.zungjunggame.teavm

import com.badlogic.gdx.Files
import com.github.xpenatan.gdx.teavm.backends.web.WebPreloadApplicationListener
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.LanguagePack
import inego.zungjunggame.persist.resolveStartupLocale
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.PlatformStateBackend

/**
 * The gdx-teavm preload screen, held up until the persisted language's pack is
 * readable so `Main` never starts without it.
 *
 * [create] runs after `Gdx.app`/`Gdx.files` are set: it resolves the startup
 * locale (the same [inego.zungjunggame.persist.resolveStartupLocale] `Main`
 * uses, so a first visit seeds `ui.locale` before the zip is chosen),
 * registers an opened-listener, then asks
 * [LanguagePack.ensure] for that zip. `ensure` defers to the fetcher TeaLauncher
 * installed, so the queue stays non-zero until the download finishes. A failed
 * fetch is released by [render]'s `isAssetFailed` guard, which keeps the tab from
 * hanging on the progress screen; `Main` then boots with untranslated keys.
 */
class WebLanguagePackPreloadListener : WebPreloadApplicationListener() {
    private var pendingTag: String? = null

    private val opened: (String) -> Unit = { tag ->
        if (tag == pendingTag) release()
    }

    override fun create() {
        super.create()
        val tag = readPersistedTag()
        if (LanguagePack.isOpen(tag)) return
        pendingTag = tag
        LanguagePack.addOpenedListener(opened)
        addQueue()
        LanguagePack.ensure(tag)
    }

    override fun render() {
        super.render()
        val tag = pendingTag ?: return
        if (assetLoader.isAssetFailed(Files.FileType.Internal, "lang/$tag.zip")) release()
    }

    private fun release() {
        pendingTag = null
        LanguagePack.removeOpenedListener(opened)
        subtractQueue()
    }

    private fun readPersistedTag(): String = try {
        val store = DefaultStateStore(PlatformStateBackend.create())
        store.resolveStartupLocale().tag
    } catch (_: Throwable) {
        AppLocale.DEFAULT.tag
    }
}
