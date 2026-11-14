package inego.zungjunggame.ui.stack

import inego.zungjunggame.ui.ComponentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RouterTest {

    private val components = ComponentFactory { route -> FakeComponent(ComponentId(route.key.value)) }

    private val specs = LayerSpecProvider { route ->
        when (route) {
            is Route.Screen -> LayerSpec.defaultsFor(LayerRole.Screen)
            is Route.Layer -> LayerSpec.defaultsFor(LayerRole.Context)
            is Route.Modal -> LayerSpec.defaultsFor(LayerRole.Modal)
            is Route.Overlay -> LayerSpec.defaultsFor(LayerRole.Overlay)
            is Route.Reference -> LayerSpec.defaultsFor(LayerRole.Overlay)
            is Route.Tooltip -> LayerSpec.defaultsFor(LayerRole.Tooltip)
        }
    }

    private val home = Route.Screen("home")
    private val confirm = Route.Modal("confirm")
    private val banner = Route.Overlay("banner")
    private val settings = Route.Modal("settings")

    private fun router(changes: MutableList<Stack> = mutableListOf()): Router =
        Router(home, components, specs).apply { addObserver { changes += it } }

    @Test
    fun startsWithSingleScreen() {
        val router = router()
        assertEquals(1, router.stack.entries.size)
        assertEquals(home, router.current)
        assertTrue(router.isOpen(home.key))
        assertEquals(LayerRole.Screen, router.topSpec().role)
    }

    @Test
    fun pushAppendsAndNotifiesOnce() {
        val changes = mutableListOf<Stack>()
        val router = router(changes)
        router.push(confirm)
        assertEquals(listOf(home.key, confirm.key), router.stack.entries.map { it.route.key })
        assertEquals(1, changes.size)
        assertSame(router.stack, changes.single())
    }

    @Test
    fun pushDuplicateKeyFails() {
        val router = router()
        router.push(confirm)
        assertFailsWith<IllegalArgumentException> { router.push(Route.Modal("confirm")) }
    }

    @Test
    fun aRePushInstantiatesAFreshComponent() {
        val router = router()
        router.push(confirm)
        val first = router.stack.entries.last().component
        router.pop()
        router.push(confirm)
        val second = router.stack.entries.last().component
        assertNotSame(first, second)
    }

    @Test
    fun popRemovesTopAndStopsAtFloor() {
        val changes = mutableListOf<Stack>()
        val router = router(changes)
        router.push(confirm)
        changes.clear()

        assertTrue(router.pop())
        assertEquals(listOf(home.key), router.stack.entries.map { it.route.key })
        assertEquals(1, changes.size)

        assertFalse(router.pop())
        assertEquals(1, changes.size)
    }

    @Test
    fun popDismissibleRemovesTopmostDismissibleAndAbove() {
        val router = router()
        router.push(settings)
        router.push(banner)
        router.push(confirm)

        assertTrue(router.popDismissible())
        assertEquals(
            listOf(home.key, settings.key, banner.key),
            router.stack.entries.map { it.route.key },
        )
    }

    @Test
    fun popDismissibleReturnsFalseWithoutDismissibleLayer() {
        val router = router()
        router.push(banner)

        assertFalse(router.popDismissible())
        assertEquals(2, router.stack.entries.size)
    }

    @Test
    fun popToRemovesAboveTarget() {
        val router = router()
        router.push(settings)
        router.push(banner)
        router.push(confirm)

        assertTrue(router.popTo(settings.key))
        assertEquals(listOf(home.key, settings.key), router.stack.entries.map { it.route.key })
    }

    @Test
    fun popToReturnsFalseForTopAndAbsent() {
        val router = router()
        router.push(settings)
        router.push(banner)

        assertFalse(router.popTo(banner.key))
        assertFalse(router.popTo(Route.Modal("missing").key))
        assertEquals(3, router.stack.entries.size)
    }

    @Test
    fun removeDropsTargetAndEverythingAbove() {
        val changes = mutableListOf<Stack>()
        val router = router(changes)
        router.push(settings)
        router.push(banner)
        router.push(confirm)
        changes.clear()

        assertTrue(router.remove(settings.key))
        assertEquals(listOf(home.key), router.stack.entries.map { it.route.key })
        assertEquals(1, changes.size)

        assertFalse(router.remove(settings.key))
        assertFalse(router.remove(home.key))
        assertEquals(1, changes.size)
    }

    @Test
    fun removeExactDropsTargetAloneAndPreservesLayersAbove() {
        val changes = mutableListOf<Stack>()
        val router = router(changes)
        router.push(settings)
        router.push(banner)
        router.push(confirm)
        changes.clear()

        assertTrue(router.removeExact(banner.key))
        assertEquals(listOf(home.key, settings.key, confirm.key), router.stack.entries.map { it.route.key })
        assertEquals(1, changes.size)

        assertFalse(router.removeExact(banner.key))
        assertFalse(router.removeExact(home.key))
        assertEquals(1, changes.size)
    }
}

