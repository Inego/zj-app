package inego.zungjunggame.i18n

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs one `lang/<tag>.zip` per [AppLocale.tag] plus `lang/index.txt`.
 *
 * The task is Gradle-facing only: it does its file IO in [main]/[pack] and never
 * touches Gdx, so it can run before any backend starts. [LanguagePack] owns the
 * inverse (reading a pack); the two share the entry layout — a single `pack`
 * entry holding the resolved bundle and every reference body (see [writePack]).
 *
 * The packer resolves a language's bundle exactly as `I18NBundle` would for
 * [AppLocale.javaLocale] (most specific suffix first, base last), then fills every
 * base key the language lacks with its English value. That fill is what makes keys
 * listed in `assets/i18n/untranslated-keys.txt` present in the pack, so the game
 * never opens a parent bundle at runtime.
 *
 * Output is deterministic — sorted keys, sorted entries, a fixed zip timestamp and
 * an escape-only property serializer — so Gradle up-to-date checks and artifacts
 * are reproducible.
 */
object LanguagePacker {

    private const val I18N_DIR = "i18n"
    private const val REFERENCE_DIR = "reference"
    private const val BASE_BUNDLE = "ui.properties"
    private const val PACK_ENTRY = "pack"
    private const val MARKDOWN_SUFFIX = ".md"
    private const val ZIPS_DIR = "lang"
    private const val INDEX_NAME = "index.txt"

