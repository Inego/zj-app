package inego.zungjunggame.render.reference

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LanguagePack
import inego.zungjunggame.render.ui.ListItem
import java.util.Locale

/**
 * Table of contents + page bodies for the Zung Jung reference browser.
 *
 * Content lives under `assets/reference/<locale>/` as page bodies only. The
 * locale-invariant taxonomy — section/page ids, file paths, order, indent and
 * dotted numbers — lives **once** in `assets/reference/structure.json`. The
 * translatable navigation strings (browser title, section labels, page titles)
 * live in the UI bundles `assets/i18n/ui_<lang>.properties` under `reference.*`
 * keys. See that folder’s README.
 *
 * **Hierarchy is content, not code:** TOC order, nesting, indent and numbers
 * come from `structure.json` (page order, `level`, `number`) — nothing is
 * invented in Kotlin.
 *
 * **Localization:** call [setLocale] (default [DEFAULT_LOCALE] = `en-US`).
 * Strings resolve from the bundle for the **pack’s** language ([stringsLocale]),
 * never the ambient UI locale, so a pack that falls back to the default still
 * reads as one language.
 */
object ReferenceCatalog {

    const val DEFAULT_LOCALE: String = "en-US"

    /**
     * File whose presence marks a locale pack as shipped. `index.json` is gone
     * (taxonomy lives in `structure.json`), so [inego.zungjunggame.i18n.LocaleController]
     * probes a body every pack must carry — the About page — to decide whether a
     * `reference/<tag>/` pack exists.
     */
    const val PACK_PROBE_FILE: String = "about.md"

    /**
     * App About / License page. Lives in the Rules section so
     * it reuses the Reference browser; [id] is stable across locales.
     */
    const val ABOUT_PAGE_ID: String = "about-license"

    /** Stable section keys (match `id` in `reference/structure.json`). */
    enum class Section(val id: String) {
        Rules("rules"),
        Scoring("scoring"),
    }

    data class Page(
        val id: String,
        /** TOC row label for the active locale (no dotted-number prefix). */
        val title: String,
        val markup: String,
        /**
         * Locale-relative file path from structure.json (e.g. `scoring/patterns/2.1.1.md`).
         * Used to resolve markdown link destinations that point at files.
         */
        val file: String = "",
        /** TOC number from structure.json; null when the page is unnumbered. */
        val number: String? = null,
        /** TOC indent from structure.json; when null, inferred from [number]. */
        val level: Int? = null,
    )

    /** Resolved target of a markdown hyperlink in reference markup. */
    sealed interface LinkTarget {
        data class Page(val section: Section, val pageId: String) : LinkTarget
        data class SectionTarget(val section: Section) : LinkTarget
        data class External(val url: String) : LinkTarget
    }

    @Volatile
    private var assetSource: ReferenceAssetSource? = null

    @Volatile
    private var locale: String = DEFAULT_LOCALE

    @Volatile
    private var cache: LoadedLocale? = null

    /** Parsed `structure.json`; locale-invariant, so cached across [setLocale]. */
    @Volatile
    private var structureCache: List<StructureSection>? = null

    /**
     * Optional test / special-case wiring. Pass null to restore default resolution.
     * Clears the in-memory cache.
     */
    fun configure(
        source: ReferenceAssetSource? = null,
        locale: String = DEFAULT_LOCALE,
    ) {
        assetSource = source
        this.locale = locale
        cache = null
        structureCache = null
    }

    /** Switch active content locale (e.g. `zh-CN`). Reloads on next access. */
    fun setLocale(locale: String) {
        if (this.locale == locale) return
        this.locale = locale
        cache = null
    }

    fun currentLocale(): String = locale

    val sectionLabels: List<String>
        get() = Section.entries.map { sectionLabel(it) }

    fun sectionLabel(section: Section): String =
        ensureLoaded().sectionLabels[section]
            ?: section.name

