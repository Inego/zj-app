package inego.zungjunggame.runtime

import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.UiEnvironment

/** libGDX-backed [UiEnvironment] */
class GdxUiEnvironment(
    private val densityScale: Float = 1f,
    private val safeAreaProvider: () -> Rect? = { null },
) : UiEnvironment {

    override fun dp(value: Float): Float = value * densityScale

    override val safeArea: Rect? get() = safeAreaProvider()
}
