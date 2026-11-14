package inego.zungjunggame.render.assets

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ManagedGpuResources] is the piece of work that turns "a forced
 * context loss left the app showing felt only" into a recoverable fault.
 *
 * None of it is observable from a JVM unit test — there is no GL context here, and
 * the acceptance proof is G2's forced `loseContext` in a browser. What *is*
 * testable, and what these pin, is the part that is pure bookkeeping and that has
 * already gone wrong once: the registry's own behaviour. A registry that silently
 * loses a step, or that lets one failing step strand the rest, would reproduce the
 * same "nothing on screen" symptom with a different cause, and the browser run
 * would not distinguish them.
 */
class ManagedGpuResourcesTest {

    @BeforeTest
    fun clear() {
        ManagedGpuResources.resetForTests()
    }

    @AfterTest
    fun clearAgain() {
        // The registry is process-global and `AppGraph.create()` populates it for
        // real, so a test that leaks entries would change the behaviour of every
        // later test in this JVM. Both clearups are deliberate.
        ManagedGpuResources.resetForTests()
    }

    @Test
    fun reRegisteringTheSameKeyReplacesRatherThanPilesUp() {
        var first = 0
        var second = 0
        ManagedGpuResources.register("SpriteBatch") { first++ }
        ManagedGpuResources.register("SpriteBatch") { second++ }

        assertEquals(
            1,
            ManagedGpuResources.size,
            "A re-registration must replace, not accumulate: the batch is registered " +
                "from `create()`, and a second registration would mean two shader " +
                "rebuilds per context loss for one batch.",
        )

        ManagedGpuResources.recoverAll("test")
        assertEquals(0, first, "the replaced step must not run")
        assertEquals(1, second)
    }

    @Test
    fun oneFailingStepDoesNotStrandTheRest() {
        var after = 0
        ManagedGpuResources.register("before") { throw IllegalStateException("no GL context") }
        ManagedGpuResources.register("after") { after++ }

        // Must not propagate: the alternative is a frame that paints nothing over one
        // bad step, which is the exact symptom this class exists to remove.
        ManagedGpuResources.recoverAll("test")

        assertEquals(
            1,
            after,
            "A step after a throwing step must still run. If this is red, a single " +
                "recoverable resource failure blinds the whole app again.",
        )
    }

    @Test
    fun anUnregisteredOwnerIsNeverRecovered() {
        var runs = 0
        ManagedGpuResources.register("disposable") { runs++ }
        ManagedGpuResources.unregister("disposable")

        assertEquals(0, ManagedGpuResources.size)
        ManagedGpuResources.recoverAll("test")
        assertEquals(0, runs, "a disposed owner must not be recovered into a dead object")
    }

    @Test
    fun recoveryRunsStepsInRegistrationOrder() {
        val order = mutableListOf<String>()
        ManagedGpuResources.register("shader") { order += "shader" }
        ManagedGpuResources.register("meshes") { order += "meshes" }
        ManagedGpuResources.register("whitePixel") { order += "whitePixel" }

        ManagedGpuResources.recoverAll("test")

        // The shader is rebuilt before the meshes are invalidated because a mesh
        // reallocating its buffers against a dead program is wasted work at best.
        assertEquals(listOf("shader", "meshes", "whitePixel"), order)
    }

    @Test
    fun anEmptyRegistryIsANoOpRatherThanAnError() {
        // `AppGraph.create()` registers at boot, so a real recovery never sees an
        // empty registry — but the coordinator can fire before boot completes, and
        // throwing there would be worse than doing nothing.
        ManagedGpuResources.recoverAll("test")
    }

    @Test
    fun registerLazyTextureDelegatesToTheDropCallback() {
        var drops = 0
        ManagedGpuResources.registerLazyTexture("whitePixel") { drops++ }

        ManagedGpuResources.recoverAll("test")

        assertEquals(
            1,
            drops,
            "an unmanaged texture cannot be re-uploaded in place; recovery drops the " +
                "cached instance so the next draw builds one against the live context.",
        )
    }
}
