package inego.zungjunggame.lwjgl3

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopApiConfigTest {

    @AfterTest
    fun restoreDefault() {
        DesktopApiConfig.install()
    }

    @Test
    fun `parses a baked url`() {
        val text = "# comment\napiBaseUrl=https://play.zungjung.org\n"
        assertEquals("https://play.zungjung.org", DesktopApiConfig.parse(text))
    }

    @Test
    fun `returns null for a blank value`() {
        assertNull(DesktopApiConfig.parse("apiBaseUrl=\n"))
        assertNull(DesktopApiConfig.parse("apiBaseUrl=   \n"))
    }

    @Test
    fun `returns null for the unreplaced placeholder`() {
        // A resource processed without the Gradle filter must not become a URL.
        assertNull(DesktopApiConfig.parse("apiBaseUrl=__API_BASE_URL__\n"))
    }

    @Test
    fun `returns null when the key or text is absent`() {
        assertNull(DesktopApiConfig.parse(null))
        assertNull(DesktopApiConfig.parse("# only a comment\n"))
    }

}
