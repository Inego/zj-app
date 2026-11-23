package inego.zungjunggame.features.reference

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.persist.ReferenceNavState
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.platform.UiCursor
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.render.richtext.RichTextView
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.SegmentedTabs
import inego.zungjunggame.render.ui.TocBrowserColumn
import inego.zungjunggame.ui.GestureSurface
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * What a completed press on the browser resolved to.
 *
 * The overlay is a pure view: it never navigates. It reports the control the
 * gesture landed on, and the owning component turns that into a
 * `ComponentAction → Intent`. A drag resolves no control; the offsets it moved to
 * are read back through [ReferenceBrowserOverlay.navigation] and emitted beside
 * the verdict.
 */
data class ReferenceVerdict(
    /** The × exit control was activated. */
    val close: Boolean = false,
    /** The ← history control was activated. */
    val historyBack: Boolean = false,
    /** Selected TOC row, when the gesture resolved one. */
    val tocPageId: String? = null,
    /** Selected article hyperlink (raw href), when the gesture resolved one. */
    val linkHref: String? = null,
    /** Selected section tab, when the gesture resolved one. */
    val section: ReferenceCatalog.Section? = null,
)

/**
 * Full-screen **Zung Jung reference** mode.
 *
 * This is not a floating window over the table: when visible it **replaces**
 * the play table entirely ([inego.zungjunggame.Main] skips table draw while
 * this is open). The backdrop fills the viewport edge-to-edge; interactive
 * chrome lays out inside [SafeArea] so cutouts (e.g. front camera in
 * landscape) do not cover the TOC pull tab or top-right controls.
 *
 * **Side-by-side** (desktop landscape and other non-compact viewports):
 * ```
 * ┌ title                                   [← hist] [× exit] ─┐
 * │ ┌ Rules | Scoring ┐ ┌ content (RichTextView) ─────────────┐ │
 * │ │ TOC list        │ │  markdown links → push history      │ │
 * │ │ (scrollable)    │ │  ← / Backspace pops (scrolls kept)  │ │
 * │ └─────────────────┘ └─────────────────────────────────────┘ │
 * └─────────────────────────────────────────────────────────────┘
 * ```
 *
 * **Compact / horizontal-saving** (always on Android; desktop when
 * `screenH > screenW`): content uses the full body width. The TOC is a
 * left drawer overlay that does not reflow content. Open/close uses a short
 * slide; when closed the drawer (and its edge) are gone, with only the pull
 * tab remaining on the left. The tab is semi-transparent and can be dragged
 * vertically along the edge so it does not permanently cover article text.
 *
 * Left column holds section tabs above a scrollable TOC with a shared
 * [inego.zungjunggame.render.ui.VerticalScrollbar]. The article pane shows the
 * selected page via [RichTextView] (same scrollbar chrome).
 *
 * **History:** TOC taps, section changes, and in-article markdown links push a
 * frame (section, page, TOC scroll, content scroll). The history **←** control
 * (and **Backspace**) pops that stack and restores both scroll positions.
 * The top-right **×** exits the browser only ([ReferenceVerdict.close]);
 * Esc does the same via the back/dismiss route.
 *
 * **Pure view:** the overlay never navigates. A completed press reports the
 * control it resolved ([ReferenceVerdict]); the owning component emits the
 * matching `ComponentAction` → `Intent`, and the effect performs the navigation.
 *
 * **Section tabs:** Rules / Scoring each keep their last page and both scroll
 * offsets in-session. Clicking back to a tab restores that place instead of
 * jumping to the section default. First visit to a section still opens its
 * default page. Deep links and in-article links still land on the named page.
 *
 * Navigation is sticky across app restarts because the owning component stores
 * it as the `Reference` route's UI state in the route UI store: current section,
 * page, both scrolls, and the compact TOC drawer's open state (not the in-session
 * back stack or the per-section bookmarks). This overlay is a controlled view and
 * never touches persistence: [render] is handed the stored navigation every frame,
 * and a gesture's moved offsets are read back through [navigation] and emitted at the
 * moment they land.
 */
