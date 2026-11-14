package inego.zungjunggame.lwjgl3

import inego.zungjunggame.net.ApiConfig
import java.nio.charset.StandardCharsets

/**
 * Desktop API base URL baked at build time from an explicit `-PapiBaseUrl=...`
 * or from `desktopApiBaseUrl` in the gitignored `local.properties`.
 *
 * A local dev build bakes nothing, so [ApiConfig.DEFAULT_BASE_URL]
 * (`http://127.0.0.1:8080`) stays in effect. Pointing a desktop build at a
 * deployed server is otherwise impossible: unlike Android there is no dev-URL
 * file and unlike Wasm there is no page origin to derive from.
 *
 * Baking is a classpath resource rather than a system property so it survives
 * the `StartupHelper` / `DesktopCrashJvm` child-JVM relaunches.
 */
internal object DesktopApiConfig {
    private const val RESOURCE = "/desktop-api.properties"
    private const val KEY = "apiBaseUrl="

    /**
     * The value in [text], or null when the file is absent, the key is missing,
     * the value is blank, or it still holds the unreplaced `__API_BASE_URL__`
     * placeholder (a resource processed without the Gradle filter).
     */
    internal fun parse(text: String?): String? {
        if (text == null) return null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith(KEY)) continue
            val value = trimmed.removePrefix(KEY).trim()
            if (value.isEmpty() || value.startsWith("__")) return null
            return value
        }
        return null
    }

    /** Bake the build's URL into [ApiConfig]; true when one was installed. */
    fun install(): Boolean = ApiConfig.bakeBaseUrl(load())

    private fun load(): String? =
        DesktopApiConfig::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { parse(it.readText()) }
}
