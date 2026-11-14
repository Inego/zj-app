package inego.zungjunggame.features.auth

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.Intent
import inego.zungjunggame.net.AliasPolicy
import inego.zungjunggame.net.MAX_ALIAS
import inego.zungjunggame.net.MAX_SPECIAL_KEY
import inego.zungjunggame.platform.LineRequest
import inego.zungjunggame.platform.OverlayTextPrompt
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.StackState

/**
 * How the player supplies one short text credential: a display name, or the
 * special key the Google Play reviewer types.
 *
 * [StackAliasPrompt] is the in-app card (desktop / Wasm): a modal on the stack,
 * typed with a real keyboard.
 * [NativeAliasPrompt] is the OS dialog (touch): no card, IME up, seed selected
 * so the first keystroke replaces the current name.
 *
 * Account code talks only to this type. The composition root picks the
 * implementation from [inego.zungjunggame.input.InputProfile.usesKeyboard].
 */
interface AliasPrompt {
    enum class Kind {
        FirstLogin,
        ChangeName,
        SpecialKey,
        ;

        val routeId: String
            get() = when (this) {
                FirstLogin -> RouteIds.FIRST_LOGIN_ALIAS
                ChangeName -> RouteIds.CHANGE_DISPLAY_NAME
                SpecialKey -> RouteIds.SPECIAL_KEY
            }
    }

    fun show(kind: Kind, seed: String)
    fun hide(kind: Kind)
    fun isOpen(kind: Kind): Boolean
    fun isBusy(kind: Kind): Boolean
    fun text(kind: Kind): String
    fun setBusy(kind: Kind, busy: Boolean)
    fun setError(kind: Kind, message: String)
    /** Native drops the in-app alias routes; the stack card is identity. */
    fun restoredStack(state: StackState): StackState = state
}

/** The card title for [kind]. Display names and the special key share the card. */
internal val AliasPrompt.Kind.titleKey: String
    get() = when (this) {
        AliasPrompt.Kind.SpecialKey -> "special_key.title"
        AliasPrompt.Kind.FirstLogin, AliasPrompt.Kind.ChangeName -> "alias.edit_title"
    }

/** The field label for [kind]. */
internal val AliasPrompt.Kind.fieldKey: String
    get() = when (this) {
        AliasPrompt.Kind.SpecialKey -> "special_key.field"
        AliasPrompt.Kind.FirstLogin, AliasPrompt.Kind.ChangeName -> "alias.field"
    }

/** The submit button for [kind], when no request is in flight. */
internal val AliasPrompt.Kind.submitKey: String
    get() = when (this) {
        AliasPrompt.Kind.SpecialKey -> "special_key.submit"
        AliasPrompt.Kind.FirstLogin, AliasPrompt.Kind.ChangeName -> "alias.save"
    }

/** The submit button for [kind] while a request is in flight. */
internal val AliasPrompt.Kind.busyKey: String
    get() = when (this) {
        AliasPrompt.Kind.SpecialKey -> "special_key.busy"
        AliasPrompt.Kind.FirstLogin, AliasPrompt.Kind.ChangeName -> "alias.saving"
    }

/** UTF-16 units the field holds. */
internal val AliasPrompt.Kind.maxLength: Int
    get() = when (this) {
        AliasPrompt.Kind.SpecialKey -> MAX_SPECIAL_KEY
        AliasPrompt.Kind.FirstLogin, AliasPrompt.Kind.ChangeName -> MAX_ALIAS
    }

/**
 * An edit the field may apply, or null to accept anything that fits the length.
 * A display name carries the shared grapheme/column limits; the opaque special
 * key does not.
 */
internal val AliasPrompt.Kind.acceptEdit: ((before: String, after: String) -> Boolean)?
    get() = when (this) {
        AliasPrompt.Kind.SpecialKey -> null
        AliasPrompt.Kind.FirstLogin, AliasPrompt.Kind.ChangeName -> AliasPolicy::allowsEdit
    }

/**
 * In-app alias card. Visibility is stack membership; the widget owns the
 * draft, busy, and error. Presenting seeds the card with the current name and
 * takes the keyboard; a card already up keeps the player's typing.
 *
 * This is the display-name editor's **CHILD** shape (see `app/RouteModals.kt`):
 * the card is raised over the game-menu card and dismissing it reveals the menu.
 * The push lives here, below `app/`, because only the prompt knows whether this
 * platform uses a card at all ([NativeAliasPrompt] uses the OS dialog instead).
 */
