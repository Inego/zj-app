package inego.zungjunggame.i18n

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.I18NBundle
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.Locale
import java.util.MissingResourceException
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin façade over libGDX [I18NBundle].
 *
 * Bundles are immutable for a locale: [setLocale] loads a **new** bundle.
 * Base path is `i18n/ui` → `assets/i18n/ui.properties`, `ui_ru.properties`, …
 *
 * Call [bootstrap] once after Gdx is ready (or with a test [FileHandle]).
 * Until then [get] returns the key itself so call sites never NPE.
 */
object L10n {
    private const val DEFAULT_BASE_PATH = "i18n/ui"

    @Volatile
    private var baseHandle: FileHandle? = null

    @Volatile
    private var bundle: I18NBundle? = null

    @Volatile
    private var locale: Locale = Locale.US

    /**
     * Bundles resolved by explicit locale, independent of the active one. Content
     * that must follow a *pack* locale rather than the chrome locale (the
     * reference catalog) reads through [get] with its own locale.
     */
    private val perLocaleBundles = HashMap<Locale, I18NBundle>()

    /**
     * Keys already reported missing, by locale. A missing key is usually drawn every
     * frame, so the error belongs in the log once, not in every frame's output.
     */
    private val reportedMisses = ConcurrentHashMap<String, Boolean>()


    val isReady: Boolean
        get() = bundle != null

    /**
     * The picker entry for the active bundle, or the default before [bootstrap]. Plural
     * inflection and other locale rules key off this so they follow the loaded language.
     */
    val currentLocale: AppLocale
        get() = AppLocale.fromJavaLocale(locale) ?: AppLocale.DEFAULT

    /**
     * Load the bundle for [locale]. Safe to call repeatedly.
     *
     * @param base optional override (tests: filesystem handle to `…/i18n/ui`).
     *   When null the open [LanguagePack] wins; otherwise the default
     *   filesystem base (`i18n/ui`) is used.
     */
    fun bootstrap(
        locale: Locale = Locale.US,
        base: FileHandle? = null,
    ) {
        baseHandle = base
        perLocaleBundles.clear()
        setLocale(locale)
    }