class ReferenceBrowserOverlay(
    private val contentView: RichTextView = RichTextView(),
) : Disposable, GestureSurface<ReferenceVerdict> {
    private val glyphLayout = GlyphLayout()

    private val tabs = SegmentedTabs()
    /** Shared TOC rail / compact drawer; owns the row list and its input. */
    private val tocColumn = TocBrowserColumn()
    /** Exit reference mode → return to the play table. */
    private val exitButton = CloseButton(CloseButton.Style.Close)
    /** Pop in-session navigation history (dimmed when empty). */
    private val historyBackButton = CloseButton(CloseButton.Style.Back)

    private var section: ReferenceCatalog.Section = ReferenceCatalog.Section.Rules
    /**
     * Current page id. Empty until the first [render] applies a default or the
     * owner's restore — must not touch [ReferenceCatalog] in field initializers, because
     * [Main] constructs this overlay before libGDX finishes [AndroidApplication]
     * setup (Gdx.files / assets are not ready yet).
     */
    private var pageId: String = ""

    /** True when navigation history can restore a previous page. */
    var canGoBack: Boolean = false
        private set

    /**
     * The browser's place, read off the widgets it last painted. Volatile session
     * bookkeeping (history, bookmarks) is built from this, and a gesture that
     * moved either pane reports it through the bridge at the moment it lands.
     */
    fun navigation(): ReferenceNavState = appliedNav()

    /**
     * False until the first [render] applied the owner's navigation. Before that
     * the widgets still hold their construction defaults, so [navigation] reads a
     * not-yet-loaded place and must not be written back to the store: doing so
     * would clobber a restored navigation with the default page on the first
     * frame (motion runs before draw).
     */
    fun hasAppliedNavigation(): Boolean = hasNavigationSession

    /** The frame the last [render] applied, read off the widgets it painted. */
    private fun appliedNav(): ReferenceNavState = ReferenceNavState(
        section = section,
        pageId = pageId.ifEmpty { ReferenceCatalog.defaultPageId(section) },
        tocScrollY = tocColumn.list.scrollY,
        contentScrollY = contentView.scrollY,
        drawerOpen = tocColumn.drawerOpen,
    )

    /**
     * The article pane's offset after a consumed wheel. A non-null answer is what
     * lets a consumed wheel reach the bridge at all; the full navigation either
     * pane settled at is read back through [navigation].
     */
    override fun scrollOffset(): Float = contentView.scrollY

    override fun scrollCoasting(): Boolean = tocColumn.list.isCoasting || contentView.isCoasting

    /** Compact TOC drawer is still easing open or closed. */
    fun chromeAnimating(): Boolean = tocColumn.isDrawerAnimating()

    /**
     * Advance both panes' fling inertia; [deltaSec] comes from the host's frame.
     * A coast that moved either pane is read back through [navigation] by the
     * component after this returns.
     */
    fun advance(deltaSec: Float) {
        if (deltaSec <= 0f) return
        tocColumn.advance(deltaSec)
        contentView.advance(deltaSec)
    }

    /**
     * False until the first [render] applied a frame. Re-opening keeps the
     * in-memory session (history, bookmarks, loaded content) and applies the
     * owner's stored navigation through [render] again.
     */
    private var hasNavigationSession: Boolean = false

    /**
     * Catalog locale last used to fill TOC labels + article markup.
     * When the user changes language while the browser is closed, the next
     * [render] must rebind content even though section/page ids are stable
     * across packs.
     */
    private var loadedContentLocale: String? = null

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    /** Body rect (article area under the title band) from last layout. */
    private var bodyLeft = 0f
    private var bodyBottom = 0f
    private var bodyW = 0f
    private var bodyH = 0f

    /** Which region is receiving the active press/drag, if any. */
    private var dragTarget: DragTarget = DragTarget.None

    private enum class DragTarget {
        None,
        Exit,
        HistoryBack,
        Toc,
        Content,
        /** Press on section tabs — not a scroll region. */
        Chrome,
    }





    /** Full viewport — always contains the pointer while painted. */
    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /**
     * Wheel / trackpad. Routes to the TOC or the article based on pointer position.
     */
    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        // Column claims the rail / open drawer; everything else scrolls the article.
        tocColumn.onScroll(amountY, x, y)?.let { return true }
        if (contentView.contains(x, y) || containsPanel(x, y)) {
            contentView.onScroll(amountY)
        }
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        dragTarget = DragTarget.None
        tocColumn.cancelGesture()
        contentView.cancelGesture() // clear any in-flight content / scrollbar drag

        if (!containsPanel(x, y)) return false

        // The column owns the rail / drawer / pull tab / scrim and the list.
        val inColumn = tocColumn.hitsColumn(x, y)
        when {
            exitButton.contains(x, y) -> dragTarget = DragTarget.Exit
            canGoBack && historyBackButton.contains(x, y) ->
                dragTarget = DragTarget.HistoryBack
            // Tabs sit in the column's header band, above the list.
            inColumn && tabs.contains(x, y) -> dragTarget = DragTarget.Chrome
            inColumn -> {
                dragTarget = DragTarget.Toc
                tocColumn.onPointerDown(x, y)
            }
            contentView.contains(x, y) -> {
                dragTarget = DragTarget.Content
                contentView.onPointerDown(x, y)
            }
            else -> dragTarget = DragTarget.Chrome
        }
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ReferenceVerdict? {
        when (dragTarget) {
            DragTarget.Toc -> tocColumn.onPointerDrag(y)
            DragTarget.Content -> contentView.onPointerDrag(y)
            DragTarget.None,
            DragTarget.Chrome,
            DragTarget.Exit,
            DragTarget.HistoryBack,
            -> return null
        }
        // A drag resolves no control; the offset it moved to is read back through
        // [navigation] and emitted beside this verdict.
        return ReferenceVerdict()
    }

    /**
     * End gesture: resolve which control the press landed on — the × exit,
     * the ← history control, a TOC row, an article link, or a section tab.
     *
     * The overlay never navigates: it returns the selection and the component
     * emits the matching `ComponentAction`. A drag resolves no control.
     */
    override fun onPointerUp(x: Float, y: Float): ReferenceVerdict {
        val target = dragTarget
        dragTarget = DragTarget.None

        return when (target) {
            DragTarget.Exit -> {
                val hit =
                    !x.isNaN() &&
                        !y.isNaN() &&
                        exitButton.contains(x, y)
                ReferenceVerdict(close = hit)
            }
            DragTarget.HistoryBack -> {
                val hit =
                    !x.isNaN() &&
                        !y.isNaN() &&
                        historyBackButton.contains(x, y)
                ReferenceVerdict(historyBack = hit)
            }
            DragTarget.Toc -> {
                // The column resolves list taps, pull-tab taps, and the drawer
                // scrim internally; a row selection is a navigation verdict and
                // the drawer's new state is read back through [navigation]
                // beside it.
                val result = tocColumn.onPointerUp(x, y)
                ReferenceVerdict(tocPageId = result.selectedId)
            }
            DragTarget.Content -> {
                val result = contentView.onPointerUp()
                ReferenceVerdict(linkHref = result.linkHref)
            }
            DragTarget.Chrome -> {
                val tab =
                    if (!x.isNaN() && !y.isNaN()) tabs.hitIndex(x, y) else null
                ReferenceVerdict(
                    section = tab?.let { ReferenceCatalog.Section.entries[it] },
                )
            }
            DragTarget.None -> ReferenceVerdict()
        }
    }

    override fun cancelGesture() {
        dragTarget = DragTarget.None
        tocColumn.cancelGesture()
        contentView.cancelGesture()
    }



    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        nav: ReferenceNavState,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        canGoBack: Boolean = false,
    ) {
        this.canGoBack = canGoBack
        Fonts.ensureCreated()
        val px = whitePixel()

        applyNavigation(nav)

        // Backdrop goes edge-to-edge (under cutouts); layout uses the safe rect.
        batch.setColor(AppPalette.panel)
        batch.draw(px, 0f, 0f, screenW, screenH)
        batch.setColor(Color.WHITE)

        val safe = SafeArea.current()
        panelLeft = safe.left
        panelBottom = safe.bottom
        panelW = (screenW - safe.horizontal).coerceAtLeast(1f)
        panelH = (screenH - safe.vertical).coerceAtLeast(1f)

        val portrait = screenH > screenW

        val pad = if (portrait) max(Fonts.px(10f), panelW * 0.025f) else Fonts.px(18f)
        val chromeInset = CloseButton.cornerInset()
        // Rightmost: exit (×). Immediately left: history back (←).
        exitButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH, chromeInset)
        historyBackButton.layoutAt(
            left = exitButton.leftX - CloseButton.siblingGap() - exitButton.side,
            bottom = exitButton.bottomY,
            side = exitButton.side,
        )

        // Title band (centered in the width left of both chrome controls).
        val title = ReferenceCatalog.browserTitle().ifBlank {
            L10n.get("reference.fallback_title")
        }
        val titleFont = Fonts.labelActive
        glyphLayout.setText(titleFont, title)
        val titleH = max(titleFont.lineHeight, exitButton.side)
        val bandBottom = panelBottom + panelH - chromeInset - titleH
        val titleAreaLeft = panelLeft + pad
        val chromeW = exitButton.side + CloseButton.siblingGap() + historyBackButton.side
        val titleAreaW =
            (panelW - pad - chromeInset - chromeW - Fonts.px(8f)).coerceAtLeast(1f)
        titleFont.color = AppPalette.inkStrong
        titleFont.draw(
            batch,
            title,
            titleAreaLeft + (titleAreaW - glyphLayout.width).coerceAtLeast(0f) * 0.5f,
            bandBottom + titleH * 0.5f + glyphLayout.height * 0.5f,
        )
        titleFont.color = Color.WHITE

        historyBackButton.render(
            batch,
            px,
            pointerWorldX,
            pointerWorldY,
            enabled = canGoBack,
        )
        exitButton.render(batch, px, pointerWorldX, pointerWorldY)

        val bodyTop = bandBottom - Fonts.px(10f)
        this.bodyBottom = panelBottom + pad
        this.bodyH = max(Fonts.px(80f), bodyTop - bodyBottom)
        this.bodyLeft = panelLeft + pad
        this.bodyW = panelW - pad * 2f

        val tabH = max(Fonts.px(36f), Fonts.button.lineHeight + Fonts.px(14f))
        tocColumn.headerHeight = tabH + Fonts.px(8f)
        tocColumn.layout(
            panelLeft = panelLeft,
            panelW = panelW,
            bodyLeft = bodyLeft,
            bodyBottom = bodyBottom,
            bodyW = bodyW,
            bodyH = bodyH,
            screenW = screenW,
            screenH = screenH,
        )

        // Reading gutter between the TOC rail's divider and the article text.
        // Side-by-side only: in compact the content rect already carries the
        // body's screen-edge pad (there is no rail tucked beside the text).
        val articlePad = if (tocColumn.compactMode) 0f else Fonts.px(16f)

        // Article first: the compact drawer paints above it.
        contentView.render(
            batch,
            camera,
            tocColumn.contentLeft + articlePad,
            tocColumn.contentBottom,
            (tocColumn.contentW - articlePad).coerceAtLeast(Fonts.px(80f)),
            tocColumn.contentH,
            nav.contentScrollY,
            pointerWorldX,
            pointerWorldY,
        )
        tocColumn.render(
            batch,
            camera,
            px,
            nav.tocScrollY,
            pointerWorldX,
            pointerWorldY,
        ) { b, white ->
            tabs.layout(
                tocColumn.headerLeft,
                tocColumn.headerBottom + tocColumn.headerH - tabH,
                tocColumn.headerW,
                tabH,
            )
            tabs.render(b, white, pointerWorldX, pointerWorldY)
        }

        // After layout + content hit geometry is current for this frame.
        updateDesktopCursor(pointerWorldX, pointerWorldY)
    }

    /**
     * Desktop only: hand cursor over section tabs, selectable TOC rows, and
     * article hyperlinks. Touch backends no-op via [UiCursor].
     */
    private fun updateDesktopCursor(pointerWorldX: Float, pointerWorldY: Float) {
        if (!InputProfile.detect().usesHover) return
        if (pointerWorldX.isNaN() || pointerWorldY.isNaN()) {
            UiCursor.arrow()
            return
        }
        val overTabs =
            tocColumn.hitsColumn(pointerWorldX, pointerWorldY) &&
                tabs.contains(pointerWorldX, pointerWorldY)
        val overToc = tocColumn.list.itemAt(pointerWorldX, pointerWorldY) != null
        val overLink = contentView.linkAt(pointerWorldX, pointerWorldY) != null
        if (overTabs || overToc || overLink) {
            UiCursor.hand()
        } else {
            UiCursor.arrow()
        }
    }

    /**
     * Apply the frame's navigation to the loaded content. The owner's value is
     * authoritative: a changed section or page rebuilds the TOC + article, a
     * locale change rebinds them in place, and the drawer follows the handed
     * value (a restore snaps; a player toggle already animated in the column).
     */
    private fun applyNavigation(nav: ReferenceNavState) {
        val first = !hasNavigationSession
        if (first) {
            hasNavigationSession = true
        }
        val sectionChanged = nav.section != section
        val validPage = ReferenceCatalog.hasPage(nav.section, nav.pageId)
        val pageId = if (validPage) nav.pageId else ReferenceCatalog.defaultPageId(nav.section)
        val pageChanged = pageId != this.pageId
        val localeChanged = loadedContentLocale != ReferenceCatalog.currentLocale()
        if (first || sectionChanged || pageChanged || localeChanged) {
            section = nav.section
            this.pageId = pageId
            loadContent(selectedId = pageId)
        }
        if (tocColumn.drawerOpen != nav.drawerOpen) {
            tocColumn.setDrawerOpen(nav.drawerOpen, snap = first)
        }
    }

    /** Rebuild the active section's TOC + article body at [selectedId]. */
    private fun loadContent(selectedId: String) {
        tabs.setLabels(ReferenceCatalog.sectionLabels, selectedIndex = section.ordinal)
        tocColumn.setItems(
            items = ReferenceCatalog.listItems(section),
            selectedId = selectedId,
            resetScroll = true,
        )
        val page = ReferenceCatalog.page(section, selectedId)
        contentView.setMarkup(page?.markup ?: "")
        loadedContentLocale = ReferenceCatalog.currentLocale()
    }

    private fun whitePixel(): Texture = WhitePixel.get()

    override fun dispose() {
        tocColumn.dispose()
        contentView.dispose()
    }
}
