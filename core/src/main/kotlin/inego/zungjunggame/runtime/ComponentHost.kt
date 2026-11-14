package inego.zungjunggame.runtime

import inego.zungjunggame.input.FrameModel
import inego.zungjunggame.input.HitResolver
import inego.zungjunggame.input.Intent
import inego.zungjunggame.input.IntentSink
import inego.zungjunggame.input.ResolvedHit
import inego.zungjunggame.session.table.DiscardOrigin
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.FramePointers
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.LayoutLookup
import inego.zungjunggame.ui.SpaceChildren
import inego.zungjunggame.ui.SpaceProvider
import inego.zungjunggame.ui.TextFieldHost
import inego.zungjunggame.ui.Transform
import inego.zungjunggame.ui.UiEnvironment
import inego.zungjunggame.ui.stack.ContentPolicy
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.Stack
import inego.zungjunggame.ui.withLocalSpace
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Runtime glue binding the router, component registry, UI services, and intent
 * sink: per-frame frames, hit→intent dispatch, and the painted entry's ports.
 *
 * The stack's paint range is the one authority for what is up: motion, layout,
 * paint, and input all read it, so an entry that is covered is not modeled at
 * all. The host constructs one [Frame] per entry per phase, so a component never
 * stores its emit channel and a value one entry reported cannot reach another.
 * GL-free except [draw], which explicitly takes a [Canvas].
 */
