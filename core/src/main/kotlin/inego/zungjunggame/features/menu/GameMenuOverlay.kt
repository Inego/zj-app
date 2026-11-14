package inego.zungjunggame.features.menu

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.render.FeltStamps
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.TextFit
import inego.zungjunggame.render.ui.UiIcons
import inego.zungjunggame.session.hosts.MatchPhase
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.SessionKind
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * Which two-level menu this overlay is showing. Mapped 1:1 from [SessionKind].
 *
 * Root is the only place that can enter a mode. Child contexts cannot jump to
 * a sibling — leave with **Exit to main menu**.
 */
enum class MenuContext {
    Root,
    LocalGame,
    Tutorial,
    OnlinePlay,
    ;

    companion object {
        fun from(kind: SessionKind): MenuContext = when (kind) {
            SessionKind.Menu -> Root
            SessionKind.FreePlay -> LocalGame
            SessionKind.Tutorial -> Tutorial
            SessionKind.Online -> OnlinePlay
        }
    }
}

/**
 * GL-free snapshot of the menu's bounds for the frame last laid out: the card and
 * the open account dropdown row, which extends past the card.
 *
 * Producers (the overlay after [GameMenuOverlay.render]) publish it so a
 * component can gate the wheel and the drag without a `SpriteBatch`; the overlay
 * itself resolves which row, badge, or dropdown item a release landed on.
 */
data class GameMenuGeometry(
    val panel: Rect?,
    val dropdownItem: Rect?,
)

/**
 * What a completed press on the card resolved.
 *
 * The overlay is a controlled view: it is handed the body offset and the account
 * drawer each frame and owns neither. A release reports the row it selected, the
 * offset a drag or scroll settled at, or the drawer's new state — and the owning
 * component writes that state to the route UI store.
 */
data class GameMenuVerdict(
    /** Stable id of the row the release selected, in the overlay's vocabulary. */
    val selectedId: String? = null,
    /**
     * Where a body drag is right now, reported from the drag steps so a
     * continuous scroll travels the same action channel as the wheel and the
     * release.
     */
    val dragScrollOffset: Float? = null,
    /** Where a body drag settled, when the release ended a scroll. */
    val settledScrollOffset: Float? = null,
    /** The account drawer's new state, when the release toggled or closed it. */
    val accountDropdownOpen: Boolean? = null,
)

/**
 * How the signed-out identity badge reads, as the account flow reports it.
 *
 * The badge is the only control that starts sign-in, so it is also the only
 * place an attempt can report itself: the hub is signed-in only, nothing is
 * raised over the menu, and the account flow owns no surface of its own. Each
 * state is a fact about the flow, not a decision about paint — the badge's
 * appearance is the overlay's.
 *
 * Every state the badge draws is also a control: a sign-in attempt owns no
 * separate cancel or reopen button, so the badge itself carries the way back
 * into the flow.
 */
enum class MenuSignIn {
    /** Signed out, nothing attempted: **Log in**. */
    Ready,

    /**
     * An attempt is in flight. Pressing the badge restarts it — the browser tab
     * or system sheet it opened may be gone, and a player who closed the tab has
     * no other way to bring it back.
     */
    Pending,

    /** The last attempt failed: the badge is the retry. */
    Failed,

    /**
     * This build has no Google sign-in at all (no provider, or no client id).
     * The badge still shows, because the special-key row may be usable.
     */
    Unavailable,
}

/** The label [state] paints. */
private fun signInLabelKey(state: MenuSignIn): String = when (state) {
    MenuSignIn.Ready -> "menu.log_in"
    MenuSignIn.Pending -> "menu.logging_in"
    MenuSignIn.Failed -> "menu.log_in_again"
    MenuSignIn.Unavailable -> "menu.log_in"
}

/**
 * Game menu: item list is a function of [MenuContext], not a bag of independent
 * flags. Each context composes the rows it owns with one shared utility block —
 * Reference, language, and Settings ([addSharedUtilityRows]) — so a display
 * preference stays reachable on the root menu **and** in every child context
 * (local game, tutorial, online). About / License is the only root-exclusive
 * utility row; context-specific rows (score table, forfeit, …) are
 * driven by [inego.zungjunggame.render.gameMenuFlags].
 *
 * Opened from the top-right ☰ on a local / online board or the tutorial shell;
 * touch free-space press+release (empty board / coach-quiz felt); or Esc on
 * desktop.
 * Closed by clicking outside the panel, Esc again (desktop), or choosing an item
 * (language opens [inego.zungjunggame.features.settings.LanguagePickerOverlay]
 * instead of cycling). Root stays open on empty felt (nothing underneath).
 *
 * On a short viewport (Android landscape) the whole card — title, optional
 * subtitle, identity, and rows — scrolls inside [OverlayViewport]'s safe rect.
 * The card yields the felt's fixed bottom-right stamp block: it stands centred
 * while the corner leaves a wing beside or a band below it, slides left only as
 * far as that wing needs, and gives up height for the band when it is too wide
 * to leave a wing at all ([OverlayViewport.placeBeside]). The title block never
 * stays sticky; it occupies compact rows (the title centred in the card, the
 * optional subtitle right-aligned beneath it, then the account / notices controls
 * right-aligned on their own row).
 *
 * Menu rows may include an optional leading chrome icon (see [MenuItem.leadingIcon]);
 * the language row uses [UiIcons.globe] plus the active locale endonym.
 *
 * The identity badge is the account's one control: signed in it opens the account
 * drawer, signed out a sign-in chooser with **Google account** and **Special key**.
 * The badge itself reports the Google attempt ([MenuSignIn]) — ready, in flight,
 * or failed — and the Google row restarts one whose browser surface was lost or
 * retries a failed one. The special key opens its own card. The badge is therefore
 * also where an attempt reports itself: a sign-in owns no surface of its own, and
 * the Online hub it lands in is signed-in only.
 *
 * The card is controlled: [render] is handed the body offset and the account
 * drawer's open state every frame, and a release reports what it resolved
 * ([GameMenuVerdict]). The overlay keeps only the frame it last painted; the
 * owning component stores what the player set.
 */
