package inego.zungjunggame.i18n

import java.util.Locale

/**
 * BCP 47 tags the environment prefers, most preferred first.
 *
 * Launchers replace [provider] before the first cold start. The default is
 * [Locale.getDefault], so a desktop process that never installed a provider
 * still maps a language. Desktop replaces it with the display locale.
 * Android installs the configuration list. Wasm installs
 * `navigator.languages`: TeaVM's [Locale.getDefault] is not the browser.
 */
object PlatformLanguages {
    var provider: () -> List<String> = { defaultJvmTags() }

    fun preferredTags(): List<String> = try {
        provider().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun defaultJvmTags(): List<String> {
        val tag = AppLocale.localeTag(Locale.getDefault())
        return if (tag.isEmpty()) emptyList() else listOf(tag)
    }
}
