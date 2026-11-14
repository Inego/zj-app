package inego.zungjunggame.i18n

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.state.StateSlot

/**
 * Single entry point for hot-switch localization.
 *
 * Loads the persisted locale → [L10n] bundle → [Fonts] script profile →
 * [ReferenceCatalog] locale → [LocaleBus]. Reference content falls back to
 * [ReferenceCatalog.DEFAULT_LOCALE] when the chosen tag has no
 * `assets/reference/<tag>/` pack yet.
 */
object LocaleController {
    /** Owner of the persisted locale; null in tests that never wire a store. */
    @Volatile
    private var localeSlot: StateSlot<AppLocale>? = null

    @Volatile
    var current: AppLocale = AppLocale.DEFAULT
        private set

    /** Main-thread listener armed while a switch waits for its pack to open. */
    private var pendingListener: ((String) -> Unit)? = null

    /**
     * Call once from [inego.zungjunggame.Main.create] after Gdx is ready.
     *
     * @param localeSlot persisted locale; the composition root supplies it
     * @param locale the persisted value the cold start already read (`AppState`);
     *   when null the slot is read here
     * @param bundleBase optional test override for [L10n.bootstrap]
     */
    fun bootstrap(
        localeSlot: StateSlot<AppLocale>? = null,
        locale: AppLocale? = null,
        bundleBase: FileHandle? = null,
    ) {
        this.localeSlot = localeSlot
        // Cold start: the pack is already readable (desktop/Android internal, Wasm
        // preloaded), so apply now from whatever source exists — including the
        // authoring filesystem fallback in dev/tests.
        apply(
            locale ?: localeSlot?.requireValue() ?: AppLocale.DEFAULT,
            persist = false,
            bundleBase = bundleBase,
            awaitPack = false,
        )
    }

    /**
     * Switch language, persist, notify listeners. No-op if already active.
     *
     * On the web the target zip is fetched asynchronously, so the switch only
     * completes once [LanguagePack] reports that pack open; until then [current]
     * and [L10n] are unchanged.
     */
    fun setLocale(locale: AppLocale) {
        if (locale == current && L10n.isReady) {
            return
        }
        apply(locale, persist = true, bundleBase = null, awaitPack = true)
    }

    /**
     * Endonym (native self-name) for [locale] — used in the language picker
     * so the list stays recognizable in every UI language.
     */
    fun endonym(locale: AppLocale): String = locale.endonym

    /**
     * Main-menu language row label: **endonym** of the active locale only.
     * The row pairs this with [inego.zungjunggame.render.ui.UiIcons.globe]
     * (no "Language: …" prefix).
     */
    fun languageMenuLabel(): String = endonym(current)

    /** Reset for unit tests. */
    fun resetForTests() {
        cancelPending()
        localeSlot = null
        current = AppLocale.DEFAULT
        L10n.resetForTests()
        LanguagePack.resetForTests()
        LocaleBus.clearListeners()
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
    }

    private fun apply(
        locale: AppLocale,
        persist: Boolean,
        bundleBase: FileHandle?,
        awaitPack: Boolean,
    ) {
        cancelPending()
        if (!awaitPack || bundleBase != null || LanguagePack.isOpen(locale.tag)) {
            finish(locale, persist, bundleBase)
            return
        }
        // The web startup set ships no `i18n/ui`, and the pack arrives from the
        // fetcher after [LanguagePack.ensure] returns. Finish only when the pack
        // opens, so L10n/ReferenceCatalog never read a missing base (the
        // MissingResourceException this guards against).
        val listener: (String) -> Unit = { opened ->
            if (opened == locale.tag) {
                val self = pendingListener
                pendingListener = null
                if (self != null) LanguagePack.removeOpenedListener(self)
                finish(locale, persist, null)
            }
        }
        pendingListener = listener
        LanguagePack.addOpenedListener(listener)
        LanguagePack.ensure(locale.tag)
    }

    private fun cancelPending() {
        pendingListener?.let { LanguagePack.removeOpenedListener(it) }
        pendingListener = null
    }

    private fun finish(
        locale: AppLocale,
        persist: Boolean,
        bundleBase: FileHandle?,
    ) {
        val t0 = System.nanoTime()
        current = locale
        if (bundleBase != null || !L10n.isReady) {
            L10n.bootstrap(locale.javaLocale, base = bundleBase)
        } else {
            L10n.setLocale(locale.javaLocale)
        }
        // Rebuild FreeType families when ScriptProfile changes (en↔ru is a no-op).
        // Safe before Gdx graphics on pure unit tests (Fonts catches / no-ops).
        try {
            if (Gdx.app != null) {
                Fonts.applyLocale(locale)
            }
        } catch (_: Throwable) {
            // Headless tests without Gdx.files / graphics.
        }
        val refTag = resolveReferenceLocaleTag(locale.tag)
        ReferenceCatalog.setLocale(refTag)
        if (persist) {
            localeSlot?.set(locale)
        }
        LocaleBus.fire(locale)
        Gdx.app?.log(
            "Locale",
            "locale=${locale.tag} reference=$refTag pack=${LanguagePack.openTag ?: "none"} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000L}",
        )
    }

    /**
     * Prefer an existing reference pack for [tag]; else language-only; else default.
     */
    fun resolveReferenceLocaleTag(tag: String): String {
        if (referencePackExists(tag)) return tag
        val lang = tag.substringBefore('-')
        if (lang.isNotEmpty() && lang != tag && referencePackExists(lang)) return lang
        return ReferenceCatalog.DEFAULT_LOCALE
    }

    /**
     * A shipped pack is a tag listed in `lang/index.txt` (stage 4 ships that index
     * with the game). The index is absent in authoring/headless runs, so fall back
     * to probing a body every pack carries — [ReferenceCatalog.PACK_PROBE_FILE].
     */
    private fun referencePackExists(tag: String): Boolean {
        val indexed = LanguagePack.availableTags()
        if (indexed.isNotEmpty()) return tag in indexed
        return try {
            val path = "reference/$tag/${ReferenceCatalog.PACK_PROBE_FILE}"
            if (Gdx.app != null && Gdx.files != null) {
                return Gdx.files.internal(path).exists()
            }
            val cwd = java.io.File(".").canonicalFile
            val candidates = listOf(
                java.io.File(cwd, "assets/$path"),
                java.io.File(cwd.parentFile, "assets/$path"),
            )
            candidates.any { it.isFile }
        } catch (_: Exception) {
            false
        }
    }
}
