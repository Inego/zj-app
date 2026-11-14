package inego.zungjunggame.app

import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.net.TableTicket
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.session.FakeWebSocketClient
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.session.online.TableTicketRequester
import inego.zungjunggame.ui.*
import inego.zungjunggame.ui.stack.*
import kotlin.test.*

class OnlineConnectSyncTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private val components = ComponentFactory { route -> StubComponent(ComponentId(route.key.value)) }
    private val specs = LayerSpecProvider { route -> RouteSpecs.specFor(route) }

    private val root = Route.Screen(RouteIds.ROOT_MENU)
    private val onlineHub = Route.Layer(RouteIds.ONLINE_HUB)
    private val onlineTable = Route.Layer(RouteIds.ONLINE_TABLE)
    private val onlineConnectKey = Route.Modal(RouteIds.ONLINE_CONNECT).key
    private val gameMenu = Route.Modal(RouteIds.GAME_MENU)

    private fun router(vararg entries: Route): Router {
        val r = Router(entries.first(), components, specs)
        entries.drop(1).forEach { r.push(it) }
        return r
    }

    private fun sampleSnapshot(mySeat: Int = 0): SeatSnapshotWire {
        val seatViews = (0..3).map { s ->
            SeatSnapshotViewWire(
                seat = s,
                concealedCount = 13,
                concealedTiles = if (s == mySeat) listOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9) else null,
                drawnTile = if (s == mySeat) 10 else -1,
                hasDrawnTile = s == mySeat,
                melds = emptyList(),
                river = listOf(15, 16, 17),
                seatWindTile = s,
            )
        }
        return SeatSnapshotWire(
            handNumber = 1,
            mySeat = mySeat,
            eastSeat = 0,
            liveWallSize = 70,
            currentPlayer = mySeat,
            decisionType = "ActOnHand",
            lastDiscarder = null,
            lastDiscarded = null,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = seatViews,
        )
    }

    private fun createOnlineHost(ticketFails: Boolean = false): OnlinePlayHost {
        val ws = FakeWebSocketClient()
        val ticketRequester: TableTicketRequester = if (ticketFails) {
            { _, _, cb -> cb(Result.failure(RuntimeException("ticket refused"))) }
        } else {
            { _, _, cb -> cb(Result.success(TableTicket("ws://test", "tkt", 0))) }
        }
        return OnlinePlayHost(
            tableId = "tbl-test",
            token = "jwt-test",
            wsClient = ws,
            requestTicket = ticketRequester,
        )
    }

    @Test
    fun restoredOnlineTablePushesCoverWithoutAttachedHost() {
        val r = router(root, onlineTable)
        assertFalse(r.isOpen(onlineConnectKey))

        syncOnlineConnect(r, SessionKind.Online, onlineHost = null)

        assertTrue(r.isOpen(onlineConnectKey), "covering card must join when online table is restored")
        assertEquals(onlineConnectKey, r.stack.top.route.key)
    }

    @Test
    fun attachedHostBeforeSeatResolutionKeepsCover() {
        val r = router(root, onlineTable)
        val host = createOnlineHost()
        host.connect()

        syncOnlineConnect(r, SessionKind.Online, host)

        assertTrue(r.isOpen(onlineConnectKey), "cover must stay up while seat is unresolved")
    }

    @Test
    fun seatResolutionPopsCoverRevealingBoard() {
        val r = router(root, onlineTable)
        val host = createOnlineHost()
        host.connect()

        syncOnlineConnect(r, SessionKind.Online, host)
        assertTrue(r.isOpen(onlineConnectKey))

        // First seat snapshot arrives: seat is resolved and table view is ready
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(sampleSnapshot()))
        assertNotNull(host.tableView)

        syncOnlineConnect(r, SessionKind.Online, host)
        assertFalse(r.isOpen(onlineConnectKey), "cover must pop once seat and table view are resolved")
        assertEquals(onlineTable.key, r.stack.top.route.key)
    }

    @Test
    fun reconnectRequiredRepushesCoverOverActiveTable() {
        val r = router(root, onlineTable)
        val host = createOnlineHost(ticketFails = true)
        host.connect()
        repeat(20) { host.advance(1f) }
        assertTrue(host.reconnectRequired)

        syncOnlineConnect(r, SessionKind.Online, host)
        assertTrue(r.isOpen(onlineConnectKey), "reconnect required must push covering card")
    }

    @Test
    fun suppressingModalOccludesCoverWithoutKillingRoute() {
        val r = router(root, onlineTable)

        // Opening game menu before cover: suppressesConnectCard blocks push
        r.push(gameMenu)
        syncOnlineConnect(r, SessionKind.Online, onlineHost = null)
        assertFalse(r.isOpen(onlineConnectKey))

        // Dismissing game menu: next frame pushes connect card
        r.pop()
        syncOnlineConnect(r, SessionKind.Online, onlineHost = null)
        assertTrue(r.isOpen(onlineConnectKey))

        // Pushing game menu over active connect card: connect card route is preserved
        r.push(gameMenu)
        assertEquals(listOf(root.key, onlineTable.key, onlineConnectKey, gameMenu.key), r.stack.entries.map { it.route.key })
        syncOnlineConnect(r, SessionKind.Online, onlineHost = null)
        assertTrue(r.isOpen(onlineConnectKey), "connect card route must not be killed by occluding modal")

        // Closing game menu leaves connect card as top
        r.pop()
        assertEquals(onlineConnectKey, r.stack.top.route.key)
    }

    /**
     * The model's answer to a table that is never adopted (the coordinator's
     * adoption deadline, which its own test drives) is to take the table layer
     * down; the cover follows it out and the hub is what is left. `online.table`
     * is kept, so the hub's Live tab offers Rejoin — the model-driven Retry.
     */
    @Test
    fun adoptionTimeoutRevealsTheHubAndDropsTheCover() {
        val r = router(root, onlineHub, onlineTable)
        syncOnlineConnect(r, SessionKind.Online, onlineHost = null)
        assertTrue(r.isOpen(onlineConnectKey), "the unadopted table shows the cover")

        // The coordinator's deadline popped the table (its test covers the clock);
        // this is the route state the frame after it.
        r.remove(onlineTable.key)
        syncOnlineConnect(r, SessionKind.Menu, onlineHost = null)

        assertFalse(r.isOpen(onlineConnectKey))
        assertEquals(onlineHub.key, r.stack.top.route.key)
    }

    @Test
    fun nonOnlineSessionPopsCover() {
        val r = router(root, onlineTable)
        syncOnlineConnect(r, SessionKind.Online, onlineHost = null)
        assertTrue(r.isOpen(onlineConnectKey))

        // Leaving table context back to menu
        syncOnlineConnect(r, SessionKind.Menu, onlineHost = null)
        assertFalse(r.isOpen(onlineConnectKey))
    }
}
