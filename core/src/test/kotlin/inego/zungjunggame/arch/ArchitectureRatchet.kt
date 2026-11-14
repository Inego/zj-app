package inego.zungjunggame.arch

/**
 * Identifiers for the architecture invariants frozen by [ArchitectureConformanceTest].
 *
 * Each rule is detected by a pure source scan of the main sources; see the test for the
 * exact matcher. Add new rules here (and a matcher + test in the conformance test).
 */
enum class Rule(val description: String) {
    /** `ui/` must not depend on the application shell or any concrete sibling subsystem. */
    UI_PURE(
        "ui/ must not import app, runtime, features, session, render, online, tutorial, platform, " +
            "input or net",
    ),

    /** `state/` must not depend on the application shell or any concrete sibling subsystem. */
    STATE_PURE(
        "state/ must not import app, runtime, features, session, render, online, tutorial, platform, " +
            "input or net",
    ),

    /** `session/` must not depend on concrete render/online/tutorial/features/app types. */
    SESSION_NO_CONCRETE(
        "session/ must not import render., online., tutorial., features. or app.",
    ),

    /** `features/` must not depend on the application shell. */
    FEATURES_NO_APP(
        "features/ must not import app",
    ),

    /** `render/` must not depend on the concrete host/coordinator implementations. */
    RENDER_NO_CONCRETE_HOST(
        "render/ must not import session.OnlinePlayHost, session.FreePlayHost, " +
            "tutorial.TutorialHost or online.OnlineCoordinator",
    ),

    /** The mutable session-kind setter is being retired; nothing may call or declare it. */
    NO_SETTABLE_KIND(
        "no main source may call setKind( or declare fun setKind(",
    ),

    /** Hosts must expose narrow ports rather than a nested `Hooks` interface. */
    NO_HOOKS(
        "session/, online/ and tutorial/ must not declare or implement a Hooks interface",
    ),

    /** A component owns no GL resource, so it must not implement `Disposable`. */
    NO_DISPOSABLE_COMPONENT(
        "no Component may implement com.badlogic.gdx.utils.Disposable",
    ),

    /**
     * A route that hands a local space to the layers above it must be a
     * [SpaceProvider], so composition works through the declared policy.
     */
    PROVIDER_ROUTE_IS_SPACE_PROVIDER(
        "every ContentPolicy.ProvidesSpace route's component must implement ui.SpaceProvider",
    ),

    /** `tutorial/` is render-free: it must not import the feature widgets it once drove. */
    TUTORIAL_NO_FEATURE_WIDGETS(
        "tutorial/ must not import features.tutorial.TutorialCoachOverlay / TutorialQuizOverlay / TutorialShell / features.reference.ReferenceBrowserOverlay",
    ),

    /** `layout` is pure: it must not write a field on a widget or host. */
    NO_LAYOUT_OVERLAY_WRITES(
        "a Component.layout body must not assign to a qualified property",
    ),
}

/**
 * Shrink-only allowlist for [ArchitectureConformanceTest].
 *
 * Every path listed here is a *known, temporarily accepted* violation. The test fails when a
 * listed path no longer violates its rule (stale entry) and when a violation appears that is
 * not listed. During the component-architecture refactor entries must only ever be deleted.
 *
 * Paths are relative to `core/src/main/kotlin/inego/zungjunggame` and use `/` separators,
 * e.g. `session/FreePlayHost.kt`.
 */
object ArchitectureRatchet {
    val allowed: Map<Rule, Set<String>> = mapOf(
        Rule.UI_PURE to emptySet(),
        Rule.STATE_PURE to emptySet(),
        Rule.SESSION_NO_CONCRETE to emptySet(),
        Rule.FEATURES_NO_APP to emptySet(),
        Rule.RENDER_NO_CONCRETE_HOST to emptySet(),
        Rule.NO_SETTABLE_KIND to emptySet(),
        Rule.NO_HOOKS to emptySet(),
        Rule.NO_DISPOSABLE_COMPONENT to emptySet(),
        Rule.PROVIDER_ROUTE_IS_SPACE_PROVIDER to emptySet(),
        Rule.TUTORIAL_NO_FEATURE_WIDGETS to emptySet(),
        Rule.NO_LAYOUT_OVERLAY_WRITES to emptySet(),
    )
}
