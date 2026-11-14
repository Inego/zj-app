package inego.zungjunggame.runtime

import inego.zungjunggame.input.GestureArbiter
import inego.zungjunggame.input.InputEvent
import inego.zungjunggame.input.Intent
import inego.zungjunggame.input.IntentSink
import inego.zungjunggame.input.ResolvedHit
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.GestureHost
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.Transform
import inego.zungjunggame.ui.UiEnvironment
import inego.zungjunggame.ui.stack.ContentPolicy
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.Stack
import inego.zungjunggame.ui.stack.fakeInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CoastingBridge(
    override val id: ComponentId,
    private val coasting: () -> Boolean,
) : GestureBridge<Unit>(
    surface = {
        object : GestureSurface<Unit> {
            override fun onPointerUp(x: Float, y: Float) {}
            override fun scrollCoasting(): Boolean = coasting()
        }
    },
), Component {
    override fun layout(frame: Frame): Layout = Layout()
    override fun draw(frame: Frame, canvas: Canvas) {}
}

private class ClaimingHost(
    override val id: ComponentId,
    private val content: Layout,
) : Component, GestureHost {
    var presses = 0
        private set

    override fun layout(frame: Frame): Layout = content
    override fun draw(frame: Frame, canvas: Canvas) {}
    override fun onGesturePress(x: Float, y: Float, frame: Frame): Boolean {
        presses++
        return true
    }
}

private class CountingComponent(
    override val id: ComponentId,
    private val content: Layout = Layout(),
) : Component {
    var layouts: Int = 0
        private set
    var motions: Int = 0
        private set
    var lastWorldWidth: Float = -1f
        private set
    var lastWorldHeight: Float = -1f
        private set

    override fun motion(frame: Frame) {
        motions++
        lastWorldWidth = frame.constraints.worldWidth
        lastWorldHeight = frame.constraints.worldHeight
    }

    override fun layout(frame: Frame): Layout {
        layouts++
        return content
    }

    override fun draw(frame: Frame, canvas: Canvas) {}
}

private class LookupComponent(
    override val id: ComponentId,
    private val selfKey: RouteKey,
    private val lowerKey: RouteKey,
) : Component {
    var lower: Layout? = null
    var self: Layout? = null

    override fun layout(frame: Frame): Layout {
        lower = frame.layouts.of(lowerKey)
        self = frame.layouts.of(selfKey)
        return Layout()
    }

    override fun draw(frame: Frame, canvas: Canvas) {}
}

private class FakeRegistry : ComponentRegistry {
    var layouts: Map<RouteKey, Layout> = emptyMap()
    var components: Map<RouteKey, Component> = emptyMap()
    var specs: Map<RouteKey, LayerSpec> = emptyMap()
    val actionIntents = mutableMapOf<String, Intent>()
    var outside: Intent? = null

    override fun create(route: Route): Component =
        components[route.key]
            ?: CountingComponent(ComponentId(route.key.value), layouts[route.key] ?: Layout())
    override fun specFor(route: Route): LayerSpec =
        specs[route.key] ?: LayerSpec.defaultsFor(roleFor(route))

    override fun actionToIntent(route: Route, action: ComponentAction): Intent? = actionIntents[action.id]

    override fun outsideIntent(route: Route): Intent? = outside
}

private object FakeUi : UiEnvironment {
    override fun dp(value: Float): Float = value
    override val safeArea: Rect? = null
}

private fun roleFor(route: Route): LayerRole = when (route) {
    is Route.Screen -> LayerRole.Screen
    is Route.Layer -> LayerRole.Context
    is Route.Modal -> LayerRole.Modal
    is Route.Overlay -> LayerRole.Overlay
    is Route.Tooltip -> LayerRole.Tooltip
    is Route.Reference -> LayerRole.Overlay
}

class ComponentHostTest {

    private val constraints = Constraints(100f, 100f, 0f, 0f, 0f)
    private val session = SessionSnapshot(SessionKind.Menu, false, null, null)
    private val home = Route.Screen("home")
    private val confirm = Route.Modal("confirm")
    private val settings = Route.Modal("settings")
    private val banner = Route.Overlay("banner")

