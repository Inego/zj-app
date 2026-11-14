package inego.zungjunggame.persist

import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.PlatformLanguages
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey
import inego.zungjunggame.state.StateStore

/**
 * Versioned codec for the persisted UI language.
 *
 * v1: the [AppLocale.tag] (BCP 47-style, e.g. `en-US`, `ru`). An unknown tag is
 * corrupt data and falls back to the key's default.
 */
object AppLocaleSchema : Schema<AppLocale> {
    override val version: Int = 1

    override fun encode(value: AppLocale): ByteArray = RecordWriter()
        .field(value.tag)
        .build()

    override fun decode(bytes: ByteArray): AppLocale? = try {
        val reader = RecordReader(bytes)
        val tag = reader.field()
        if (!reader.atEnd()) return null
        AppLocale.entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted UI language: namespace `ui`, name `locale`. */
val AppLocaleKey: StateKey<AppLocale> = StateKey(
    namespace = "ui",
    name = "locale",
    schema = AppLocaleSchema,
    default = { AppLocale.DEFAULT },
)

/**
 * The locale a cold start should open.
 *
 * A stored [AppLocaleKey] wins, including a corrupt value that [read] already
 * turned into [AppLocale.DEFAULT]. [read] also returns that default when the
 * key was never written, so absence is [StateStore.contains]. A missing key
 * is the first launch in this environment: map [PlatformLanguages.preferredTags],
 * persist the choice (English included), and flush. The flush is load-bearing.
 * The felt-frame reader and the later boot each open their own preferences
 * backend, and Android does not show an unflushed editor to the second one.
 */
fun StateStore.resolveStartupLocale(): AppLocale {
    if (contains(AppLocaleKey)) {
        return read(AppLocaleKey) ?: AppLocale.DEFAULT
    }
    val locale = AppLocale.match(PlatformLanguages.preferredTags())
    write(AppLocaleKey, locale)
    flush()
    return locale
}
