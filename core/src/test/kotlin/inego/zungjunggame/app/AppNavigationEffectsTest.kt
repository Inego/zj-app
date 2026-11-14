package inego.zungjunggame.app

import inego.zungjunggame.persist.ReferenceNavState
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.StackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppNavigationEffectsTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private class TestEnv {
        val router = Router(
            Route.Screen(RouteIds.ROOT_MENU),
            { StubComponent(ComponentId(it.key.value)) },
            { RouteSpecs.specFor(it) },
        )
        val transitions = ScreenTransitions(RouteController(router))
        var currentNav: ReferenceNavState? = null
        var writeCount: Int = 0

        val effects = AppNavigationEffects(
            transitions = transitions,
            writeNavigation = {
                writeCount++
                currentNav = it
            },
            readNavigation = { currentNav },
        )

        fun hasReferenceRoute(): Boolean =
            router.stack.contains(Route.Reference(StackState.EMPTY).key)
    }

    @Test
    fun openReferenceNullRaisesRouteWithoutOverwritingStore() {
        val env = TestEnv()
        assertFalse(env.hasReferenceRoute())

        env.effects.openReference(null)

        assertTrue(env.hasReferenceRoute())
        assertEquals(null, env.currentNav)
        assertFalse(env.effects.canGoBack())
    }

    @Test
    fun openReferenceResolvesPageAndWritesNavState() {
        val env = TestEnv()

        env.effects.openReference(ReferenceCatalog.ABOUT_PAGE_ID)

        assertTrue(env.hasReferenceRoute())
        val nav = env.currentNav
        assertNotNull(nav)
        assertEquals(ReferenceCatalog.Section.Rules, nav.section)
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, nav.pageId)
        assertFalse(env.effects.canGoBack())
    }

    @Test
    fun subsequentNavigationPushesHistoryAndAllowsBack() {
        val env = TestEnv()

        // First page: About (in Rules)
        env.effects.openReference(ReferenceCatalog.ABOUT_PAGE_ID)
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, env.currentNav?.pageId)
        assertFalse(env.effects.canGoBack())

        // Second page: pattern 2.1 (in Scoring)
        env.effects.openReference("2.1")
        assertEquals(ReferenceCatalog.Section.Scoring, env.currentNav?.section)
        assertEquals("pattern-2.1", env.currentNav?.pageId)
        assertTrue(env.effects.canGoBack())

        // Pop back to About
        env.effects.referenceHistoryBack()
        assertEquals(ReferenceCatalog.Section.Rules, env.currentNav?.section)
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, env.currentNav?.pageId)
        assertFalse(env.effects.canGoBack())
    }

    @Test
    fun openReferenceSectionRestoresBookmark() {
        val env = TestEnv()

        // Open Scoring section, then navigate to pattern 2.1
        env.effects.openReferenceSection("scoring")
        assertEquals(ReferenceCatalog.Section.Scoring, env.currentNav?.section)

        env.effects.openReference("2.1")
        assertEquals("pattern-2.1", env.currentNav?.pageId)

        // Switch to Rules section
        env.effects.openReferenceSection("rules")
        assertEquals(ReferenceCatalog.Section.Rules, env.currentNav?.section)

        // Switch back to Scoring section: bookmark should be restored
        env.effects.openReferenceSection("scoring")
        assertEquals(ReferenceCatalog.Section.Scoring, env.currentNav?.section)
        assertEquals("pattern-2.1", env.currentNav?.pageId)
    }

    @Test
    fun sameSectionPageNavigationPreservesTocScrollAndDrawer() {
        val env = TestEnv()
        val defaultRulesPage = ReferenceCatalog.defaultPageId(ReferenceCatalog.Section.Rules)

        // Seed store with an active Rules page, scrolled TOC, scrolled content, and open drawer
        env.currentNav = ReferenceNavState(
            section = ReferenceCatalog.Section.Rules,
            pageId = defaultRulesPage,
            tocScrollY = 120f,
            contentScrollY = 350f,
            drawerOpen = true,
        )

        // Navigate to About (same section: Rules)
        env.effects.openReference(ReferenceCatalog.ABOUT_PAGE_ID)

        val nav = env.currentNav
        assertNotNull(nav)
        assertEquals(ReferenceCatalog.Section.Rules, nav.section)
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, nav.pageId)
        assertEquals(120f, nav.tocScrollY, "Same-section page switch must preserve TOC scroll")
        assertEquals(0f, nav.contentScrollY, "Content scroll must reset to top")
        assertTrue(nav.drawerOpen, "Same-section page switch must preserve drawer state")
    }

    @Test
    fun sectionSwitchPreservesDrawerOpen() {
        val env = TestEnv()
        env.currentNav = ReferenceNavState(
            section = ReferenceCatalog.Section.Rules,
            pageId = ReferenceCatalog.defaultPageId(ReferenceCatalog.Section.Rules),
            tocScrollY = 80f,
            contentScrollY = 200f,
            drawerOpen = true,
        )

        // Switch to Scoring section
        env.effects.openReferenceSection("scoring")

        val nav = env.currentNav
        assertNotNull(nav)
        assertEquals(ReferenceCatalog.Section.Scoring, nav.section)
        assertEquals(0f, nav.tocScrollY, "New section visit resets TOC scroll to top")
        assertEquals(0f, nav.contentScrollY, "New section visit resets content scroll to top")
        assertTrue(nav.drawerOpen, "Section switch must preserve drawer state")
    }

    @Test
    fun referenceHistoryBackPreservesCurrentDrawerState() {
        val env = TestEnv()
        val defaultRulesPage = ReferenceCatalog.defaultPageId(ReferenceCatalog.Section.Rules)

        // Start on About with drawer closed
        env.currentNav = ReferenceNavState(
            section = ReferenceCatalog.Section.Rules,
            pageId = ReferenceCatalog.ABOUT_PAGE_ID,
            tocScrollY = 50f,
            contentScrollY = 100f,
            drawerOpen = false,
        )

        // Navigate to another page in Rules
        env.effects.openReference(defaultRulesPage)
        assertEquals(defaultRulesPage, env.currentNav?.pageId)
        assertFalse(env.currentNav!!.drawerOpen)

        // Player opens the drawer on the new page
        env.currentNav = env.currentNav!!.copy(drawerOpen = true)

        // Navigate to Scoring pattern 2.1
        env.effects.openReference("2.1")
        assertEquals("pattern-2.1", env.currentNav?.pageId)
        assertTrue(env.currentNav!!.drawerOpen)

        // Player closes the drawer while on 2.1
        env.currentNav = env.currentNav!!.copy(drawerOpen = false)

        // Pop back to defaultRulesPage: section/page/scrolls restored, drawer stays closed!
        env.effects.referenceHistoryBack()
        assertEquals(ReferenceCatalog.Section.Rules, env.currentNav?.section)
        assertEquals(defaultRulesPage, env.currentNav?.pageId)
        assertFalse(env.currentNav!!.drawerOpen, "Back must preserve the current frame's closed drawer")

        // Player re-opens drawer
        env.currentNav = env.currentNav!!.copy(drawerOpen = true)

        // Pop back to About: place/scrolls restored, drawer stays open!
        env.effects.referenceHistoryBack()
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, env.currentNav?.pageId)
        assertEquals(50f, env.currentNav?.tocScrollY)
        assertEquals(100f, env.currentNav?.contentScrollY)
        assertTrue(env.currentNav!!.drawerOpen, "Back must preserve the current frame's open drawer")
    }

    @Test
    fun samePlaceOpenReferenceDoesNotWriteStoreNorResetScroll() {
        val env = TestEnv()
        val defaultRulesPage = ReferenceCatalog.defaultPageId(ReferenceCatalog.Section.Rules)

        env.currentNav = ReferenceNavState(
            section = ReferenceCatalog.Section.Rules,
            pageId = defaultRulesPage,
            tocScrollY = 60f,
            contentScrollY = 400f,
            drawerOpen = true,
        )
        val initialWrites = env.writeCount

        // Re-tapping the exact current page
        env.effects.openReference(defaultRulesPage)

        assertEquals(initialWrites, env.writeCount, "Same-page open must not write store")
        assertEquals(400f, env.currentNav?.contentScrollY, "Same-page open must not reset content scroll")
        assertTrue(env.hasReferenceRoute(), "Route must still be raised")
        assertFalse(env.effects.canGoBack(), "No history frame must be pushed")

        // Same-section tab re-tap
        env.effects.openReferenceSection("rules")

        assertEquals(initialWrites, env.writeCount, "Same-section tab re-tap must not write store")
        assertEquals(400f, env.currentNav?.contentScrollY, "Content scroll remains untouched")
        assertTrue(env.hasReferenceRoute())
        assertFalse(env.effects.canGoBack())
    }
}
