package inego.zungjunggame.render.reference

import inego.zungjunggame.render.richtext.Block
import inego.zungjunggame.render.richtext.Inline
import inego.zungjunggame.render.richtext.RichColor
import inego.zungjunggame.render.richtext.RichDoc

object CatalogLinkPolicy {
    /** Inline tutorial href that opens the Settings panel, not a catalog page. */
    const val SETTINGS_HREF: String = "settings"

    fun isInternalPage(href: String): Boolean =
        when (ReferenceCatalog.resolveHref(href)) {
            is ReferenceCatalog.LinkTarget.Page,
            is ReferenceCatalog.LinkTarget.SectionTarget -> true
            else -> false
        }

    /** True for in-app actions tutorial copy may link (today: Settings). */
    fun isAppHref(href: String): Boolean =
        href.equals(SETTINGS_HREF, ignoreCase = true)

    /**
     * Drop linkHref (and default link color) on spans that are not an
     * internal catalog page or a known in-app href. External / unknown
     * become ordinary body text.
     */
    fun stripNonCatalogLinks(
        doc: RichDoc,
        onReject: (href: String, reason: RejectReason) -> Unit = { _, _ -> },
    ): RichDoc {
        fun transformInline(inline: Inline): Inline {
            if (inline !is Inline.Text || inline.linkHref == null) return inline
            val href = inline.linkHref
            if (href.isBlank()) {
                onReject(href, RejectReason.Empty)
                val newStyle = if (inline.style.color == RichColor.Named("link")) {
                    inline.style.copy(color = null)
                } else {
                    inline.style
                }
                return inline.copy(style = newStyle, linkHref = null)
            }
            if (isAppHref(href)) return inline
            return when (ReferenceCatalog.resolveHref(href)) {
                is ReferenceCatalog.LinkTarget.Page,
                is ReferenceCatalog.LinkTarget.SectionTarget -> inline
                is ReferenceCatalog.LinkTarget.External -> {
                    onReject(href, RejectReason.External)
                    val newStyle = if (inline.style.color == RichColor.Named("link")) {
                        inline.style.copy(color = null)
                    } else {
                        inline.style
                    }
                    inline.copy(style = newStyle, linkHref = null)
                }
                null -> {
                    onReject(href, RejectReason.Unresolved)
                    val newStyle = if (inline.style.color == RichColor.Named("link")) {
                        inline.style.copy(color = null)
                    } else {
                        inline.style
                    }
                    inline.copy(style = newStyle, linkHref = null)
                }
            }
        }

        fun transformBlock(block: Block): Block = when (block) {
            is Block.Heading -> block.copy(spans = block.spans.map(::transformInline))
            is Block.Paragraph -> block.copy(spans = block.spans.map(::transformInline))
            is Block.BulletList -> block.copy(items = block.items.map { it.map(::transformInline) })
            is Block.NumberedList -> block.copy(items = block.items.map { it.map(::transformInline) })
            is Block.Table -> block.copy(
                header = block.header.map { it.map(::transformInline) },
                rows = block.rows.map { row -> row.map { cell -> cell.map(::transformInline) } },
            )
        }

        return RichDoc(doc.blocks.map(::transformBlock))
    }

    /**
     * Consume-link test seam: Page → (section, pageId); External / unknown → null.
     * [TutorialHost.openCatalogHref] resolves via [ReferenceCatalog.resolveHref] directly
     * to classify and log specific rejection reasons.
     */
    fun resolveTutorialHref(href: String): Pair<ReferenceCatalog.Section, String>? =
        when (val target = ReferenceCatalog.resolveHref(href)) {
            is ReferenceCatalog.LinkTarget.Page -> target.section to target.pageId
            is ReferenceCatalog.LinkTarget.SectionTarget -> target.section to ""
            else -> null
        }

    enum class RejectReason { External, Unresolved, Empty }
}
