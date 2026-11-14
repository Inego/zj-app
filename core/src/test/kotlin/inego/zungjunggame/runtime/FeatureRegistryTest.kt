package inego.zungjunggame.runtime

import inego.zungjunggame.input.Intent
import inego.zungjunggame.ui.*
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureRegistryTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private object StubActions : RouteActions {
        override fun toIntent(route: Route, action: ComponentAction): Intent? =
            if (action.id == "go") Intent.Hint else null

        override fun outside(): Intent = Intent.Back
    }

    private val home = Route.Screen("home")

    private fun registry(): FeatureRegistry = FeatureRegistry().apply {
        register(
            routeKey = home.key,
            factory = { route -> StubComponent(ComponentId(route.key.value)) },
            spec = LayerSpec.defaultsFor(LayerRole.Screen),
            actions = StubActions,
        )
    }

    @Test
    fun createBuildsComponentForKey() {
        val component = registry().create(home)

        assertEquals(ComponentId("screen:home"), component.id)
    }

    @Test
    fun duplicateKeyRejected() {
        val registry = registry()

        assertFailsWith<IllegalArgumentException> {
            registry.register(
                routeKey = home.key,
                factory = { route -> StubComponent(ComponentId(route.key.value)) },
                spec = LayerSpec.defaultsFor(LayerRole.Screen),
                actions = StubActions,
            )
        }
    }

    @Test
    fun unknownRouteThrows() {
        val registry = registry()
        val missing = Route.Screen("missing")

        assertFailsWith<IllegalArgumentException> { registry.create(missing) }
        assertFailsWith<IllegalArgumentException> { registry.specFor(missing) }
        assertFailsWith<IllegalArgumentException> {
            registry.actionToIntent(missing, ComponentAction("go"))
        }
        assertFailsWith<IllegalArgumentException> { registry.outsideIntent(missing) }
    }

    @Test
    fun factoryReceivesRouteInstance() {
        val registry = FeatureRegistry().apply {
            register(
                routeKey = RouteKey("modal:confirm"),
                factory = { route -> StubComponent(ComponentId(route.args.getValue("id"))) },
                spec = LayerSpec.defaultsFor(LayerRole.Modal),
                actions = StubActions,
            )
        }

        val component = registry.create(Route.Modal("confirm", mapOf("id" to "from-args")))

        assertEquals(ComponentId("from-args"), component.id)
    }
}
