package inego.zungjunggame.i18n

import inego.zungjunggame.render.reference.ReferenceAssetSources
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Content-free assertions about the shipped UI bundles.
 *
 * **The rule this file exists to enforce: a test never asserts *what* the copy says.**
 * Wording belongs to whoever edits `assets/i18n/ui*.properties`, so rewording or
 * translating a string must never require a Kotlin change. That was not always true
 * here: prefixing every bundle value with `Zz` once turned 27 tests red, and every
 * one of them was asserting copy. Those assertions are gone; this file and
 * [BundleCoverageTest] hold the structure they were mixed up with.
 *
 * What *is* a defect, and is asserted here:
 *  - a locale that omits a key that is not declared English-only in
 *    `assets/i18n/untranslated-keys.txt` (listed keys fall back to the English base);
 *  - a locale that declares a key the base does not (a typo or a stale name);
 *  - an `untranslated-keys.txt` entry that is not in the base, or that every locale
 *    has since translated (a stale exemption);
 *  - a blank value, or one that echoes its own key (a key typo, or no bundle loaded);
 *  - a template that lost a `{0}` placeholder on the way into another language;
 *  - a bundle no [AppLocale] can reach.
 *
 * Feature tests then assert **composition** — which key, with which arguments, in
 * which order — by comparing against `L10n.get` / `L10n.format` for that very key.
 * That is wording-free and still fails when the wrong key, the wrong arguments or
 * the wrong template is used, which is the part that is actually the code's job.
 */
object TestBundle {

    /** Tag of the base bundle, `assets/i18n/ui.properties`. */
    const val BASE_TAG = "en"

    fun assetsRoot(): File = assertNotNull(
        ReferenceAssetSources.findAssetsRoot(),
        "assets/ not found from ${File(".").absolutePath}",
    )

    /** `en` → `ui.properties`, `ru` → `ui_ru.properties`. */
    fun fileFor(tag: String): File =
        File(assetsRoot(), if (tag == BASE_TAG) "i18n/ui.properties" else "i18n/ui_$tag.properties")

    /**
     * Every shipped UI bundle, sorted. Derived from the files on disk, so dropping a
     * new `ui_<tag>.properties` in is enough for every guard below to cover it.
     */
    fun shippedTags(): List<String> {
        val dir = File(assetsRoot(), "i18n")
        val tags = dir
            .listFiles { f: File -> f.isFile && f.name.startsWith("ui") && f.name.endsWith(".properties") }
            .orEmpty()
            .map { tagOf(it.name) }
            .sorted()
        assertTrue(tags.contains(BASE_TAG), "no ui.properties under ${dir.absolutePath}")
        return tags
    }

    private fun tagOf(fileName: String): String =
        when (val suffix = fileName.removePrefix("ui").removeSuffix(".properties")) {
            "" -> BASE_TAG
            else -> suffix.removePrefix("_")
        }

    /**
     * The Java locale for a shipped tag, resolved through the app's own picker
     * mapping — so a bundle no [AppLocale] entry maps to is a failure here rather
     * than a silent English fallback at runtime.
     */
    fun localeFor(tag: String): Locale {
        val locale = AppLocale.fromTag(tag).javaLocale
        assertEquals(tag, locale.language.lowercase(), "no AppLocale entry maps to shipped bundle '$tag'")
        return locale
    }