    /**
     * Replace the active bundle for [locale]. No-op if not yet [bootstrap]ped
     * and no base can be resolved.
     */
    fun setLocale(locale: Locale) {
        this.locale = locale
        val base = resolveBase(locale) ?: run {
            bundle = null
            Gdx.app?.log("LangPack", "bundle locale=${localeLabel(locale)} source=none state=notReady")
            return
        }
        val t0 = System.nanoTime()
        val source = sourceName(locale)
        // `createBundle` throws MissingResourceException when the base carries no
        // bundle for the locale (e.g. no open pack and no `i18n/ui` on disk). Keep
        // the previous bundle rather than crash the caller.
        val created = try {
            I18NBundle.createBundle(base, locale)
        } catch (t: Throwable) {
            Gdx.app?.log(
                "LangPack",
                "bundle:failed locale=${localeLabel(locale)} source=$source " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            return
        }
        bundle = created
        Gdx.app?.log(
            "LangPack",
            "bundle locale=${localeLabel(locale)} source=$source ms=${(System.nanoTime() - t0) / 1_000_000L}",
        )
    }

    fun get(key: String): String {
        val b = bundle ?: return key
        return try {
            b.get(key)
        } catch (_: MissingResourceException) {
            miss(key, locale)
        } catch (_: Exception) {
            // libGDX wraps missing keys as GdxRuntimeException on some backends.
            miss(key, locale)
        }
    }

    /**
     * [key] resolved in the bundle for an explicit [locale], without touching the
     * active UI locale. Returns [key] when no bundle or key exists.
     */
    fun get(key: String, locale: Locale): String {
        val b = findBundle(locale) ?: return key
        return try {
            b.get(key)
        } catch (_: MissingResourceException) {
            miss(key, locale)
        } catch (_: Exception) {
            miss(key, locale)
        }
    }

    private fun findBundle(locale: Locale): I18NBundle? {
        if (bundle != null && locale == this.locale) return bundle
        perLocaleBundles[locale]?.let { return it }
        val base = resolveBase(locale) ?: return null
        val t0 = System.nanoTime()
        return try {
            I18NBundle.createBundle(base, locale).also {
                perLocaleBundles[locale] = it
                Gdx.app?.log(
                    "LangPack",
                    "bundle locale=${localeLabel(locale)} source=${sourceName(locale)} scope=aux " +
                        "ms=${(System.nanoTime() - t0) / 1_000_000L}",
                )
            }
        } catch (t: Throwable) {
            Gdx.app?.log(
                "LangPack",
                "bundle:failed locale=${localeLabel(locale)} ${t.javaClass.simpleName}: ${t.message}",
            )
            null
        }
    }

    /** Where [locale]'s bundle resolves from: the bootstrap override, the open pack, or the filesystem. */
    private fun sourceName(locale: Locale): String {
        if (baseHandle != null) return "override"
        val tag = AppLocale.fromJavaLocale(locale)?.tag ?: return "filesystem"
        return if (LanguagePack.isOpen(tag)) "pack" else "filesystem"
    }

    private fun localeLabel(locale: Locale): String =
        AppLocale.fromJavaLocale(locale)?.tag ?: locale.toString()

    /**
     * The base handle to load [locale] from. An explicit [bootstrap] override
     * wins (tests/dev), then an open [LanguagePack] for the locale's picker
     * entry, then the filesystem `i18n/ui`.
     */
    private fun resolveBase(locale: Locale): FileHandle? {
        baseHandle?.let { return it }
        packHandle(locale)?.let { return it }
        return resolveDefaultBase()
    }

    /**
     * An in-memory handle over the open pack's `bundle.properties`, or null when
     * that locale's pack is not open. A pack beats the loose `i18n/ui` tree, so
     * production never reads the filesystem once a pack is open.
     */
    private fun packHandle(locale: Locale): FileHandle? {
        val tag = AppLocale.fromJavaLocale(locale)?.tag ?: return null
        if (!LanguagePack.isOpen(tag)) return null
        val bytes = LanguagePack.bundleBytes() ?: return null
        return PackFileHandle(bytes)
    }

    fun format(key: String, vararg args: Any?): String {
        val b = bundle ?: return key
        return try {
            if (args.isEmpty()) b.get(key) else b.format(key, *args)
        } catch (_: MissingResourceException) {
            miss(key, locale)
        } catch (_: Exception) {
            miss(key, locale)
        }
    }

    /**
     * The bundle exists but [key] does not resolve. Report it once per locale and
     * key, then keep the raw key so a missing string never crashes a call site.
     */
    private fun miss(key: String, locale: Locale): String {
        val tag = AppLocale.fromJavaLocale(locale)?.tag ?: locale.toString()
        if (reportedMisses.putIfAbsent("$tag/$key", true) == null) {
            Gdx.app?.error(
                "LangPack",
                "missing key=$key locale=$tag source=${sourceName(locale)}",
            )
        }
        return key
    }

    /** Drop bundle (tests). */
    fun resetForTests() {
        bundle = null
        perLocaleBundles.clear()
        reportedMisses.clear()
        baseHandle = null
        locale = Locale.US
    }

    private fun resolveDefaultBase(): FileHandle? {
        if (Gdx.app != null && Gdx.files != null) {
            return Gdx.files.internal(DEFAULT_BASE_PATH)
        }
        // Headless tests: walk for assets/i18n/ui.properties
        val root = findAssetsRoot() ?: return null
        return FileHandle(File(root, "i18n/ui"))
    }

    private fun findAssetsRoot(): File? {
        val cwd = File(".").canonicalFile
        val candidates = listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
            File(cwd, "../assets"),
        )
        return candidates.firstOrNull { File(it, "i18n").isDirectory }
    }
}

/**
 * A [FileHandle] over the open pack's `bundle.properties` bytes.
 *
 * `I18NBundle` uses this handle only through `name()`, `sibling()`, `read()`
 * and `reader(String)`, so serving the packed bytes there is enough for the
 * public `I18NBundle.createBundle` path. That path calls the real (protected)
 * `load(Reader)` **and then** the private `setLocale`, which is what
 * initializes the bundle's `TextFormatter` — the key detail that makes
 * `format(key, args)` work without reflection or a reimplemented formatter.
 *
 * `name()` is a stable base, and every `sibling` resolves back to the same
 * handle, so the whole candidate chain (`bundle_ru`, `bundle`, `bundle.properties`)
 * reads the one packed bundle.
 */
private class PackFileHandle(private val bytes: ByteArray) : FileHandle() {
    override fun name(): String = "bundle"

    override fun sibling(name: String): FileHandle = this

    override fun read(): InputStream = ByteArrayInputStream(bytes)

    override fun reader(charset: String): Reader =
        InputStreamReader(ByteArrayInputStream(bytes), charset)
}