    private fun router(registry: FakeRegistry): Router = Router(
        home,
        RegistryComponentFactory(registry),
        RegistrySpecProvider(registry),
    )

    private fun host(
        registry: FakeRegistry,
        router: Router,
        sink: IntentSink = IntentSink {},
    ): ComponentHost = ComponentHost(router, registry, FakeUi, { session }, sink)

    private fun layoutWith(id: String): Layout {
        val regions = HitRegions<ComponentAction>()
        regions.add(Rect(4f, 4f, 2f, 2f), ComponentAction(id))
        return Layout(hitRegions = regions)
    }

    private fun component(stack: Stack, key: RouteKey): CountingComponent =
        stack.find(key)!!.component as CountingComponent

    @Test
    fun appSnapshotCarriesStackAndSession() {
        val registry = FakeRegistry()
        val router = router(registry)
        val snap = host(registry, router).appSnapshot()

        assertSame(router.stack, snap.stack)
        assertSame(session, snap.session)
    }

    @Test
    fun motionAdvancesOnlyThePaintedRange() {
        val registry = FakeRegistry()
        val router = router(registry)
        val host = host(registry, router)
        val homeComponent = component(router.stack, home.key)

        host.motion(constraints.copy(deltaSec = 1f / 60f))
        assertEquals(1, homeComponent.motions)

        // An opaque board covers the floor: the frame paints only the board, so
        // only the board's motion is advanced.
        val board = Route.Layer(RouteIds.LOCAL_BOARD)
        router.push(board)
        host.motion(constraints.copy(deltaSec = 1f / 60f))
        assertEquals(1, homeComponent.motions, "a covered entry is not painted, so it does not advance")
        assertEquals(1, component(router.stack, board.key).motions)

        // A zero-length frame is not a motion step.
        host.motion(constraints)
        assertEquals(1, component(router.stack, board.key).motions)
    }

    @Test
    fun aCoastingScrollAsksForAnotherPresentWithoutAnEmit() {
        val registry = FakeRegistry()
        var coasting = true
        registry.components = mapOf(
            home.key to CoastingBridge(ComponentId(home.key.value)) { coasting },
        )
        val router = router(registry)
        val host = host(registry, router)

        // The release that arms a fling does not emit: the offset is unchanged,
        // and motion already ran. The present decision still has to see the coast.
        assertTrue(host.consumePaintRequest())
        coasting = false
        assertFalse(host.consumePaintRequest(), "a settled scroll does not keep presenting")

        coasting = true
        assertTrue(host.consumePaintRequest())
        router.push(Route.Layer(RouteIds.LOCAL_BOARD))
        assertFalse(
            host.consumePaintRequest(),
            "a coast under an opaque layer is not painted, so it does not keep presenting",
        )
    }

    @Test
    fun motionDeliversProviderSpaceToInSpaceChildren() {
        val registry = FakeRegistry()
        val shell = Route.Layer("shell")
        val lesson = Route.Overlay("lesson")
        val menu = Route.Modal("menu")
        val space = Transform(Rect(10f, 20f, 80f, 40f))
        registry.specs = mapOf(
            shell.key to LayerSpec.defaultsFor(LayerRole.Context)
                .copy(content = ContentPolicy.ProvidesSpace),
            lesson.key to LayerSpec.defaultsFor(LayerRole.Overlay)
                .copy(content = ContentPolicy.InSpace(0)),
        )
        registry.layouts = mapOf(shell.key to Layout(space = space))
        val router = router(registry)
        router.push(shell)
        router.push(lesson)
        router.push(menu)

        host(registry, router).motion(constraints.copy(deltaSec = 1f / 60f))

        val lessonComponent = component(router.stack, lesson.key)
        val menuComponent = component(router.stack, menu.key)
        assertEquals(80f, lessonComponent.lastWorldWidth)
        assertEquals(40f, lessonComponent.lastWorldHeight)
        assertEquals(100f, menuComponent.lastWorldWidth)
        assertEquals(100f, menuComponent.lastWorldHeight)
    }