    /** Keys → values exactly as [tag]'s own file declares them, with no fallback. */
    fun declared(tag: String): Map<String, String> {
        val file = fileFor(tag)
        assertTrue(file.isFile, "missing bundle ${file.absolutePath}")
        val props = Properties()
        file.reader(Charsets.UTF_8).use { props.load(it) }
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    /**
     * [key] must be declared in [tag]'s own bundle **and** [L10n] must currently be
     * serving that value. Catches an untranslated key (served from the base file
     * instead), a blank value, and a key typo.
     *
     * Pass [declared] when checking many keys against one locale, to parse once.
     */
    fun assertServedFromOwnFile(key: String, tag: String, declared: Map<String, String> = declared(tag)) {
        val file = fileFor(tag).name
        val value = assertNotNull(declared[key], "$file does not declare '$key'")
        assertTrue(value.isNotBlank(), "'$key' is blank in $file")
        assertTrue(value != key, "'$key' echoes its own name in $file")
        assertEquals(value, L10n.get(key), "L10n does not serve '$key' from $file")
    }

    /**
     * The keys intentionally shipped in English only, read from
     * `assets/i18n/untranslated-keys.txt`. A locale may omit exactly these; every
     * other base key it must declare. Keeping the list in a file (not in Kotlin)
     * lets whoever adds an English string mark it without touching code.
     */
    fun untranslatedKeys(): Set<String> {
        val file = File(assetsRoot(), "i18n/untranslated-keys.txt")
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        return file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Every base key must be declared by every shipped locale, except the ones
     * listed as English-only in `untranslated-keys.txt`. A locale must never
     * declare a key the base lacks, and every exemption must name a real base key.
     */
    fun assertBundlesDeclareEveryKey() {
        val base = declared(BASE_TAG)
        val exempt = untranslatedKeys()
        val unknown = (exempt - base.keys).sorted()
        assertTrue(unknown.isEmpty(), "untranslated-keys.txt names key(s) the base lacks: $unknown")
        for (tag in shippedTags()) {
            if (tag == BASE_TAG) continue
            val file = fileFor(tag).name
            val keys = declared(tag)
            val missing = (base.keys - keys.keys - exempt).sorted()
            val extra = (keys.keys - base.keys).sorted()
            assertTrue(missing.isEmpty(), "$file is missing ${missing.size} key(s): ${missing.take(10)}")
            assertTrue(extra.isEmpty(), "$file declares ${extra.size} key(s) the base lacks: ${extra.take(10)}")
        }
    }

    /**
     * An exemption is stale once every shipped locale declares the key: the entry
     * no longer exempts anything and would hide a future regression. It must be
     * removed from `untranslated-keys.txt`.
     */
    fun assertUntranslatedListIsNotStale() {
        val exempt = untranslatedKeys()
        if (exempt.isEmpty()) return
        val locales = shippedTags().filter { it != BASE_TAG }
        val declaredByLocale = locales.associateWith { declared(it) }
        val stale = exempt.filter { key -> locales.all { key in declaredByLocale.getValue(it) } }.sorted()
        assertTrue(stale.isEmpty(), "untranslated-keys.txt lists key(s) every locale now translates: $stale")
    }

    /** No shipped value may be blank or echo its key, in any language. */
    fun assertNoBlankOrKeyEchoingValues() {
        for (tag in shippedTags()) {
            val file = fileFor(tag).name
            for ((key, value) in declared(tag)) {
                assertTrue(value.isNotBlank(), "'$key' is blank in $file")
                assertTrue(value != key, "'$key' echoes its own name in $file")
            }
        }
    }

    private val PLACEHOLDER = Regex("""\{(\d+)\}""")

    /** Placeholder indices a template uses: `"{0} - {1}"` → `{0, 1}`. */
    fun placeholders(value: String): Set<Int> =
        PLACEHOLDER.findAll(value).map { it.groupValues[1].toInt() }.toSet()

    /**
     * A translation must keep the template's placeholders. Dropping `{0}` silently
     * loses the value it was there to show — a defect, not a wording choice.
     */
    fun assertTemplatesKeepTheirPlaceholders() {
        val exempt = untranslatedKeys()
        for ((key, baseValue) in declared(BASE_TAG)) {
            val want = placeholders(baseValue)
            if (want.isEmpty()) continue
            for (tag in shippedTags()) {
                if (tag == BASE_TAG) continue
                val declared = declared(tag)
                if (key !in declared) {
                    // Only an English-only key may be absent; anything else is a
                    // missing translation, and is reported by the coverage guard.
                    assertTrue(key in exempt, "${fileFor(tag).name} omits '$key', which is not English-only")
                    continue
                }
                val got = placeholders(declared[key].orEmpty())
                assertEquals(want, got, "'$key' placeholder set differs in ${fileFor(tag).name}")
            }
        }
    }

    /**
     * [L10n.format] must substitute every argument and leave no placeholder behind.
     * Runs against whatever locale [L10n] is currently on.
     */
    fun assertFormats(key: String, vararg args: Any) {
        val out = L10n.format(key, *args)
        assertTrue(out.isNotBlank() && out != key, "'$key' did not resolve: '$out'")
        assertFalse(PLACEHOLDER.containsMatchIn(out), "'$key' left a placeholder unfilled: '$out'")
        for (arg in args) {
            assertTrue(out.contains(arg.toString()), "'$key' dropped its argument '$arg': '$out'")
        }
    }
}
