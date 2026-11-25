package inego.zungjunggame.features.online.hub

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.ui.stack.OnlineTab
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Online hub route's state codec: round-trip persistence of the hub state.
 */
class OnlineHubCodecTest {

    @Test
    fun roundTripsTheTabBothScrollsAndTheDraft() {
        val state = OnlineHubState(
            tab = OnlineTab.ShadowRanked,
            liveScroll = 12f,
            shadowScroll = 34f,
            draft = GameConfig(rounds = 3, allowChickenHands = true),
        )

        assertEquals(state, OnlineHubCodec.decode(OnlineHubCodec.encode(state)))
    }

    @Test
    fun roundTripsWithNoDraft() {
        val state = OnlineHubState(tab = OnlineTab.Live, liveScroll = 5f)

        assertEquals(state, OnlineHubCodec.decode(OnlineHubCodec.encode(state)))
    }

    @Test
    fun unlimitedDraftRoundTrips() {
        val state = OnlineHubState(
            tab = OnlineTab.ShadowRanked,
            draft = GameConfig.unlimited(allowChickenHands = true),
        )

        assertEquals(state, OnlineHubCodec.decode(OnlineHubCodec.encode(state)))
    }
}
