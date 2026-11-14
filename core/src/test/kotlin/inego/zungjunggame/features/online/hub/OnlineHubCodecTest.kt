package inego.zungjunggame.features.online.hub

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.ui.stack.OnlineTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Online hub route's state codec: the round trip plus the gates that keep an
 * unreadable tab, scroll, or draft from reaching the widget.
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

    @Test
    fun negativeScrollsAreClampedToZero() {
        val loaded = OnlineHubCodec.decode(encode(liveScroll = -12f, shadowScroll = -1f))

        assertEquals(0f, loaded?.liveScroll)
        assertEquals(0f, loaded?.shadowScroll)
    }

    @Test
    fun unknownTabDecodesAsNull() {
        assertNull(OnlineHubCodec.decode(encode(tab = "Secret")))
    }

    @Test
    fun nonPositiveRoundsDecodeAsNull() {
        assertNull(OnlineHubCodec.decode(encode(draft = true, rounds = "0")))
        assertNull(OnlineHubCodec.decode(encode(draft = true, rounds = "-5")))
    }

    @Test
    fun unknownOrMissingVersionDecodesAsNull() {
        assertNull(OnlineHubCodec.decode(encode(version = OnlineHubCodec.VERSION + 1)))
        assertNull(OnlineHubCodec.decode(""))
        assertNull(OnlineHubCodec.decode("junk"))
    }

    @Test
    fun truncatedAndTrailingFieldsDecodeAsNull() {
        val text = encode()
        assertNull(OnlineHubCodec.decode(text + "1:x"))
        assertNull(OnlineHubCodec.decode(text.substringBeforeLast(':')))
    }

    private fun encode(
        version: Int = OnlineHubCodec.VERSION,
        tab: String = OnlineTab.Live.name,
        liveScroll: Float = 0f,
        shadowScroll: Float = 0f,
        draft: Boolean = false,
        rounds: String = "",
        chicken: Boolean = false,
    ): String {
        val writer = RecordWriter()
            .int(version)
            .field(tab)
            .float(liveScroll)
            .float(shadowScroll)
            .boolean(draft)
        if (draft) {
            writer.field(rounds)
            writer.boolean(chicken)
        }
        return writer.build().toString(Charsets.UTF_8)
    }
}
