package inego.zungjunggame.net

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiConfigTest {

    @AfterTest
    fun restoreDefault() {
        ApiConfig.bakeBaseUrl(null)
        ApiConfig.baseUrl = ApiConfig.DEFAULT_BASE_URL
        assertNull(ApiConfig.bakedBaseUrl)
    }

    @Test
    fun `normalize trims and drops comments`() {
        assertEquals("http://192.168.1.10:8080", ApiConfig.normalizeBaseUrl("  http://192.168.1.10:8080/ \n"))
        assertNull(ApiConfig.normalizeBaseUrl(""))
        assertNull(ApiConfig.normalizeBaseUrl("  # comment"))
    }

    @Test
    fun `pick prefers host on the phone subnet`() {
        val urls = listOf(
            "http://10.0.0.5:8080",
            "http://192.168.1.10:8080",
            "http://172.16.1.2:8080",
        )
        assertEquals(
            "http://192.168.1.10:8080",
            ApiConfig.pickBaseUrl("192.168.1.3", urls),
        )
        assertEquals("http://10.0.0.5:8080", ApiConfig.pickBaseUrl(null, urls))
        assertEquals("http://10.0.0.5:8080", ApiConfig.pickBaseUrl("8.8.8.8", urls))
    }

    @Test
    fun `applyDevUrlFile sets baseUrl`() {
        ApiConfig.applyDevUrlFile(
            "http://10.8.0.1:8080\nhttp://192.168.1.10:8080\n",
            "192.168.1.3",
        )
        assertEquals("http://192.168.1.10:8080", ApiConfig.baseUrl)
    }

    @Test
    fun `bakeBaseUrl installs the url and reports success`() {
        assertTrue(ApiConfig.bakeBaseUrl("  https://play.zungjung.org/ \n"))
        assertEquals("https://play.zungjung.org", ApiConfig.bakedBaseUrl)
        assertEquals("https://play.zungjung.org", ApiConfig.baseUrl)
    }

    @Test
    fun `bakeBaseUrl clears on blank input`() {
        ApiConfig.bakeBaseUrl("https://play.zungjung.org")
        assertEquals(false, ApiConfig.bakeBaseUrl(""))
        assertNull(ApiConfig.bakedBaseUrl)
        // Clearing the bake leaves whatever baseUrl was last set.
        assertEquals("https://play.zungjung.org", ApiConfig.baseUrl)
    }

    @Test
    fun `baked url outranks the dev url file`() {
        ApiConfig.bakeBaseUrl("https://play.zungjung.org")
        ApiConfig.applyDevUrlFile(
            "http://10.8.0.1:8080\nhttp://192.168.1.10:8080\n",
            "192.168.1.3",
        )
        assertEquals("https://play.zungjung.org", ApiConfig.baseUrl)
    }

    @Test
    fun samePrefix24() {
        assertTrue(ApiConfig.samePrefix24("192.168.1.10", "192.168.1.3"))
        assertEquals(false, ApiConfig.samePrefix24("192.168.1.10", "192.168.2.3"))
    }

    @Test
    fun `resolveWebBaseUrl respects priority`() {
        // 1. Query parameter takes precedence over global and origin
        assertEquals(
            "http://override.example.com:8080",
            ApiConfig.resolveWebBaseUrl(
                queryParam = "http://override.example.com:8080/",
                configGlobal = "http://127.0.0.1:8080",
                windowOrigin = "https://play.zungjung.org",
            ),
        )

        // 2. Global takes precedence over origin when query param is empty / missing
        assertEquals(
            "http://127.0.0.1:8080",
            ApiConfig.resolveWebBaseUrl(
                queryParam = "  ",
                configGlobal = "http://127.0.0.1:8080/",
                windowOrigin = "https://play.zungjung.org",
            ),
        )

        // 3. Origin is used when query param and global are empty / missing (production mode)
        assertEquals(
            "https://play.zungjung.org",
            ApiConfig.resolveWebBaseUrl(
                queryParam = null,
                configGlobal = "",
                windowOrigin = "https://play.zungjung.org/",
            ),
        )

        // 4. Origin "null" (file:// or sandboxed) falls back to default
        assertEquals(
            ApiConfig.DEFAULT_BASE_URL,
            ApiConfig.resolveWebBaseUrl(
                queryParam = null,
                configGlobal = null,
                windowOrigin = "null",
            ),
        )

        // 5. Explicit custom fallback when all inputs are empty
        assertEquals(
            "http://custom-fallback:8080",
            ApiConfig.resolveWebBaseUrl(
                queryParam = "",
                configGlobal = "",
                windowOrigin = "",
                fallback = "http://custom-fallback:8080/",
            ),
        )
    }
}
