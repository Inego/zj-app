package inego.zungjunggame.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import inego.zungjunggame.platform.AndroidGpuContexts

/**
 * Stage C1: Android is declared durable **and** has a real loss signal, and the
 * launcher wires both.
 *
 * ### The two halves cannot be shipped apart
 *
 * This is the most important claim the file makes, and it is why the launcher
 * assertions are here at all:
 *
 *  - **durable with no event is blind.** `contextsAreDurable = true` switches off
 *    the frame-gap rebuild *and* the resume rebuild. If the platform's only
 *    recovery were the gap heuristic, a real EGL loss would draw nothing and
 *    nothing would ask for a frame. The web hit exactly that failure during
 *    verification: a forced `loseContext` sat unserved for **40 s** until a
 *    synthetic mouse move scheduled a frame (plans/06 §7).
 *  - **an event on a volatile platform pays twice anyway.** The `resume` request
 *    still fires, *and* after Stage G the rebuild compiles a new batch shader and
 *    abandons the live one — `rebuildFaces` runs `ManagedGpuResources` for every
 *    reason except `new runtime`. So the notify would arrive at a platform that
 *    still rebuilds on every Home press: correct, and pointless.
 *
 * Hence the launcher install and the graphics subclass are one change, and this
 * file fails if either is reverted alone.
 *
 * ### Why this file reads the launcher instead of running it
 *
 * Same concession [DesktopGpuContextPolicyTest] makes, and for the same reason:
 * the install and the `createGraphics` override are side effects of `onCreate`,
 * not functions anything can call. `:core:test` is a JVM test with no device, so
 * the only way to pin a launcher is to read it. That is a real limitation, and it
 * is why C2 exists: the two Honor runs are what prove the wiring delivers a
 * `contextRecreated`, and a source-shape test cannot stand in for them.
 *
 * ### What a revert costs, per half
 *
 * Dropping the durable install restores the 438–493 ms glyph rebuild on every
 * background/return (A5, three cycles) — a hitch the user sees. Dropping the
 * subclass while keeping durable is worse and quieter: a real loss then has no
 * path at all and the app draws felt forever. Both failures are silent, which is
 * exactly why they are pinned here rather than left to the plan.
 */
class AndroidGpuContextPolicyTest {

