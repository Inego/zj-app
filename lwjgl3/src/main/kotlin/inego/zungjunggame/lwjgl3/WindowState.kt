package inego.zungjunggame.lwjgl3

import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Last desktop window geometry for cold-start restore.
 *
 * Persisted as the `platform.window` key in the desktop's keyed state folder
 * ([DesktopFileStateBackend]). The launcher reads it **before** libGDX exists,
 * which is why this key lives on a Gdx-free backend rather than the core one.
 */
data class WindowState(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    /** Window top-left; null means “let the OS / libGDX center”. */
    val x: Int? = null,
    val y: Int? = null,
    val fullscreen: Boolean = false,
    val maximized: Boolean = false,
) {
    /**
     * The storable form: a size at or above the minimum — a corrupt or
     * hand-edited value must not open an unusable window — and maximized only
     * when not fullscreen, since a fullscreen frame already covers one.
     */
    fun sanitized(): WindowState = WindowState(
        width = width.coerceAtLeast(MIN_WIDTH),
        height = height.coerceAtLeast(MIN_HEIGHT),
        x = x,
        y = y,
        fullscreen = fullscreen,
        maximized = maximized && !fullscreen,
    )

    companion object {
        const val DEFAULT_WIDTH = 1024
        const val DEFAULT_HEIGHT = 720
        const val MIN_WIDTH = 640
        const val MIN_HEIGHT = 480

        val DEFAULT = WindowState()
    }
}

/** Versioned codec for [WindowState]. v1: size, position, fullscreen, maximized. */
object WindowStateSchema : Schema<WindowState> {
    override val version: Int = 1

    override fun encode(value: WindowState): ByteArray = RecordWriter()
        .int(value.width)
        .int(value.height)
        .field(value.x?.toString() ?: "")
        .field(value.y?.toString() ?: "")
        .boolean(value.fullscreen)
        .boolean(value.maximized)
        .build()

    override fun decode(bytes: ByteArray): WindowState? = try {
        val reader = RecordReader(bytes)
        val state = WindowState(
            width = reader.int(),
            height = reader.int(),
            x = reader.optionalInt(),
            y = reader.optionalInt(),
            fullscreen = reader.boolean(),
            maximized = reader.boolean(),
        )
        if (!reader.atEnd()) return null
        state
    } catch (_: IllegalArgumentException) {
        null
    }

    /** An absent position is an empty field; anything else must be a number. */
    private fun RecordReader.optionalInt(): Int? {
        val field = field()
        if (field.isEmpty()) return null
        return field.toIntOrNull() ?: throw IllegalArgumentException("not an int")
    }
}

/** Persisted desktop window geometry: namespace `platform`, name `window`. */
val WindowStateKey: StateKey<WindowState> = StateKey(
    namespace = "platform",
    name = "window",
    schema = WindowStateSchema,
    default = { WindowState.DEFAULT },
)