    @Test
    fun beginFrameLaysOutThePaintRangeOnce() {
        val registry = FakeRegistry()
        val router = router(registry)
        router.push(banner)
        router.push(confirm)

        val host = host(registry, router)
        val frames = host.beginFrame(constraints)

        assertEquals(1, component(router.stack, home.key).layouts)
        assertEquals(1, component(router.stack, banner.key).layouts)
        assertEquals(1, component(router.stack, confirm.key).layouts)

        host.resolve(frames, 50f, 50f)
        assertEquals(1, component(router.stack, home.key).layouts)
    }

    @Test
    fun aCoveredEntryIsNeitherLaidOutNorHitTested() {
        val registry = FakeRegistry()
        val board = Route.Layer(RouteIds.LOCAL_BOARD)
        registry.layouts = mapOf(
            home.key to layoutWith("screen"),
            board.key to layoutWith("board"),
        )
        val router = router(registry)
        router.push(board)

        val host = host(registry, router)
        val frames = host.beginFrame(constraints)

        assertEquals(0, component(router.stack, home.key).layouts)
        assertEquals(1, component(router.stack, board.key).layouts)
        // Input walks the same range: the covered floor's layout is not even built.
        val on = host.resolve(frames, 5f, 5f) as ResolvedHit.On
        assertEquals(board.key, on.instance.route.key)
        assertEquals("board", on.action.id)
    }

    @Test
    fun resolveHitsTopDown() {
        val registry = FakeRegistry()
        registry.layouts = mapOf(
            home.key to layoutWith("screen"),
            confirm.key to layoutWith("modal"),
        )
        val router = router(registry)
        router.push(confirm)
        val host = host(registry, router)

        val on = host.resolve(host.beginFrame(constraints), 5f, 5f) as ResolvedHit.On
        assertEquals(confirm.key, on.instance.route.key)
    }

    @Test
    fun passThroughLayerResolvesInProviderLocalSpace() {
        val registry = FakeRegistry()
        val child = Route.Overlay("child")
        registry.specs = mapOf(
            home.key to LayerSpec.defaultsFor(LayerRole.Screen).copy(content = ContentPolicy.ProvidesSpace),
        )
        registry.layouts = mapOf(
            home.key to Layout(space = Transform(Rect(10f, 10f, 100f, 100f))),
            child.key to layoutWith("child"),
        )
        val router = router(registry)
        router.push(child)
        val host = host(registry, router)

        val on = host.resolve(host.beginFrame(constraints), 15f, 15f) as ResolvedHit.On

        assertEquals(child.key, on.instance.route.key)
        assertEquals("child", on.action.id)
    }

    @Test
    fun reservedSpacePointsFallThroughToTheProvider() {
        val content = Rect(0f, 0f, 100f, 100f)
        val pullTab = Rect(0f, 30f, 12f, 40f)
        val shell = Route.Layer("shell")
        val card = Route.Overlay("card")
        val shellHost = ClaimingHost(
            ComponentId(shell.key.value),
            Layout(panel = content, scrollable = true, space = Transform(content, listOf(pullTab))),
        )
        val cardHost = ClaimingHost(
            ComponentId(card.key.value),
            Layout(panel = content, scrollable = true),
        )
        val registry = FakeRegistry()
        registry.components = mapOf(shell.key to shellHost, card.key to cardHost)
        registry.specs = mapOf(
            shell.key to LayerSpec.defaultsFor(LayerRole.Context)
                .copy(content = ContentPolicy.ProvidesSpace),
            card.key to LayerSpec.defaultsFor(LayerRole.Overlay)
                .copy(content = ContentPolicy.InSpace(0)),
        )
        val router = router(registry)
        router.push(shell)
        router.push(card)
        val host = host(registry, router)
        val frames = host.beginFrame(constraints)
        val sink = GestureArbiter.Sink { x, y, touchStartedOnTarget, suppressMenuOpen -> }

        GestureArbiter().handle(InputEvent.PointerDown(6f, 50f), router.stack, frames, sink)
        assertEquals(1, shellHost.presses, "a press on the pull tab belongs to the shell")
        assertEquals(0, cardHost.presses)

        GestureArbiter().handle(InputEvent.PointerDown(50f, 50f), router.stack, frames, sink)
        assertEquals(1, shellHost.presses)
        assertEquals(1, cardHost.presses, "the rest of the viewport still belongs to the card")

        val open = ClaimingHost(
            ComponentId("shell-open"),
            Layout(panel = content, scrollable = true, space = Transform(content, listOf(content))),
        )
        val covered = ClaimingHost(
            ComponentId("card-covered"),
            Layout(panel = content, scrollable = true),
        )
        val openRegistry = FakeRegistry()
        openRegistry.components = mapOf(shell.key to open, card.key to covered)
        openRegistry.specs = registry.specs
        val openRouter = router(openRegistry)
        openRouter.push(shell)
        openRouter.push(card)
        val openFrames = host(openRegistry, openRouter).beginFrame(constraints)
        GestureArbiter().handle(InputEvent.PointerDown(50f, 50f), openRouter.stack, openFrames, sink)
        assertEquals(1, open.presses, "an open drawer reserves the whole viewport")
        assertEquals(0, covered.presses)
    }

