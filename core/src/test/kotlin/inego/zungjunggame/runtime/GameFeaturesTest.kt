package inego.zungjunggame.runtime

import inego.zungjunggame.ui.ComponentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameFeaturesTest {

    /**
     * GL-free deps: providers are lazy, so install never runs them. [FeatureDeps.table]
     * and [FeatureDeps.hintButtonOverlay] would need GL/assets if any `create` path
     * consumed them; none of [GameFeatures.FEATURE_ROUTES] does, so error stamps make
     * an accidental invocation loud.
     */
    private fun deps(): FeatureDeps = FeatureDeps(
        table = { error("no GL in tests: the board table provider must stay lazy") },
        hintButtonOverlay = { error("no GL in tests: the hint overlay provider must stay lazy") },
    )

    private fun installed(): FeatureRegistry =
        FeatureRegistry().also { GameFeatures.install(it, deps()) }

    @Test
    fun catalogHasNoUncoveredOrDuplicateRoute() {
        val covered = GameFeatures.FEATURE_ROUTES.map(GameFeatures::catalogIdOf)

        assertEquals(RouteIds.all.size, covered.size, "a catalog id is covered twice")
        assertEquals(RouteIds.all.toSet(), covered.toSet(), "a RouteIds constant is uncovered")
    }

    @Test
    fun catalogReflectsEveryRouteIdsConstant() {
        val constants = RouteIds::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()

        assertEquals(constants, RouteIds.all.toSet(), "RouteIds.all must list every constant")
    }

    @Test
    fun featureRoutesAreUnique() {
        val keys = GameFeatures.FEATURE_ROUTES.map { it.key }

        assertEquals(keys.size, keys.toSet().size, "duplicate feature route")
    }

    @Test
    fun everyFeatureRouteBelongsToTheCatalog() {
        for (route in GameFeatures.FEATURE_ROUTES) {
            assertTrue(
                GameFeatures.catalogIdOf(route) in RouteIds.all,
                "route outside RouteIds.all: ${route.key.value}",
            )
        }
    }

    @Test
    fun installRegistersEveryFeatureRoute() {
        val registry = installed()

        for (route in GameFeatures.FEATURE_ROUTES) {
            val component = registry.create(route)
            assertEquals(
                ComponentId(route.key.value),
                component.id,
                "component id for ${route.key.value}",
            )
            assertEquals(
                RouteSpecs.specFor(route),
                registry.specFor(route),
                "layer spec for ${route.key.value}",
            )
        }
    }

    @Test
    fun installRejectsDuplicateRegistration() {
        val registry = installed()

        assertFailsWith<IllegalArgumentException> { GameFeatures.install(registry, deps()) }
    }
}
