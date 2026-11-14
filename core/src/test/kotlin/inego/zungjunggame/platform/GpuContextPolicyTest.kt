package inego.zungjunggame.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The polarity of [GpuContextPolicy.contextsAreDurable] is the single most
 * mistake-prone fact — it was got wrong twice, and both times
 * silently, because nothing about the type says which way round it is.
 *
 * So it is pinned here, in the one place a reader can check it without hunting
 * through the plan.
 */
class GpuContextPolicyTest {

    @Test
    fun durableIsTrue() {
        // TRUE = the GL objects SURVIVE backgrounding. If this ever reads false,
        // read this again before changing anything: the flag is not "is durable",
        // it is "is volatile".
        assertTrue(DurableGpuContexts.contextsAreDurable)
    }

    @Test
    fun volatileIsFalse() {
        assertFalse(VolatileGpuContexts.contextsAreDurable)
    }

    @Test
    fun theDefaultFactoryIsVolatile() {
        // The safe default: a platform nobody installed rebuilds on a long gap,
        // which is the behaviour that predates the whole seam.
        assertFalse(GpuContextPolicy.create().contextsAreDurable)
    }

    @Test
    fun durableStillHonoursARealEvent() {
        // A durable platform has no recreation event of its own, so the default
        // object is inert — but it must not throw or fire spuriously, because
        // Stage B/W install a *different* policy that does.
        var fired = 0
        val d = DurableGpuContexts.onContextRecreated { fired++ }
        d.dispose()
        assertEquals(0, fired, "the durable default declares no event; it does not invent one")
    }

    @Test
    fun volatileAlsoDeclaresNoEventYet() {
        // `VolatileGpuContexts` is the conservative *default* for a platform
        // nobody has installed a policy for. Android no longer uses it (Stage C
        // installs `AndroidGpuContexts`, which is durable **and** event-backed);
        // it is still here so a headless test or a new target gets today's
        // behaviour rather than a wrong answer. It declares no event, because a
        // platform that has one is exactly the platform that should not be
        // reaching for the inert object.
        var fired = 0
        VolatileGpuContexts.onContextRecreated { fired++ }.dispose()
        assertEquals(0, fired)
    }
}