    fun pages(section: Section): List<Page> {
        val loaded = ensureLoaded()
        val metas = loaded.metaBySection[section].orEmpty()
        return metas.map { loadPage(loaded, it) }
    }

    /**
     * Browser window title from the pack language’s `reference.title` bundle key.
     * Empty string if the bundle omitted it.
     */
    fun browserTitle(): String =
        ensureLoaded().browserTitle

    /**
     * TOC rows for [section]: one row per index page, in index order.
     * Indent and muted number come from the page title / optional overrides.
     * Uses taxonomy metadata only — reads zero `.md` page bodies.
     */
    fun listItems(section: Section): List<ListItem> =
        ensureLoaded().metaBySection[section].orEmpty().map { metaToListItem(it) }

    /** True when [id] exists in [section]'s taxonomy (reads zero `.md` bodies). */
    fun hasPage(section: Section, id: String): Boolean =
        ensureLoaded().metaById[section]?.containsKey(id) == true

    /**
     * Resolve one page with its markdown body, reading and caching only that
     * page's `.md` file on first access.
     */
    fun page(section: Section, id: String): Page? {
        val loaded = ensureLoaded()
        val meta = loaded.metaById[section]?.get(id) ?: return null
        return loadPage(loaded, meta)
    }

    fun defaultPageId(section: Section): String =
        ensureLoaded().metaBySection.getValue(section).first().id

    /**
     * Resolve a markdown link destination from reference body markup.
     *
     * Accepts:
     * - page ids (`pattern-2.1.1`, `scoring-basics`, `rules-overview`)
     * - `#page-id`
     * - locale-relative file paths (`scoring/patterns/2.1.1.md`)
     * - bare dotted numbers (`2.1.1` → `pattern-2.1.1`)
     * - external `http(s)://` / `mailto:` URLs
     *
     * @return null when nothing in the catalog matches and it is not external
     */
    fun resolveHref(href: String): LinkTarget? {
        var h = href.trim()
        if (h.isEmpty()) return null

        if (h.startsWith("http://", ignoreCase = true) ||
            h.startsWith("https://", ignoreCase = true) ||
            h.startsWith("mailto:", ignoreCase = true)
        ) {
            return LinkTarget.External(h)
        }

        if (h.startsWith('#')) {
            h = h.substring(1).trim()
            if (h.isEmpty()) return null
        }

        when (h.lowercase()) {
            "reference", "scores", "scoring", "reference:scoring", "reference:scores", "reference-scoring", "reference-scores" ->
                return LinkTarget.SectionTarget(Section.Scoring)
            "rules", "reference:rules", "reference-rules" ->
                return LinkTarget.SectionTarget(Section.Rules)
            "about", "license", "about-license", "reference:about" ->
                return LinkTarget.Page(Section.Rules, ABOUT_PAGE_ID)
        }

        val loaded = ensureLoaded()

        // Exact page id in any section (taxonomy-only; zero body reads).
        for (section in Section.entries) {
            loaded.metaById[section]?.get(h)?.let { return LinkTarget.Page(section, it.id) }
        }

        // Locale-relative file path.
        val fileNorm = h.removePrefix("./").replace('\\', '/').trimStart('/')
        if (fileNorm.isNotEmpty()) {
            for (section in Section.entries) {
                for (p in loaded.metaBySection[section].orEmpty()) {
                    if (p.file.isEmpty()) continue
                    if (p.file == fileNorm ||
                        p.file.endsWith("/$fileNorm") ||
                        fileNorm.endsWith(p.file)
                    ) {
                        return LinkTarget.Page(section, p.id)
                    }
                }
            }
        }

        // Bare pattern number → pattern-{n}
        if (BARE_PATTERN_NUMBER.matches(h)) {
            return resolveHref("pattern-$h")
        }

        return null
    }

    private val BARE_PATTERN_NUMBER = Regex("""^\d+(?:\.\d+)*$""")

