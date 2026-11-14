package inego.zungjunggame.ui.stack

/**
 * The two tabs of the unified Online screen.
 *
 * Declared here, beside [SessionKind], because the tab is a navigation fact the
 * global intent vocabulary carries: `input/Intent` may not depend on `features/`,
 * and the offline-open / select-tab commands name the tab they mean. The hub
 * route's wire form keeps the enum's own constant names (`Live` / `ShadowRanked`).
 */
enum class OnlineTab { Live, ShadowRanked }
