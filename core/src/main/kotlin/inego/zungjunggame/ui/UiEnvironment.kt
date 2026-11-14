package inego.zungjunggame.ui

/**
 * Injected UI services a component may consult while laying out or drawing.
 *
 * Replaces the ad-hoc static `Fonts` / `L10n` / safe-area access so a component
 * is testable with a fake.
 */
interface UiEnvironment {
    /** Convert design density-independent pixels to world units. */
    fun dp(value: Float): Float

    /** Safe-area inset for the current viewport, or null when unconstrained. */
    val safeArea: Rect?
}
