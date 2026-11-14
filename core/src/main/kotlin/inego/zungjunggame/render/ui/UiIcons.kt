package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.assets.TextureRecovery
import inego.zungjunggame.render.assets.buildThenSwap

/**
 * Lucide outline chrome icons (white-on-transparent PNGs under `assets/ui/`).
 *
 * Loaded lazily on first use so unit tests and early [Main] construction do not
 * require libGDX files. Tint with [com.badlogic.gdx.graphics.g2d.SpriteBatch.setColor]
 * for normal / hover / disabled.
 *
 * Sources and licenses: `third_party/lucide-icons/`.
 */
object UiIcons : Disposable {
    private var loaded = false

    private var closeTex: Texture? = null
    private var backTex: Texture? = null
    private var chevronLeftTex: Texture? = null
    private var chevronRightTex: Texture? = null
    private var chevronUpTex: Texture? = null
    private var chevronDownTex: Texture? = null
    private var infoTex: Texture? = null
    private var globeTex: Texture? = null
    private var bellTex: Texture? = null
    private var menuTex: Texture? = null

    val close: Texture
        get() {
            ensureLoaded()
            return closeTex!!
        }

    val back: Texture
        get() {
            ensureLoaded()
            return backTex!!
        }

    val chevronLeft: Texture
        get() {
            ensureLoaded()
            return chevronLeftTex!!
        }

    val chevronRight: Texture
        get() {
            ensureLoaded()
            return chevronRightTex!!
        }

    /** Lucide `chevron-up` — collapse / fold closed. */
    val chevronUp: Texture
        get() {
            ensureLoaded()
            return chevronUpTex!!
        }

    /** Lucide `chevron-down` — expand / fold open. */
    val chevronDown: Texture
        get() {
            ensureLoaded()
            return chevronDownTex!!
        }

    /** Lucide `info` — circle with i (help / open reference topic). */
    val info: Texture
        get() {
            ensureLoaded()
            return infoTex!!
        }

    /** Lucide `globe` — localization / language control. */
    val globe: Texture
        get() {
            ensureLoaded()
            return globeTex!!
        }

    /** Lucide `bell` — notices inbox / notification control. */
    val bell: Texture
        get() {
            ensureLoaded()
            return bellTex!!
        }

    /** Lucide `menu` — board menu control (Esc / system-back equivalent). */
    val menu: Texture
        get() {
            ensureLoaded()
            return menuTex!!
        }

    fun ensureLoaded() {
        if (loaded) return
        // Build-then-swap even on first load: a failure part-way disposes the
        // partial uploads instead of stranding them. `loaded` flips only once the
        // whole set is published.
        buildThenSwap(
            previous = emptyList(),
            dispose = { it.disposeQuietly() },
            build = ::buildIcons,
            publish = { icons ->
                publishIcons(icons)
                loaded = true
            },
        )
        // These are unmanaged `Texture(FileHandle)` instances, so a discarded EGL
        // context leaves them dead with no libGDX machinery to bring them back.
        TextureRecovery.registerOnly(RECOVERY_KEY, ::reload)
    }

    /**
     * Upload every icon in field order; [track] reports each upload so a partial
     * set can be rolled back.
     */
    private fun buildIcons(track: (Texture) -> Texture): List<Texture> = listOf(
        track(load("ui/close.png")),
        track(load("ui/back.png")),
        track(load("ui/chevron_left.png")),
        track(load("ui/chevron_right.png")),
        track(load("ui/chevron_up.png")),
        track(load("ui/chevron_down.png")),
        track(load("ui/info.png")),
        track(load("ui/globe.png")),
        track(load("ui/bell.png")),
        track(load("ui/menu.png")),
    )

    /** Fields, in the order [buildIcons] produces them. */
    private fun publishIcons(icons: List<Texture>) {
        closeTex = icons[0]
        backTex = icons[1]
        chevronLeftTex = icons[2]
        chevronRightTex = icons[3]
        chevronUpTex = icons[4]
        chevronDownTex = icons[5]
        infoTex = icons[6]
        globeTex = icons[7]
        bellTex = icons[8]
        menuTex = icons[9]
    }

    /**
     * Re-read every icon from its PNG. Render thread only.
     *
     * Build-then-publish: the ten replacements are uploaded before any field is
     * published, so a failure part-way leaves the previous icon set published
     * rather than a mix of new and disposed handles. On failure the partial
     * uploads are disposed and the old set (and [loaded]) stays.
     *
     * The previous set is **abandoned, not disposed**. Its GL names died with the
     * context that is being recovered, and the new context may have reissued one
     * of them to a live texture; deleting it would make an icon draw that texture
     * (a close "×" rendered as a tile was exactly that). See [buildThenSwap].
     */
    private fun reload() {
        if (!loaded) return
        val previous = listOfNotNull(
            closeTex, backTex, chevronLeftTex, chevronRightTex,
            chevronUpTex, chevronDownTex, infoTex, globeTex, bellTex, menuTex,
        )
        buildThenSwap(
            previous = previous,
            dispose = { it.disposeQuietly() },
            disposePrevious = false,
            build = ::buildIcons,
            publish = ::publishIcons,
        )
    }

    private fun Texture.disposeQuietly() {
        try {
            dispose()
        } catch (_: Throwable) {
            // Context already gone; nothing to release.
        }
    }

    override fun dispose() {
        TextureRecovery.unregister(RECOVERY_KEY)
        closeTex?.disposeQuietly()
        backTex?.disposeQuietly()
        chevronLeftTex?.disposeQuietly()
        chevronRightTex?.disposeQuietly()
        chevronUpTex?.disposeQuietly()
        chevronDownTex?.disposeQuietly()
        infoTex?.disposeQuietly()
        globeTex?.disposeQuietly()
        bellTex?.disposeQuietly()
        menuTex?.disposeQuietly()
        closeTex = null
        backTex = null
        chevronLeftTex = null
        chevronRightTex = null
        chevronUpTex = null
        chevronDownTex = null
        infoTex = null
        globeTex = null
        bellTex = null
        menuTex = null
        loaded = false
    }

    private const val RECOVERY_KEY = "UiIcons"

    private fun load(path: String): Texture =
        Texture(path).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
}
