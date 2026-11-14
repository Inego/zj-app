@file:JvmName("AndroidSystemLanguages")

package inego.zungjunggame.android

import android.content.res.Configuration
import inego.zungjunggame.i18n.AppLocale
import java.util.Locale

/**
 * The activity configuration's locale list, most preferred first.
 * A per-app language is already on this configuration, so the list is the
 * one the process started with.
 */
fun preferredTags(configuration: Configuration): List<String> {
    val list = configuration.locales
    val locales: List<Locale> = List(list.size()) { index -> list.get(index) }
    return locales.map { AppLocale.localeTag(it) }.filter { it.isNotEmpty() }
}
