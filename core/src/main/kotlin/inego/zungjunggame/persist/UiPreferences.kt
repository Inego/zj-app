package inego.zungjunggame.persist

import inego.zungjunggame.state.Migration
import inego.zungjunggame.state.MigrationResult
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Display preferences: how the app *looks*, not how the game is *played*.
 *
 * Kept separate from [inego.zungjunggame.engine.GameConfig] (rounds, chicken
 * hands) on purpose — those change the rules of a match and belong to New Game
 * Setup, while these apply to every session, online tables and tutorials
 * included, and must be settable with no game in progress.
 */
data class UiPreferences(
    /**
     * Draw a small tag on every face-up tile — the digit for number tiles, the
     * localized tag for honors (see `render/TileLabels.kt`). A reading aid for
     * players who cannot yet recognize the faces at a glance. Off by default.
     */
    val showTileLabels: Boolean = false,
    /**
     * Master switch for table sound effects. Off silences every cue; [soundVolume]
     * is remembered regardless, so switching back on restores the level the player
     * chose instead of a default.
     */
    val soundsEnabled: Boolean = true,
    /**
     * Sound-effect level, `0..[MAX_SOUND_VOLUME]`; `0` is silent. A discrete step
     * because the Settings card reuses the hub's `Slider` widget.
     */
    val soundVolume: Int = DEFAULT_SOUND_VOLUME,
    /**
     * Where the player parked the hand-end score-breakdown card: an offset from its
     * centered rest pose, in world pixels (see `render/ui/ParkDrag.kt`).
     *
     * A player-chosen place is the player's, not the session's: the card keeps it
     * between hands and across a restart, which is why it lives here rather than in
     * the card. The card clamps whatever it is given, so a value saved on a larger
     * window cannot park it off-screen.
     */
    val breakdownParkX: Float = 0f,
    val breakdownParkY: Float = 0f,
    /**
     * True when the player folded the hand-end score-breakdown card down to its
     * headline and Total, keeping the board visible.
     *
     * The card's own display state, but the player's choice, so it is remembered
     * here beside the parked place: the card is handed the fold to render and never
     * flips it itself, and that survives the card hiding between hands and the app
     * restarting.
     */
    val breakdownCollapsed: Boolean = false,
    /**
     * Where the player left the hand-end card's scrollable body: pixels scrolled
     * down from the top (see `render/ui/OverlayBodyScroll`).
     *
     * The body is an announced layer, so its route is not in the stack snapshot and
     * no route UI state runs for it. The offset is the player's display state all
     * the same, so it lives here beside the card's park and fold: the component
     * hands it in when the card appears and the prefs effect writes each settle
     * back, and it survives the card hiding and the restart.
     */
    val breakdownScrollY: Float = 0f,
    /**
     * Where the player parked the HINT button: an offset from its rest pose above
     * the hand row, in world pixels (see `render/ui/ParkDrag.kt`).
     *
     * The board's movable chrome is the player's arrangement, the same as the
     * hand-end card's place: it belongs to no session and no table, so it is
     * remembered here and handed back to the board widget when it appears. The
     * widget clamps whatever it is given, so a value saved on a larger window
     * cannot park the button off-screen.
     */
    val hintParkX: Float = 0f,
    val hintParkY: Float = 0f,
    /**
     * Where the player parked the claim / action row: an offset from its centered
     * rest pose above the hand. Beside [hintParkX]/[hintParkY] because the board's
     * two movable blocks are one class of player-set display state.
     */
    val claimParkX: Float = 0f,
    val claimParkY: Float = 0f,
) {
    companion object {
        val DEFAULT: UiPreferences = UiPreferences()

        /** Widest value the Settings volume slider exposes. */
        const val MAX_SOUND_VOLUME: Int = 10

        /** Default sound level: half the slider, quiet enough to not startle. */
        const val DEFAULT_SOUND_VOLUME: Int = 5
    }
}

/**
 * Versioned codec for [UiPreferences].
 *
 * v1: `showTileLabels` alone. v2 adds the hand-end card's park, v3 its fold, v4
 * the board's movable-chrome places (the HINT button and the claim row), v5 the
 * hand-end card's body scroll, v6 the sound switch and level. Every older payload
 * migrates by decoding at its own version — where the fields added later are
 * simply absent — and re-encoding one version up, so each player's existing
 * choices survive the upgrade.
 */
object UiPreferencesSchema : Schema<UiPreferences> {
    override val version: Int = 6

    private const val MIN_READABLE_VERSION = 1

    override fun encode(value: UiPreferences): ByteArray = encodeOf(value, version)

    override fun decode(bytes: ByteArray): UiPreferences? = decodeOf(bytes, version)

    override val migrations: List<Migration> = (MIN_READABLE_VERSION until version).map { from ->
        Migration { _, bytes ->
            decodeOf(bytes, from)
                ?.let { MigrationResult.Migrated(encodeOf(it, from + 1)) }
                ?: MigrationResult.Drop
        }
    }

    private fun encodeOf(value: UiPreferences, at: Int): ByteArray {
        val writer = RecordWriter().boolean(value.showTileLabels)
        if (at >= 2) {
            writer.float(value.breakdownParkX)
            writer.float(value.breakdownParkY)
        }
        if (at >= 3) writer.boolean(value.breakdownCollapsed)
        if (at >= 4) {
            writer.float(value.hintParkX)
            writer.float(value.hintParkY)
            writer.float(value.claimParkX)
            writer.float(value.claimParkY)
        }
        if (at >= 5) writer.float(value.breakdownScrollY)
        if (at >= 6) {
            writer.boolean(value.soundsEnabled)
            writer.int(value.soundVolume)
        }
        return writer.build()
    }

    private fun decodeOf(bytes: ByteArray, at: Int): UiPreferences? = try {
        val reader = RecordReader(bytes)
        val showTileLabels = reader.boolean()
        val parkX = if (at >= 2) reader.float() else 0f
        val parkY = if (at >= 2) reader.float() else 0f
        val collapsed = at >= 3 && reader.boolean()
        val hintParkX = if (at >= 4) reader.float() else 0f
        val hintParkY = if (at >= 4) reader.float() else 0f
        val claimParkX = if (at >= 4) reader.float() else 0f
        val claimParkY = if (at >= 4) reader.float() else 0f
        val breakdownScrollY = if (at >= 5) reader.float() else 0f
        val soundsEnabled = at < 6 || reader.boolean()
        val soundVolume = if (at >= 6) reader.int() else UiPreferences.DEFAULT_SOUND_VOLUME
        if (!reader.atEnd()) return null
        UiPreferences(
            showTileLabels = showTileLabels,
            soundsEnabled = soundsEnabled,
            soundVolume = soundVolume,
            breakdownParkX = parkX,
            breakdownParkY = parkY,
            breakdownCollapsed = collapsed,
            hintParkX = hintParkX,
            hintParkY = hintParkY,
            claimParkX = claimParkX,
            claimParkY = claimParkY,
            breakdownScrollY = breakdownScrollY,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted display preferences: namespace `ui`, name `prefs`. */
val UiPreferencesKey: StateKey<UiPreferences> = StateKey(
    namespace = "ui",
    name = "prefs",
    schema = UiPreferencesSchema,
    default = { UiPreferences.DEFAULT },
)
