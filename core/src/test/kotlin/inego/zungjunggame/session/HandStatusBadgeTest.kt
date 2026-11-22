package inego.zungjunggame.session

import inego.zungjunggame.ui.stack.SessionKind
import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.hosts.LocalPlayHost
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.session.snapshots.formatHandStatusLabel
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HandStatusBadgeTest {

    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        LocaleController.resetForTests()
        val base = requireUiBase()
        L10n.bootstrap(Locale.US, base)
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
        LocaleController.resetForTests()
    }

    private fun requireUiBase(): FileHandle {
        val candidates = listOf(
            File("../assets/i18n/ui"),
            File("assets/i18n/ui"),
            File("../../assets/i18n/ui"),
        )
        val f = candidates.firstOrNull { File("${it.path}.properties").isFile }
            ?: error("ui.properties not found in candidate paths: $candidates")
        return FileHandle(f)
    }

    @Test
    fun `formatHandStatusLabel in English for Tutorial, FreePlay, and Online`() {
        // Tutorial shows fixed label
        assertEquals(
            L10n.get("status.tutorial"),
            formatHandStatusLabel(
                sessionKind = SessionKind.Tutorial,
                handNumber = 1,
                maxHands = 4,
            ),
        )

        // FreePlay finite (4 rounds = 16 hands)
        assertEquals(
            L10n.format("status.hand_of", 1, 16),
            formatHandStatusLabel(
                sessionKind = SessionKind.FreePlay,
                handNumber = 1,
                maxHands = 16,
            ),
        )
        assertEquals(
            L10n.format("status.hand_of", 3, 16),
            formatHandStatusLabel(
                sessionKind = SessionKind.FreePlay,
                handNumber = 3,
                maxHands = 16,
            ),
        )

        // FreePlay unlimited (no max hands)
        assertEquals(
            L10n.format("status.hand", 5),
            formatHandStatusLabel(
                sessionKind = SessionKind.FreePlay,
                handNumber = 5,
                maxHands = null,
            ),
        )

        // Online 1 round (4 hands)
        assertEquals(
            L10n.format("status.hand_of", 1, 4),
            formatHandStatusLabel(
                sessionKind = SessionKind.Online,
                handNumber = 1,
                maxHands = 4,
            ),
        )
        assertEquals(
            L10n.format("status.hand_of", 4, 4),
            formatHandStatusLabel(
                sessionKind = SessionKind.Online,
                handNumber = 4,
                maxHands = 4,
            ),
        )

        // Online 2 rounds (8 hands)
        assertEquals(
            L10n.format("status.hand_of", 3, 8),
            formatHandStatusLabel(
                sessionKind = SessionKind.Online,
                handNumber = 3,
                maxHands = 8,
            ),
        )
    }

    @Test
    fun `formatHandStatusLabel in Russian for FreePlay and Online`() {
        val base = requireUiBase()
        L10n.bootstrap(Locale.forLanguageTag("ru"), base)

        assertEquals(
            L10n.format("status.hand_of", 1, 16),
            formatHandStatusLabel(
                sessionKind = SessionKind.FreePlay,
                handNumber = 1,
                maxHands = 16,
            ),
        )

        assertEquals(
            L10n.format("status.hand_of", 1, 4),
            formatHandStatusLabel(
                sessionKind = SessionKind.Online,
                handNumber = 1,
                maxHands = 4,
            ),
        )
    }

    @Test
    fun `LocalPlayHost exposes hand number and max hands from GameSession`() {
        val session = GameSession(
            sessionSlot = sessionSlot(),
            gameConfigSlot = gameConfigSlot(),
            persistAllowed = { false },
        )
        session.startGame(LocalGameConfig(GameConfig(rounds = 1))) // 1 round = 4 hands

        val host = LocalPlayHost(
            liveHand = session,
            board = BoardController(),
        )

        val playHost: PlayHost = host
        assertEquals(1, playHost.currentHandNumber)
        assertEquals(4, playHost.maxHands)
    }

    @Test
    fun `LocalPlayHost supports explicit hand number and max hands providers`() {
        var handNum = 2
        var maxHands: Int? = 8
        val session = GameSession(
            sessionSlot = sessionSlot(),
            gameConfigSlot = gameConfigSlot(),
            persistAllowed = { false },
        )

        val host = LocalPlayHost(
            liveHand = session,
            board = BoardController(),
            currentHandNumberProvider = { handNum },
            maxHandsProvider = { maxHands },
        )

        val playHost: PlayHost = host
        assertEquals(2, playHost.currentHandNumber)
        assertEquals(8, playHost.maxHands)

        handNum = 5
        maxHands = null
        assertEquals(5, playHost.currentHandNumber)
        assertEquals(null, playHost.maxHands)
    }

    @Test
    fun `LocalPlayHost exposes allowChickenHands from GameSession and provider`() {
        val session = GameSession(
            sessionSlot = sessionSlot(),
            gameConfigSlot = gameConfigSlot(),
            persistAllowed = { false },
        )
        session.startGame(LocalGameConfig(GameConfig(allowChickenHands = true)))

        val hostFromSession = LocalPlayHost(
            liveHand = session,
            board = BoardController(),
        )
        assertEquals(true, hostFromSession.allowChickenHands)

        val hostFromProvider = LocalPlayHost(
            liveHand = session,
            board = BoardController(),
            allowChickenHandsProvider = { false },
        )
        assertEquals(false, hostFromProvider.allowChickenHands)
    }
}
