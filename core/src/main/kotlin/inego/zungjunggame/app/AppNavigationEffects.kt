package inego.zungjunggame.app

import com.badlogic.gdx.Gdx
import inego.zungjunggame.persist.ReferenceNavState
import inego.zungjunggame.render.reference.ReferenceCatalog

/**
 * Reference browser open / deep-link / back / dismiss.
 *
 * Owns the reference surface's lifetime: the browser is a stack layer, so
 * opening pushes the [inego.zungjunggame.ui.stack.Route.Reference] route through
 * [ScreenTransitions]. In-session navigation history and section bookmarks live on
 * this effect (process-lifetime, not persisted), while the active [ReferenceNavState]
 * is stored in the route UI store via [writeNavigation] / [readNavigation]: the store is
 * the one owner of where the browser is, and nothing is queued on the widget. The
 * Effect never touches another domain's surface.
 */
internal class AppNavigationEffects(
    private val transitions: ScreenTransitions,
    private val writeNavigation: (ReferenceNavState) -> Unit,
    private val readNavigation: () -> ReferenceNavState? = { null },
) : NavigationEffects {

    private val history = ArrayDeque<ReferenceNavState>()
    private val sectionBookmarks = ReferenceSectionBookmarks()

    fun canGoBack(): Boolean = history.isNotEmpty()

    override fun openReference(pageId: String?) {
        if (pageId == null) {
            transitions.openReference()
            return
        }
        val base = readNavigation() ?: defaultNavigation()
        val targetNav = when (val resolved = ReferenceCatalog.resolveHref(pageId)) {
            is ReferenceCatalog.LinkTarget.Page -> {
                if (resolved.section == base.section && resolved.pageId == base.pageId) {
                    transitions.openReference()
                    return
                }
                val sameSection = resolved.section == base.section
                base.copy(
                    section = resolved.section,
                    pageId = resolved.pageId,
                    tocScrollY = if (sameSection) base.tocScrollY else 0f,
                    contentScrollY = 0f,
                )
            }
            is ReferenceCatalog.LinkTarget.SectionTarget -> {
                if (resolved.section == base.section) {
                    transitions.openReference()
                    return
                }
                resolveSectionTarget(resolved.section, base)
            }
            is ReferenceCatalog.LinkTarget.External -> null
            null -> {
                val section = ReferenceCatalog.Section.entries.firstOrNull { it.id == pageId }
                if (section != null) {
                    if (section == base.section) {
                        transitions.openReference()
                        return
                    }
                    resolveSectionTarget(section, base)
                } else {
                    null
                }
            }
        } ?: return

        recordTransition(targetNav)
        writeNavigation(targetNav)
        transitions.openReference()
    }

    override fun openReferenceSection(sectionId: String) {
        val section = ReferenceCatalog.Section.entries.firstOrNull { it.id == sectionId } ?: return
        val base = readNavigation() ?: defaultNavigation()
        if (section == base.section) {
            transitions.openReference()
            return
        }
        val targetNav = resolveSectionTarget(section, base)
        recordTransition(targetNav)
        writeNavigation(targetNav)
        transitions.openReference()
    }

    private fun resolveSectionTarget(
        targetSection: ReferenceCatalog.Section,
        base: ReferenceNavState,
    ): ReferenceNavState {
        val bookmark = sectionBookmarks.restore(targetSection) { id ->
            ReferenceCatalog.page(targetSection, id) != null
        }
        return if (bookmark != null) {
            base.copy(
                section = targetSection,
                pageId = bookmark.pageId,
                tocScrollY = bookmark.tocScrollY,
                contentScrollY = bookmark.contentScrollY,
            )
        } else {
            base.copy(
                section = targetSection,
                pageId = ReferenceCatalog.defaultPageId(targetSection),
                tocScrollY = 0f,
                contentScrollY = 0f,
            )
        }
    }

    override fun openReferenceExternal(url: String) {
        runCatching { Gdx.net.openURI(url) }
    }

    override fun referenceHistoryBack() {
        val prev = history.removeLastOrNull() ?: return
        val current = readNavigation()
        if (current != null && current.section != prev.section) {
            sectionBookmarks.snapshot(
                section = current.section,
                pageId = current.pageId,
                tocScrollY = current.tocScrollY,
                contentScrollY = current.contentScrollY,
            )
        }
        val base = current ?: defaultNavigation()
        val targetNav = base.copy(
            section = prev.section,
            pageId = prev.pageId,
            tocScrollY = prev.tocScrollY,
            contentScrollY = prev.contentScrollY,
        )
        writeNavigation(targetNav)
    }

    override fun dismissReference() {
        transitions.dismissReference()
    }

    private fun recordTransition(targetNav: ReferenceNavState) {
        val current = readNavigation() ?: return
        if (current.section == targetNav.section && current.pageId == targetNav.pageId) return
        history.addLast(current)
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
        }
        if (current.section != targetNav.section) {
            sectionBookmarks.snapshot(
                section = current.section,
                pageId = current.pageId,
                tocScrollY = current.tocScrollY,
                contentScrollY = current.contentScrollY,
            )
        }
    }

    companion object {
        private const val MAX_HISTORY = 64

        private fun defaultNavigation(): ReferenceNavState = ReferenceNavState(
            section = ReferenceCatalog.Section.Rules,
            pageId = ReferenceCatalog.defaultPageId(ReferenceCatalog.Section.Rules),
            tocScrollY = 0f,
            contentScrollY = 0f,
            drawerOpen = false,
        )
    }
}

/** Last place inside one Reference section (page + both scroll offsets). */
internal data class SectionBookmark(
    val pageId: String,
    val tocScrollY: Float,
    val contentScrollY: Float,
)

/**
 * In-session last position per Rules / Scoring tab.
 * First visit has no bookmark (caller opens the section default).
 */
internal class ReferenceSectionBookmarks {
    private val last = mutableMapOf<ReferenceCatalog.Section, SectionBookmark>()

    fun snapshot(
        section: ReferenceCatalog.Section,
        pageId: String,
        tocScrollY: Float,
        contentScrollY: Float,
    ) {
        if (pageId.isEmpty()) return
        last[section] = SectionBookmark(
            pageId = pageId,
            tocScrollY = tocScrollY.coerceAtLeast(0f),
            contentScrollY = contentScrollY.coerceAtLeast(0f),
        )
    }

    fun restore(
        section: ReferenceCatalog.Section,
        pageExists: (String) -> Boolean,
    ): SectionBookmark? {
        val bookmark = last[section] ?: return null
        if (!pageExists(bookmark.pageId)) return null
        return bookmark
    }
}
