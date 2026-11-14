package inego.zungjunggame.i18n

import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.engine.PatternIds
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.features.menu.GameMenuOverlay
import inego.zungjunggame.features.online.hub.LiveTabPanel
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.online.RatingLine
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.features.score.ScoreBreakdownText
import inego.zungjunggame.tutorial.TutorialCatalog
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The i18n façade and the app's wiring to the bundle.
 *
 * **Nothing in this class asserts what the copy says.** Wording is edited in
 * `assets/i18n/ui*.properties` by whoever owns the language, and that must never
 * require a Kotlin change. [BundleCoverageTest] + [TestBundle] own the structural
 * guarantees (every key declared in every locale, no blanks, templates keep their
 * placeholders); what is tested here is behaviour: *which* key a call site reads,
 * with *which* arguments, and *which* locale's bundle is being served.
 *
 * So a feature expectation is written as `L10n.get("the.key")` /
 * `L10n.format("the.key", args)` rather than `"the words"`. That still fails on the
 * wrong key, wrong arguments or wrong template — the code's actual job — and never
 * on a reworded translation.
 */
class L10nTest {
    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        LanguagePack.resetForTests()
        LocaleController.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
        LanguagePack.resetForTests()
        LocaleController.resetForTests()
        PlayerLabels.clearCustomNames()
    }

    /**
     * The pack is the production source; the filesystem `assets/i18n` tree is the
     * fallback. `format` is the part that matters: a bundle created by the public
     * `createBundle` path initializes its `TextFormatter`, so a templated key
     * substitutes rather than NPEs.
     */
    @Test
    fun servesOpenPackBeforeTheFilesystemBase() {
        val sentinel = "PACK_TITLE_SENTINEL"
        val bundle = (
            "menu.title=$sentinel\n" +
                "menu.signed_in_as={0} - {1}\n"
            ).toByteArray(Charsets.UTF_8)
        val zip = LanguagePacker.writeZip(mapOf("pack" to LanguagePacker.writePack(bundle, emptyMap())))
        assertTrue(LanguagePack.openBytes(AppLocale.Russian.tag, zip))
        try {
            L10n.bootstrap(AppLocale.Russian.javaLocale, base = null)
            assertEquals(sentinel, L10n.get("menu.title"))
            assertEquals("alice - 42", L10n.format("menu.signed_in_as", "alice", 42))
        } finally {
            LanguagePack.resetForTests()
        }

        // Pack closed: the no-pack path still walks to the real assets bundle.
        L10n.bootstrap(AppLocale.Russian.javaLocale, base = null)
        val real = TestBundle.declared(AppLocale.Russian.tag).getValue("menu.title")
        assertEquals(L10n.get("menu.title"), real)
        assertTrue(real != sentinel, "filesystem fallback served the pack sentinel")
    }

    @Test
    fun servesEachLocaleFromItsOwnFile() {
        // One key per area, so a locale served from the wrong file shows up here.
        val keys = listOf(
            "menu.title", "menu.language_title", "menu.online", "menu.about", "menu.log_out",
            "confirm.log_out", "seat.opposite", "history.title", "hand.next",
            "tutorial.chapter.orient", "claim.hu",
        )
        L10n.bootstrap(AppLocale.DEFAULT.javaLocale, requireUiBase())
        for (tag in TestBundle.shippedTags()) {
            L10n.setLocale(TestBundle.localeFor(tag))
            val declared = TestBundle.declared(tag)
            for (key in keys) TestBundle.assertServedFromOwnFile(key, tag, declared)
        }
    }

    @Test
    fun unknownKeyIsReturnedInsteadOfCrashing() {
        val base = requireUiBase()
        L10n.bootstrap(Locale.US, base)
        // Façade catch, not a crash — the contract every call site relies on.
        assertEquals("no.such.key", L10n.get("no.such.key"))
        assertEquals("no.such.key", L10n.format("no.such.key", 1))
    }

    @Test
    fun switchingLocaleServesTheOtherFileWithoutRestart() {
        val base = requireUiBase()
        L10n.bootstrap(Locale.US, base)
        val sample = listOf("menu.title", "seat.left", "history.title")
        for (key in sample) TestBundle.assertServedFromOwnFile(key, "en")

        L10n.setLocale(TestBundle.localeFor("ru"))
        for (key in sample) TestBundle.assertServedFromOwnFile(key, "ru")

        L10n.setLocale(Locale.US)
        for (key in sample) TestBundle.assertServedFromOwnFile(key, "en")
    }

    @Test
    fun formatSubstitutesEveryArgumentInEveryLocale() {
        L10n.bootstrap(Locale.US, requireUiBase())
        for (tag in TestBundle.shippedTags()) {
            L10n.setLocale(TestBundle.localeFor(tag))
            TestBundle.assertFormats("menu.signed_in_as", "alice", "1482")
            TestBundle.assertFormats("online.latency", 42)
        }
    }

    @Test
    fun identityLineIsNameAndRoundedRating() {
        L10n.bootstrap(Locale.US, requireUiBase())
        val rating = assertNotNull(MatchHistoryFormat.ratingLabel(1482.4))
        val line = assertNotNull(GameMenuOverlay.identityLine("alice", 1482.4))
        assertEquals(L10n.format("menu.signed_in_as", "alice", rating), line)
        // Structure: the name and the rounded number both survive the template.
        assertTrue(line.contains("alice"), "identity line lost the name: '$line'")
        assertTrue(line.contains("1482"), "identity line lost the rating: '$line'")
        assertEquals("alice", GameMenuOverlay.identityLine("alice", null))
        assertNull(GameMenuOverlay.identityLine(null, 1500.0))
    }

    @Test
    fun everyRatingDisplayRoundsToTheSameNumber() {
        L10n.bootstrap(Locale.US, requireUiBase())
        // A shipped account's stored rating: fractional, and the title row rounds it
        // to 1200. Truncating it in one panel and grouping it in another is how the
        // lobby came to read 1,199 under a menu that said 1200.
        val serverRating = 1199.7509569395518
        val settlement = RatingLine(
            name = "alice",
            before = 1199,
            delta = 1,
            after = 1200,
            isHuman = true,
        )
        val displays = listOf(
            assertNotNull(GameMenuOverlay.identityLine("alice", serverRating)),
            LiveTabPanel.ratingLine(serverRating),
            ScoreBreakdownText.ratingDisplayText(settlement),
        )
        for (text in displays) {
            assertTrue(text.contains("1200"), "rating display lost the rounded value: '$text'")
            assertFalse(text.contains("1,200"), "rating display grouped its digits: '$text'")
        }
    }

    @Test
    fun startingLevelKeysResolveInEveryLocale() {
        val keys = listOf(
            "starting_level.title", "starting_level.body", "starting_level.novice",
            "starting_level.amateur", "starting_level.strong", "starting_level.setting",
            "starting_level.error_generic",
        )
        L10n.bootstrap(Locale.US, requireUiBase())
        for (tag in TestBundle.shippedTags()) {
            L10n.setLocale(TestBundle.localeFor(tag))
            val declared = TestBundle.declared(tag)
            for (key in keys) TestBundle.assertServedFromOwnFile(key, tag, declared)
        }
    }

    @Test
    fun relativeSeatNamesFollowTheViewer() {
        L10n.bootstrap(Locale.US, requireUiBase())
        PlayerLabels.setPlayerNames(null, null)
        assertEquals(L10n.get("seat.you"), PlayerLabels.nameForAbsoluteSeat(0, humanSeat = 0))
        assertEquals(L10n.get("seat.right"), PlayerLabels.nameForAbsoluteSeat(1, humanSeat = 0))
        assertEquals(L10n.get("seat.opposite"), PlayerLabels.nameForAbsoluteSeat(2, humanSeat = 0))
        assertEquals(L10n.get("seat.left"), PlayerLabels.nameForAbsoluteSeat(3, humanSeat = 0))
        // The same absolute seats, seen from seat 1.
        assertEquals(L10n.get("seat.left"), PlayerLabels.nameForAbsoluteSeat(0, humanSeat = 1))
        assertEquals(L10n.get("seat.you"), PlayerLabels.nameForAbsoluteSeat(1, humanSeat = 1))
        assertEquals(L10n.get("seat.right"), PlayerLabels.nameForAbsoluteSeat(2, humanSeat = 1))
        assertEquals(L10n.get("seat.opposite"), PlayerLabels.nameForAbsoluteSeat(3, humanSeat = 1))
    }

    @Test
    fun windNamesMapToTheirOwnKeys() {
        val keys = listOf("wind.east", "wind.south", "wind.west", "wind.north")
        L10n.bootstrap(Locale.US, requireUiBase())
        for (tag in TestBundle.shippedTags()) {
            L10n.setLocale(TestBundle.localeFor(tag))
            val declared = TestBundle.declared(tag)
            for (key in keys) TestBundle.assertServedFromOwnFile(key, tag, declared)
        }
        L10n.setLocale(Locale.US)
        assertEquals(L10n.get("wind.east"), PlayerLabels.windName(Tiles.EAST))
        assertEquals(L10n.get("wind.south"), PlayerLabels.windName(Tiles.SOUTH))
        assertEquals(L10n.get("wind.west"), PlayerLabels.windName(Tiles.WEST))
        assertEquals(L10n.get("wind.north"), PlayerLabels.windName(Tiles.NORTH))
        assertEquals("?", PlayerLabels.windName(Tiles.RED))
    }

    @Test
    fun stage1ChromeKeysResolveInEveryLocale() {
        val keys = listOf(
            "setup.start", "confirm.yes", "confirm.log_out", "claim.pung",
            "seat.you", "seat.opposite", "wind.east", "scores.total", "hand.next", "hand.draw", "hint.label",
            "status.hand", "status.tutorial",
            "menu.log_in",
            "alias.change_name",
            "alias.edit_title", "alias.save", "alias.cancel",
            "menu.signed_in_as", "menu.online", "menu.about", "menu.play_against_bots", "menu.continue", "menu.exit_to_main_menu",
            "menu.forfeit", "confirm.forfeit",
            "presence.title", "presence.signed_in_as", "presence.online", "presence.playing", "presence.waiting",
            "presence.includes_you", "presence.looking",
            "presence.preset", "presence.preset_no_bots", "presence.preset_prefer_humans", "presence.preset_start_quickly",
            "presence.rounds", "presence.rounds_one", "presence.rounds_two", "presence.rounds_either",
            "presence.chicken", "presence.chicken_yes", "presence.chicken_no", "presence.chicken_no_pref",
            "presence.chicken_allowed", "presence.chicken_disallowed",
            "presence.you_waiting", "presence.you_playing", "presence.you_idle",
            "presence.status_wait_no_bots", "presence.status_wait_prefer_four", "presence.status_wait_three", "presence.status_wait_two",
            "presence.status_seated", "presence.status_seated_hint",
            "presence.updated", "presence.counts_pending", "presence.error_stale",
            "online.live.find_game", "online.live.cancel_search",
            "online.live.rejoin", "online.live.status_searching",
            "online.live.status_interrupted", "online.live.status_joining",
            "online.live.status_in_progress",

            "online.live.notice_aborted", "online.live.notice_unavailable", "online.live.notice_session",
            "online.live.notice_cooldown",
            "online.auth_expired", "online.table_gone",
            "online.waiting_opponent",
            "online.away_title", "online.away_message", "online.away_resume",
            "online.latency",
            "history.title", "history.past_games", "shadowRanked.past_attempts", "history.empty",
            "history.load_more", "history.today", "history.place_1", "hand.play_again",
            "hand.back_home", "hand.rating_self", "hand.rating_changes",
        )
        L10n.bootstrap(Locale.US, requireUiBase())
        for (tag in TestBundle.shippedTags()) {
            L10n.setLocale(TestBundle.localeFor(tag))
            val declared = TestBundle.declared(tag)
            for (key in keys) TestBundle.assertServedFromOwnFile(key, tag, declared)
        }
    }

    @Test
    fun patternKeysResolveInEveryLocale() {
        val plain = listOf(
            PatternIds.SEVEN_PAIRS, PatternIds.THIRTEEN_TERMINALS, PatternIds.MIXED_ONE_SUIT,
            PatternIds.ALL_SEQUENCES, PatternIds.NINE_GATES, PatternIds.CHICKEN_HAND,
        )
        L10n.bootstrap(Locale.US, requireUiBase())
        for (tag in TestBundle.shippedTags()) {
            L10n.setLocale(TestBundle.localeFor(tag))
            val declared = TestBundle.declared(tag)
            for (key in plain) TestBundle.assertServedFromOwnFile(key, tag, declared)
            TestBundle.assertServedFromOwnFile(PatternIds.VALUE_HONOR_SETS, tag, declared)
            // The one templated pattern must substitute its set count.
            TestBundle.assertFormats(PatternIds.VALUE_HONOR_SETS, 3)
        }
    }

    @Test
    fun tutorialCatalogKeysResolveInEveryLocale() {
        val base = requireUiBase()
        for (locale in AppLocale.entries) {
            L10n.bootstrap(locale.javaLocale, base)
            for ((_, titleKey) in TutorialCatalog.chapters) {
                val title = L10n.get(titleKey)
                assertTrue(
                    title != titleKey && title.isNotBlank(),
                    "missing ${locale.tag} chapter $titleKey",
                )
            }
            for ((_, titleKey) in TutorialCatalog.campaign) {
                val title = L10n.get(titleKey)
                assertTrue(
                    title != titleKey && title.isNotBlank(),
                    "missing ${locale.tag} lesson $titleKey",
                )
            }
        }
    }

    @Test
    fun referenceLocaleResolvesEveryShippedPack() {
        // Swept over assets/reference/, so adding a language adds no cases here:
        // an exact pack tag is itself, a region variant follows a language-only
        // pack when one exists, and anything else falls back to the default.
        val packs = shippedReferenceTags()
        assertTrue(packs.isNotEmpty(), "no shipped reference packs under assets/reference")
        for (tag in packs) {
            assertEquals(tag, LocaleController.resolveReferenceLocaleTag(tag), "exact pack '$tag'")
            val language = tag.substringBefore('-')
            val expected = if (language in packs) language else REFERENCE_CATALOG_DEFAULT
            assertEquals(
                expected,
                LocaleController.resolveReferenceLocaleTag("$language-ZZ"),
                "region variant of '$tag'",
            )
        }
        // A well-formed tag with no pack and no language pack falls back too.
        assertEquals(REFERENCE_CATALOG_DEFAULT, LocaleController.resolveReferenceLocaleTag("zz-ZZ"))
    }

    @Test
    fun localeBusFiresListeners() {
        var seen: AppLocale? = null
        val listener = LocaleBus.Listener { seen = it }
        LocaleBus.addListener(listener)
        LocaleBus.fire(AppLocale.Russian)
        assertEquals(AppLocale.Russian, seen)
        LocaleBus.removeListener(listener)
    }

    @Test
    fun tileLabelKeysResolveInEveryLocale() {
        // Order matters to the tile face, and every tag must exist in every language;
        // what the letters *are* is the translator's business (see TileLabelsTest).
        val keys = TileLabels.HONOR_KEYS + listOf("menu.settings", "settings.title", "settings.tile_labels")
        L10n.bootstrap(Locale.US, requireUiBase())
        for (tag in TestBundle.shippedTags()) {
            L10n.setLocale(TestBundle.localeFor(tag))
            val declared = TestBundle.declared(tag)
            for (key in keys) TestBundle.assertServedFromOwnFile(key, tag, declared)
        }
    }

    private fun requireUiBase(): FileHandle {
        val root = findAssetsRoot()
        assertNotNull(root, "assets/ with i18n/ not found from cwd")
        val props = File(root, "i18n/ui.properties")
        assertTrue(props.isFile, "missing ${props.absolutePath}")
        return FileHandle(File(root, "i18n/ui"))
    }

    private fun findAssetsRoot(): File? {
        val cwd = File(".").canonicalFile
        return listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
            File(cwd, "../assets"),
        ).firstOrNull { File(it, "i18n/ui.properties").isFile }
    }

    private fun shippedReferenceTags(): List<String> =
        File(TestBundle.assetsRoot(), "reference")
            .listFiles { f: File -> f.isDirectory && File(f, "about.md").isFile }
            .orEmpty()
            .map { it.name }
            .sorted()

    companion object {
        // Avoid importing ReferenceCatalog just for the constant in assert names.
        private const val REFERENCE_CATALOG_DEFAULT = "en-US"
    }
}
