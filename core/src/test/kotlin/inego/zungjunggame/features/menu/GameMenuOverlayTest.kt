package inego.zungjunggame.features.menu

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import java.io.File
import java.util.*
import kotlin.test.*

class GameMenuOverlayTest {
    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        LocaleController.resetForTests()
        val root = findAssetsRoot()
        assertNotNull(root, "assets/ with i18n/ not found from cwd")
        L10n.bootstrap(Locale.US, FileHandle(File(root, "i18n/ui")))
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
        LocaleController.resetForTests()
    }

    @Test
    fun rootMenuEntersModesAndQuits() {
        val actions = actions(MenuContext.Root, showContinue = true)
        assertEquals(
            listOf(
                GameMenuOverlay.Action.Players,
                GameMenuOverlay.Action.PlayAgainstBots,
                GameMenuOverlay.Action.Tutorial,
                GameMenuOverlay.Action.Reference,
                GameMenuOverlay.Action.OpenLanguage,
                GameMenuOverlay.Action.Settings,
                GameMenuOverlay.Action.About,
                GameMenuOverlay.Action.Exit,
            ),
            actions,
        )
        assertFalse(GameMenuOverlay.Action.NewGame in actions)
        assertFalse(GameMenuOverlay.Action.Continue in actions)
        assertFalse(GameMenuOverlay.Action.ExitToMainMenu in actions)
        assertFalse(GameMenuOverlay.Action.ScoreTable in actions)
        assertFalse(GameMenuOverlay.Action.LogOut in actions)
        assertFalse(GameMenuOverlay.Action.LogIn in actions)
    }

    @Test
    fun localGameMenuCannotOpenTutorialOrOnline() {
        val actions = actions(
            MenuContext.LocalGame,
            showScoreTable = true,
            showContinue = true,
        )
        assertEquals(
            listOf(
                GameMenuOverlay.Action.Continue,
                GameMenuOverlay.Action.ScoreTable,
                GameMenuOverlay.Action.NewGame,
                GameMenuOverlay.Action.Reference,
                GameMenuOverlay.Action.OpenLanguage,
                GameMenuOverlay.Action.Settings,
                GameMenuOverlay.Action.ExitToMainMenu,
            ),
            actions,
        )
        assertFalse(GameMenuOverlay.Action.About in actions)
        assertFalse(GameMenuOverlay.Action.PlayAgainstBots in actions)
        assertFalse(GameMenuOverlay.Action.Tutorial in actions)
        assertFalse(GameMenuOverlay.Action.Players in actions)
        assertFalse(GameMenuOverlay.Action.LogOut in actions)
        assertFalse(GameMenuOverlay.Action.LogIn in actions)
        assertFalse(GameMenuOverlay.Action.Exit in actions)
    }

    @Test
    fun tutorialMenuOnlyLeavesToRoot() {
        val actions = actions(MenuContext.Tutorial)
        assertEquals(
            listOf(
                GameMenuOverlay.Action.Reference,
                GameMenuOverlay.Action.OpenLanguage,
                GameMenuOverlay.Action.Settings,
                GameMenuOverlay.Action.ExitToMainMenu,
            ),
            actions,
        )
        assertFalse(GameMenuOverlay.Action.About in actions)
        assertFalse(GameMenuOverlay.Action.NewGame in actions)
        assertFalse(GameMenuOverlay.Action.Tutorial in actions)
        assertFalse(GameMenuOverlay.Action.Players in actions)
        assertFalse(GameMenuOverlay.Action.LogOut in actions)
        assertFalse(GameMenuOverlay.Action.LogIn in actions)
        assertFalse(GameMenuOverlay.Action.Exit in actions)
    }

    @Test
    fun identityLineFormatsNameAndRating() {
        val withRating = assertNotNull(GameMenuOverlay.identityLine("alice", 1482.0))
        // Same key + args the production code formats; no copy is pinned here.
        assertEquals(L10n.format("menu.signed_in_as", "alice", "1482"), withRating)
        // Name and the Kotlin-rounded rating both survive regardless of wording.
        assertTrue(withRating.contains("alice"))
        assertTrue(withRating.contains("1482"))
        assertEquals("alice", GameMenuOverlay.identityLine("alice", null))
        assertEquals(null, GameMenuOverlay.identityLine(null, null))
    }

    @Test
    fun overflowKeepsLastRowHittableAfterScroll() {
        val overlay = GameMenuOverlay()
        val actions = listOf(
            GameMenuOverlay.Action.Continue,
            GameMenuOverlay.Action.ScoreTable,
            GameMenuOverlay.Action.NewGame,
            GameMenuOverlay.Action.Reference,
            GameMenuOverlay.Action.OpenLanguage,
            GameMenuOverlay.Action.ExitToMainMenu,
        )
        val itemH = 144f
        val itemGap = 24f
        val bodyBottom = 32f
        val bodyH = 400f
        overlay.seedLayoutForTest(
            panelLeft = 200f,
            panelBottom = 12f,
            panelW = 400f,
            panelH = 1200f,
            itemH = itemH,
            itemGap = itemGap,
            bodyBottom = bodyBottom,
            bodyH = bodyH,
            actions = actions,
        )
        assertTrue(overlay.overflowingForTest)
        val cx = 400f
        val firstY = bodyBottom + bodyH - itemH * 0.5f
        assertEquals(GameMenuOverlay.Action.Continue, overlay.actionAt(cx, firstY))
        assertEquals(null, overlay.actionAt(cx, bodyBottom + itemH * 0.5f))

        // The owner scrolls by handing the offset in; the card renders it and
        // stores nothing of its own.
        overlay.seedLayoutForTest(
            panelLeft = 200f,
            panelBottom = 12f,
            panelW = 400f,
            panelH = 1200f,
            itemH = itemH,
            itemGap = itemGap,
            bodyBottom = bodyBottom,
            bodyH = bodyH,
            scrollY = overlay.maxScrollYForTest,
            actions = actions,
        )
        val lastY = bodyBottom + itemH * 0.5f
        assertEquals(GameMenuOverlay.Action.ExitToMainMenu, overlay.actionAt(cx, lastY))
        overlay.onPointerDown(cx, lastY)
        val up = overlay.onPointerUp(cx, lastY)
        assertEquals(GameMenuOverlay.Action.ExitToMainMenu.name, up.selectedId)
    }

    @Test
    fun headerIsInTheScrollBodyAndIsNotHittable() {
        val overlay = GameMenuOverlay()
        val actions = listOf(
            GameMenuOverlay.Action.Continue,
            GameMenuOverlay.Action.ScoreTable,
            GameMenuOverlay.Action.NewGame,
            GameMenuOverlay.Action.Reference,
            GameMenuOverlay.Action.OpenLanguage,
            GameMenuOverlay.Action.ExitToMainMenu,
        )
        val itemH = 144f
        val itemGap = 24f
        val headerBlockH = 72f
        val bodyBottom = 32f
        val bodyH = 400f
        overlay.seedLayoutForTest(
            itemH = itemH,
            itemGap = itemGap,
            bodyBottom = bodyBottom,
            bodyH = bodyH,
            headerBlockH = headerBlockH,
            actions = actions,
        )
        assertTrue(overlay.overflowingForTest)
        val cx = 400f
        val headerY = bodyBottom + bodyH - headerBlockH * 0.5f
        assertEquals(null, overlay.actionAt(cx, headerY))

        val firstY = bodyBottom + bodyH - headerBlockH - itemH * 0.5f
        assertEquals(GameMenuOverlay.Action.Continue, overlay.actionAt(cx, firstY))
        assertTrue(
            overlay.actionAt(cx, bodyBottom + itemH * 0.5f) !=
                GameMenuOverlay.Action.ExitToMainMenu,
        )

        overlay.onPointerDown(cx, headerY)
        // Finger up (y-up world) scrolls the body down, so the drag moves the list
        // instead of selecting the row under the lift.
        overlay.onPointerDrag(cx, headerY + 80f)
        val drag = overlay.onPointerUp(cx, headerY + 80f)
        assertTrue(overlay.scrollOffset() > 0f)
        assertEquals(null, drag.selectedId)

        overlay.seedLayoutForTest(
            itemH = itemH,
            itemGap = itemGap,
            bodyBottom = bodyBottom,
            bodyH = bodyH,
            headerBlockH = headerBlockH,
            scrollY = overlay.maxScrollYForTest,
            actions = actions,
        )
        val lastY = bodyBottom + itemH * 0.5f
        assertEquals(GameMenuOverlay.Action.ExitToMainMenu, overlay.actionAt(cx, lastY))
    }

    @Test
    fun bodyDragDoesNotSelectARow() {
        val overlay = GameMenuOverlay()
        overlay.seedLayoutForTest(
            itemH = 144f,
            itemGap = 24f,
            bodyBottom = 32f,
            bodyH = 400f,
            actions = listOf(
                GameMenuOverlay.Action.Continue,
                GameMenuOverlay.Action.ScoreTable,
                GameMenuOverlay.Action.NewGame,
                GameMenuOverlay.Action.Reference,
                GameMenuOverlay.Action.OpenLanguage,
                GameMenuOverlay.Action.ExitToMainMenu,
            ),
        )
        val cx = 400f
        val y = 32f + 400f - 72f
        overlay.onPointerDown(cx, y)
        val before = requireNotNull(overlay.scrollOffset())
        overlay.onPointerDrag(cx, y + 80f)
        val up = overlay.onPointerUp(cx, y + 80f)
        assertTrue(overlay.scrollOffset() > before)
        assertEquals(null, up.selectedId)
    }

    @Test
    fun onlinePlayMenuCannotJumpToLocalOrTutorial() {
        val actions = actions(
            MenuContext.OnlinePlay,
            showScoreTable = true,
            showForfeit = true,
        )
        assertEquals(
            listOf(
                GameMenuOverlay.Action.ScoreTable,
                GameMenuOverlay.Action.Forfeit,
                GameMenuOverlay.Action.Reference,
                GameMenuOverlay.Action.OpenLanguage,
                GameMenuOverlay.Action.Settings,
                GameMenuOverlay.Action.ExitToMainMenu,
            ),
            actions,
        )
        assertFalse(GameMenuOverlay.Action.About in actions)
        assertFalse(GameMenuOverlay.Action.NewGame in actions)
        assertFalse(GameMenuOverlay.Action.Tutorial in actions)
        assertFalse(GameMenuOverlay.Action.Players in actions)
        assertFalse(GameMenuOverlay.Action.LogOut in actions)
        assertFalse(GameMenuOverlay.Action.LogIn in actions)
        assertFalse(GameMenuOverlay.Action.Exit in actions)
    }

    @Test
    fun finishedOnlineMenuDropsForfeitButKeepsScores() {
        val actions = actions(
            MenuContext.OnlinePlay,
            showScoreTable = true,
            showForfeit = false,
        )
        assertEquals(
            listOf(
                GameMenuOverlay.Action.ScoreTable,
                GameMenuOverlay.Action.Reference,
                GameMenuOverlay.Action.OpenLanguage,
                GameMenuOverlay.Action.Settings,
                GameMenuOverlay.Action.ExitToMainMenu,
            ),
            actions,
        )
    }

    @Test
    fun settingsRowIsSharedByRootAndEveryChildContext() {
        for (context in MenuContext.entries) {
            assertTrue(
                GameMenuOverlay.Action.Settings in actions(context),
                "Settings must be reachable from $context",
            )
        }
        // Still one shared utility block, not a per-context special case: the three
        // utility rows stay adjacent and keep Reference → language → Settings order.
        for (context in MenuContext.entries) {
            val rows = actions(context)
            val utilities = rows.filter {
                it == GameMenuOverlay.Action.Reference ||
                    it == GameMenuOverlay.Action.OpenLanguage ||
                    it == GameMenuOverlay.Action.Settings
            }
            assertEquals(
                listOf(
                    GameMenuOverlay.Action.Reference,
                    GameMenuOverlay.Action.OpenLanguage,
                    GameMenuOverlay.Action.Settings,
                ),
                utilities,
                "utility block drifted in $context",
            )
            assertEquals(
                rows.indexOf(GameMenuOverlay.Action.Reference) + 2,
                rows.indexOf(GameMenuOverlay.Action.Settings),
                "utility rows are not adjacent in $context",
            )
        }
    }

    @Test
    fun rootMenuOmitsExitWhenDisabled() {
        val actions = actions(MenuContext.Root, showExit = false)
        assertFalse(GameMenuOverlay.Action.Exit in actions)
        assertEquals(
            listOf(
                GameMenuOverlay.Action.Players,
                GameMenuOverlay.Action.PlayAgainstBots,
                GameMenuOverlay.Action.Tutorial,
                GameMenuOverlay.Action.Reference,
                GameMenuOverlay.Action.OpenLanguage,
                GameMenuOverlay.Action.Settings,
                GameMenuOverlay.Action.About,
            ),
            actions,
        )
    }

    /**
     * The card is a controlled view over the drawer: the tap reports the new open
     * state, and the next frame hands the owner's value back in.
     */
    @Test
    fun identityBadgeClickReportsTheDrawerToggle() {
        val overlay = GameMenuOverlay()
        overlay.seedLayoutForTest()
        overlay.seedIdentityForTest(signedInLabel = "alice", left = 400f, bottom = 800f, width = 100f, height = 30f)

        // Closed: the badge tap reports the drawer open.
        overlay.onPointerDown(450f, 815f)
        val opened = overlay.onPointerUp(450f, 815f)
        assertEquals(null, opened.selectedId)
        assertEquals(true, opened.accountDropdownOpen)

        // The owner's open value is what the next frame hands in; the same tap
        // now reports it closed.
        overlay.seedLayoutForTest(accountDropdownOpen = true)
        overlay.seedIdentityForTest(signedInLabel = "alice", left = 400f, bottom = 800f, width = 100f, height = 30f)
        overlay.onPointerDown(450f, 815f)
        val closed = overlay.onPointerUp(450f, 815f)
        assertEquals(null, closed.selectedId)
        assertEquals(false, closed.accountDropdownOpen)
    }

    @Test
    fun clickOutsideAccountDropdownReportsItClosedWithoutSelectingAction() {
        val overlay = GameMenuOverlay()
        overlay.seedLayoutForTest(accountDropdownOpen = true)
        overlay.seedIdentityForTest(signedInLabel = "alice", left = 400f, bottom = 800f, width = 100f, height = 30f)

        overlay.onPointerDown(100f, 100f)
        val res = overlay.onPointerUp(100f, 100f)
        assertEquals(null, res.selectedId)
        assertEquals(false, res.accountDropdownOpen)
    }

    /**
     * A restored chrome card can carry `accountDropdownOpen` in its store while the
     * frame draws no identity. The drawer was not drawn, so it is not open: the
     * first press must reach the row under it instead of being eaten as a click
     * outside an invisible dropdown.
     */
    @Test
    fun aHiddenIdentityDrawerDoesNotSwallowTheFirstRowPress() {
        val overlay = GameMenuOverlay()
        overlay.seedLayoutForTest(
            showIdentity = false,
            accountDropdownOpen = true,
            actions = listOf(GameMenuOverlay.Action.Continue),
        )
        val cx = 400f
        val rowY = 32f + 938f - 144f * 0.5f

        assertEquals(GameMenuOverlay.Action.Continue, overlay.actionAt(cx, rowY))
        overlay.onPointerDown(cx, rowY)
        val up = overlay.onPointerUp(cx, rowY)
        assertEquals(GameMenuOverlay.Action.Continue.name, up.selectedId)
        assertNull(up.accountDropdownOpen, "the hidden drawer must not report a close")
    }

    @Test
    fun canExitReturnsFalseOnWebGL() {
        val prev = Gdx.app
        try {
            val app = java.lang.reflect.Proxy.newProxyInstance(
                Application::class.java.classLoader,
                arrayOf(Application::class.java),
            ) { _, method, _ ->
                if (method.name == "getType") Application.ApplicationType.WebGL else null
            } as Application
            Gdx.app = app
            assertFalse(GameMenuOverlay.canExit())
            val actions = actions(MenuContext.Root)
            assertFalse(GameMenuOverlay.Action.Exit in actions)
        } finally {
            Gdx.app = prev
        }
    }

    @Test
    fun canExitReturnsTrueOnDesktopAndNull() {
        val prev = Gdx.app
        try {
            Gdx.app = null
            assertTrue(GameMenuOverlay.canExit())
            val app = java.lang.reflect.Proxy.newProxyInstance(
                Application::class.java.classLoader,
                arrayOf(Application::class.java),
            ) { _, method, _ ->
                if (method.name == "getType") Application.ApplicationType.Desktop else null
            } as Application
            Gdx.app = app
            assertTrue(GameMenuOverlay.canExit())
            val actions = actions(MenuContext.Root)
            assertTrue(GameMenuOverlay.Action.Exit in actions)
        } finally {
            Gdx.app = prev
        }
    }

    @Test
    fun clickingNoticesButtonResolvesNoticesAction() {
        val overlay = GameMenuOverlay()
        overlay.seedLayoutForTest()
        overlay.seedNoticesForTest(left = 400f, bottom = 800f, width = 40f, height = 40f)

        assertTrue(overlay.isInsideNoticesButton(420f, 820f))
        assertFalse(overlay.isInsideNoticesButton(390f, 820f))

        overlay.onPointerDown(420f, 820f)
        val res = overlay.onPointerUp(420f, 820f)
        assertEquals(GameMenuOverlay.Action.Notices.name, res.selectedId)
        assertEquals(false, res.accountDropdownOpen)
    }

    @Test
    fun clickingNoticesButtonWhenDropdownIsOpenClosesDropdownAndResolvesNoticesAction() {
        val overlay = GameMenuOverlay()
        overlay.seedLayoutForTest(accountDropdownOpen = true)
        overlay.seedIdentityForTest(signedInLabel = "alice", left = 400f, bottom = 800f, width = 100f, height = 40f)
        overlay.seedNoticesForTest(left = 510f, bottom = 800f, width = 40f, height = 40f)

        overlay.onPointerDown(520f, 820f)
        val res = overlay.onPointerUp(520f, 820f)
        assertEquals(GameMenuOverlay.Action.Notices.name, res.selectedId)
        assertEquals(false, res.accountDropdownOpen)
    }

    private fun actions(
        context: MenuContext,
        showScoreTable: Boolean = false,
        showForfeit: Boolean = false,
        showContinue: Boolean = false,
        showExit: Boolean = GameMenuOverlay.canExit(),
    ): List<GameMenuOverlay.Action> =
        GameMenuOverlay.itemsFor(
            context = context,
            showScoreTable = showScoreTable,
            showForfeit = showForfeit,
            showContinue = showContinue,
            showExit = showExit,
        ).map { it.action }

    private fun findAssetsRoot(): File? {
        val cwd = File(".").canonicalFile
        return listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
            File(cwd, "../assets"),
        ).firstOrNull { File(it, "i18n/ui.properties").isFile }
    }
}