    @Test
    fun pointOutsideScopeDoesNotHitScopedChild() {
        val registry = FakeRegistry()
        val child = Route.Overlay("child")
        registry.specs = mapOf(
            home.key to LayerSpec.defaultsFor(LayerRole.Screen).copy(content = ContentPolicy.ProvidesSpace),
        )
        registry.layouts = mapOf(
            home.key to Layout(space = Transform(Rect(10f, 10f, 100f, 100f))),
            child.key to layoutWith("child"),
        )
        val router = router(registry)
        router.push(child)
        val host = host(registry, router)

        assertNull(host.resolve(host.beginFrame(constraints), 200f, 200f))
    }

    @Test
    fun inputOwningLayerResetsScopeToScreenSpace() {
        val registry = FakeRegistry()
        val modal = Route.Modal("confirm")
        val above = Route.Overlay("above")
        registry.specs = mapOf(
            home.key to LayerSpec.defaultsFor(LayerRole.Screen).copy(content = ContentPolicy.ProvidesSpace),
        )
        registry.layouts = mapOf(
            home.key to Layout(space = Transform(Rect(10f, 10f, 100f, 100f))),
            modal.key to Layout(),
            above.key to layoutWith("above"),
        )
        val router = router(registry)
        router.push(modal)
        router.push(above)
        val host = host(registry, router)

        val on = host.resolve(host.beginFrame(constraints), 5f, 5f) as ResolvedHit.On

        assertEquals(above.key, on.instance.route.key)
        assertEquals("above", on.action.id)
    }