    /**
     * Map flat index pages to TOC rows. No synthetic rows — parents must be
     * real pages in the locale index if they should appear in the tree.
     */
    fun buildTocItems(pages: List<Page>): List<ListItem> =
        pages.map { pageToListItem(it) }

    /**
     * Build a single page row for the TOC. The muted number and indent come from
     * `structure.json`; the label is the locale’s `reference.page.<id>.title`.
     */
    fun pageToListItem(page: Page): ListItem {
        val number = page.number?.takeIf { it.isNotBlank() }
        val level = page.level?.coerceAtLeast(0)
            ?: number?.let { (numberSegments(it).size - 1).coerceAtLeast(0) }
            ?: 0
        return ListItem(
            id = page.id,
            label = page.title,
            number = number,
            level = level,
            selectable = true,
        )
    }

    private fun metaToListItem(meta: PageMeta): ListItem {
        val number = meta.number?.takeIf { it.isNotBlank() }
        val level = meta.level?.coerceAtLeast(0)
            ?: number?.let { (numberSegments(it).size - 1).coerceAtLeast(0) }
            ?: 0
        return ListItem(
            id = meta.id,
            label = meta.title,
            number = number,
            level = level,
            selectable = true,
        )
    }

    private fun numberSegments(number: String): List<String> =
        number.split('.').filter { it.isNotEmpty() }

    private fun ensureLoaded(): LoadedLocale {
        cache?.let { if (it.locale == locale) return it }
        val loaded = loadLocale(locale)
        cache = loaded
        return loaded
    }

    private fun source(): ReferenceAssetSource =
        assetSource ?: ReferenceAssetSources.default().also { assetSource = it }

    private fun loadLocale(loc: String): LoadedLocale {
        val src = source()
        val structure = structure(src)
        val strings = stringsLocale(loc)

        val browserTitle = optionalString("reference.title", strings)
        val labels = LinkedHashMap<Section, String>()
        val metaBySection = LinkedHashMap<Section, List<PageMeta>>()
        val metaById = LinkedHashMap<Section, Map<String, PageMeta>>()

        for ((section, pages1) in structure) {
            labels[section] = requireString(
                "reference.section.${section.id}.label",
                strings,
                "section '${section.id}' label for pack '$loc'",
            )

            val metas = ArrayList<PageMeta>(pages1.size)
            val byId = LinkedHashMap<String, PageMeta>(pages1.size)
            for ((id, file, number, level) in pages1) {
                val title = requireString(
                    "reference.page.$id.title",
                    strings,
                    "page '$id' title for pack '$loc'",
                )
                val meta = PageMeta(
                    id = id,
                    title = title,
                    file = file,
                    number = number,
                    level = level,
                )
                metas += meta
                byId[id] = meta
            }
            metaBySection[section] = metas
            metaById[section] = byId
        }

        for (section in Section.entries) {
            check(metaBySection.containsKey(section)) {
                "reference/structure.json: required section '${section.id}' is missing"
            }
            check(metaBySection.getValue(section).isNotEmpty()) {
                "reference/structure.json: section '${section.id}' has no pages"
            }
        }

        return LoadedLocale(
            locale = loc,
            source = src,
            browserTitle = browserTitle,
            sectionLabels = labels,
            metaBySection = metaBySection,
            metaById = metaById,
        )
    }

    /** Read and cache one page's markdown body on demand. */
    private fun loadPage(loaded: LoadedLocale, meta: PageMeta): Page {
        loaded.bodyCache[meta.id]?.let { return it }
        val markupPath = "reference/${loaded.locale}/${meta.file}"
        val markup = pageBody(loaded.source, loaded.locale, meta.file)
            ?: error("Missing reference page: $markupPath")
        val page = Page(
            id = meta.id,
            title = meta.title,
            markup = markup.trimEnd() + "\n",
            file = meta.file,
            number = meta.number,
            level = meta.level,
        )
        loaded.bodyCache[meta.id] = page
        return page
    }