    private fun launcherSource(): String {
        val file = File("../android/src/main/java/inego/zungjunggame/android/AndroidLauncher.java")
        assertTrue(file.exists(), "expected the Android launcher (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    /**
     * The launcher's own code, with block and line comments stripped.
     *
     * A source-shape assertion has to look at code, or it asserts on prose — the
     * same trap that caught the Stage E3 bail-path scan matching
     * `scheduleNextPresent` inside its own explanatory comment.
     */
    private fun codeOnly(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//.*"""), "")

    // ---- the policy object itself (this part IS runnable) --------------------------

    @Test
    fun theAndroidPolicyIsDurable() {
        assertTrue(
            AndroidGpuContexts().contextsAreDurable,
            "C1: Android is durable. libGDX calls setPreserveEGLContextOnPause(true) " +
                "in the AndroidGraphics constructor, so a Home press suspends the GL " +
                "thread without destroying the EGL context (plans/06 §6 Q6, confirmed " +
                "on the Honor: PID and ActivityRecord unchanged). If this is red the " +
                "438-493 ms resume rebuild of A5 is back on every background/return.",
        )
    }

    @Test
    fun aDurableAndroidPlatformDoesNotRebuildOnResume() {
        // The consequence of the flag above, asserted through the same function the
        // runtime uses, so the two cannot drift.
        assertFalse(
            shouldRequestOnResume(AndroidGpuContexts().contextsAreDurable),
            "C1: with contextsAreDurable = true, `resume()` must ask for nothing. " +
                "This is the gate Stage A installed and B/W activated; on Android it " +
                "is what removes the per-cycle rebuild.",
        )
    }

    @Test
    fun androidIgnoresAFrameGapButStillHonoursTheSurfaceEvent() {
        // Both halves of the durable install, at the level they are decided.
        // `renderFrame` passes `gapTriggers = !contextsAreDurable`.
        val t0 = 1_000_000_000L
        val durable = GpuRecoveryCoordinator(FrameStallRecovery(1500L))
        durable.consume(t0, gapTriggers = false)
        assertFalse(
            durable.consume(t0 + 5_000_000_000L, gapTriggers = false).rebuild,
            "C1: a 5 s gap on Android must not rebuild once it is durable. Main " +
                "turns continuous rendering off, so an idle menu produces such gaps " +
                "by design and the threshold cannot tell idleness from a loss.",
        )

        val loss = GpuRecoveryCoordinator(FrameStallRecovery(1500L))
        loss.consume(t0, gapTriggers = false)
        loss.request("contextRecreated")
        assertTrue(
            loss.consume(t0 + 16_000_000L, gapTriggers = false).rebuild,
            "C1: the second onSurfaceCreated must still rebuild with the gap path " +
                "off. This is the repair path a real EGL loss depends on.",
        )
    }

    @Test
    fun aSurfaceRecreationFiresEverySubscriberOnce() {
        val contexts = AndroidGpuContexts()
        var a = 0
        var b = 0
        contexts.onContextRecreated { a++ }
        contexts.onContextRecreated { b++ }
        assertEquals(2, contexts.subscriberCount)

        contexts.onSurfaceRecreated()
        contexts.onSurfaceRecreated()
        assertEquals(2, a, "each onSurfaceCreated re-entry reports once per subscriber")
        assertEquals(2, b)
    }

    @Test
    fun aDisposedSubscriptionIsNotCalledAgain() {
        // The render loop disposes the subscription with its scope. A stale one
        // that outlived it would resurrect a cleared flag and request a rebuild
        // into a runtime that no longer exists.
        val contexts = AndroidGpuContexts()
        var fired = 0
        val handle = contexts.onContextRecreated { fired++ }
        contexts.onSurfaceRecreated()
        handle.dispose()
        contexts.onSurfaceRecreated()
        assertEquals(1, fired)
        assertEquals(0, contexts.subscriberCount)
    }

    @Test
    fun oneFailingSubscriberDoesNotStrandTheOthers() {
        // A context loss is not a moment to lose the GL thread over. The web's
        // `WebGpuContextSignal` has the same guard for the same reason: a thrown
        // subscriber there would kill the BrowserFiber mid-notify.
        val contexts = AndroidGpuContexts()
        var survived = 0
        contexts.onContextRecreated { throw IllegalStateException("boom") }
        contexts.onContextRecreated { survived++ }
        contexts.onSurfaceRecreated()
        assertEquals(1, survived, "the subscriber after the failing one still runs")
    }

    // ---- the launcher wiring (source shape, because :core:test has no device) ------
    //
    // These assert *which* policy and *which* graphics object the launcher names,
    // not merely that the words appear somewhere. The weaker form was tried first
    // and passed against all three of the mutations below, because a bare
    // `contains("AndroidGpuContexts")` is still true when the import, the field
    // declaration and a comment all survive the install being reverted.

    /**
     * The policy class the launcher actually hands out, resolved through one
     * indirection.
     *
     * The install is `setFactory(() -> gpuContexts)` over a field, so the name in
     * the lambda is not the class. A version of this that only read the lambda
     * compared `"gpuContexts"` to `"AndroidGpuContexts"` and failed on a correct
     * launcher; the obvious weakening — comparing the *file* for the word — then
     * passed against all three launcher mutations, because the import, the field
     * declaration and the comments all survive a revert.
     *
     * So the lambda's name is followed to its `new X()` assignment, and the
     * indirection is what makes the mutation visible.
     */
    private fun installedPolicy(source: String): String? {
        val code = codeOnly(source)
        val factory = Regex(
            """GpuContextPolicy\s*(?:\.Companion)?\s*\.\s*(?:setFactory|factory\s*=)\s*\(?\s*[^;]*?\(\s*\)\s*->\s*(\w+)""",
        ).find(code)?.groupValues?.get(1) ?: return null
        val inline = Regex("""\b$factory\s*=\s*new\s+(\w+)""").find(code)
            ?: return factory
        return inline.groupValues[1]
    }

    @Test
    fun theAndroidLauncherInstallsTheAndroidPolicy() {
        assertEquals(
            "AndroidGpuContexts",
            installedPolicy(launcherSource()),
            "C1: the launcher must install AndroidGpuContexts. It is durable AND " +
                "event-backed, which is the only combination that is correct: " +
                "DurableGpuContexts is blind to a real loss, and VolatileGpuContexts " +
                "still pays the 438-493 ms resume rebuild on every background/return.",
        )
    }

    @Test
    fun theAndroidLauncherNoLongerMentionsTheVolatilePolicy() {
        // Stated as an absence over the whole file, comments stripped, rather than
        // as a comparison of the resolved name. The failure that actually happens
        // is the old line being *left behind* when the new one is added, and a
        // launcher carrying both compiles and silently takes whichever assignment
        // runs last — so "which name did the regex find" is the wrong question.
        val code = codeOnly(launcherSource())
        assertFalse(
            "VolatileGpuContexts" in code,
            "AndroidLauncher still references VolatileGpuContexts. With two " +
                "installs the app is durable-or-not by statement order, and which " +
                "one survives decides whether a real loss can be repaired at all.",
        )
    }

    @Test
    fun theLauncherInstallsExactlyOnePolicy() {
        // The variant the assertions above cannot see: a *second* install line
        // added below the first. Whichever runs last wins, so the effective
        // platform fact is the last statement, not the first.
        val code = codeOnly(launcherSource())
        val installs = Regex("""GpuContextPolicy\s*(?:\.Companion)?\s*\.\s*(?:setFactory|factory\s*=)""")
            .findAll(code).count()
        assertEquals(1, installs, "the launcher must install the GpuContextPolicy factory exactly once")
    }

    @Test
    fun theLauncherBuildsTheReportingGraphics() {
        // The subclass *existing* is not enough — it must be the one
        // `createGraphics` hands libGDX. Reverting the return to a plain
        // `AndroidGraphics` leaves the whole nested class in the file, so this is
        // the assertion that actually catches it.
        val code = codeOnly(launcherSource())
        val created = code.substringAfter("protected AndroidGraphics createGraphics")
        assertTrue(
            Regex("""return\s+new\s+LossReportingGraphics\s*\(""").containsMatchIn(created),
            "C1: createGraphics must return LossReportingGraphics. A plain " +
                "AndroidGraphics compiles, boots, and never reports a surface " +
                "re-entry — so Android is durable with no repair path, and a real " +
                "loss draws felt forever.",
        )
    }

    @Test
    fun theReportingGraphicsIsWiredToTheInstalledPolicy() {
        // The same object, not a second one. Two instances and the notify lands
        // on a policy nobody subscribed to: durable, event-backed, and deaf.
        val code = codeOnly(launcherSource())
        val created = code.substringAfter("protected AndroidGraphics createGraphics")
        assertTrue(
            Regex("""LossReportingGraphics\s*\([^)]*gpuContexts""").containsMatchIn(created),
            "C1: the graphics object must be handed the installed AndroidGpuContexts " +
                "instance. Constructing a second one would leave the notify reaching " +
                "a policy with no subscriber — the failure C2's log would show as a " +
                "second onSurfaceCreated with no contextRecreated after it.",
        )
    }

    @Test
    fun theLauncherReportsTheSecondSurfaceAndOnlyTheSecond() {
        val code = codeOnly(launcherSource())
        // Where the notify lives: inside the graphics subclass, not in onResume
        // or a pause listener. A notify off the pause path is the thing Stage C
        // replaced — it would fire on every Home press, which is the rebuild this
        // stage exists to delete.
        val override = code.substringAfter("public void onSurfaceCreated")
        assertTrue(
            Regex("""onSurfaceRecreated\s*\(\s*\)""").containsMatchIn(override),
            "C1: the notify must come from onSurfaceCreated. Durable with no notify " +
                "means a lost EGL context is never repaired — the web's own " +
                "verification run left one unserved for 40 s.",
        )
        // The first surface must not notify. `AppRuntime` subscribes from
        // `create()`, which libGDX calls from `onSurfaceChanged` — *after* the
        // first `onSurfaceCreated`. An early notify would race the coordinator's
        // first-writer against `new runtime` and run loss recovery against a batch
        // that was just created: the felt-only boot Stage G gated off.
        assertTrue(
            Regex("""if\s*\(\s*seenSurface\s*\)""").containsMatchIn(override),
            "C1: the notify must be guarded by a seen-surface flag. Notifying the " +
                "first surface is the felt-only-boot regression, and it only shows " +
                "on a device — hence the guard being asserted here as well as C2.",
        )
    }

    @Test
    fun superRunsBeforeTheNotify() {
        // libGDX's own `onSurfaceCreated` calls setupGL and invalidates every
        // managed mesh, texture, shader program and frame buffer. The notify asks
        // for the *rest* — font pages, unmanaged textures, the batch shader — and
        // must not run before that repair is in place.
        val body = codeOnly(launcherSource()).substringAfter("public void onSurfaceCreated")
        val superAt = body.indexOf("super.onSurfaceCreated")
        val notifyAt = body.indexOf("onSurfaceRecreated")
        assertTrue(
            superAt in 0..<notifyAt,
            "C1: super.onSurfaceCreated must run before contexts.onSurfaceRecreated(). " +
                "libGDX repairs the managed objects there; the notify only requests " +
                "the rebuild that follows it.",
        )
    }

    @Test
    fun theColdStartFontRebuildStays() {
        // Stage C replaced the *guess* behind this call, not the call. A second
        // runtime is a new AndroidGraphics, so the seen-surface flag starts clear
        // and the re-entry signal does not fire for it — this request is the only
        // thing covering that case. Deleting it would trade 218 ms of boot for a
        // black-box-text risk on the one platform that can lose a context.
        // (The call lives in AppGraph, not the launcher.)
        val graph = File("../core/src/main/kotlin/inego/zungjunggame/app/AppGraph.kt")
        assertTrue(graph.exists(), "expected AppGraph (cwd=${File(".").absolutePath})")
        assertTrue(
            codeOnly(graph.readText()).contains("requestFontRebuild()"),
            "C1: AppGraph must still call runtime.requestFontRebuild(). A second " +
                "runtime in one process (Android activity recreation) builds a new " +
                "AndroidGraphics, so onSurfaceCreated is silent for it and this is " +
                "the only signal that covers it.",
        )
    }

    // ---- the `new runtime` gate (C1's other half) ---------------------------------

    @Test
    fun aColdStartRepairsNothingButTheSharedPixel() {
        // Both directions of the asymmetry, and they are deliberately opposite:
        //
        //  - `new runtime` must NOT run `ManagedGpuResources`. A live context with
        //    freshly allocated objects has nothing to repair, and invalidating the
        //    batch shader and every mesh is what shipped green felt on **first
        //    load** with no context loss ever firing (Stage G's own regression).
        //  - it MUST still drop the shared `WhitePixel`, which is a process-wide
        //    name and therefore the one object a second runtime in the same
        //    process inherits across a context change.
        assertFalse(
            shouldRecoverManagedGpuResources("new runtime"),
            "a freshly-constructed runtime inherits a LIVE context. Running " +
                "ManagedGpuResources here drops the batch shader and invalidates " +
                "every mesh mid-boot — measured as a felt-only first frame.",
        )
        // The pixel is dropped outside this gate, so the assertion is that the
        // gate cannot be widened to cover it by accident: it must not become
        // "always run recovery".
        assertTrue(
            shouldRecoverManagedGpuResources("contextRecreated"),
            "a real context loss MUST repair the managed objects, or the batch's " +
                "dead shader and mesh names make every draw fail and the frame is " +
                "felt only (Stage G).",
        )
    }
}