    /**
     * Entry timestamp for every zip entry. 1980-01-01 is the DOS epoch, so the
     * writer stores it in the standard date fields (no extra timestamp field) and
     * the bytes do not vary with the clock or the machine timezone.
     */
    private val FIXED_ENTRY_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 0)

    /** `""` → `ui.properties`; `zh` → `ui_zh.properties`. */
    fun bundleFileName(suffix: String): String =
        if (suffix.isEmpty()) BASE_BUNDLE else "ui_$suffix.properties"

    /**
     * Bundle-file suffixes to try for [tag], most specific first, base last — the
     * same chain [com.badlogic.gdx.utils.I18NBundle] walks for the locale:
     * `zh-HK` → `["zh_HK", "zh", ""]` (`Locale("zh", "HK")` falls through `ui_zh`).
     */
    fun sourceSuffixes(tag: String): List<String> {
        val locale = AppLocale.fromTag(tag).javaLocale
        val suffixes = ArrayList<String>(3)
        val language = locale.language
        val country = locale.country
        if (language.isNotEmpty() && country.isNotEmpty()) suffixes += "${language}_$country"
        if (language.isNotEmpty()) suffixes += language
        suffixes += ""
        return suffixes
    }

    /**
     * The bundle file name for [tag]: the first candidate [exists] accepts, or null
     * when not even the base bundle exists. [exists] is asked about file names, so
     * the rule is testable without a directory.
     */
    fun pickLanguageBundle(suffixes: List<String>, exists: (String) -> Boolean): String? =
        suffixes.firstOrNull { exists(bundleFileName(it)) }?.let(::bundleFileName)

    /**
     * The resolved bundle: every base key, with [language]'s value where that file
     * declares it and the English [base] value otherwise. Sorted by key.
     */
    fun resolveBundle(language: Map<String, String>, base: Map<String, String>): Map<String, String> {
        val resolved = LinkedHashMap<String, String>(base.size)
        for (key in base.keys.sorted()) resolved[key] = language[key] ?: base.getValue(key)
        return resolved
    }

    /**
     * [map] as a stable `.properties` document: keys sorted, `\n` line endings, no
     * comment or timestamp line, non-ASCII escaped as `\uXXXX`. Bytes are
     * ISO-8859-1 so the result is pure ASCII — what `java.util.Properties` and
     * libGDX `PropertiesUtils.load` expect.
     */
    fun writeProperties(map: Map<String, String>): ByteArray {
        val out = StringBuilder(map.size * 48)
        for (key in map.keys.sorted()) {
            appendEscaped(out, key, isKey = true)
            out.append('=')
            appendEscaped(out, map.getValue(key), isKey = false)
            out.append('\n')
        }
        return out.toString().toByteArray(StandardCharsets.ISO_8859_1)
    }

    /**
     * The single zip entry of a pack: the resolved bundle and every page body in
     * one blob, so the whole pack is one deflated stream with a shared
     * dictionary (`ZipOutputStream` deflates each entry on its own, resetting the
     * window).
     *
     * Framing: `LanguagePack.parsePack` inverts it.
     * ```
     * <bundle-byte-count>\n<bundle-bytes><pages>
     * ```
     * where `<pages>` is [writePages]' output.
     */
    fun writePack(bundle: ByteArray, pages: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bundle.size.toString().toByteArray(StandardCharsets.US_ASCII))
        out.write('\n'.code)
        out.write(bundle)
        out.write(writePages(pages))
        return out.toByteArray()
    }

    /**
     * Every page body, sorted by path, framed as
     * `<relative-path>\n<utf8-byte-count>\n<bytes>`.
     *
     * The count — not a delimiter — makes any body unambiguous, including one
     * containing newlines or a line that looks like a path.
     */
    fun writePages(pages: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (path in pages.keys.sorted()) {
            val body = pages.getValue(path)
            out.write(path.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.write(body.size.toString().toByteArray(StandardCharsets.US_ASCII))
            out.write('\n'.code)
            out.write(body)
        }
        return out.toByteArray()
    }

    /** [entries] as a deflated zip with sorted ASCII entry names. */
    fun writeZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in entries.keys.sorted()) {
                val entry = ZipEntry(name)
                entry.setTimeLocal(FIXED_ENTRY_TIME)
                zip.putNextEntry(entry)
                zip.write(entries.getValue(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Reads `assetsRoot`, writes `<outRoot>/lang/<tag>.zip` and `lang/index.txt`. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: LanguagePacker <assetsRoot> <outRoot>" }
        pack(File(args[0]), File(args[1]))
    }

    fun pack(assetsRoot: File, outRoot: File) {
        val i18nDir = File(assetsRoot, I18N_DIR)
        val referenceDir = File(assetsRoot, REFERENCE_DIR)
        val base = loadProperties(File(i18nDir, BASE_BUNDLE))
        val tags = AppLocale.entries.map { it.tag }.sorted()
        val basePages = readPages(File(referenceDir, AppLocale.DEFAULT_TAG))

        val outDir = File(outRoot, ZIPS_DIR)
        outDir.mkdirs()

        for (tag in tags) {
            val source = pickLanguageBundle(sourceSuffixes(tag)) { File(i18nDir, it).isFile }
                ?: error("No language bundle for '$tag' under $i18nDir")
            val language = if (source == BASE_BUNDLE) base else loadProperties(File(i18nDir, source))

            val pages = resolvePages(readPages(File(referenceDir, tag)), basePages)
            val bundle = writeProperties(resolveBundle(language, base))
            File(outDir, "$tag.zip").writeBytes(writeZip(mapOf(PACK_ENTRY to writePack(bundle, pages))))
        }

        File(outDir, INDEX_NAME).writeText(tags.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }

    private fun loadProperties(file: File): Map<String, String> {
        val props = Properties()
        file.reader(StandardCharsets.UTF_8).use { props.load(it) }
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    /**
     * Every reference page: [language]'s body where that pack has one and the
     * English [base] body otherwise — the page-level twin of [resolveBundle], so a
     * page not translated yet ships in English instead of breaking the pack.
     */
    fun resolvePages(language: Map<String, ByteArray>, base: Map<String, ByteArray>): Map<String, ByteArray> {
        val resolved = LinkedHashMap<String, ByteArray>(base.size + language.size)
        for (path in (base.keys + language.keys).sorted()) {
            resolved[path] = language[path] ?: base.getValue(path)
        }
        return resolved
    }

    private fun readPages(localeDir: File): Map<String, ByteArray> =
        markdownFiles(localeDir).associate { relativeMarkdownPath(localeDir, it) to it.readBytes() }

    private fun markdownFiles(localeDir: File): List<File> =
        if (!localeDir.isDirectory) emptyList()
        else localeDir.walkTopDown().filter { it.isFile && it.name.endsWith(MARKDOWN_SUFFIX) }.sortedBy { it.path }.toList()

    private fun relativeMarkdownPath(localeDir: File, file: File): String =
        localeDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    private fun appendEscaped(out: StringBuilder, value: String, isKey: Boolean) {
        for (i in value.indices) {
            val c = value[i]
            when {
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\u000C' -> out.append("\\f")
                c == '=' || c == ':' || c == '#' || c == '!' -> out.append('\\').append(c)
                c == ' ' -> if (i == 0 || isKey) out.append("\\ ") else out.append(' ')
                c.code !in 0x20..0x7E ->
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
    }
}
