package inego.zungjunggame.platform

/**
 * The device's time zone, in the two forms the client needs.
 *
 * [provider] **names** it: an IANA id (`Europe/Berlin`) the server stores with
 * the account, so email reports are rendered in the player's own clock.
 * [offsetProvider] **places an instant inside it**: minutes east of UTC *at that
 * instant*, because a zone's offset follows its own rules (daylight saving), so
 * the answer is asked per instant and never cached as one number.
 *
 * Launchers install both before the first frame. Core does not read
 * `java.time` or `java.util.TimeZone`: TeaVM's zone is not the browser's, and
 * the web launcher reads `Intl.DateTimeFormat().resolvedOptions().timeZone`.
 * Desktop and Android install `ZoneId.systemDefault()`.
 *
 * A null id means "do not change a zone the server already has". A null offset
 * means "this process cannot place an instant": the rating chart's popup then
 * reads UTC — the axis's own clock — and says so instead of pretending.
 */
object PlatformTimeZone {
    var provider: () -> String? = { null }

    /**
     * Minutes east of UTC in the device's zone at [epochMs], or null when this
     * process cannot report one.
     */
    var offsetProvider: (Long) -> Int? = { null }

    fun id(): String? = try {
        provider()?.trim()?.ifEmpty { null }
    } catch (_: Throwable) {
        null
    }

    /** The device's UTC offset in minutes at [epochMs], or null when unknown. */
    fun offsetMinutesAt(epochMs: Long): Int? = try {
        offsetProvider(epochMs)?.takeIf { it in -MAX_OFFSET_MINUTES..MAX_OFFSET_MINUTES }
    } catch (_: Throwable) {
        null
    }

    /** Real zone offsets run from -12:00 to +14:00; anything else is a bug. */
    private const val MAX_OFFSET_MINUTES = 14 * 60
}