    @Test
    fun dispatchOnMapsActionToIntent() {
        val registry = FakeRegistry()
        val router = router(registry)
        val emitted = mutableListOf<Intent>()
        val host = host(registry, router) { emitted += it }
        val instance = router.stack.top
        registry.actionIntents["tap"] = Intent.Hint

        assertTrue(host.dispatch(ResolvedHit.On(instance, ComponentAction("tap"))))
        assertEquals(listOf<Intent>(Intent.Hint), emitted)

        emitted.clear()
        assertFalse(host.dispatch(ResolvedHit.On(instance, ComponentAction("missing"))))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun dispatchOutsideUsesOutsideIntent() {
        val registry = FakeRegistry()
        val router = router(registry)
        val emitted = mutableListOf<Intent>()
        val host = host(registry, router) { emitted += it }
        val instance = router.stack.top

        registry.outside = Intent.Back
        assertTrue(host.dispatch(ResolvedHit.Outside(instance)))
        assertEquals(listOf<Intent>(Intent.Back), emitted)

        emitted.clear()
        registry.outside = null
        assertFalse(host.dispatch(ResolvedHit.Outside(instance)))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun framesEmitActionsThroughTheRegistry() {
        val registry = FakeRegistry()
        val router = router(registry)
        val emitted = mutableListOf<Intent>()
        val host = host(registry, router) { emitted += it }
        registry.actionIntents["slider"] = Intent.SetMinOtherHumans(2)

        val frames = host.beginFrame(constraints)
        frames.frameFor(router.stack.top).emit(ComponentAction("slider"))

        assertEquals(listOf<Intent>(Intent.SetMinOtherHumans(2)), emitted)
    }

    @Test
    fun dispatchNullEmitsNothing() {
        val registry = FakeRegistry()
        val emitted = mutableListOf<Intent>()
        val host = host(registry, router(registry)) { emitted += it }

        assertFalse(host.dispatch(null))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun motionAndBeginFrameShareTheCallerAssembledModel() {
        var assemblies = 0
        val registry = FakeRegistry()
        val router = router(registry)
        val host = ComponentHost(
            router,
            registry,
            FakeUi,
            { session },
            {},
            model = { _, _ ->
                assemblies++
                AppModel.Absent
            },
        )

        val assembled = host.assembleModel()
        host.motion(constraints.copy(deltaSec = 1f / 60f), assembled)
        host.beginFrame(constraints, assembled)

        assertEquals(1, assemblies)
    }

    @Test
    fun staleModelCannotLayoutAnEntryPushedAfterItWasBuilt() {
        val registry = FakeRegistry()
        val router = router(registry)
        val host = host(registry, router)
        val stale = host.beginFrame(constraints)

        router.push(confirm)
        val pushed = requireNotNull(router.stack.find(confirm.key))

        assertFailsWith<NoSuchElementException> { stale.layoutFor(pushed) }
    }

    @Test
    fun paintLayoutAfterAPushPinsTheSettingsCrash() {
        val registry = FakeRegistry()
        val router = router(registry)
        router.push(banner)
        val host = host(registry, router)

        // beginFrame keys the modeled set from [home, banner] for input. The
        // Settings link then pushes a modal without the input loop relayouting.
        // Paint assembles and lays out fresh, so the pushed entry is present
        // instead of missing (the crash).
        val inputFrames = host.beginFrame(constraints)
        router.push(settings)

        val paintFrames = host.beginFrame(constraints, host.assembleModel())

        assertNotSame(inputFrames, paintFrames)
        paintFrames.layoutFor(requireNotNull(router.stack.find(settings.key)))
        assertEquals(settings.key, router.stack.top.route.key)
    }

    @Test
    fun aHigherEntryReadsTheLowerLayoutAndNeverItsOwn() {
        val registry = FakeRegistry()
        val homeLayout = Layout(panel = Rect(0f, 0f, 100f, 100f))
        registry.layouts = mapOf(home.key to homeLayout)
        val bannerComponent = LookupComponent(ComponentId(banner.key.value), banner.key, home.key)
        registry.components = mapOf(banner.key to bannerComponent)
        val router = router(registry)
        router.push(banner)

        host(registry, router).beginFrame(constraints)

        assertSame(homeLayout, bannerComponent.lower, "the higher entry sees the lower sibling's layout")
        assertNull(bannerComponent.self, "an entry's own layout is not cached while it is laid out")
    }

    @Test
    fun spaceBandsGroupsInSpaceChildrenInStackOrderUpToTheNextProvider() {
        val provider = fakeInstance(
            Route.Layer("shell"),
            role = LayerRole.Context,
            content = ContentPolicy.ProvidesSpace,
        )
        val board = fakeInstance(
            Route.Overlay("board"),
            role = LayerRole.Overlay,
            content = ContentPolicy.InSpace(0),
        )
        val chrome = fakeInstance(Route.Modal("confirm"), role = LayerRole.Modal)
        val coach = fakeInstance(
            Route.Overlay("coach"),
            role = LayerRole.Overlay,
            content = ContentPolicy.InSpace(1),
        )
        val score = fakeInstance(
            Route.Overlay("score"),
            role = LayerRole.Overlay,
            content = ContentPolicy.InSpace(0),
        )
        val inner = fakeInstance(
            Route.Overlay("inner"),
            role = LayerRole.Overlay,
            content = ContentPolicy.ProvidesSpace,
        )
        val orphan = fakeInstance(
            Route.Overlay("orphan"),
            role = LayerRole.Overlay,
            content = ContentPolicy.InSpace(0),
        )
        val entries = listOf(provider, board, chrome, coach, score, inner, orphan)

        val bands = spaceBands(entries, 0)

        assertEquals(listOf(board, score), bands[0])
        assertEquals(listOf(coach), bands[1])
        assertNull(bands[2])
    }

}
