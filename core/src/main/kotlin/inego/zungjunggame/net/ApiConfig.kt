package inego.zungjunggame.net

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx

/**
 * Local multiplayer backend. Override [baseUrl] if the server is not on this host.
 *
 * Desktop / Wasm stay on loopback ([DEFAULT_BASE_URL]). A physical phone on WiFi
 * is a LAN client: `:android:install*` / `:android:run` write host LAN URLs to
 * [DEV_URL_FILE] (app-private), and [applyDevUrlFile] picks the one on the
 * phone's subnet. `zj-server` must bind `0.0.0.0:8080` for that path.
 *
 * A build may instead bake a fixed server address (Android `-PapiBaseUrl=...`).
 * [bakeBaseUrl] records it and it then outranks the dev-URL file, so a shipped
 * production APK can never be redirected to a developer's LAN host.
 */
object ApiConfig {
    const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
    const val DEV_URL_FILE = "dev-api-url.txt"

    var baseUrl: String = DEFAULT_BASE_URL

    /**
     * Build-time server address installed by [bakeBaseUrl], or null for local
     * dev builds. While non-null, [applyDevUrlFile] and [loadFromLocalFile]
     * are no-ops.
     */
    var bakedBaseUrl: String? = null
        private set

    /**
     * Install a build-time base URL (blank / comment input clears it).
     * Returns true when a URL was installed.
     */
    fun bakeBaseUrl(raw: String?): Boolean {
        val normalized = normalizeBaseUrl(raw ?: "")
        bakedBaseUrl = normalized
        if (normalized != null) baseUrl = normalized
        return normalized != null
    }

    fun normalizeBaseUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty() || t.startsWith("#")) return null
        return t
    }

    /**
     * Resolves the API base URL for Web/Wasm clients based on priority:
     * 1. Query parameter override (`?apiBase=...`)
     * 2. Global JS configuration (`window.__ZJ_API_BASE__`)
     * 3. Current page origin (`window.location.origin`), unless "null"
     * 4. [fallback] (defaults to [DEFAULT_BASE_URL])
     */
    fun resolveWebBaseUrl(
        queryParam: String?,
        configGlobal: String?,
        windowOrigin: String?,
        fallback: String = DEFAULT_BASE_URL,
    ): String {
        normalizeBaseUrl(queryParam ?: "")?.let { return it }
        normalizeBaseUrl(configGlobal ?: "")?.let { return it }
        normalizeBaseUrl(windowOrigin ?: "")?.let { if (it != "null") return it }
        return normalizeBaseUrl(fallback) ?: DEFAULT_BASE_URL
    }

    fun hostFromUrl(url: String): String? {
        val rest = url.substringAfter("://", missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        return rest.substringBefore('/').substringBefore(':')
    }

    fun samePrefix24(a: String, b: String): Boolean {
        val ap = a.split('.')
        val bp = b.split('.')
        return !(ap.size != 4 || bp.size != 4) && ap[0] == bp[0] && ap[1] == bp[1] && ap[2] == bp[2]
    }

    /**
     * Choose a base URL from [urls] (one per line or already split).
     * Prefers a host on the same /24 as [deviceIp]; otherwise the first valid URL.
     */
    fun pickBaseUrl(deviceIp: String?, urls: List<String>): String? {
        val normalized = urls.mapNotNull { normalizeBaseUrl(it) }
        if (normalized.isEmpty()) return null
        val device = deviceIp?.trim()?.takeIf { it.isNotEmpty() }
        if (device != null) {
            normalized.firstOrNull { url ->
                hostFromUrl(url)?.let { samePrefix24(it, device) } == true
            }?.let { return it }
        }
        return normalized.first()
    }

    fun applyDevUrlFile(text: String, deviceIp: String? = null) {
        if (bakedBaseUrl != null) return
        pickBaseUrl(deviceIp, text.lineSequence().toList())?.let { baseUrl = it }
    }

    /** Android: [DEV_URL_FILE] in local storage. No-op on desktop / web. */
    fun loadFromLocalFile(deviceIp: String? = null) {
        if (bakedBaseUrl != null) return
        try {
            val app = Gdx.app ?: return
            if (app.type != Application.ApplicationType.Android) return
            val f = Gdx.files.local(DEV_URL_FILE)
            if (!f.exists()) return
            applyDevUrlFile(f.readString(), deviceIp)
        } catch (_: Throwable) {
            // Never let a missing/corrupt file block boot.
        }
    }
}