class GameMenuOverlay : Disposable, GestureSurface<GameMenuVerdict> {
    private val glyphLayout = GlyphLayout()
    private val body = OverlayBodyScroll()

    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var items: List<MenuItem> = emptyList()
    private var itemH = 0f
    private var itemGap = 0f
    private var rowLeft = 0f
    private var rowW = 0f
    /** Title + identity + gap before the first row, inside the scroll body. */
    private var headerBlockH = 0f

    /** The open state [render] was handed this frame; the owner's value. */
    private var accountDropdownOpen: Boolean = false

    private var currentSignedInLabel: String? = null
    private var identityBadgeLeft = 0f
    private var identityBadgeBottom = 0f
    private var identityBadgeW = 0f
    private var identityBadgeH = 0f
    private var noticesButtonLeft = 0f
    private var noticesButtonBottom = 0f
    private var noticesButtonW = 0f
    private var noticesButtonH = 0f
    private var dropdownRectLeft = 0f
    private var dropdownRectBottom = 0f
    private var dropdownRectW = 0f
    private var dropdownRectH = 0f

    /** One row of the account dropdown. */
    private data class DropdownEntry(
        val action: Action,
        val label: String,
        val enabled: Boolean = true,
    )

    /** The open dropdown's rows, in paint order (top first); the release resolves one. */
    private data class DropdownRow(
        val entry: DropdownEntry,
        val rect: Rect,
    )

    private val dropdownItems = ArrayList<DropdownRow>()

    private var pressTarget: PressTarget = PressTarget.None

    private enum class PressTarget { None, Body, Chrome, Identity, DropdownItem, OutsideDropdown, Notices }

    enum class Action {
        ScoreTable,
        /** Rules + scoring reference browser. */
        Reference,
        /** Reference browser on the About / License page. */
        About,
        /** Open the language picker (multi-locale list). */
        OpenLanguage,
        /** Open the Settings panel (display preferences, not game rules). */
        Settings,
        NewGame,
        /** Resume a parked local game (local-game menu). */
        Continue,
        /** Enter the local-game context from the root menu. */
        PlayAgainstBots,
        /** Open the tutorial lesson nav (root menu only). */
        Tutorial,
        /** Sign in with Google. */
        LogIn,
        /** Open the special-key field for the Google Play review account. */
        LogInSpecialKey,
        LogOut,
        /** Open the Account panel (name, Change name, Delete account). */
        Account,
        /** Open the player's notice inbox. */
        Notices,
        /** Opens the unified Online hub from the root menu; needs a signed-in account. */
        Players,
        /** Permanently leave a running online table (confirmed). */
        Forfeit,
        /** Leave the current child context and return to the root menu. */
        ExitToMainMenu,
        /** Quit the app (root menu only; omitted on Web/Wasm where browser tabs are not closed). */
        Exit,
    }

    /**
     * One menu row: action, label text, and optional leading tintable icon.
     *
     * [leadingIcon] is a white-on-transparent chrome glyph (from [UiIcons]);
     * it is drawn at the label ink color and sized relative to the row.
     *
     * [enabled] false paints the row muted and makes it unselectable: a row whose
     * action cannot be taken is shown so the menu's shape stays stable, not so it
     * can be pressed. The press machine resolves rows through [actionAt], which
     * refuses a disabled row, so a hit region is never published for one.
     */
    data class MenuItem(
        val action: Action,
        val label: String,
        val leadingIcon: Texture? = null,
        val enabled: Boolean = true,
    )

    override fun cancelGesture() {
        body.cancel()
        pressTarget = PressTarget.None
    }

    fun isInsideIdentityBadge(worldX: Float, worldY: Float): Boolean {
        if (identityBadgeW <= 0f) return false
        return worldX >= identityBadgeLeft && worldX <= identityBadgeLeft + identityBadgeW &&
            worldY >= identityBadgeBottom && worldY <= identityBadgeBottom + identityBadgeH
    }

    fun isInsideNoticesButton(worldX: Float, worldY: Float): Boolean {
        if (noticesButtonW <= 0f) return false
        return worldX >= noticesButtonLeft && worldX <= noticesButtonLeft + noticesButtonW &&
            worldY >= noticesButtonBottom && worldY <= noticesButtonBottom + noticesButtonH
    }

    fun isInsideDropdown(worldX: Float, worldY: Float): Boolean {
        return !(!accountDropdownOpen || dropdownRectW <= 0f) && worldX >= dropdownRectLeft && worldX <= dropdownRectLeft + dropdownRectW &&
                worldY >= dropdownRectBottom && worldY <= dropdownRectBottom + dropdownRectH
    }