class StackAliasPrompt(
    private val router: () -> Router?,
    private val overlay: AliasCardOverlay,
) : AliasPrompt {
    private fun isRouteOpen(id: String): Boolean =
        router()?.isOpen(Route.Modal(id).key) == true

    private fun push(id: String) {
        router()?.let { if (!it.isOpen(Route.Modal(id).key)) it.push(Route.Modal(id)) }
    }

    private fun remove(id: String) {
        router()?.remove(Route.Modal(id).key)
    }

    override fun show(kind: AliasPrompt.Kind, seed: String) {
        if (isRouteOpen(kind.routeId)) return
        overlay.edit(kind, seed)
        push(kind.routeId)
    }

    override fun hide(kind: AliasPrompt.Kind) {
        remove(kind.routeId)
    }

    override fun isOpen(kind: AliasPrompt.Kind): Boolean = isRouteOpen(kind.routeId)

    override fun isBusy(kind: AliasPrompt.Kind): Boolean =
        isOpen(kind) && overlay.busy

    override fun text(kind: AliasPrompt.Kind): String =
        if (isOpen(kind)) overlay.aliasText else ""

    override fun setBusy(kind: AliasPrompt.Kind, busy: Boolean) {
        if (!isOpen(kind)) return
        overlay.setBusy(busy)
    }

    override fun setError(kind: AliasPrompt.Kind, message: String) {
        if (!isOpen(kind)) return
        overlay.setError(message)
    }
}

/**
 * OS dialog as the whole display-name surface. Never pushes a card. OK is
 * submit; cancel is back. A validation/network error re-opens the dialog
 * with the same seed selected.
 */
class NativeAliasPrompt(
    private val prompt: OverlayTextPrompt,
    private val emit: (Intent) -> Unit,
) : AliasPrompt {
    private var kind: AliasPrompt.Kind? = null
    private var draft: String = ""
    private var busy: Boolean = false
    private var error: String = ""
    private var gen: Int = 0

    override fun restoredStack(state: StackState): StackState {
        val drop = AliasPrompt.Kind.entries.map { it.routeId }.toSet()
        val kept = state.entries.filter { it.id !in drop }
        return if (kept.size == state.entries.size) state else StackState(kept)
    }

    override fun show(kind: AliasPrompt.Kind, seed: String) {
        if (this.kind == kind) return
        hideOpen()
        this.kind = kind
        draft = seed
        busy = false
        error = ""
        requestDialog()
    }

    override fun hide(kind: AliasPrompt.Kind) {
        if (this.kind != kind) return
        hideOpen()
    }

    override fun isOpen(kind: AliasPrompt.Kind): Boolean = this.kind == kind

    override fun isBusy(kind: AliasPrompt.Kind): Boolean = isOpen(kind) && busy

    override fun text(kind: AliasPrompt.Kind): String = if (isOpen(kind)) draft else ""

    override fun setBusy(kind: AliasPrompt.Kind, busy: Boolean) {
        if (!isOpen(kind)) return
        this.busy = busy
        if (busy) error = ""
    }

    override fun setError(kind: AliasPrompt.Kind, message: String) {
        if (!isOpen(kind)) return
        busy = false
        error = message
        requestDialog()
    }

    private fun hideOpen() {
        gen += 1
        kind = null
        busy = false
        error = ""
        prompt.dismiss()
    }

    private fun requestDialog() {
        val open = kind ?: return
        val captured = ++gen
        prompt.request(
            LineRequest(
                title = L10n.get(open.titleKey),
                text = draft,
                hint = error.ifBlank { L10n.get(open.fieldKey) },
                selectAll = true,
                maxLength = open.maxLength,
                acceptEdit = open.acceptEdit,
            ),
        ) { value ->
            if (captured != gen || kind != open) return@request
            if (value == null) {
                emit(open.cancelIntent)
                return@request
            }
            draft = value
            emit(open.submitIntent)
        }
    }

    private val AliasPrompt.Kind.submitIntent: Intent
        get() = when (this) {
            AliasPrompt.Kind.FirstLogin -> Intent.FirstLoginSubmit
            AliasPrompt.Kind.ChangeName -> Intent.SaveDisplayName
            AliasPrompt.Kind.SpecialKey -> Intent.SpecialKeySubmit
        }

    private val AliasPrompt.Kind.cancelIntent: Intent
        get() = when (this) {
            AliasPrompt.Kind.FirstLogin -> Intent.FirstLoginBack
            AliasPrompt.Kind.ChangeName -> Intent.CancelDisplayName
            AliasPrompt.Kind.SpecialKey -> Intent.SpecialKeyCancel
        }
}
