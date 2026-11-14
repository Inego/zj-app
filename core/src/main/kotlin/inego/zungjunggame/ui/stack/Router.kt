package inego.zungjunggame.ui.stack

import inego.zungjunggame.ui.Scope

/**
 * Sole mutation authority for a [Stack].
 *
 * Every mutation rebuilds the immutable stack and notifies every observer
 * exactly once; rejected mutations throw or return false without notifying.
 *
 * The router owns entry lifetimes: every entry it materializes gets a child
 * [Scope], and the scope closes exactly when the entry leaves the stack. A
 * component's entry-bound resources therefore have one owner (the router) and
 * one end (the entry's removal), with no per-component detach hook.
 */
class Router(
    initial: Route,
    private val components: ComponentFactory,
    private val specs: LayerSpecProvider,
    /** Parent of every entry scope; the composition root supplies the app scope. */
    private val scope: Scope = Scope.root("router"),
    /**
     * Called once for each entry that leaves the stack, after its scope closes.
     * The composition root clears the entry's route UI state here, so player-set
     * state lives exactly as long as the entry does.
     */
    private val onEntryRemoved: (RouteKey) -> Unit = {},
) {
    private val observers = mutableListOf<(Stack) -> Unit>()

    var stack: Stack
        private set

    val current: Route get() = stack.top.route

    init {
        val first = instance(initial)
        require(first.spec.role == LayerRole.Screen) { "Initial route must be a Screen" }
        stack = Stack(listOf(first))
    }

    /** Registers [listener] to run once after every successful mutation. */
    fun addObserver(listener: (Stack) -> Unit) {
        observers += listener
    }

    /**
     * Append [route]; fails fast if its key is already present.
     *
     * The root menu is the one screen at index 0 and is never pushed or removed;
     * everything above it is a context layer or a chrome layer.
     */
    fun push(route: Route) {
        require(!stack.contains(route.key)) { "Route already present: ${route.key.value}" }
        require(route !is Route.Screen) { "A screen is the floor, not a layer: ${route.key.value}" }
        update(stack.entries + instance(route))
    }

    /**
     * Insert [route] directly above [key]'s entry, below every layer already up.
     *
     * For a layer that belongs to the composition of the one below it rather than
     * to the chrome stacked over it: the tutorial lesson is the shell's content
     * however much chrome was restored above the shell. Fails fast when [key] is
     * absent, when [route] is already present, and when it is a screen.
     */
    fun pushAbove(key: RouteKey, route: Route) {
        val index = stack.entries.indexOfFirst { it.route.key == key }
        require(index >= 0) { "No entry to insert above: ${key.value}" }
        require(route !is Route.Screen) { "A screen is the floor, not a layer: ${route.key.value}" }
        require(!stack.contains(route.key)) { "Route already present: ${route.key.value}" }
        val entries = stack.entries
        update(entries.take(index + 1) + instance(route) + entries.drop(index + 1))
    }

    /**
     * Insert [route] into the content group of its **context** — above the context's
     * own content and any content already announced into it, below whatever chrome
     * stands over that context.
     *
     * The context is the nearest lower [LayerRole.Context] layer — the surface the
     * player is on: the local board, the online table, the shell, the hub, a context
     * menu (the floor when none is up). Content belongs to that surface whether or not
     * it provides local space and whether or not it establishes a session: a
     * `ContentPolicy.InSpace` layer appended over a restored card (the hand-end
     * ledger over a re-opened score table, in a game whose context publishes no
     * scope) hid that card, because the host takes the covered layer's overlay down
     * while its route stays up.
     *
     * Where a provider *is* up, this is also what keeps the content in the scope it is
     * laid out and painted in — appended, a beat or ledger would be measured in world
     * space while painted inside the content zone (an input-owning layer between them
     * clears the scope) and land shifted by the zone's origin.
     *
     * The provider's *own* content is not this call's business: a layer that the
     * provider hosts directly is anchored to the provider itself ([pushAbove]), so
     * its place does not depend on the order two announcements arrive in.
     */
    fun pushContent(route: Route) {
        require(route !is Route.Screen) { "A screen is the floor, not a layer: ${route.key.value}" }
        require(!stack.contains(route.key)) { "Route already present: ${route.key.value}" }
        val entries = stack.entries
        val context = entries.indexOfLast { it.spec.role == LayerRole.Context }
        // Above the floor when no context is up (the content belongs to the floor's
        // context), then past the content already in that context's group: siblings
        // stack in announcement order and chrome stays on top of all of them. A
        // layer only ever advances past content in the same or a lower band, so a
        // band-0 ledger announced after the band-1 coach still lands *under* it,
        // keeping input order (walked top-down) equal to paint order.
        var insertAt = if (context < 0) 1.coerceAtMost(entries.size) else context + 1
        val band = (specs.specFor(route).content as? ContentPolicy.InSpace)?.band ?: 0
        while (insertAt < entries.size) {
            val existing = entries[insertAt].spec.content
            if (existing !is ContentPolicy.InSpace || existing.band > band) break
            insertAt++
        }
        update(entries.take(insertAt) + instance(route) + entries.drop(insertAt))
    }

    /**
     * Insert [route] directly above the floor, below every layer already up.
     *
     * For a context whose parent is the floor — the online hub, the tutorial
     * shell. Fails fast if its key is already present.
     */
    fun pushAboveFloor(route: Route) = pushAbove(stack.entries.first().route.key, route)

    /** Remove the top entry; false when only the floor remains. */
    fun pop(): Boolean {
        if (stack.entries.size <= 1) return false
        update(stack.entries.dropLast(1))
        return true
    }

    /** Remove every entry strictly above [key]; false when absent or already top. */
    fun popTo(key: RouteKey): Boolean {
        val index = stack.entries.indexOfFirst { it.route.key == key }
        if (index < 0 || index == stack.entries.lastIndex) return false
        update(stack.entries.take(index + 1))
        return true
    }

    /**
     * Remove [key] and every entry above it; false when absent or the floor.
     * Use for an owned layer that must leave even when it is covered.
     */
    fun remove(key: RouteKey): Boolean {
        val index = stack.entries.indexOfFirst { it.route.key == key }
        if (index <= 0) return false
        update(stack.entries.take(index))
        return true
    }

    /**
     * Remove [key] alone; false when absent or the floor.
     * Use for an unowned layer (such as a content overlay) that must leave
     * without tearing down layers standing over it.
     */
    fun removeExact(key: RouteKey): Boolean {
        val index = stack.entries.indexOfFirst { it.route.key == key }
        if (index <= 0) return false
        update(stack.entries.take(index) + stack.entries.drop(index + 1))
        return true
    }

    /**
     * The **generic stack dismissal**: pop the topmost [LayerSpec.dismissOnBack] layer and
     * everything above it; false when none is dismissable.
     *
     * This is not the app's back — that is `AppDispatcher.back()`, which resolves
     * the topmost route's `RouteActions.back` to an `Intent`. See [LayerSpec.dismissOnBack].
     */
    fun popDismissible(): Boolean {
        val index = stack.entries.indices.lastOrNull { i ->
            i > 0 && stack.entries[i].spec.dismissOnBack
        } ?: return false
        update(stack.entries.take(index))
        return true
    }

    fun isOpen(key: RouteKey): Boolean = stack.contains(key)

    fun topSpec(): LayerSpec = stack.top.spec

    /**
     * Persistable snapshot of the current stack: every entry whose layer
     * [LayerPolicy.persists], bottom to top, each carrying the policy it was
     * pushed with and its state text.
     *
     * [stateOf] projects an entry's state text — the route UI store, in the
     * composition root — and the snapshot never reads a component to learn a value.
     *
     * The stack is the record of where the player was, so a layer is dropped only
     * when its policy says the owner announces it and would supply its content
     * again (see `RouteSpecs`); nothing is dropped because of its role.
     */
    fun snapshot(stateOf: (RouteInstance) -> String?): StackState = StackState(
        stack.entries.filter { it.spec.policy.persists }
            .map { it.route.toEntryState(it.spec.policy, stateOf(it)) },
    )

    /**
     * Rebuilds the stack from [state] using the injected [ComponentFactory] and
     * [LayerSpecProvider]. A layer's persisted policy is restored with it, so the
     * entry comes back presenting as it left.
     *
     * Returns true and republishes exactly once on success. Returns false and
     * leaves the current stack untouched when [state] is empty or cannot be
     * materialized (unknown route id, non-Screen floor, duplicate keys). Never
     * throws.
     *
     * Entry state is not applied here: the composition root seeds the route UI
     * store from the incoming stack, and presenters read it lazily, so construction
     * order does not matter.
     */
    fun restore(state: StackState): Boolean {
        if (state.entries.isEmpty()) return false
        val rebuilt = ArrayList<RouteInstance>(state.entries.size)
        try {
            for (entry in state.entries) {
                val route = entry.toRoute()
                if (route == null) {
                    disposeAll(rebuilt)
                    return false
                }
                rebuilt += instance(route)
            }
        } catch (_: IllegalArgumentException) {
            disposeAll(rebuilt)
            return false
        }
        return try {
            update(rebuilt)
            true
        } catch (_: IllegalArgumentException) {
            disposeAll(rebuilt)
            false
        }
    }

    /** Frees entries built for a restore that never reached the stack. */
    private fun disposeAll(entries: List<RouteInstance>) {
        for (entry in entries) entry.scope.dispose()
    }

    /**
     * The entry for [route]: its component, and the spec with the policy this push
     * resolved. A [Route.Layer] may carry a policy of its own — how we got here is
     * part of what the layer is — and everything else uses its declared one.
     */
    private fun instance(route: Route): RouteInstance {
        val declared = specs.specFor(route)
        val policy = (route as? Route.Layer)?.policy ?: declared.policy
        val entryScope = scope.child("route:${route.key.value}")
        val component = try {
            components.create(route)
        } catch (failure: IllegalArgumentException) {
            entryScope.dispose()
            throw failure
        }
        return RouteInstance(route, component, declared.copy(policy = policy), entryScope)
    }

    private fun update(next: List<RouteInstance>) {
        val previous = stack.entries
        stack = Stack(next)
        for (observer in observers.toList()) observer(stack)
        // Lifetimes follow stack membership, and only after the new stack is
        // published: a kept entry keeps its instance and scope, a leaving entry's
        // scope closes here, and no observer ever sees a disposed entry.
        for (entry in previous) {
            if (next.none { it === entry }) {
                entry.scope.dispose()
                onEntryRemoved(entry.route.key)
            }
        }
    }
}
