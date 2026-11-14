package inego.zungjunggame.persist

import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.ui.RouteStateCodec

/**
 * Last Reference browser navigation: section, page, the two scroll offsets, and
 * the compact TOC drawer's open state.
 *
 * The in-session back stack and the per-section bookmarks are volatile by design
 * and are not part of this value. The drawer is the player's display state, so it
 * rides this route's component state with the rest of the navigation.
 */
data class ReferenceNavState(
    val section: ReferenceCatalog.Section,
    val pageId: String,
    val tocScrollY: Float = 0f,
    val contentScrollY: Float = 0f,
    val drawerOpen: Boolean = false,
)

/**
 * Text codec for [ReferenceNavState], stored as the `Reference` route's
 * route UI state.
 *
 * v1: a version field, the section name, the page id, then the two scroll
 * offsets. v2 appends the compact TOC drawer's open state. A v1 payload still
 * decodes — the drawer defaults to closed — so a stored navigation is not thrown
 * away by the upgrade. Unknown versions, unknown sections, blank page ids, and
 * malformed text all decode to null, so a route entry with unreadable state simply
 * opens the browser at its default page.
 */
object ReferenceNavCodec : RouteStateCodec<ReferenceNavState> {
    override val type = "referenceNav"

    const val VERSION: Int = 2

    /** Oldest payload layout this codec still reads; below this it is unknown. */
    private const val MIN_READABLE_VERSION = 1

    override fun encode(value: ReferenceNavState): String = RecordWriter()
        .int(VERSION)
        .field(value.section.name)
        .field(value.pageId)
        .float(value.tocScrollY)
        .float(value.contentScrollY)
        .boolean(value.drawerOpen)
        .build()
        .toString(Charsets.UTF_8)

    override fun decode(text: String): ReferenceNavState? = try {
        val reader = RecordReader(text.toByteArray(Charsets.UTF_8))
        val version = reader.int()
        if (version !in MIN_READABLE_VERSION..VERSION) return null
        val sectionName = reader.field()
        val pageId = reader.field()
        val tocScrollY = reader.float()
        val contentScrollY = reader.float()
        val drawerOpen = version >= 2 && reader.boolean()
        if (!reader.atEnd()) return null
        val section = ReferenceCatalog.Section.entries.firstOrNull { it.name == sectionName }
        when {
            section == null || pageId.isBlank() -> null
            else -> ReferenceNavState(
                section = section,
                pageId = pageId,
                tocScrollY = tocScrollY.coerceAtLeast(0f),
                contentScrollY = contentScrollY.coerceAtLeast(0f),
                drawerOpen = drawerOpen,
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}