    /**
     * A page body: the open pack's copy when [loc]'s pack is open, else the
     * authoring/dev [ReferenceAssetSource]. The shared taxonomy manifest always
     * comes from [structure], never the pack.
     *
     * A page the locale has not translated yet reads the [DEFAULT_LOCALE] body,
     * as `LanguagePacker.resolvePages` already does inside a pack.
     */
    private fun pageBody(src: ReferenceAssetSource, loc: String, file: String): String? {
        if (LanguagePack.isOpen(loc)) {
            LanguagePack.readText(file)?.let { return it }
        }
        return src.readText("reference/$loc/$file")
            ?: src.readText("reference/$DEFAULT_LOCALE/$file")
    }

    /** Read a `reference.*` bundle key, failing when it is absent (no English fallthrough). */
    private fun requireString(key: String, locale: Locale, what: String): String {
        val value = L10n.get(key, locale)
        check(value != key) { "Missing reference string '$key' ($what)" }
        return value
    }

    /** Read a `reference.*` bundle key, empty when absent (chrome has its own fallback). */
    private fun optionalString(key: String, locale: Locale): String =
        L10n.get(key, locale).takeIf { it != key }.orEmpty()

    /**
     * Parse the single locale-invariant taxonomy manifest. Cached until
     * [configure] resets the asset source.
     */
    private fun structure(src: ReferenceAssetSource): List<StructureSection> {
        structureCache?.let { return it }
        val path = "reference/structure.json"
        val text = src.readText(path) ?: error("Missing reference structure: $path")
        val root: JsonValue = JsonReader().parse(text)
        val sectionsJson = root.get("sections")
            ?: error("$path: missing 'sections'")

        val result = ArrayList<StructureSection>(sectionsJson.size)
        for (sectionJson in sectionsJson) {
            val sectionId = sectionJson.getString("id")
                ?: error("$path: section missing id")
            val section = Section.entries.firstOrNull { it.id == sectionId }
                ?: error("$path: unknown section id '$sectionId'")
            val pagesJson = sectionJson.get("pages")
                ?: error("$path: section '$sectionId' missing pages")

            val pages = ArrayList<StructurePage>(pagesJson.size)
            for (pageJson in pagesJson) {
                val id = pageJson.getString("id")
                    ?: error("$path: page missing id in section '$sectionId'")
                val file = pageJson.getString("file")
                    ?: error("$path: page '$id' missing file")
                val number = pageJson.getString("number", null)?.takeIf { it.isNotBlank() }
                val level = if (pageJson.has("level")) {
                    pageJson.getInt("level").coerceAtLeast(0)
                } else {
                    null
                }
                pages += StructurePage(
                    id = id,
                    file = file.replace('\\', '/'),
                    number = number,
                    level = level,
                )
            }
            result += StructureSection(section, pages)
        }
        structureCache = result
        return result
    }

    /**
     * Bundle locale that owns the reference strings for a resolved pack [tag].
     *
     * The reference reads as one language, so its strings follow the **pack**,
     * not the ambient UI locale. This matters when a pack falls back to
     * `en-US`: the chrome stays translated while the Reference (title, tabs,
     * pages and bodies) stays in the pack’s language.
     */
    fun stringsLocale(tag: String): Locale = AppLocale.fromTag(tag).javaLocale

    private data class StructurePage(
        val id: String,
        val file: String,
        val number: String?,
        val level: Int?,
    )

    private data class StructureSection(
        val section: Section,
        val pages: List<StructurePage>,
    )

    private data class PageMeta(
        val id: String,
        val title: String,
        val file: String,
        val number: String?,
        val level: Int?,
    )

    private class LoadedLocale(
        val locale: String,
        val source: ReferenceAssetSource,
        val browserTitle: String,
        val sectionLabels: Map<Section, String>,
        val metaBySection: Map<Section, List<PageMeta>>,
        val metaById: Map<Section, Map<String, PageMeta>>,
        val bodyCache: MutableMap<String, Page> = HashMap(),
    )
}