class ComponentHost(
    private val router: Router,
    private val registry: ComponentRegistry,
    private val ui: UiEnvironment,
    private val session: () -> SessionSnapshot,
    private val intents: IntentSink,
    /**
     * Assembles the frame's immutable [AppModel] from the session snapshot and the
     * frame's drained one-shot paint input. Supplied by the composition root; the
     * host never hardcodes a slice.
     */
    private val model: (SessionSnapshot, DiscardOrigin?) -> AppModel = { _, _ -> AppModel.Absent },
    /**
     * Lifecycle trace: which entries the host restored and how the stack moved.
     *
     * A layer that silently never lights up is otherwise indistinguishable from one
     * that did — the host is the only place that decides — so the cold start names
     * every step it takes here.
     */
    private val log: (String) -> Unit = {},
    /**
     * A one-shot present scheduled [delayMs] from now. The composition root
     * supplies the platform's timer; a headless host has none. This is the
     * deadline wake behind [Frame.wakeAfter], kept separate from
     * [Frame.keepPainting]'s continuous request.
     */
    private val requestFrameAfter: (Long) -> Unit = {},
) {
    val routes: RouteController get() = RouteController(router)

    private var observedStack: Stack = router.stack

    /** A painted entry asked for another present during this frame. */
    private var paintRequested = false

    /**
     * True when this frame's motion or paint still has something to show.
     *
     * A scroll coast is asked for here rather than only through an emit: the
     * pointer-up that arms it runs after [motion], and the release does not
     * change the offset.
     */
    fun consumePaintRequest(): Boolean {
        if (paintedSurfaceCoasting()) paintRequested = true
        return paintRequested.also { paintRequested = false }
    }

    private fun paintedSurfaceCoasting(): Boolean =
        router.stack.paintRange.any { entry ->
            (entry.component as? GestureBridge<*>)?.scrollCoasting() == true
        }

    init {
        router.addObserver { current ->
            val previous = observedStack
            observedStack = current
            onStackChanged(previous, current)
        }
        // Routes can already be on the stack when the host is built (cold-start
        // transitions and persisted-stack restore run before the host exists).
        log("host restore ${keysOf(observedStack)}")
    }

    fun appSnapshot(): AppSnapshot = AppSnapshot(router.stack, session())

    /** Assembles the frame's [AppModel] from the live session and one-shot paint input. */
    fun assembleModel(frameInput: DiscardOrigin? = null): AppModel = model(session(), frameInput)

    /**
     * Advances frame-scoped motion for every painted entry (the same paint range
     * [draw] consumes), before the frame is laid out. A component whose surface
     * has inertia reads its port, advances the widget, and emits the new value
     * through its frame, so the store — never a widget — is what the frame
     * renders from.
     *
     * InSpace children receive the provider's space here, the same rect
     * [beginFrame] and [draw] use. The provider's [Component.layout] is the
     * publisher of that rect, so the host reads it after the provider has
     * advanced and before the children do.
     */
    fun motion(constraints: Constraints, frameModel: AppModel = assembleModel()) {
        // A focused caret blinks on the wall clock, including a frame whose
        // delta was clamped to zero.
        for (entry in router.stack.paintRange) {
            val component = entry.component
            if (component is TextFieldHost && component.textFieldFocused) paintRequested = true
        }
        if (constraints.deltaSec <= 0f) return
        var space: Transform? = null
        for (entry in router.stack.paintRange) {
            val spec = entry.spec
            if (spec.ownsInput) space = null
            val parent = space
            val providesSpace = spec.content is ContentPolicy.ProvidesSpace
            val frame = frameInSpace(entry, constraints, parent, providesSpace, frameModel)
            entry.component.motion(frame)
            if (providesSpace) space = entry.component.layout(frame).space
        }
    }

    /** Lays out the paint range once and returns the frame's cached model. */
    fun beginFrame(constraints: Constraints, frameModel: AppModel = assembleModel()): FrameModel {
        val entries = router.stack.paintRange
        val byComponent = IdentityHashMap<Component, RouteInstance>()
        for (entry in entries) byComponent[entry.component] = entry

        // The pointer belongs to the topmost input-owning layer; every layer below it
        // is occluded and must not read the pointer. Hover is a preview of the hit
        // path, so a covered control that a press could not reach must not highlight:
        // [HitResolver] stops its walk at that same layer, and delivering the raw
        // pointer downward let a card's hover leak onto the menu behind it.
        val inputOwnerIndex = entries.indexOfLast { it.spec.ownsInput }
        val occluded: MutableSet<Component> =
            Collections.newSetFromMap(IdentityHashMap())
        if (inputOwnerIndex > 0) {
            for (i in 0 until inputOwnerIndex) occluded.add(entries[i].component)
        }

        // Active local-space scope (screen space): published by the nearest lower
        // layer whose spec provides one, cleared by an input-owning layer. It comes
        // from [Layout.space], never from [Layout.panel]: a provider's own
        // bounds and the space it hands to the layers above it are different rects.
        var space: Transform? = null

        // NOTE: this closure is stateful — it advances [space] once per modeled
        // entry — so it depends on [FrameModel] building entries in stack order
        // from a fresh model. [byComponent] is the fail-fast for a stale model:
        // an entry pushed after this model was built is not laid out from it.
        //
        // The frame's layout lookup reads the entries already built: the host lays
        // out bottom→top, so a presenter's `layout` sees every lower sibling's
        // published [Layout] and none of the ones above it.
        lateinit var frames: FrameModel
        val layouts = LayoutLookup { key -> frames.layoutOf(key) }
        frames = FrameModel { entry ->
            byComponent.getValue(entry.component)
            val spec = entry.spec
            if (spec.ownsInput) space = null

            val delivered = if (entry.component in occluded) {
                constraints.copy(
                    pointerX = Float.NaN,
                    pointerY = Float.NaN,
                    pointers = FramePointers.EMPTY,
                )
            } else {
                constraints
            }

            val parent = space
            val providesSpace = spec.content is ContentPolicy.ProvidesSpace
            val component = entry.component
            val frame = frameInSpace(
                entry, constraints, parent, providesSpace, frameModel, layouts, delivered,
            )
            val layout = if (parent != null && !providesSpace) {
                component.layout(frame).withLocalSpace(parent)
            } else {
                component.layout(frame)
            }
            if (providesSpace) space = layout.space
            FrameModel.Entry(frame, component, layout)
        }

        for (entry in entries) frames.frameFor(entry)
        return frames
    }

    /**
     * Frame for [entry] in the active space scope: InSpace / Normal children of a
     * provider get that provider's rect as their world; a provider itself (and a
     * layer that just cleared the scope) keeps the caller's constraints.
     */
    private fun frameInSpace(
        entry: RouteInstance,
        constraints: Constraints,
        parent: Transform?,
        providesSpace: Boolean,
        frameModel: Any,
        layouts: LayoutLookup = LayoutLookup { null },
        delivered: Constraints = constraints,
    ): Frame {
        if (parent != null && !providesSpace) {
            val (localX, localY) = parent.toLocal(delivered.pointerX, delivered.pointerY)
                ?: (Float.NaN to Float.NaN)
            return frameFor(
                entry,
                Constraints(
                    worldWidth = parent.rect.width,
                    worldHeight = parent.rect.height,
                    pointerX = localX,
                    pointerY = localY,
                    deltaSec = constraints.deltaSec,
                    safeArea = constraints.safeArea,
                    // The samples follow the same translation the single pointer
                    // gets, so a component inside a scope compares them with the
                    // geometry it laid out in that scope.
                    pointers = delivered.pointers.inSpace(parent),
                ),
                parent,
                frameModel,
                layouts,
            )
        }
        return frameFor(entry, delivered, parent, frameModel, layouts)
    }

    /** One entry's frame: its constraints, space, the app's UI services, and its emit channel. */
    private fun frameFor(
        entry: RouteInstance,
        constraints: Constraints,
        space: Transform?,
        frameModel: Any,
        layouts: LayoutLookup = LayoutLookup { null },
    ): Frame {
        val route = entry.route
        return Frame(
            constraints = constraints,
            env = ui,
            space = space,
            emit = { action ->
                paintRequested = true
                registry.actionToIntent(route, action)?.let { intents.emit(it) }
            },
            layouts = layouts,
            model = frameModel,
            requestPaint = { paintRequested = true },
            requestWake = { delayMs -> requestFrameAfter(delayMs) },
        )
    }

    fun resolve(frames: FrameModel, x: Float, y: Float): ResolvedHit? =
        HitResolver(frames).resolve(router.stack, x, y)

    fun dispatch(hit: ResolvedHit?): Boolean = when (hit) {
        is ResolvedHit.On -> {
            val intent = registry.actionToIntent(hit.instance.route, hit.action)
            intent != null && emit(intent)
        }
        is ResolvedHit.Outside -> {
            val intent = registry.outsideIntent(hit.instance.route)
            intent != null && emit(intent)
        }
        null -> false
    }

    /**
     * Offers [keycode] to the topmost painted entry that owns a focused text
     * field, through that entry's frame. True when the field consumed it; a key
     * it does not consume still reaches the global keyboard bindings.
     */
    fun textKeyDown(frames: FrameModel, keycode: Int): Boolean {
        val entry = router.stack.paintRange.asReversed().firstOrNull { candidate ->
            (frames.componentFor(candidate) as? TextFieldHost)?.textFieldFocused == true
        } ?: return false
        val field = frames.componentFor(entry) as? TextFieldHost ?: return false
        return field.onKeyDown(keycode, frames.frameFor(entry))
    }

    /** Records a stack change. The frame models the new range; nothing is attached. */
    fun onStackChanged(previous: Stack, current: Stack) {
        if (previous.entries.map { it.route.key } != current.entries.map { it.route.key }) {
            log("host stack ${keysOf(previous)} -> ${keysOf(current)}")
        }
    }

    /** One-line stack: the keys bottom→top, which is what a stack trace reads. */
    private fun keysOf(stack: Stack): String =
        stack.entries.joinToString(",") { it.route.key.value }

    /**
     * Paints the paint-range entries bottom→top, reusing [frames]' cached frames
     * and layouts. Requires GL.
     *
     * This is the stack's paint range: painting starts at the topmost
     * [inego.zungjunggame.ui.stack.Presentation.Opaque] layer, so what an opaque
     * layer covers is not painted at all — "renders what is below it" is a
     * property of the stack order rather than of each component — and it is the
     * same range motion, layout, and input read.
     *
     * A [SpaceProvider] is composed rather than drawn: it paints its own pixels
     * around the [inego.zungjunggame.ui.stack.ContentPolicy.InSpace] children of
     * each band, inside its [Layout.space]. The host owns iteration, band
     * membership, and the transform; the provider owns the order.
     */
    fun draw(canvas: Canvas, frames: FrameModel) {
        val entries = router.stack.paintRange
        var providerIndex: Int? = null
        for ((index, entry) in entries.withIndex()) {
            val content = entry.spec.content
            val component = frames.componentFor(entry)
            when (content) {
                is ContentPolicy.ProvidesSpace if component is SpaceProvider -> {
                    providerIndex = index
                    paintEntry(entries, index, frames, canvas)
                }

                is ContentPolicy.InSpace if providerIndex != null -> Unit
                else -> component.draw(frames.frameFor(entry), canvas)
            }
        }
    }

    /**
     * Paints one entry: a provider composes its bands, anything else draws.
     * Recursion is here so a child that is itself a provider composes the same
     * way.
     */
    private fun paintEntry(
        entries: List<RouteInstance>,
        index: Int,
        frames: FrameModel,
        canvas: Canvas,
    ) {
        val entry = entries[index]
        val component = frames.componentFor(entry)
        if (entry.spec.content is ContentPolicy.ProvidesSpace && component is SpaceProvider) {
            component.paintSpace(
                frames.frameFor(entry),
                frames.layoutFor(entry),
                canvas,
                spaceChildren(entries, index, frames, canvas),
            )
        } else {
            component.draw(frames.frameFor(entry), canvas)
        }
    }

    /**
     * The [SpaceChildren] of the provider at [providerIndex]: for one band, the
     * `InSpace` entries above it until the next `ProvidesSpace`, in stack order,
     * painted inside the provider's [Layout.space].
     */
    private fun spaceChildren(
        entries: List<RouteInstance>,
        providerIndex: Int,
        frames: FrameModel,
        canvas: Canvas,
    ): SpaceChildren {
        val space = frames.layoutFor(entries[providerIndex]).space
        val bands = spaceBands(entries, providerIndex)
        return SpaceChildren { band ->
            val members = bands[band] ?: return@SpaceChildren
            canvas.withTransform(space) {
                for (child in members) paintEntry(entries, entries.indexOf(child), frames, canvas)
            }
        }
    }

    private fun emit(intent: Intent): Boolean {
        intents.emit(intent)
        return true
    }
}

/**
 * The [providerIndex] provider's `InSpace` children grouped by band, in stack
 * order, up to the next `ProvidesSpace`. Pure over the declarations, so band
 * membership and order are testable without a canvas.
 */
internal fun spaceBands(
    entries: List<RouteInstance>,
    providerIndex: Int,
): Map<Int, List<RouteInstance>> {
    val bands = HashMap<Int, MutableList<RouteInstance>>()
    for (index in providerIndex + 1 until entries.size) {
        when (val content = entries[index].spec.content) {
            is ContentPolicy.ProvidesSpace -> break
            is ContentPolicy.InSpace -> bands.getOrPut(content.band) { mutableListOf() } += entries[index]
            else -> Unit
        }
    }
    return bands
}