    /** True when the point lies on the menu panel (or open dropdown). */
    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        if (worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
        ) {
            return true
        }
        if (accountDropdownOpen && isInsideDropdown(worldX, worldY)) {
            return true
        }
        return false
    }

    /** Menu item under the pointer, or null. Uses live scroll, not last-frame hits. */
    fun actionAt(worldX: Float, worldY: Float): Action? {
        if (items.isEmpty() || itemH <= 0f) return null
        if (accountDropdownOpen) return null
        if (isInsideIdentityBadge(worldX, worldY)) return null
        if (isInsideNoticesButton(worldX, worldY)) return null
        if (!body.hitsContent(worldX, worldY)) return null
        val stride = itemH + itemGap
        val index = body.rowIndexAt(worldY, headerBlockH, itemH, stride, items.size)
            ?: return null
        val item = items[index]
        return item.action.takeIf { item.enabled }
    }

    /**
     * Pure snapshot of the card the last [render] laid out (or the last
     * [seedLayoutForTest] installed), plus the open dropdown's row. No GL / fonts;
     * safe to call from a headless layout pass.
     */
    fun measureGeometry(): GameMenuGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        val dropdown = if (accountDropdownOpen && dropdownRectW > 0f) {
            Rect(dropdownRectLeft, dropdownRectBottom, dropdownRectW, dropdownRectH)
        } else {
            null
        }
        return GameMenuGeometry(panel, dropdown)
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        accountDropdownOpen = false
        body.wheel(amountY)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        body.cancel()
        pressTarget = PressTarget.None

        if (accountDropdownOpen) {
            if (isInsideDropdown(x, y)) {
                pressTarget = PressTarget.DropdownItem
                return true
            }
            if (isInsideIdentityBadge(x, y)) {
                pressTarget = PressTarget.Identity
                return true
            }
            if (isInsideNoticesButton(x, y)) {
                pressTarget = PressTarget.Notices
                return true
            }
            pressTarget = PressTarget.OutsideDropdown
            return true
        }

        if (isInsideNoticesButton(x, y)) {
            pressTarget = PressTarget.Notices
            return true
        }

        if (isInsideIdentityBadge(x, y)) {
            pressTarget = PressTarget.Identity
            return true
        }

        if (!containsPanel(x, y)) return false
        if (body.contains(x, y) && body.beginPointer(x, y)) {
            pressTarget = PressTarget.Body
            return true
        }
        pressTarget = PressTarget.Chrome
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): GameMenuVerdict? {
        if (pressTarget != PressTarget.Body) return null
        val offset = body.dragTo(y)
        return GameMenuVerdict(dragScrollOffset = offset)
    }

    override fun onPointerUp(x: Float, y: Float): GameMenuVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        return when (target) {
            PressTarget.Identity -> {
                body.cancel()
                if (isInsideIdentityBadge(x, y)) {
                    // The badge is a doorway in both states. Signed in it reveals
                    // the account drawer; signed out it reveals the sign-in
                    // chooser, because Google is no longer the only method.
                    val next = !accountDropdownOpen
                    accountDropdownOpen = next
                    GameMenuVerdict(accountDropdownOpen = next)
                } else {
                    GameMenuVerdict()
                }
            }
            PressTarget.Notices -> {
                body.cancel()
                if (isInsideNoticesButton(x, y)) {
                    accountDropdownOpen = false
                    GameMenuVerdict(selectedId = Action.Notices.name, accountDropdownOpen = false)
                } else {
                    GameMenuVerdict()
                }
            }
            PressTarget.DropdownItem -> {
                body.cancel()
                val selected = dropdownItems
                    .lastOrNull { it.rect.contains(x, y) }
                    ?.entry
                    ?.takeIf { it.enabled }
                    ?.action
                accountDropdownOpen = false
                GameMenuVerdict(selectedId = selected?.name, accountDropdownOpen = false)
            }
            PressTarget.OutsideDropdown -> {
                body.cancel()
                accountDropdownOpen = false
                GameMenuVerdict(accountDropdownOpen = false)
            }
            PressTarget.Body -> {
                val wasDrag = body.endPointer()
                val action = if (!wasDrag && !x.isNaN() && !y.isNaN()) {
                    actionAt(x, y)
                } else {
                    null
                }
                GameMenuVerdict(
                    selectedId = action?.name,
                    settledScrollOffset = if (wasDrag) body.scrollY else null,
                )
            }
            PressTarget.Chrome, PressTarget.None -> {
                body.cancel()
                GameMenuVerdict()
            }
        }
    }

    /**
     * @param scrollY body offset the owner handed in; the card stores none of its own
     * @param accountDropdownOpen the drawer's open state the owner handed in
     * @param context which two-level menu to show
     * @param showScoreTable when true, include Score table (local / online child menus)
     * @param showForfeit when true, include Forfeit on the online menu. False once the
     *   match is over, when the row could only mislead (see [MatchPhase])
     * @param showContinue when true, include Continue on the local-game menu (parked or live game)
     * @param showIdentity when false, suppress the identity badge / account dropdown
     *   entirely. A null [signedInLabel] would still draw the signed-out badge (its
     *   chooser may include the special key even with no Google), so every child
     *   menu and the chrome `game_menu` card pass false: the root menu is the one
     *   sign-in entry.
     * @param signedInLabel alias when signed in; also shows Log out. Null shows the
     *   signed-out badge, whose label and state come from [signIn]
     * @param eloRating current server rating; omitted from the identity line when null
     * @param signIn what the signed-out badge has to say: ready to log in, an
     *   attempt in flight, or failed. Pressing it opens the sign-in chooser, whose
     *   Google row restarts or retries the attempt; the special-key row is always
     *   available. Ignored while signed in — the alias always wins.
     * @param showExit when true, include Exit on the root menu. Defaults to [canExit]
     */
    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        scrollY: Float,
        accountDropdownOpen: Boolean = false,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        context: MenuContext = MenuContext.Root,
        showScoreTable: Boolean = false,
        showForfeit: Boolean = false,
        showContinue: Boolean = false,
        showIdentity: Boolean = true,
        signedInLabel: String? = null,
        eloRating: Double? = null,
        signIn: MenuSignIn = MenuSignIn.Ready,
        showExit: Boolean = canExit(),
        showNotices: Boolean = false,
        hasUnseenNotices: Boolean = false,
    ) {
        // The press machine only enters drawer mode when this frame drew the
        // drawer: an undrawn drawer is not open, so a persisted `GAME_MENU` card
        // with `accountDropdownOpen` set cannot swallow the first tap.
        this.accountDropdownOpen = showIdentity && accountDropdownOpen
        Fonts.ensureCreated()

        val px = whitePixel()
        items = itemsFor(
            context = context,
            showScoreTable = showScoreTable,
            showForfeit = showForfeit,
            showContinue = showContinue,
            showExit = showExit,
            signedIn = signedInLabel != null,
        )

        val titleFont = Fonts.labelActive
        val subtitleFont = Fonts.label
        val identityFont = Fonts.label
        val itemFont = Fonts.button
        val title = L10n.get("menu.title")
        // The local-play card names its context under the title. It covers both
        // the local menu and the chrome card raised over a running local board,
        // where the account badge would not mean anything.
        val subtitle = when (context) {
            MenuContext.LocalGame -> L10n.get("menu.play_against_bots")
            else -> null
        }
        currentSignedInLabel = if (showIdentity) signedInLabel else null

        glyphLayout.setText(titleFont, title)
        val titleW = glyphLayout.width
        val titleLineH = titleFont.lineHeight
        val subtitleW = if (subtitle != null) Fonts.textWidth(subtitleFont, subtitle) else 0f
        val subtitleLineH = if (subtitle != null) subtitleFont.lineHeight else 0f
        val subtitleGap = if (subtitle != null) Fonts.px(2f) else 0f
        val titleBlockH = titleLineH + subtitleGap + subtitleLineH
        val titleBlockW = max(titleW, subtitleW)

        val padX = Fonts.px(28f)
        val padY = Fonts.px(16f)
        val titleGap = Fonts.px(10f)
        // The badge is a doorway in both states: signed in it opens the account
        // drawer, signed out the sign-in chooser. It stays visible even when this
        // build cannot offer Google, because the special-key row may still work.
        val badgeShown = showIdentity
        // Every painted badge opens something. The chevron says so, and the
        // control row below the title reserves its width so no state reflows the
        // card.
        val chevronSize = Fonts.px(14f)
        val chevronGap = Fonts.px(4f)
        val isChevronShown = badgeShown
        val badgePadX = Fonts.px(10f)
        val badgePadY = Fonts.px(5f)
        val badgeLineH = identityFont.lineHeight
        val badgeH = if (showIdentity) max(badgeLineH, chevronSize) + badgePadY * 2f else 0f
        val noticesBtnSize = if (showNotices) badgeH else 0f
        val noticesBtnGap = if (showNotices && showIdentity) Fonts.px(8f) else 0f

        val avail = OverlayViewport.available(screenW, screenH)
        val portrait = screenH > screenW
        val maxPanelW = OverlayViewport.maxPanelWidth(
            screenW * (if (portrait) 0.88f else 0.72f),
            avail,
            screenW,
            Fonts.px(24f),
        )
        // The chip may use the whole max panel on its own row. A stored name
        // that still does not fit is ellipsized; the rating suffix stays.
        val maxInner = (maxPanelW.coerceAtMost(avail.width) - padX * 2f).coerceAtLeast(1f)
        val badgeChrome =
            badgePadX * 2f +
                (if (isChevronShown) chevronGap + chevronSize else 0f) +
                noticesBtnSize + noticesBtnGap
        val textBudget = (maxInner - badgeChrome).coerceAtLeast(1f)
        val badgeText = when {
            !badgeShown -> ""
            signedInLabel != null ->
                fittedIdentityLine(identityFont, signedInLabel, eloRating, textBudget)
            else -> TextFit.ellipsize(identityFont, L10n.get(signInLabelKey(signIn)), textBudget)
        }
        val badgeTextW = if (badgeShown) Fonts.textWidth(identityFont, badgeText) else 0f
        val badgeContentW = badgeTextW + (if (isChevronShown) chevronGap + chevronSize else 0f)
        val badgeW = if (badgeShown) badgeContentW + badgePadX * 2f else 0f
        // The control row is sized to the widest thing the badge can say, while
        // the plate itself hugs its own label: a sign-in reporting progress must
        // not resize the card or reflow the title, and an idle **Log in** must
        // not stand on the air reserved for "Logging in…".
        val widestBadgeTextW = if (!badgeShown || signedInLabel != null) {
            badgeTextW
        } else {
            MenuSignIn.entries.maxOf { state ->
                Fonts.textWidth(
                    identityFont,
                    TextFit.ellipsize(identityFont, L10n.get(signInLabelKey(state)), textBudget),
                )
            }
        }
        val badgeZoneW = if (badgeShown) {
            widestBadgeTextW + badgePadX * 2f +
                (if (isChevronShown) chevronGap + chevronSize else 0f)
        } else {
            0f
        }
        val headerRightW = badgeZoneW + noticesBtnSize + noticesBtnGap

        itemH = max(Fonts.px(48f), itemFont.lineHeight + Fonts.px(22f))
        itemGap = Fonts.px(8f)
        val iconSize = max(Fonts.px(26f), itemFont.lineHeight * 0.95f)
        val iconGap = Fonts.px(10f)

        // The title block and the account/notices controls are stacked, so the
        // card only needs the wider of the two, not their sum.
        var contentW = max(titleBlockW, headerRightW)
        for ((action, label, leadingIcon) in items) {
            glyphLayout.setText(itemFont, label)
            val rowIcon = leadingIcon ?: iconFor(action)
            val rowContentW = if (rowIcon != null) {
                iconSize + iconGap + glyphLayout.width
            } else {
                glyphLayout.width
            }
            contentW = max(contentW, rowContentW)
        }

        panelW = max(Fonts.px(240f), contentW + padX * 2f)
        panelW = if (InputProfile.detect() == InputProfile.Touch) {
            max(panelW, screenW * 0.36f).coerceAtMost(maxPanelW)
        } else {
            panelW.coerceAtMost(maxPanelW)
        }
        panelW = panelW.coerceAtMost(avail.width)

        // A badge that is not drawn must not reserve its height either: the
        // notices button is the only thing left on a build with no sign-in.
        val badgeBoxH = if (badgeShown) badgeH else 0f
        // The title always owns the first line; the account/notices controls sit
        // right-aligned on the next one.
        val headerRowH = if (!badgeShown && !showNotices) {
            titleBlockH
        } else {
            titleBlockH + Fonts.px(4f) + max(badgeBoxH, noticesBtnSize)
        }
        headerBlockH = headerRowH + titleGap
        val chromeH = padY * 2f
        val n = items.size
        val itemsH = n * itemH + (n - 1).coerceAtLeast(0) * itemGap
        val bodyContentH = headerBlockH + itemsH
        val fit = OverlayViewport.fitSticky(avail.height, chromeH, bodyContentH)
        // The stamps have one home in the felt's bottom-right corner. The card
        // yields to it: centred while the corner leaves a wing beside or a band
        // below it, slid left only as far as the wing needs, shortened only when
        // even that cannot clear it.
        val placed = OverlayViewport.placeBeside(
            avail = avail,
            panelW = panelW,
            panelH = fit.cardH,
            obstacle = FeltStamps.rect(screenW, screenH),
            gap = Fonts.px(FeltStamps.GAP_DP),
        )
        panelH = placed.height
        panelLeft = placed.left
        panelBottom = placed.bottom
        body.layout(
            left = placed.left,
            bottom = placed.bottom + padY,
            width = panelW,
            height = (placed.height - chromeH).coerceAtLeast(0f),
            contentHeight = bodyContentH,
            scrollY = scrollY,
        )
        rowLeft = panelLeft + Fonts.px(12f)
        rowW = body.contentWidth(panelW, Fonts.px(24f), Fonts.px(24f))

        // Soft dim so the board stays visible behind the card.
        batch.setColor(AppPalette.scrimLight)
        batch.draw(px, 0f, 0f, screenW, screenH)

        // Card body + ivory rim (matches claim / confirm chrome).
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val hoverAction =
            actionAt(body.hoverX(pointerWorldX), body.hoverY(pointerWorldY))

        body.drawBody(batch, camera, px, pointerWorldX, pointerWorldY) {
            var itemTop = body.contentTop
            drawHeader(
                batch = batch,
                px = px,
                titleFont = titleFont,
                subtitleFont = subtitleFont,
                identityFont = identityFont,
                title = title,
                subtitle = subtitle,
                badgeText = badgeText,
                signedInLabel = signedInLabel,
                signIn = signIn,
                badgeShown = badgeShown,
                showNotices = showNotices,
                hasUnseenNotices = hasUnseenNotices,
                headerTop = itemTop,
                headerRowH = headerRowH,
                titleLineH = titleLineH,
                titleBlockH = titleBlockH,
                subtitleLineH = subtitleLineH,
                subtitleGap = subtitleGap,
                badgeW = badgeW,
                badgeH = badgeH,
                badgePadX = badgePadX,
                badgeTextW = badgeTextW,
                chevronSize = chevronSize,
                chevronGap = chevronGap,
                isChevronShown = isChevronShown,
                noticesBtnSize = noticesBtnSize,
                noticesBtnGap = noticesBtnGap,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
                padX = padX,
                itemFont = itemFont,
            )
            itemTop -= headerBlockH
            for (item in items) {
                val bottom = itemTop - itemH
                if (bottom < body.bottom + body.height && itemTop > body.bottom) {
                    drawRow(
                        batch = batch,
                        px = px,
                        item = item,
                        bottom = bottom,
                        hovered = item.action == hoverAction,
                        iconSize = iconSize,
                        iconGap = iconGap,
                    )
                }
                itemTop = bottom - itemGap
            }
        }

        if (showIdentity && accountDropdownOpen) {
            drawDropdown(batch, px, itemFont, pointerWorldX, pointerWorldY)
        }
    }

    private fun drawHeader(
        batch: SpriteBatch,
        px: Texture,
        titleFont: BitmapFont,
        subtitleFont: BitmapFont,
        identityFont: BitmapFont,
        title: String,
        subtitle: String?,
        badgeText: String,
        signedInLabel: String?,
        signIn: MenuSignIn,
        badgeShown: Boolean,
        showNotices: Boolean,
        hasUnseenNotices: Boolean,
        headerTop: Float,
        headerRowH: Float,
        titleLineH: Float,
        titleBlockH: Float,
        subtitleLineH: Float,
        subtitleGap: Float,
        badgeW: Float,
        badgeH: Float,
        badgePadX: Float,
        badgeTextW: Float,
        chevronSize: Float,
        chevronGap: Float,
        isChevronShown: Boolean,
        noticesBtnSize: Float,
        noticesBtnGap: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
        padX: Float,
        itemFont: BitmapFont,
    ) {
        val headerBottom = headerTop - headerRowH
        val titleBlockTop = headerTop
        glyphLayout.setText(titleFont, title)
        val titleY = titleBlockTop - titleLineH + (titleLineH + glyphLayout.height) * 0.5f
        // The title owns the header's first line and centres on the card.
        val titleLeft = panelLeft + (panelW - glyphLayout.width) * 0.5f
        titleFont.draw(batch, title, titleLeft, titleY)

        if (subtitle != null) {
            glyphLayout.setText(subtitleFont, subtitle)
            val subtitleY = titleBlockTop - titleBlockH +
                (subtitleLineH + glyphLayout.height) * 0.5f
            // The subtitle keeps its right edge under the title.
            val subtitleRight = panelLeft + panelW - padX - body.gutter
            Fonts.drawText(
                batch,
                subtitleFont,
                subtitle,
                subtitleRight - glyphLayout.width,
                subtitleY,
                AppPalette.inkMuted,
            )
        }

        if (!badgeShown) {
            // No badge, no dropdown, and no stale rect for the press machine: the
            // chrome card over a live board has no account surface to open, and a
            // build without Google has none to offer.
            identityBadgeLeft = 0f
            identityBadgeBottom = 0f
            identityBadgeW = 0f
            identityBadgeH = 0f
            dropdownRectW = 0f
            dropdownRectH = 0f
            dropdownItems.clear()
        }

        if (!showNotices) {
            noticesButtonLeft = 0f
            noticesButtonBottom = 0f
            noticesButtonW = 0f
            noticesButtonH = 0f
        }

        if (!badgeShown && !showNotices) {
            return
        }

        val badgeBottom = headerBottom

        val rightEdge = panelLeft + panelW - padX - body.gutter
        val btnLeft = if (showNotices) rightEdge - noticesBtnSize else 0f
        val badgeLeft = if (showNotices) btnLeft - noticesBtnGap - badgeW else rightEdge - badgeW

        if (showNotices) {
            val btnBottom = headerBottom
            noticesButtonLeft = btnLeft
            noticesButtonBottom = btnBottom
            noticesButtonW = noticesBtnSize
            noticesButtonH = noticesBtnSize

            val hoveredNotices = isInsideNoticesButton(pointerWorldX, pointerWorldY)
            val bgNotices = when {
                hoveredNotices -> AppPalette.plateHover
                else -> AppPalette.tint(AppPalette.plate, 0.75f)
            }
            val rimNotices = when {
                hoveredNotices -> AppPalette.tint(AppPalette.rim, 0.70f)
                else -> AppPalette.tint(AppPalette.rim, 0.25f)
            }
            OverlayChrome.drawPanel(
                batch, px, btnLeft, btnBottom, noticesBtnSize, noticesBtnSize,
                bgNotices, rimNotices, max(1f, Fonts.px(1.2f)),
            )

            val bellIconSize = max(Fonts.px(16f), noticesBtnSize * 0.58f)
            val bellLeft = btnLeft + (noticesBtnSize - bellIconSize) * 0.5f
            val bellBottom = btnBottom + (noticesBtnSize - bellIconSize) * 0.5f
            val bellColor = if (hoveredNotices) AppPalette.inkStrong else AppPalette.tint(AppPalette.ink, 0.95f)
            batch.setColor(bellColor)
            batch.draw(UiIcons.bell, bellLeft, bellBottom, bellIconSize, bellIconSize)
            batch.setColor(Color.WHITE)

            if (hasUnseenNotices) {
                val dotSize = max(Fonts.px(7f), noticesBtnSize * 0.22f)
                val dotOffset = Fonts.px(3f)
                val dotLeft = btnLeft + noticesBtnSize - dotSize - dotOffset
                val dotBottom = btnBottom + noticesBtnSize - dotSize - dotOffset
                val dotBorder = max(1f, Fonts.px(1f))
                batch.setColor(AppPalette.panelDeep)
                batch.draw(px, dotLeft - dotBorder, dotBottom - dotBorder, dotSize + dotBorder * 2f, dotSize + dotBorder * 2f)
                batch.setColor(AppPalette.danger)
                batch.draw(px, dotLeft, dotBottom, dotSize, dotSize)
                batch.setColor(Color.WHITE)
            }
        }

        if (badgeShown) {
            identityBadgeLeft = badgeLeft
            identityBadgeBottom = badgeBottom
            identityBadgeW = badgeW
            identityBadgeH = badgeH

            val entries = if (signedInLabel != null) {
                listOf(
                    DropdownEntry(Action.Account, L10n.get("menu.account")),
                    DropdownEntry(Action.LogOut, L10n.get("menu.log_out")),
                )
            } else {
                listOf(
                    DropdownEntry(
                        Action.LogIn,
                        L10n.get("menu.log_in_google"),
                        // The special-key row stays usable when this build has no
                        // Google: a review device may have nothing else.
                        enabled = signIn != MenuSignIn.Unavailable,
                    ),
                    DropdownEntry(Action.LogInSpecialKey, L10n.get("menu.log_in_special_key")),
                )
            }
            var maxItemW = 0f
            for (entry in entries) {
                glyphLayout.setText(itemFont, entry.label)
                maxItemW = max(maxItemW, glyphLayout.width)
            }
            val dropdownPadX = Fonts.px(16f)
            val dropdownW = max(badgeW, maxItemW + dropdownPadX * 2f).coerceAtLeast(Fonts.px(140f))
            val rowH = max(Fonts.px(42f), itemFont.lineHeight + Fonts.px(16f))
            val dropdownH = rowH * entries.size
            val dropdownLeft = (badgeLeft + badgeW - dropdownW).coerceAtLeast(panelLeft + padX)
            val dropdownTop = badgeBottom - Fonts.px(4f)
            val dropdownBottom = dropdownTop - dropdownH

            dropdownRectLeft = dropdownLeft
            dropdownRectBottom = dropdownBottom
            dropdownRectW = dropdownW
            dropdownRectH = dropdownH
            dropdownItems.clear()
            var rowTop = dropdownTop
            for (entry in entries) {
                dropdownItems += DropdownRow(
                    entry = entry,
                    rect = Rect(dropdownLeft, rowTop - rowH, dropdownW, rowH),
                )
                rowTop -= rowH
            }

            val hoveredBadge = isInsideIdentityBadge(pointerWorldX, pointerWorldY)
            // Signed in, the badge is a doorway, not a state: accent ink, the drawer's
            // own plate. Signed out it reports the sign-in flow: the resting plate
            // while the attempt is ready or running, and the destructive pair after
            // a failure, to say "that did not work" without inventing a third
            // vocabulary. Pending is not painted inert: it is still the control
            // that reopens a sign-in whose browser tab was lost.
            val bgBadge = when {
                hoveredBadge || accountDropdownOpen -> AppPalette.plateHover
                signedInLabel != null -> AppPalette.tint(AppPalette.plate, 0.75f)
                signIn == MenuSignIn.Failed -> AppPalette.dangerPlate
                else -> AppPalette.tint(AppPalette.plate, 0.90f)
            }
            val rimBadge = when {
                accountDropdownOpen -> AppPalette.tint(AppPalette.accent, 0.90f)
                hoveredBadge -> AppPalette.tint(AppPalette.rim, 0.70f)
                signedInLabel != null -> AppPalette.tint(AppPalette.rim, 0.25f)
                signIn == MenuSignIn.Failed -> AppPalette.dangerRim
                else -> AppPalette.rim
            }
            OverlayChrome.drawPanel(
                batch, px, badgeLeft, badgeBottom, badgeW, badgeH,
                bgBadge, rimBadge, max(1f, Fonts.px(1.2f)),
            )

            val textColor = when {
                signedInLabel != null -> AppPalette.accent
                signIn == MenuSignIn.Failed -> AppPalette.dangerInk
                else -> AppPalette.ink
            }
            val textH = Fonts.textHeight(identityFont, badgeText)
            val textBaseline = badgeBottom + (badgeH + textH) * 0.5f
            Fonts.drawText(batch, identityFont, badgeText, badgeLeft + badgePadX, textBaseline, textColor)

            if (isChevronShown) {
                val chevronTex = if (accountDropdownOpen) UiIcons.chevronUp else UiIcons.chevronDown
                val chevronLeft = badgeLeft + badgePadX + badgeTextW + chevronGap
                val chevronBottom = badgeBottom + (badgeH - chevronSize) * 0.5f
                batch.setColor(textColor)
                batch.draw(chevronTex, chevronLeft, chevronBottom, chevronSize, chevronSize)
                batch.setColor(Color.WHITE)
            }
        }
    }

    private fun drawDropdown(
        batch: SpriteBatch,
        px: Texture,
        itemFont: BitmapFont,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        val border = OverlayChrome.buttonRimPx()
        OverlayChrome.drawPanel(
            batch, px, dropdownRectLeft, dropdownRectBottom, dropdownRectW, dropdownRectH,
            AppPalette.tint(AppPalette.plateDeep, 0.98f),
            AppPalette.tint(AppPalette.panelRim, 0.65f),
            border,
        )

        for (row in dropdownItems) {
            val rect = row.rect
            val entry = row.entry
            val hovered = entry.enabled && rect.contains(pointerWorldX, pointerWorldY)
            val destructive = entry.action == Action.LogOut
            if (hovered) {
                batch.setColor(
                    if (destructive) AppPalette.dangerPlateBright
                    else OverlayChrome.rowFill(hovered = true),
                )
                batch.draw(
                    px,
                    rect.left + border,
                    rect.bottom + border,
                    rect.width - border * 2f,
                    rect.height - border * 2f,
                )
                batch.setColor(Color.WHITE)
            }
            glyphLayout.setText(itemFont, entry.label)
            itemFont.color = when {
                !entry.enabled -> OverlayChrome.inertInk()
                hovered && destructive -> AppPalette.dangerInkBright
                hovered -> AppPalette.inkStrong
                destructive -> AppPalette.dangerInkSoft
                else -> AppPalette.ink
            }
            val textX = rect.left + (rect.width - glyphLayout.width) * 0.5f
            val textY = rect.bottom + (rect.height + glyphLayout.height) * 0.5f
            itemFont.draw(batch, entry.label, textX, textY)
            itemFont.color = Color.WHITE
        }
    }

    private fun drawRow(
        batch: SpriteBatch,
        px: Texture,
        item: MenuItem,
        bottom: Float,
        hovered: Boolean,
        iconSize: Float,
        iconGap: Float,
    ) {
        val itemFont = Fonts.button
        val leadingIcon = item.leadingIcon ?: iconFor(item.action)
        val destructive = item.action == Action.Forfeit
        val bg = when {
            !item.enabled -> OverlayChrome.inertFill()
            destructive && hovered -> AppPalette.dangerPlateBright
            destructive -> AppPalette.dangerPlate
            else -> OverlayChrome.rowFill(hovered)
        }
        val rim = when {
            !item.enabled -> OverlayChrome.inertRim()
            destructive && hovered -> AppPalette.dangerRimBright
            destructive -> AppPalette.dangerRim
            else -> OverlayChrome.rowRim()
        }
        OverlayChrome.drawPanel(
            batch, px, rowLeft, bottom, rowW, itemH, bg, rim, OverlayChrome.buttonRimPx(),
        )

        glyphLayout.setText(itemFont, item.label)
        val textColor = when {
            !item.enabled -> OverlayChrome.inertInk()
            destructive && hovered -> AppPalette.dangerInkBright
            destructive -> AppPalette.dangerInkSoft
            hovered -> AppPalette.inkStrong
            else -> AppPalette.ink
        }

        val contentWRow = if (leadingIcon != null) {
            iconSize + iconGap + glyphLayout.width
        } else {
            glyphLayout.width
        }
        val contentLeft = rowLeft + (rowW - contentWRow) * 0.5f
        val textBaseline = bottom + itemH * 0.5f + glyphLayout.height * 0.5f

        if (leadingIcon != null) {
            val iconBottom = bottom + (itemH - iconSize) * 0.5f
            batch.setColor(textColor)
            batch.draw(leadingIcon, contentLeft, iconBottom, iconSize, iconSize)
            batch.setColor(Color.WHITE)
            itemFont.color = textColor
            itemFont.draw(
                batch,
                item.label,
                contentLeft + iconSize + iconGap,
                textBaseline,
            )
        } else {
            itemFont.color = textColor
            itemFont.draw(
                batch,
                item.label,
                contentLeft,
                textBaseline,
            )
        }
        itemFont.color = Color.WHITE
    }

    /**
     * Place a scrollable menu without [render] (unit tests; no Gdx / fonts).
     */
    internal fun seedLayoutForTest(
        panelLeft: Float = 200f,
        panelBottom: Float = 12f,
        panelW: Float = 400f,
        panelH: Float = 1200f,
        itemH: Float = 144f,
        itemGap: Float = 24f,
        bodyBottom: Float = 32f,
        bodyH: Float = 938f,
        headerBlockH: Float = 0f,
        scrollY: Float = 0f,
        accountDropdownOpen: Boolean = false,
        showIdentity: Boolean = true,
        actions: List<Action> = Action.entries,
    ) {
        // Same rule [render] applies: a frame that draws no identity has no drawer.
        this.accountDropdownOpen = showIdentity && accountDropdownOpen
        this.panelLeft = panelLeft
        this.panelBottom = panelBottom
        this.panelW = panelW
        this.panelH = panelH
        this.itemH = itemH
        this.itemGap = itemGap
        this.headerBlockH = headerBlockH
        items = actions.map { MenuItem(it, it.name) }
        val itemsH = items.size * itemH + (items.size - 1).coerceAtLeast(0) * itemGap
        body.layout(panelLeft, bodyBottom, panelW, bodyH, headerBlockH + itemsH, scrollY)
        rowLeft = panelLeft + 12f
        rowW = panelW - 24f
        noticesButtonLeft = 0f
        noticesButtonBottom = 0f
        noticesButtonW = 0f
        noticesButtonH = 0f
    }

    internal fun seedIdentityForTest(
        signedInLabel: String? = "alice",
        left: Float = 500f,
        bottom: Float = 900f,
        width: Float = 120f,
        height: Float = 40f,
        dropdownWidth: Float = 140f,
        dropdownHeight: Float = 48f,
    ) {
        currentSignedInLabel = signedInLabel
        identityBadgeLeft = left
        identityBadgeBottom = bottom
        identityBadgeW = width
        identityBadgeH = height
        val dLeft = left + width - dropdownWidth
        val dBottom = bottom - 4f - dropdownHeight
        dropdownRectLeft = dLeft
        dropdownRectBottom = dBottom
        dropdownRectW = dropdownWidth
        dropdownRectH = dropdownHeight
        // Two rows, top first. Signed in: Account panel, then log out.
        // Signed out: Google account, then the special key.
        dropdownItems.clear()
        val rowH = dropdownHeight / 2f
        val entries = if (signedInLabel != null) {
            listOf(
                DropdownEntry(Action.Account, L10n.get("menu.account")),
                DropdownEntry(Action.LogOut, L10n.get("menu.log_out")),
            )
        } else {
            listOf(
                DropdownEntry(Action.LogIn, L10n.get("menu.log_in_google")),
                DropdownEntry(Action.LogInSpecialKey, L10n.get("menu.log_in_special_key")),
            )
        }
        dropdownItems += DropdownRow(entries[0], Rect(dLeft, dBottom + rowH, dropdownWidth, rowH))
        dropdownItems += DropdownRow(entries[1], Rect(dLeft, dBottom, dropdownWidth, rowH))
    }

    internal fun seedNoticesForTest(
        left: Float = 630f,
        bottom: Float = 900f,
        width: Float = 40f,
        height: Float = 40f,
    ) {
        noticesButtonLeft = left
        noticesButtonBottom = bottom
        noticesButtonW = width
        noticesButtonH = height
    }

    internal val maxScrollYForTest: Float get() = body.maxScrollYForTest
    internal val overflowingForTest: Boolean get() = body.overflowingForTest

    /**
     * Pixels the card's body is scrolled down (0 = top); the offset the bridge
     * reads after a consumed wheel.
     */
    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Advance the card's body fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    override fun dispose() {
    }

    companion object {
        /**
         * Whether the running platform supports an explicit Quit/Exit action.
         *
         * Exiting the application makes no sense on Web/Wasm ([Application.ApplicationType.WebGL])
         * running inside a browser tab.
         */
        fun canExit(): Boolean {
            val app = Gdx.app ?: return true
            return app.type != Application.ApplicationType.WebGL
        }

        /** Signed-in identity on the title row: `"name - 1482"`, or just the name. */
        fun identityLine(signedInLabel: String?, eloRating: Double?): String? {
            val name = signedInLabel ?: return null
            val rating = MatchHistoryFormat.ratingLabel(eloRating)
            return if (rating != null) L10n.format("menu.signed_in_as", name, rating) else name
        }

        /**
         * [identityLine] cut so it paints within [maxWidth]. The rating stays;
         * only the name takes the ellipsis.
         */
        internal fun fittedIdentityLine(
            font: BitmapFont,
            name: String,
            eloRating: Double?,
            maxWidth: Float,
        ): String {
            val full = identityLine(name, eloRating) ?: name
            if (maxWidth <= 0f) return ""
            if (Fonts.textWidth(font, full) <= maxWidth) return full
            val rating = MatchHistoryFormat.ratingLabel(eloRating)
                ?: return TextFit.ellipsize(font, name, maxWidth)
            val tail = L10n.format("menu.signed_in_as", "", rating)
            val fittedName = TextFit.ellipsize(font, name, maxWidth - Fonts.textWidth(font, tail))
            val formatted = L10n.format("menu.signed_in_as", fittedName, rating)
            return if (Fonts.textWidth(font, formatted) <= maxWidth) {
                formatted
            } else {
                TextFit.ellipsize(font, full, maxWidth)
            }
        }

        internal fun itemsFor(
            context: MenuContext,
            showScoreTable: Boolean = false,
            showForfeit: Boolean = false,
            showContinue: Boolean = false,
            showExit: Boolean = canExit(),
            /** Whether an account is signed in; **Play online** needs one. */
            signedIn: Boolean = false,
        ): List<MenuItem> = buildList {
            when (context) {
                MenuContext.Root -> {
                    // Online play is a signed-in surface: the hub needs an account,
                    // so the row is shown disabled rather than opening a screen
                    // that can only say "log in from the main menu" — which is the
                    // menu the player is already looking at.
                    add(MenuItem(Action.Players, L10n.get("menu.online"), enabled = signedIn))
                    add(MenuItem(Action.PlayAgainstBots, L10n.get("menu.play_against_bots")))
                    add(MenuItem(Action.Tutorial, L10n.get("menu.tutorial")))
                    addSharedUtilityRows()
                    add(MenuItem(Action.About, L10n.get("menu.about")))
                    if (showExit) {
                        add(MenuItem(Action.Exit, L10n.get("menu.exit")))
                    }
                }
                MenuContext.LocalGame -> {
                    if (showContinue) {
                        add(MenuItem(Action.Continue, L10n.get("menu.continue")))
                    }
                    addScoreTableRow(showScoreTable)
                    add(MenuItem(Action.NewGame, L10n.get("menu.new_game")))
                    addSharedUtilityRows()
                    add(MenuItem(Action.ExitToMainMenu, L10n.get("menu.exit_to_main_menu")))
                }
                MenuContext.OnlinePlay -> {
                    addScoreTableRow(showScoreTable)
                    if (showForfeit) {
                        add(MenuItem(Action.Forfeit, L10n.get("menu.forfeit")))
                    }
                    addSharedUtilityRows()
                    add(MenuItem(Action.ExitToMainMenu, L10n.get("menu.exit_to_main_menu")))
                }
                MenuContext.Tutorial -> {
                    addSharedUtilityRows()
                    add(MenuItem(Action.ExitToMainMenu, L10n.get("menu.exit_to_main_menu")))
                }
            }
        }

        private fun MutableList<MenuItem>.addSharedUtilityRows() {
            add(MenuItem(Action.Reference, L10n.get("menu.reference")))
            add(
                MenuItem(
                    action = Action.OpenLanguage,
                    label = LocaleController.languageMenuLabel(),
                ),
            )
            add(MenuItem(Action.Settings, L10n.get("menu.settings")))
        }

        private fun iconFor(action: Action): Texture? = when (action) {
            Action.OpenLanguage -> UiIcons.globe
            else -> null
        }

        private fun MutableList<MenuItem>.addScoreTableRow(showScoreTable: Boolean) {
            if (showScoreTable) {
                add(MenuItem(Action.ScoreTable, L10n.get("menu.score_table")))
            }
        }
    }
}
