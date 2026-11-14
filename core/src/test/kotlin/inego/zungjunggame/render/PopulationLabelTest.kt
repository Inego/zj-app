package inego.zungjunggame.render

import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.render.online.PopulationLabel
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PopulationLabelTest {
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
    fun snapshotFormattingHandlesNullAndValues() {
        assertNull(PopulationLabel.text(null as PresenceSnapshot?))
        val snap = PresenceSnapshot(online = 8, playing = 4, waiting = 1, self = null)
        assertEquals(L10n.format("online.live.population", 8, 4, 1), PopulationLabel.text(snap))
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
