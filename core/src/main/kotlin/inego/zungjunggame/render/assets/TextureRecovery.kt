package inego.zungjunggame.render.assets

import com.badlogic.gdx.Gdx
import kotlin.collections.iterator

/**
 * Re-upload of every texture the app owns directly.
 *
 * libGDX only auto-reloads **managed** textures (`AssetManager`-loaded, or the
 * FreeType glyph pages). Everything this app builds itself is created through
 * `new Texture(FileHandle)` or `new Texture(Pixmap)`, which are unmanaged: when
 * the Android driver discards the EGL context, those GL objects are gone and
 * nothing brings them back. Depending on when the driver evicts them, that shows
 * up as missing tiles, black tile faces, or blank icons — intermittently, which
 * is exactly how it was observed.
 *
 * ### Owners abandon the previous instances; they never dispose them
 *
 * This pass exists for a context change, so the previous textures' GL names are
 * dead. But the new context issues names from the bottom of its own sequence,
 * and by the time an owner has built its replacement the new context may have
 * reissued one of the old names to a live texture — the replacement itself,
 * another owner's set, or a glyph page. Calling `glDeleteTextures` on the old
 * name then deletes that live texture, and the handle that owns it keeps drawing
 * whatever later receives the name. Reported symptom: after switching away from
 * and back to the app, the close button's "×" drew a mahjong tile. Owners
 * therefore rebuild and publish, then drop the old references; see
 * [buildThenSwap]'s `disposePrevious`. The old context already destroyed its GPU
 * objects, so the dropped references were the only thing still pointing at them.
 *
 * The glyph atlases are *not* handled here — they need a full FreeType rebuild,
 * which [inego.zungjunggame.render.fonts.Fonts.recoverAfterBackgrounding] does.
 */
object TextureRecovery {

    /** Re-upload actions, keyed by owner so re-registration replaces rather than piles up. */
    private val owners = LinkedHashMap<String, () -> Unit>()

    /**
     * Register (or replace) an owner's re-upload action and run it once, so a
     * freshly constructed owner is immediately known to the recovery pass.
     */
    fun register(owner: String, reload: () -> Unit) {
        owners[owner] = reload
        runCatching { reload() }
            .onFailure { Gdx.app?.error("TextureRecovery", "initial $owner failed: ${it.message}", it) }
    }

    /**
     * Register without running. For owners that have already (re)created their
     * textures, where an immediate run would upload and then throw away a second
     * copy.
     */
    fun registerOnly(owner: String, reload: () -> Unit) {
        owners[owner] = reload
    }

    /** Drop an owner so a disposed asset set is never re-uploaded. */
    fun unregister(owner: String) {
        owners.remove(owner)
    }

    /** Re-upload everything. Render thread only. */
    fun recoverAll(reason: String) {
        var done = 0
        for ((owner, reload) in owners) {
            try {
                reload()
                done++
            } catch (t: Throwable) {
                Gdx.app?.error("TextureRecovery", "$owner reload failed ($reason): ${t.message}", t)
            }
        }
        Gdx.app?.log("TextureRecovery", "re-uploaded $done owner(s) after $reason")
    }

    /** Test hook: forget every owner. */
    fun resetForTests() {
        owners.clear()
    }
}
