package inego.zungjunggame.features.online

import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.features.online.OnlineConnectOverlay.Status
import inego.zungjunggame.session.online.ConnectionFailure
import inego.zungjunggame.session.hosts.ConnectionStatus
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * `statusFor` must never show a connection error to an attached client that is
 * merely waiting for its opponent.
 */
class OnlineConnectOverlayTest {
    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        LocaleController.resetForTests()
        val root = findAssetsRoot()
        L10n.bootstrap(Locale.US, FileHandle(File(root, "i18n/ui")))
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
        LocaleController.resetForTests()
    }

    @Test
    fun connectedWithoutSnapshotIsWaitingForOpponent() {
        assertEquals(
            Status.WaitingForOpponent,
            OnlineConnectOverlay.statusFor(ConnectionStatus.Connected, failure = null),
        )
    }

    @Test
    fun connectedDoesNotReadAsRetrying() {
        val connected = OnlineConnectOverlay.statusFor(ConnectionStatus.Connected, failure = null)
        val disconnected = OnlineConnectOverlay.statusFor(ConnectionStatus.Disconnected, failure = null)
        assertEquals(Status.WaitingForOpponent, connected)
        assertEquals(Status.Rejoining, disconnected)
        assertNotEquals(disconnected, connected)
    }

    @Test
    fun disconnectedWithoutFailureIsRejoiningNotRetrying() {
        assertEquals(
            Status.Rejoining,
            OnlineConnectOverlay.statusFor(ConnectionStatus.Disconnected, failure = null),
        )
    }

    /**
     * The model has no host while a restored table waits to be adopted: the card
     * must read as connecting, not as a rejoin the local fallback host invented.
     */
    @Test
    fun noHostIsConnectingNeverRejoining() {
        assertEquals(
            Status.Connecting,
            OnlineConnectOverlay.statusFor(connectionStatus = null, failure = null),
        )
        assertEquals(
            Status.Connecting,
            OnlineConnectOverlay.statusFor(ConnectionStatus.Local, failure = null),
        )
    }

    /**
     * A transient state must never wear a terminal failure's headline. Asserted on
     * the key mapping, not the wording, so the check cannot be masked by the copy.
     */
    @Test
    fun transientStatusesDoNotShareATerminalTitle() {
        val terminal = listOf(
            Status.ReconnectRequired,
            Status.AuthExpired,
            Status.TableGone,
        ).map { OnlineConnectOverlay.titleKey(it) }
        for (status in listOf(
            Status.Connecting,
            Status.Rejoining,
            Status.WaitingForOpponent,
            Status.Retrying,
        )) {
            assertFalse(OnlineConnectOverlay.titleKey(status) in terminal, status.name)
        }
    }

    /**
     * One mapping, one key per status, and every key resolves in the bundle. The
     * structural sweep replaces the per-status wording mirror.
     */
    @Test
    fun everyStatusHasItsOwnResolvableTitleKey() {
        val keys = Status.entries.map { OnlineConnectOverlay.titleKey(it) }
        assertEquals(keys.size, keys.toSet().size, "each status needs a distinct title")
        for (key in keys) {
            assertNotEquals(key, L10n.get(key), "$key is missing from the bundle")
        }
    }

    @Test
    fun unreachableFailureKeepsRetryingUntilTicketRecovers() {
        assertEquals(
            Status.Retrying,
            OnlineConnectOverlay.statusFor(
                ConnectionStatus.Disconnected,
                ConnectionFailure.Unreachable,
            ),
        )
        // Unreachable is sticky for the lifetime of the host, but the armed retry
        // moves the status on; it must not fall through to the waiting copy.
        assertEquals(
            Status.Rejoining,
            OnlineConnectOverlay.statusFor(
                ConnectionStatus.Reconnecting,
                ConnectionFailure.Unreachable,
            ),
        )
    }

    @Test
    fun terminalFailuresOutrankConnectionStatus() {
        for (status in ConnectionStatus.entries) {
            assertEquals(
                Status.AuthExpired,
                OnlineConnectOverlay.statusFor(
                    status,
                    ConnectionFailure.Authentication,
                ),
            )
            assertEquals(
                Status.TableGone,
                OnlineConnectOverlay.statusFor(
                    status,
                    ConnectionFailure.TableUnavailable,
                ),
            )
        }
    }

    @Test
    fun connectingAndRejoiningMapToTheirOwnStates() {
        assertEquals(
            Status.Connecting,
            OnlineConnectOverlay.statusFor(ConnectionStatus.Connecting, failure = null),
        )
        assertEquals(
            Status.Rejoining,
            OnlineConnectOverlay.statusFor(ConnectionStatus.Reconnecting, failure = null),
        )
        assertEquals(
            Status.ReconnectRequired,
            OnlineConnectOverlay.statusFor(ConnectionStatus.ReconnectRequired, failure = null),
        )
    }

    private fun findAssetsRoot(): File {
        val cwd = File(".").canonicalFile
        return listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
            File(cwd, "../assets"),
        ).first { File(it, "i18n/ui.properties").isFile }
    }
}
