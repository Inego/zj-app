package inego.zungjunggame.platform

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Cursor
import inego.zungjunggame.input.InputProfile

/**
 * Desktop system mouse cursor helper.
 *
 * Touch backends have no mouse cursor; calls no-op there. Tracks the last
 * applied shape so repeated [set] with the same value does not hammer the OS.
 */
object UiCursor {
    private var current: Cursor.SystemCursor? = null

    fun set(cursor: Cursor.SystemCursor) {
        if (Gdx.graphics == null) return
        if (!InputProfile.detect().usesHover) return
        if (current == cursor) return
        Gdx.graphics.setSystemCursor(cursor)
        current = cursor
    }

    /** Default arrow (call when leaving hoverable UI). */
    fun reset() {
        set(Cursor.SystemCursor.Arrow)
    }

    fun hand() = set(Cursor.SystemCursor.Hand)

    fun arrow() = set(Cursor.SystemCursor.Arrow)

    /**
     * Raise the hand while the pointer is on a live hyperlink.
     *
     * A link only ever raises: the frame starts on [reset]'s arrow and each
     * widget reports only its own link, so a host never has to know what else is
     * on screen. No-op on touch backends and when [hovered] is false.
     */
    fun overLink(hovered: Boolean) {
        if (hovered) hand()
    }
}
