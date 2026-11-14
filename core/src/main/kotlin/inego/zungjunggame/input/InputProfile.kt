package inego.zungjunggame.input

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx

/**
 * How the player is expected to interact with the UI.
 *
 * - [Desktop]: continuous mouse hover, click-to-act, keyboard shortcuts, scroll wheel.
 * - [Touch]: press-and-drag hover, release-to-act (or cancel by releasing off target);
 *   no keyboard bindings / scroll wheel. Holding a hand tile shows the magnified
 *   face above the finger, and a tap on empty felt opens the game menu.
 *
 * Shared hit-testing (discard / claim / HINT) works on both; only pointer
 * timing and desktop accelerators differ.
 *
 * Android and iOS are [Touch]. A web page is [Touch] when its primary pointer
 * is a finger ([webCoarsePointer]); a mouse stays [Desktop].
 */
enum class InputProfile {
    Desktop,
    Touch,
    ;

    /** Continuous mouse cursor hover (desktop). */
    val usesHover: Boolean get() = this == Desktop

    /**
     * Finger-down drag hover: highlight while held, act on release
     * (touch / phone).
     */
    val usesPressHover: Boolean get() = this == Touch

    val usesKeyboard: Boolean get() = this == Desktop
    val usesScrollWheel: Boolean get() = this == Desktop

    companion object {
        /**
         * Set by the web launcher before the app starts. True when the browser's
         * primary pointer is a finger (`pointer: coarse`). A mouse leaves it
         * false. Android and iOS do not read it.
         */
        var webCoarsePointer: Boolean = false

        /** Infer from the running libGDX backend and, on the web, the primary pointer. */
        fun detect(): InputProfile =
            when (Gdx.app?.type) {
                Application.ApplicationType.Android,
                Application.ApplicationType.iOS,
                -> Touch
                Application.ApplicationType.WebGL ->
                    if (webCoarsePointer) Touch else Desktop
                else -> Desktop
            }
    }
}
