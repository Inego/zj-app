package inego.zungjunggame.render

import inego.zungjunggame.platform.SafeArea
import kotlin.test.Test
import kotlin.test.assertTrue

class VersionLabelTest {

    @Test
    fun doesNotCrossLeftSafeEdgeWhenLabelIsWide() {
        val place = VersionLabel.placement(
            screenW = 200f,
            textW = 180f,
            textH = 14f,
            safe = SafeArea.Insets(left = 50f, top = 0f, right = 10f, bottom = 0f),
            marginPx = 8f,
        )
        assertTrue(place.left >= 50f + 8f)
    }
}
