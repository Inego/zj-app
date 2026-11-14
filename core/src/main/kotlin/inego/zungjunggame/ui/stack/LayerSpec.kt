package inego.zungjunggame.ui.stack

import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.RouteStateCodec

/** Declared behavior of one stack layer, derived from its role. */
enum class LayerRole { Screen, Context, Modal, Overlay, Tooltip }

/**
 * How a layer participates in the space stack: where it is laid out, and whether
 * it publishes a space for the layers composed into it.
 */
sealed interface ContentPolicy {
    /** Paints in its parent's space. */
    data object Normal : ContentPolicy

    /** Publishes a space for the layers composed into it. */
    data object ProvidesSpace : ContentPolicy

    /** Composed into the nearest lower [ProvidesSpace] layer, in [band]. */
    data class InSpace(val band: Int = 0) : ContentPolicy
}

/**
 * Single declaration of how a layer participates in paint, input, back,
 * automation, the fundamental felt overlay, and persistence.
 */
data class LayerSpec(
    val role: LayerRole,
    val ownsInput: Boolean,
    /**
     * The **generic stack dismissal**: true when [Router.popDismissible] may pop this layer
     * and everything above it, and when a press inside the layer that misses every
     * control is delivered to the layer's outside intent (the outside-tap gate).
     *
     * It is **not** "this route answers the app's back". The app's back is
     * `RouteActions.back` (`runtime/FeatureRegistry.kt`), an `Intent` resolved
     * top-down by `AppDispatcher.back()`: the board raises the game menu, a confirm
     * answers No, a menu surface hides its card. The two declarations are
     * independent and may disagree:
     * - `dismissOnBack = false` with a non-null `back()`: the board and the menu
     *   surfaces answer back while the primitive leaves them up; the hub and the
     *   away scrim resume.
     * - `dismissOnBack = true` with a null `back()`: an announced card the owner
     *   dismisses rather than back (`STARTING_LEVEL`, `ONLINE_CONNECT`).
     *
     * Never read this flag as "does this route use back?".
     */
    val dismissOnBack: Boolean,
    val blocksAutomation: Boolean,
    /**
     * The session context this layer establishes, or null when it establishes
     * none. [inego.zungjunggame.session.SessionRegistry] reads the topmost non-null
     * one: a board, a lesson, or a table decides what the app is doing; chrome
     * cards and the root menu leave it alone.
     */
    val contextKind: SessionKind?,
    /**
     * Presentation and persistence for this layer — see [LayerPolicy].
     *
     * Every role persists by default: the snapshot written on pause/exit is the
     * whole stack, so every layer the player opened is restored exactly as they
     * left it — a card's content included, since a route's args travel with its
     * entry. The exception is a layer whose content an owner supplies at show
     * time — a connect card, an away scrim, a quiz step — which would come back
     * inert or stale; those opt out by id in
     * [inego.zungjunggame.runtime.RouteSpecs], where the owner that announces them
     * is named.
     */
    val policy: LayerPolicy,
    /**
     * True when this layer's own content **is** the game-menu card — the main menu,
     * and the context menu a game is entered through. No card is ever pushed over
     * such a layer: it would be drawn twice, and dismissing it would leave the menu
     * surface blank.
     */
    val menuSurface: Boolean = false,
    /**
     * True when this layer leaves the fundamental overlay — the felt, and the
     * version stamp in its corner — visible behind itself. False for a layer that
     * paints a full surface of its own (a board, the lesson shell, the reference
     * browser, the rating chart), which supersedes it.
     */
    val onFelt: Boolean = true,
    /**
     * How this layer participates in the space stack: it paints in its parent's
     * space ([ContentPolicy.Normal]), publishes the screen-space
     * [inego.zungjunggame.ui.Layout.space] the layers above it are laid out
     * inside ([ContentPolicy.ProvidesSpace]), or is composed into the nearest
     * lower provider in a band ([ContentPolicy.InSpace]). Band 0 is the content
     * group, band 1 the coach.
     *
     * [ContentPolicy.InSpace] also decides *where* the layer goes:
     * [Router.pushContent] inserts such a layer into its context's content group —
     * above the nearest lower [LayerRole.Context] layer, below any chrome standing
     * over it — whether or not that context provides space. Appending it instead
     * would lay it out in the wrong space *and* put it over a card that is
     * supposed to cover it.
     */
    val content: ContentPolicy = ContentPolicy.Normal,
    /**
     * True when this layer occludes the online connecting card: the card must
     * not be pushed while this layer is up (the legacy `otherModal` list).
     */
    val suppressesConnectCard: Boolean = false,
    /**
     * The codec for this route's player-set state, or null when it keeps none.
     * Retention follows [policy]: `persists = false` keeps the value in the store
     * for the entry's life only (announced content); `persists = true` captures
     * it with the stack and restores it with the entry.
     */
    val state: RouteStateCodec<*>? = null,
    /**
     * True when this route's [state] outlives its entry: each write is mirrored into
     * the remembered store and read back when the route is next created, across
     * restarts. Meaningless without [state]. The Online hub opts in, so it opens on
     * the tab, scrolls and Shadow Ranked draft it last held; the rating chart opts
     * in too, so it opens on the window it last showed, rather than a default.
     */
    val remembersState: Boolean = false,
) {
    companion object {
        /** Sensible defaults for a role; concrete routes override in later stages. */
        fun defaultsFor(role: LayerRole): LayerSpec = when (role) {
            // The floor. The root screen renders the main menu, and it is the one
            // screen: everything else stacks above it.
            LayerRole.Screen -> LayerSpec(
                role = role,
                ownsInput = false,
                dismissOnBack = false,
                blocksAutomation = false,
                contextKind = null,
                policy = LayerPolicy(Presentation.Opaque, persists = true),
            )

            // A context above the floor (local play, online play, the tutorial). It
            // paints the display and owns the input that reaches it, so it supersedes
            // the felt unless it says otherwise — the local play *menu* is a context
            // whose content is the menu card, and it stands on the felt.
            LayerRole.Context -> LayerSpec(
                role = role,
                ownsInput = true,
                dismissOnBack = false,
                blocksAutomation = false,
                contextKind = null,
                policy = LayerPolicy(Presentation.Opaque, persists = true),
                onFelt = false,
            )

            LayerRole.Modal -> LayerSpec(
                role = role,
                ownsInput = true,
                dismissOnBack = true,
                blocksAutomation = true,
                contextKind = null,
                policy = LayerPolicy(Presentation.Below, persists = true),
            )

            LayerRole.Overlay -> LayerSpec(
                role = role,
                ownsInput = false,
                dismissOnBack = false,
                blocksAutomation = false,
                contextKind = null,
                policy = LayerPolicy(Presentation.Below, persists = true),
            )

            LayerRole.Tooltip -> LayerSpec(
                role = role,
                ownsInput = false,
                dismissOnBack = false,
                blocksAutomation = false,
                contextKind = null,
                policy = LayerPolicy(Presentation.Below, persists = false),
            )
        }
    }
}

/** Builds the component for a route. */
fun interface ComponentFactory { fun create(route: Route): Component }

/** Supplies the [LayerSpec] for a route. */
fun interface LayerSpecProvider { fun specFor(route: Route): LayerSpec }
