package inego.zungjunggame.render

import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.render.online.LatencyLabel
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LatencyLabelTest {
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
    fun colorBandsMatchFeltPalette() {
        assertSame(LatencyLabel.color(0), LatencyLabel.color(LatencyLabel.GOOD_MS - 1))
        assertTrue(LatencyLabel.color(LatencyLabel.GOOD_MS - 1) !== LatencyLabel.color(LatencyLabel.GOOD_MS))
        assertSame(LatencyLabel.color(LatencyLabel.GOOD_MS), LatencyLabel.color(LatencyLabel.FAIR_MS - 1))
        assertTrue(LatencyLabel.color(LatencyLabel.FAIR_MS - 1) !== LatencyLabel.color(LatencyLabel.FAIR_MS))
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
