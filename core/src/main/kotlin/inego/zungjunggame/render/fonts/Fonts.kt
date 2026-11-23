package inego.zungjunggame.render.fonts

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.input.InputProfile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.text.iterator
import inego.zungjunggame.render.ui.AppPalette

/**
 * FreeType-backed fonts: **one primary family + optional fallback**, shared by
 * chrome (menus, seats, HUD) and Reference / rich text.
 *
 * ## Policy
 *
 * - **Primary** from [ScriptProfile] (Roboto for en/ru/fr; CJK for zh/ja).
 * - **Fallback** fills missing glyphs (CJK when primary is Roboto so Han in
 *   English pattern names never tofu; Roboto when primary is CJK).
 * - Size roles (`body`, `button`, `docBody`, …) are different **sizes** of the
 *   same primary — not a second script family for documents.
 *
 * Glyphs are **incremental**. Fallback is loaded lazily on first need (Reference
 * open or mixed-script string), not on Latin cold start.
 *
 * Pixel sizes are multiplied by [uiScale] for high-DPI devices. [designPxScale]
 * is that multiplier: one design pixel in world pixels.
 */
object Fonts : Disposable {
    private const val MAX_UI_SCALE = 3f
    private const val MAX_STATIC_TEXTURE_SIZE = 1024

    /**
     * Seed glyphs for incremental FreeType faces.
     *
     * - [PRIMARY_SEED_CHARS]: `\u0000` (missing-glyph box), printable ASCII (0x20..0x7E),
     *   and the UI punctuation/symbols used across shipped screens (`—–…·×•→←Δ○◑✓`) so
     *   common chrome never faults incrementally on first draw. Accented Latin, Cyrillic,
     *   Greek, and CJK arrive incrementally via `glTexSubImage2D`.
     * - [DISPLAY_SEED_CHARS]: digits, sign, and metrics probe for the 48 dp [display] face
     *   so cold start does not pre-bake 100+ display-sized glyphs into a large atlas.
     * - [METRICS_SEED_CHARS]: minimal metrics probe for lazy fallback faces; real fallback
     *   glyphs arrive on first use.
     */
    private const val METRICS_SEED_CHARS = "\u0000xX"
    private const val PRIMARY_SEED_CHARS =
        "\u0000 !\"#\$%&'()*+,-./0123456789:;<=>?@" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`" +
            "abcdefghijklmnopqrstuvwxyz{|}~" +
            "—–…·×•→←Δ○◑✓"
    private const val DISPLAY_SEED_CHARS = "\u00000123456789+-xX"

    /**
     * Tile-label face size (design px). A label tag is a fraction of a tile — roughly
     * 10-24 world px of ink — and is always drawn scaled to the tile it annotates, so
     * this stays small instead of costing another display-sized atlas.
     */
    private const val TILE_LABEL_DESIGN_SIZE = 24

    /**
     * Every glyph a tile label can need: digits, the wind/dragon tags, and the Cyrillic
     * tags. `incremental = true` still fills in any future locale's tag on first use.
     */
    private const val TILE_LABEL_SEED_CHARS = "\u00000123456789ESWNRGWhВЮЗСКБ"

    /** Reused layout for priming `PixmapPacker.packToTexture = true` on new faces. */
    private val primerLayout = GlyphLayout()

    /**
     * Incremental [FreeTypeFontGenerator.FreeTypeBitmapFontData] that caches missing-glyph
     * lookups. LibGDX's default `getGlyph` does not record misses, so repeated fallback
     * probes (`FallbackText.hasUsableGlyph`) otherwise hit FreeType on every frame.
     */
    private class CachingFreeTypeFontData : FreeTypeFontGenerator.FreeTypeBitmapFontData() {
        private val missingChars = HashSet<Char>()
        var cacheMissing: Boolean = false

        override fun getGlyph(ch: Char): BitmapFont.Glyph? {
            if (cacheMissing && ch.code != 0 && missingChars.contains(ch)) {
                return missingGlyph
            }
            val glyph = super.getGlyph(ch)
            if (cacheMissing && ch.code != 0 &&
                (glyph == null || (missingGlyph != null && glyph === missingGlyph))
            ) {
                missingChars.add(ch)
            }
            return glyph
        }
    }

    // ── Primary generators ─────────────────────────────────────────────────
    private var primaryFamily: FontFaceResolver.Family? = null
    private var primaryRegularGen: FreeTypeFontGenerator? = null
    private var primaryBoldGen: FreeTypeFontGenerator? = null
    private var primaryItalicGen: FreeTypeFontGenerator? = null
    private var primaryBoldItalicGen: FreeTypeFontGenerator? = null

    // ── Fallback generators (optional second face) ─────────────────────────
    private var fallbackFamily: FontFaceResolver.Family? = null
    private var fallbackRegularGen: FreeTypeFontGenerator? = null
    private var fallbackBoldGen: FreeTypeFontGenerator? = null
    private var fallbackItalicGen: FreeTypeFontGenerator? = null
    private var fallbackBoldItalicGen: FreeTypeFontGenerator? = null

    /** Active script profile used to build the primary family. */
    var activeProfile: ScriptProfile = ScriptProfile.LatinCyrillic
        private set

    /** True when primary (or loaded fallback) can render Han. */
    var cjkCapable: Boolean = false
        private set

    private var primaryRegularLabel: String? = null

    // ── Primary faces (chrome eager; doc sizes lazy via [ensureDocFonts]) ──

    lateinit var body: BitmapFont
        private set
    lateinit var label: BitmapFont
        private set
    lateinit var labelActive: BitmapFont
        private set
    lateinit var button: BitmapFont
        private set
    /**
     * [button]'s weight, for a button whose label is an emphatic verb — Rejoin,
     * which asks the player to commit to going back to a live table.
     *
     * Same design size as [button], so switching weight changes weight only and
     * never the line height or the glyph width a caller has already measured.
     */
    lateinit var buttonBold: BitmapFont
        private set
    lateinit var small: BitmapFont
        private set
    /** [small]'s weight, for an emphasised word inside small text. */
    lateinit var smallBold: BitmapFont
        private set
    lateinit var wallCount: BitmapFont
        private set
    /**
     * Large chrome glyphs (e.g. tutorial seat winds). Rasterized big so UI can
     * draw at scale 1 without the blur of upscaling smaller atlas faces.
     */
    lateinit var display: BitmapFont
        private set

    /**
     * Shadow-free face for the tile-label outline (`TileDrawer.drawTileLabel`).
     *
     * Every chrome face bakes a black drop shadow, which is right for text on a panel
     * and wrong for a tag knocked out of a tile: each stamp of the ivory halo drags its
     * own shadow along, so the ring picks up a dark fringe, and the ink's shadow lands
     * inside it. Hence a face generated with no shadow at all. Missing glyphs fall back
     * through the matching shadow-free branch in [fallbackFor]; no shipped tag needs it —
     * digits, `ESWNRGWh` and the Cyrillic tags are all in this primary.
     */
    lateinit var tileLabel: BitmapFont
        private set

    lateinit var docBody: BitmapFont
        private set
    lateinit var docBodyBold: BitmapFont
        private set
    lateinit var docBodyItalic: BitmapFont
        private set
    lateinit var docBodyBoldItalic: BitmapFont
        private set
    lateinit var docHeading1: BitmapFont
        private set
    lateinit var docHeading2: BitmapFont
        private set

    // ── Fallback faces (same design sizes; null until [ensureFallback]) ────

    private var fbBody: BitmapFont? = null
    private var fbLabel: BitmapFont? = null
    private var fbLabelActive: BitmapFont? = null
    private var fbButton: BitmapFont? = null
    private var fbButtonBold: BitmapFont? = null
    private var fbSmall: BitmapFont? = null
    private var fbSmallBold: BitmapFont? = null
    private var fbWallCount: BitmapFont? = null
    private var fbDisplay: BitmapFont? = null

    /** Shadow-free fallback for [tileLabel] (see [fallbackFor]). */
    private var fbTileLabel: BitmapFont? = null
    private var fbDocBody: BitmapFont? = null
    private var fbDocBodyBold: BitmapFont? = null
    private var fbDocBodyItalic: BitmapFont? = null
    private var fbDocBodyBoldItalic: BitmapFont? = null
    private var fbDocHeading1: BitmapFont? = null
    private var fbDocHeading2: BitmapFont? = null

    var uiScale: Float = 1f
        internal set

    private var primaryCreated = false
    /** Doc-size primary faces (and italic generators) after first [ensureDocFonts]. */
    private var primaryDocsCreated = false
    /** When true, [docBody] aliases [body] — dispose only once. */
    private var docBodySharesBody = false
    /** True once fallback FreeType generators are open (faces may still be lazy). */
    private var fallbackCreated = false
    private var fallbackAttempted = false
    private var primaryOwnsGenerators = true
    private var fallbackOwnsGenerators = true

    fun px(designPx: Float): Float = designPx * uiScale

    /**
     * Ensure primary fonts exist for the current locale profile.
     * Cheap after first call; safe every frame.
     */
    fun ensureCreated() {
        if (primaryCreated) return
        buildPrimary(activeProfile)
    }

    /**
     * Rebuild fonts when [locale]'s [ScriptProfile] differs from [activeProfile].
     * No-op when the profile is unchanged (e.g. en ↔ ru).
     */
    fun applyLocale(locale: AppLocale) {
        val profile = locale.scriptProfile
        if (primaryCreated && profile == activeProfile) return
        Gdx.app?.log("Fonts", "applyLocale ${locale.tag} profile=$profile (was $activeProfile)")
        disposeAllFonts()
        buildPrimary(profile)
    }

    /**
     * Ensure document size roles exist (same primary family as chrome).
     * Doc faces are deferred from cold start — menus only need chrome sizes.
     *
     * Does **not** load the CJK fallback: English coach/reference bodies do not
     * need YaHei. Fallback opens on first Han via [ensureFallback] /
     * [textWidth] / [drawText] / seat-wind labels.
     */
    fun ensureDocFonts() {
        ensureCreated()
        if (!primaryDocsCreated) {
            buildPrimaryDocs()
        }
    }

    /**
     * Load the secondary face for missing glyphs (lazy). Safe to call repeatedly.
     *
     * Opens generators and builds faces **on demand** per size (seat winds only
     * need [display]; full chrome is not pre-baked). Document fallback sizes
     * are created the first time a doc primary is resolved via [fallbackFor].
     */
    fun ensureFallback() {
        ensureFallbackGenerators()
    }

    /**
     * Open fallback FreeType generators once. Face atlases are created lazily
     * in [fallbackFaceFor] so unused sizes cost nothing on first paint.
     */
    private fun ensureFallbackGenerators() {
        ensureCreated()
        if (fallbackCreated || fallbackAttempted) return
        fallbackAttempted = true

        val t0 = System.nanoTime()
        // If primary already CJK, still load Roboto as fallback for rare Latin gaps.
        val opened = try {
            FontFaceResolver.resolveFallbackOpened(activeProfile)
        } catch (t: Throwable) {
            Gdx.app.error("Fonts", "resolveFallback failed: ${t.message}", t)
            null
        }

        if (opened == null) {
            // Missing CJK (should not happen on WebGL once NotoSansSC is in
            // assets). Do not use error(): TeaVM maps that to console.error and
            // Chrome attaches the whole rAF history, which looks like a crash.
            Gdx.app.log(
                "Fonts",
                "No fallback font for profile=$activeProfile; Han may be omitted.",
            )
            return
        }

        val family = opened.family

        // Skip if fallback is the same face as primary.
        if (family.regular.label == primaryRegularLabel) {
            disposeGenerators(opened.regularGen, opened.boldGen)
            Gdx.app.log("Fonts", "Fallback equals primary; skipping second atlas")
            cjkCapable = cjkCapable || family.cjkCapable
            return
        }

        try {
            val regular = opened.regularGen ?: openGen(family.regular)
            val bold = when {
                opened.boldGen != null -> opened.boldGen
                sameFace(family.bold, family.regular) -> regular
                else -> openGen(family.bold)
            }
            // Italic faces only when a doc style first needs them.
            fallbackFamily = family
            fallbackRegularGen = regular
            fallbackBoldGen = bold
            fallbackItalicGen = null
            fallbackBoldItalicGen = null
            fallbackOwnsGenerators = true
            fallbackCreated = true
            cjkCapable = cjkCapable || family.cjkCapable
            val ms = (System.nanoTime() - t0) / 1_000_000L
            val msg =
                "Fallback generators ready (${ms}ms): ${family.regular.label}" +
                    if (family.bold.label != family.regular.label) " / bold=${family.bold.label}" else ""
            Gdx.app.log("Fonts", msg)
        } catch (t: Throwable) {
            disposeGenerators(opened.regularGen, opened.boldGen)
            val msg = "Fallback font load failed: ${t.javaClass.simpleName}: ${t.message}"
            Gdx.app.error("Fonts", msg, t)
            disposeFallbackOnly()
        }
    }

    /**
     * Metrics-only incremental face for [designSize]; glyphs fill in on first use.
     */
    private fun generateFallbackFace(
        designSize: Int,
        color: Color,
        bold: Boolean = false,
        italic: Boolean = false,
        shadowed: Boolean = true,
        primaryScale: BitmapFont? = null,
    ): BitmapFont? {
        ensureFallbackGenerators()
        if (!fallbackCreated) return null
        val regular = fallbackRegularGen ?: return null
        val boldGen = fallbackBoldGen ?: regular
        val gen = when {
            bold && italic -> {
                ensureFallbackItalicGens()
                fallbackBoldItalicGen ?: boldGen
            }
            italic -> {
                ensureFallbackItalicGens()
                fallbackItalicGen ?: regular
            }
            bold -> boldGen
            else -> regular
        }
        val font = generate(
            gen = gen,
            designSize = designSize,
            color = color,
            seedChars = METRICS_SEED_CHARS,
            shadowed = shadowed,
            sparse = true,
        )
        if (primaryScale != null) {
            font.data.setScale(primaryScale.data.scaleX, primaryScale.data.scaleY)
        }
        return font
    }

    private fun ensureFallbackItalicGens() {
        if (fallbackItalicGen != null && fallbackBoldItalicGen != null) return
        val regular = fallbackRegularGen ?: return
        val bold = fallbackBoldGen ?: regular
        val family = fallbackFamily
        if (family == null) {
            fallbackItalicGen = regular
            fallbackBoldItalicGen = bold
            return
        }
        val italic =
            when {
                sameFace(family.italic, family.regular) -> regular
                sameFace(family.italic, family.bold) -> bold
                else -> openGen(family.italic)
            }
        val boldItalic =
            when {
                sameFace(family.boldItalic, family.bold) -> bold
                sameFace(family.boldItalic, family.regular) -> regular
                sameFace(family.boldItalic, family.italic) -> italic
                else -> openGen(family.boldItalic)
            }
        fallbackItalicGen = italic
        fallbackBoldItalicGen = boldItalic
    }

    /**
     * Rasterize non-ASCII / mixed-script sample into primary + fallback atlases.
     */
    fun warmDocGlyphs(text: String) = warmGlyphs(text)

    /**
     * Temporarily multiplies document face scales (primary + fallback) for
     * fit-first layout (e.g. tutorial coach body). Always restores previous
     * scales, even on throw. No-op when [scale] is ~1.
     *
     * Prefer this over ad-hoc [BitmapFont.Data.setScale] so bold/italic/heading
     * and CJK fallback stay in lockstep during measure + draw.
     */
    fun withDocFontScale(scale: Float, block: () -> Unit) {
        if (abs(scale - 1f) < 0.001f) {
            block()
            return
        }
        ensureDocFonts()
        val faces = docFacesForScale()
        val saved = Array(faces.size) { i ->
            val f = faces[i]
            floatArrayOf(f.data.scaleX, f.data.scaleY)
        }
        try {
            for (f in faces) {
                f.data.setScale(f.data.scaleX * scale, f.data.scaleY * scale)
            }
            block()
        } finally {
            for (i in faces.indices) {
                faces[i].data.setScale(saved[i][0], saved[i][1])
            }
            // Any fallback doc face lazily created during [block] inherited the scaled
            // primary; unscale it so it does not stay stuck at the scaled size.
            val after = docFacesForScale()
            if (after.size > faces.size) {
                for (f in after) {
                    if (faces.none { it === f }) {
                        f.data.setScale(f.data.scaleX / scale, f.data.scaleY / scale)
                    }
                }
            }
        }
    }

    /** Primary + loaded fallback faces used by [inego.zungjunggame.render.richtext.GdxTextMeasurer]. */
    private fun docFacesForScale(): Array<BitmapFont> {
        val list = ArrayList<BitmapFont>(12)
        if (::docBody.isInitialized) list += docBody
        if (::docBodyBold.isInitialized) list += docBodyBold
        if (::docBodyItalic.isInitialized) list += docBodyItalic
        if (::docBodyBoldItalic.isInitialized) list += docBodyBoldItalic
        if (::docHeading1.isInitialized) list += docHeading1
        if (::docHeading2.isInitialized) list += docHeading2
        fbDocBody?.let { list += it }
        fbDocBodyBold?.let { list += it }
        fbDocBodyItalic?.let { list += it }
        fbDocBodyBoldItalic?.let { list += it }
        fbDocHeading1?.let { list += it }
        fbDocHeading2?.let { list += it }
        return list.toTypedArray()
    }

    fun warmGlyphs(text: String) {
        if (text.isEmpty()) return
        ensureCreated()
        val needsFb = textNeedsFallbackForWarm(text)
        if (needsFb) {
            ensureFallback()
            // Lazily create the display fallback atlas before warming Han.
            if (::display.isInitialized) fallbackFor(display)
        }
        val sample = buildString {
            val seen = HashSet<Char>(64)
            for (c in text) {
                if (c.code > 0x7F && !c.isISOControl() && seen.add(c)) append(c)
            }
        }
        if (sample.isEmpty()) return
        try {
            val layout = GlyphLayout()
            // Only warm faces that already exist — do not force doc atlases for
            // chrome-only callers (e.g. seat-wind Han on the wall table).
            val fonts = ArrayList<BitmapFont>(8)
            if (::display.isInitialized) fonts += display
            if (primaryDocsCreated) {
                if (::docBody.isInitialized) fonts += docBody
                if (::docBodyBold.isInitialized) fonts += docBodyBold
                if (::docHeading1.isInitialized) fonts += docHeading1
                if (::docHeading2.isInitialized) fonts += docHeading2
            }
            fbDisplay?.let { fonts += it }
            fbDocBody?.let { fonts += it }
            fbDocBodyBold?.let { fonts += it }
            fbDocHeading1?.let { fonts += it }
            fbDocHeading2?.let { fonts += it }
            for (font in fonts) {
                layout.setText(font, sample)
            }
        } catch (t: Throwable) {
            Gdx.app.error("Fonts", "warmGlyphs failed: ${t.message}", t)
        }
    }

    // ── Measure / draw with fallback ───────────────────────────────────────

    fun textWidth(primary: BitmapFont, text: String): Float {
        if (textNeedsFallback(primary, text)) ensureFallback()
        return FallbackText.width(primary, fallbackFor(primary), text)
    }

    /**
     * Line height of [text] on the face [drawText] will paint it with. Primary and
     * fallback faces have different metrics, so measuring the primary alone
     * misplaces mixed-script runs (Han beside Latin, CJK chrome).
     */
    fun textHeight(primary: BitmapFont, text: String): Float {
        if (text.isEmpty()) return 0f
        if (textNeedsFallback(primary, text)) ensureFallback()
        return FallbackText.height(primary, fallbackFor(primary), text)
    }

    fun drawText(
        batch: SpriteBatch,
        primary: BitmapFont,
        text: String,
        x: Float,
        y: Float,
        color: Color,
    ) {
        if (textNeedsFallback(primary, text)) ensureFallback()
        FallbackText.draw(batch, primary, fallbackFor(primary), text, x, y, color)
    }

    /**
     * Height of [text] wrapped to [wrapWidth] on the face [drawWrapped] will
     * paint it with. A wrapped block takes one face for the whole block, so the
     * wrap and the ink cannot disagree.
     */
    fun wrappedHeight(primary: BitmapFont, text: String, wrapWidth: Float, align: Int): Float {
        if (text.isEmpty()) return 0f
        if (textNeedsFallback(primary, text)) ensureFallback()
        return FallbackText.wrappedHeight(primary, fallbackFor(primary), text, wrapWidth, align)
    }

    /** Paint [text] wrapped to [wrapWidth]; measure with [wrappedHeight]. */
    fun drawWrapped(
        batch: SpriteBatch,
        primary: BitmapFont,
        text: String,
        x: Float,
        y: Float,
        wrapWidth: Float,
        align: Int,
        color: Color,
    ) {
        if (textNeedsFallback(primary, text)) ensureFallback()
        FallbackText.drawWrapped(
            batch, primary, fallbackFor(primary), text, x, y, wrapWidth, align, color,
        )
    }

    /** Returns the already-instantiated fallback face for [primary], or null without creating one. */
    private fun existingFallbackFor(primary: BitmapFont): BitmapFont? = when {
        primary === body || (docBodySharesBody && primary === docBody) -> fbBody
        primary === label -> fbLabel
        primary === labelActive -> fbLabelActive
        primary === button -> fbButton
        primary === buttonBold -> fbButtonBold
        primary === small -> fbSmall
        primary === smallBold -> fbSmallBold
        primary === wallCount -> fbWallCount
        primary === display -> fbDisplay
        primary === tileLabel -> fbTileLabel
        primaryDocsCreated && primary === docBody -> fbDocBody ?: fbBody
        primaryDocsCreated && primary === docBodyBold -> fbDocBodyBold
        primaryDocsCreated && primary === docBodyItalic -> fbDocBodyItalic
        primaryDocsCreated && primary === docBodyBoldItalic -> fbDocBodyBoldItalic
        primaryDocsCreated && primary === docHeading1 -> fbDocHeading1
        primaryDocsCreated && primary === docHeading2 -> fbDocHeading2
        else -> fbBody
    }

    fun fallbackFor(primary: BitmapFont): BitmapFont? {
        if (!fallbackCreated && !fallbackAttempted) return null
        ensureFallbackGenerators()
        if (!fallbackCreated) return null
        return when {
            // body and docBody share the same atlas when aliased.
            primary === body || (docBodySharesBody && primary === docBody) ->
                fbBody ?: generateFallbackFace(22, Color.WHITE, primaryScale = primary)?.also { fbBody = it }
            primary === label ->
                fbLabel
                    ?: generateFallbackFace(20, AppPalette.seatInk, primaryScale = primary)?.also { fbLabel = it }
            primary === labelActive ->
                fbLabelActive
                    ?: generateFallbackFace(28, Color.WHITE, primaryScale = primary)?.also { fbLabelActive = it }
            primary === button ->
                fbButton ?: generateFallbackFace(18, Color.WHITE, primaryScale = primary)?.also { fbButton = it }
            primary === buttonBold ->
                fbButtonBold
                    ?: generateFallbackFace(18, Color.WHITE, bold = true, primaryScale = primary)?.also {
                        fbButtonBold = it
                    }
            primary === small ->
                fbSmall
                    ?: generateFallbackFace(14, AppPalette.tint(AppPalette.headerInk, 0.6f), primaryScale = primary)
                        ?.also { fbSmall = it }
            primary === smallBold ->
                fbSmallBold
                    ?: generateFallbackFace(
                        14,
                        AppPalette.tint(AppPalette.headerInk, 0.6f),
                        bold = true,
                        primaryScale = primary,
                    )?.also { fbSmallBold = it }
            primary === wallCount ->
                fbWallCount
                    ?: generateFallbackFace(26, Color.WHITE, bold = true, primaryScale = primary)?.also {
                        fbWallCount = it
                    }
            primary === display ->
                fbDisplay ?: generateFallbackFace(48, Color.WHITE, primaryScale = primary)?.also { fbDisplay = it }
            // The tile-label face is shadow-free (see [tileLabel]), so its fallback must
            // be too — a shadowed one would put the dark fringe straight back.
            primary === tileLabel ->
                fbTileLabel
                    ?: generateFallbackFace(
                        TILE_LABEL_DESIGN_SIZE,
                        Color.WHITE,
                        shadowed = false,
                        primaryScale = primary,
                    )?.also { fbTileLabel = it }
            // Doc faces are lazy: `buildPrimaryDocs` creates them *and* sets
            // `primaryDocsCreated`, only when a document first needs them. Reading one
            // here without that flag threw `UninitializedPropertyAccessException` for any
            // primary this `when` did not already match — which is how an unmapped
            // face (the first `tileLabel` build) crashed the render loop. Gate the
            // branches instead of trusting callers to always pass a chrome face.
            primaryDocsCreated && primary === docBody ->
                fbDocBody
                    ?: (fbBody ?: generateFallbackFace(22, Color.WHITE, primaryScale = primary)?.also { fbBody = it })
                        ?.also { fbDocBody = it }
            primaryDocsCreated && primary === docBodyBold ->
                fbDocBodyBold
                    ?: generateFallbackFace(22, Color.WHITE, bold = true, primaryScale = primary)?.also {
                        fbDocBodyBold = it
                    }
            primaryDocsCreated && primary === docBodyItalic ->
                fbDocBodyItalic
                    ?: generateFallbackFace(22, Color.WHITE, italic = true, primaryScale = primary)?.also {
                        fbDocBodyItalic = it
                    }
            primaryDocsCreated && primary === docBodyBoldItalic ->
                fbDocBodyBoldItalic
                    ?: generateFallbackFace(22, Color.WHITE, bold = true, italic = true, primaryScale = primary)?.also {
                        fbDocBodyBoldItalic = it
                    }
            primaryDocsCreated && primary === docHeading1 ->
                fbDocHeading1
                    ?: generateFallbackFace(30, Color.WHITE, bold = true, primaryScale = primary)?.also {
                        fbDocHeading1 = it
                    }
            primaryDocsCreated && primary === docHeading2 ->
                fbDocHeading2
                    ?: generateFallbackFace(24, Color.WHITE, bold = true, primaryScale = primary)?.also {
                        fbDocHeading2 = it
                    }
            else ->
                fbBody ?: generateFallbackFace(22, Color.WHITE, primaryScale = primary)?.also { fbBody = it }
        }
    }

    /**
     * Floor [fitScale] clamps to, so a fit never renders below readable size.
     */
    const val MIN_FIT_SCALE = 0.6f

    /**
     * Uniform shrink that makes a [naturalW]-wide run fit [availableW]: the ratio
     * when it overflows, else 1. It never returns more than 1, and clamps to
     * [min] so a fit stays readable — a run that still cannot fit at the floor
     * overflows rather than shrinking to nothing.
     *
     * The measuring half of the fit contract: scale the geometry you laid out at 1
     * by this value, then paint it inside [withFaceScale] with the same value, so
     * glyphs and their columns shrink together and cannot disagree. A one-line
     * label that only needs the ink fitted can call
     * [inego.zungjunggame.render.ui.FittedLabel.draw] instead of pairing the two.
     */
    fun fitScale(naturalW: Float, availableW: Float, min: Float = MIN_FIT_SCALE): Float {
        if (naturalW <= 0f || !availableW.isFinite() || availableW <= 0f || naturalW <= availableW) {
            return 1f
        }
        return (availableW / naturalW).coerceAtLeast(min)
    }

    /**
     * Run [block] with [primary] and its paired fallback face (see [fallbackFor])
     * temporarily scaled by [scale], restoring both afterwards — including when
     * [block] throws.
     *
     * Does **not** eagerly open fallback generators or allocate an unused fallback
     * atlas: pure-ASCII callers pay zero fallback work. If [block] itself triggers
     * fallback creation for [primary], [fallbackFor] inherits [primary]'s active scale
     * and the `finally` block restores it back alongside [primary].
     */
    fun <T> withFaceScale(primary: BitmapFont, scale: Float, block: () -> T): T {
        if (scale == 1f) return block()
        val fallback = existingFallbackFor(primary)
        val pSx = primary.data.scaleX
        val pSy = primary.data.scaleY
        val fSx = fallback?.data?.scaleX
        val fSy = fallback?.data?.scaleY
        primary.data.setScale(pSx * scale, pSy * scale)
        if (fallback != null && fSx != null && fSy != null) {
            fallback.data.setScale(fSx * scale, fSy * scale)
        }
        return try {
            block()
        } finally {
            primary.data.setScale(pSx, pSy)
            val afterFallback = existingFallbackFor(primary)
            if (afterFallback != null) {
                if (afterFallback === fallback && fSx != null && fSy != null) {
                    afterFallback.data.setScale(fSx, fSy)
                } else {
                    // Fallback was lazily created during [block] at primary's scaled size.
                    afterFallback.data.setScale(pSx, pSy)
                }
            }
        }
    }

    /**
     * True when [text] contains a non-ASCII glyph that [primary] itself cannot draw.
     * Asking [primary] directly (backed by [CachingFreeTypeFontData]) avoids opening
     * the CJK fallback for symbols or scripts Roboto already covers (`→`, `Δ`, Greek,
     * Latin Extended-A, Cyrillic).
     */
    private fun textNeedsFallback(primary: BitmapFont, text: String): Boolean {
        if (fallbackCreated || fallbackAttempted) return false
        for (c in text) {
            val code = c.code
            if (code < 0x80) continue
            if (!FallbackText.hasUsableGlyph(primary, c)) return true
        }
        return false
    }

    /** Fallback check for [warmGlyphs] using the primary generator's cmap directly. */
    private fun textNeedsFallbackForWarm(text: String): Boolean {
        if (fallbackCreated || fallbackAttempted) return false
        val gen = primaryRegularGen ?: return false
        for (c in text) {
            val code = c.code
            if (code < 0x80 || c.isISOControl()) continue
            if (!gen.hasGlyph(code)) return true
        }
        return false
    }

    private fun buildPrimary(profile: ScriptProfile) {
        uiScale = computeUiScale()
        activeProfile = profile

        val t0 = System.nanoTime()
        val opened = try {
            FontFaceResolver.resolvePrimaryOpened(profile)
        } catch (t: Throwable) {
            Gdx.app.error("Fonts", "resolvePrimary failed: ${t.message}", t)
            FontFaceResolver.OpenedFamily(FontFaceResolver.robotoFamily())
        }
        val family = opened.family

        val regular = opened.regularGen ?: openGen(family.regular)
        val bold = when {
            opened.boldGen != null -> opened.boldGen
            sameFace(family.bold, family.regular) -> regular
            else -> openGen(family.bold)
        }
        // Italic / bold-italic open only when document faces are first requested.

        primaryFamily = family
        primaryRegularGen = regular
        primaryBoldGen = bold
        primaryItalicGen = null
        primaryBoldItalicGen = null
        primaryOwnsGenerators = true

        // Chrome only — menus and the table never need doc/italic atlases.
        body = generate(regular, 22, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        label = generate(regular, 20, AppPalette.seatInk, seedChars = PRIMARY_SEED_CHARS)
        labelActive = generate(regular, 28, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        button = generate(regular, 18, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        // Same design size as `button` for the same reason `smallBold` matches
        // `small`: a weight change must not move the label inside its plate.
        buttonBold = generate(bold, 18, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        small = generate(regular, 14, AppPalette.tint(AppPalette.headerInk, 0.6f), seedChars = PRIMARY_SEED_CHARS)
        // Same design size as `small`, so an emphasised word inside small text
        // changes weight only — never the line height a caller has laid out.
        smallBold = generate(bold, 14, AppPalette.tint(AppPalette.headerInk, 0.6f), seedChars = PRIMARY_SEED_CHARS)
        wallCount = generate(bold, 26, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        display = generate(regular, 48, Color.WHITE, seedChars = DISPLAY_SEED_CHARS, sparse = true)
        tileLabel = generate(
            regular,
            TILE_LABEL_DESIGN_SIZE,
            Color.WHITE,
            seedChars = TILE_LABEL_SEED_CHARS,
            shadowed = false,
            sparse = true,
        )

        primaryCreated = true
        primaryDocsCreated = false
        docBodySharesBody = false
        primaryRegularLabel = family.regular.label
        cjkCapable = family.cjkCapable
        val ms = (System.nanoTime() - t0) / 1_000_000L
        val msg =
            "Primary chrome ready ($profile, ${ms}ms): ${family.regular.label}" +
                if (family.bold.label != family.regular.label) " / bold=${family.bold.label}" else ""
        Gdx.app.log("Fonts", msg)
    }

    /**
     * Document sizes from the same primary family (Reference / coach).
     * [docBody] aliases [body] (same size/color) so we do not double-generate.
     */
    private fun buildPrimaryDocs() {
        if (primaryDocsCreated) return
        val regular = primaryRegularGen ?: return
        val bold = primaryBoldGen ?: regular
        val t0 = System.nanoTime()

        // Open italic faces only now (unused on pure chrome cold start).
        if (primaryItalicGen == null || primaryBoldItalicGen == null) {
            val family = primaryFamily ?: FontFaceResolver.robotoFamily()
            val italic =
                when {
                    sameFace(family.italic, family.regular) -> regular
                    sameFace(family.italic, family.bold) -> bold
                    else -> openGen(family.italic)
                }
            val boldItalic =
                when {
                    sameFace(family.boldItalic, family.bold) -> bold
                    sameFace(family.boldItalic, family.regular) -> regular
                    sameFace(family.boldItalic, family.italic) -> italic
                    else -> openGen(family.boldItalic)
                }
            primaryItalicGen = italic
            primaryBoldItalicGen = boldItalic
        }
        val italic = primaryItalicGen ?: regular
        val boldItalic = primaryBoldItalicGen ?: bold

        docBody = body
        docBodySharesBody = true
        docBodyBold = generate(bold, 22, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        docBodyItalic = generate(italic, 22, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        docBodyBoldItalic = generate(boldItalic, 22, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        docHeading1 = generate(bold, 30, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        docHeading2 = generate(bold, 24, Color.WHITE, seedChars = PRIMARY_SEED_CHARS)
        primaryDocsCreated = true
        val ms = (System.nanoTime() - t0) / 1_000_000L
        Gdx.app.log("Fonts", "Primary doc faces ready (${ms}ms)")
    }

    /**
     * World pixels per design pixel, clamped to [1, MAX_UI_SCALE].
     *
     * Desktop, Android, and a desktop browser use the backend density as
     * reported. A phone browser's canvas is in device pixels and TeaVM reports
     * density as `devicePixelRatio * 96/160`, which leaves one design pixel at
     * 0.6 CSS pixels. That coarse-pointer session multiplies by 160/96 so one
     * design pixel is one CSS pixel. A 1× browser is exactly 1 either way.
     */
    fun designPxScale(): Float {
        val density = Gdx.graphics.density
        val phoneWeb = Gdx.app?.type == Application.ApplicationType.WebGL &&
            InputProfile.webCoarsePointer
        val scale = if (phoneWeb) density * (160f / 96f) else density
        return scale.coerceIn(1f, MAX_UI_SCALE)
    }

    private fun computeUiScale(): Float = designPxScale()

    private fun sameFace(a: FontFaceResolver.Face, b: FontFaceResolver.Face): Boolean =
        a.faceIndex == b.faceIndex && a.file.path() == b.file.path()

    private fun openGen(face: FontFaceResolver.Face): FreeTypeFontGenerator =
        FreeTypeFontGenerator(face.file, face.faceIndex)

    /**
     * Drop process-global font state inherited from a prior GL context (Android
     * activity recreation in the same process) before bootstrapping into the new
     * context. No-op on a genuine cold process start when nothing has been built yet.
     *
     * Dead GL textures are abandoned rather than `glDeleteTextures`-disposed because
     * the new context may already have reissued those names; native FreeType
     * generators are still closed cleanly.
     */
    fun invalidateForNewContext() {
        if (!primaryCreated && !fallbackCreated && !fallbackAttempted) return
        Gdx.app?.log("Fonts", "invalidating inherited faces for new GL context")
        disposeAllFonts(disposeTextures = false)
    }

    /**
     * Recover the glyph atlases after a real GL context loss.
     *
     * **Always regenerates the faces.** A cheaper re-upload was tried and is not
     * safe on Android: `glIsTexture` keeps reporting the page texture as live
     * after the driver has wiped its contents (observed as `glLive=true` with
     * black-box text), so the re-upload path silently restores only the seeded
     * glyphs and every glyph added incrementally is lost. Regenerating is the only
     * sequence that has been observed to be correct on a device that actually
     * reproduces the failure.
     */
    fun recoverAfterBackgrounding(reason: String) {
        if (!primaryCreated && !fallbackCreated) return
        if (Gdx.gl == null) return
        rebuildAllFaces(reason)
    }

    /**
     * Evict every face and rebuild it from scratch. Needed when the driver
     * discarded or wiped the page textures: they cannot be revived by
     * re-uploading, so the only recovery is a fresh `FreeTypeFontGenerator` pass.
     *
     * Callers must be on the render thread.
     */
    fun rebuildAllFaces(reason: String) {
        val profile = activeProfile
        Gdx.app?.log("Fonts", "rebuilding all faces ($reason, $profile)")
        disposeAllFonts()
        buildPrimary(profile)
    }

    /**
     * DIAGNOSTIC hook for reproducing a lost context on demand: if the marker
     * file exists, consume it and report. Lets the recovery path be exercised on
     * a device that never loses its context by itself.
     */
    fun consumeForceRebuildMarker(): Boolean {
        return try {
            val handle = Gdx.files.local("zj_force_texture_recreate")
            if (!handle.exists()) return false
            handle.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    class BootstrapFace(
        val font: BitmapFont,
        private val generator: FreeTypeFontGenerator,
    ) : Disposable {
        override fun dispose() {
            font.dispose()
            try {
                generator.dispose()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    /**
     * Creates a single lightweight face specifically for cold-start bootstrap presentation.
     * Only glyphs present in [seedChars] (plus metrics probe chars) are rasterized into a
     * non-incremental minimal atlas (typically 128×128).
     */
    fun createBootstrapFace(locale: AppLocale, seedChars: String): BootstrapFace {
        uiScale = computeUiScale()
        val profile = locale.scriptProfile
        val opened = try {
            FontFaceResolver.resolvePrimaryOpened(profile)
        } catch (t: Throwable) {
            Gdx.app?.error("Fonts", "resolvePrimary for bootstrap failed: ${t.message}", t)
            FontFaceResolver.OpenedFamily(FontFaceResolver.robotoFamily())
        }
        val gen = opened.regularGen ?: openGen(opened.family.regular)
        if (opened.boldGen != null && opened.boldGen !== opened.regularGen) {
            disposeGenerators(opened.boldGen)
        }
        val font = generate(
            gen = gen,
            designSize = 22,
            color = AppPalette.shadowInk,
            seedChars = "$METRICS_SEED_CHARS$seedChars",
            shadowed = true,
            incremental = false,
        )
        return BootstrapFace(font, gen)
    }

    /**
     * Atlas page side (power of two) for an incremental face at [pixelSize].
     *
     * LibGDX's `FreeTypeFontGenerator` sizes every incremental `PixmapPacker` directly
     * to `maxTextureSize`, so a blanket 2048 allocates and uploads a 16 MB RGBA8888
     * page for every single face (including 2-glyph fallback faces). Sizing per face
     * drops typical pages to 256×256 (256 KB) or 512×512 (1 MB); `PixmapPacker` adds
     * another page automatically if a face ever overflows.
     */
    private fun atlasPageSize(pixelSize: Int, sparse: Boolean): Int = when {
        sparse -> if (pixelSize <= 40) 256 else 512
        pixelSize <= 36 -> 512
        else -> 1024
    }

    /**
     * Build one face. [shadowed] is false only for [tileLabel]: a baked drop shadow is
     * wanted under chrome text but turns into a dark fringe on a knocked-out tag, and
     * the shadow colour is set explicitly rather than left to the parameter default so
     * "no shadow" cannot silently become "some shadow" on another backend.
     */
    private fun generate(
        gen: FreeTypeFontGenerator,
        designSize: Int,
        color: Color,
        seedChars: String = PRIMARY_SEED_CHARS,
        shadowed: Boolean = true,
        sparse: Boolean = false,
        incremental: Boolean = true,
    ): BitmapFont {
        val pixelSize = (designSize * uiScale).roundToInt().coerceAtLeast(designSize)
        FreeTypeFontGenerator.setMaxTextureSize(
            if (incremental) atlasPageSize(pixelSize, sparse) else MAX_STATIC_TEXTURE_SIZE,
        )
        val param = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
            this.size = pixelSize
            this.color = color
            if (shadowed) {
                val shadow = (1f * uiScale).roundToInt().coerceAtLeast(1)
                shadowOffsetX = shadow
                shadowOffsetY = shadow
                shadowColor = AppPalette.tint(AppPalette.shadow, 0.55f)
            } else {
                shadowOffsetX = 0
                shadowOffsetY = 0
                shadowColor = Color.CLEAR
            }
            minFilter = Texture.TextureFilter.Linear
            magFilter = Texture.TextureFilter.Linear
            characters = seedChars
            this.incremental = incremental
        }
        val data = CachingFreeTypeFontData()
        val font = gen.generateFont(param, data)
        if (font.data.xHeight <= 0f) {
            font.data.xHeight = font.data.capHeight * 0.5f
        }
        font.data.setScale(1f)
        font.setUseIntegerPositions(true)
        if (incremental) {
            // `generateData` leaves `PixmapPacker.packToTexture = false` until the first
            // `getGlyphs()` run. Priming with an already-seeded glyph enables fast
            // `glTexSubImage2D` immediately so an early `getGlyph()` probe (such as
            // `FallbackText.hasUsableGlyph`) never dirties the whole page and triggers a
            // full-atlas re-upload.
            val primer = seedChars.firstOrNull { !it.isISOControl() }
            if (primer != null) {
                primerLayout.setText(font, primer.toString())
            }
            data.cacheMissing = true
        }
        return font
    }

    private fun disposeFallbackOnly(disposeTextures: Boolean = true) {
        if (disposeTextures) {
            // fbDocBody may alias fbBody — identity-dedupe before dispose.
            disposeFontList(
                fbBody, fbLabel, fbLabelActive, fbButton, fbButtonBold, fbSmall, fbSmallBold,
                fbWallCount, fbDisplay,
                fbTileLabel,
                fbDocBody, fbDocBodyBold, fbDocBodyItalic, fbDocBodyBoldItalic,
                fbDocHeading1, fbDocHeading2,
            )
        }
        fbBody = null
        fbLabel = null
        fbLabelActive = null
        fbButton = null
        fbButtonBold = null
        fbSmall = null
        fbSmallBold = null
        fbWallCount = null
        fbDisplay = null
        fbTileLabel = null
        fbDocBody = null
        fbDocBodyBold = null
        fbDocBodyItalic = null
        fbDocBodyBoldItalic = null
        fbDocHeading1 = null
        fbDocHeading2 = null
        if (fallbackOwnsGenerators) {
            disposeGenerators(
                fallbackRegularGen, fallbackBoldGen, fallbackItalicGen, fallbackBoldItalicGen,
            )
        }
        fallbackFamily = null
        fallbackRegularGen = null
        fallbackBoldGen = null
        fallbackItalicGen = null
        fallbackBoldItalicGen = null
        fallbackCreated = false
        fallbackOwnsGenerators = true
    }

    private fun disposeAllFonts(disposeTextures: Boolean = true) {
        disposeFallbackOnly(disposeTextures)
        fallbackAttempted = false
        if (primaryCreated) {
            if (disposeTextures) {
                disposeFontList(
                    if (::body.isInitialized) body else null,
                    if (::label.isInitialized) label else null,
                    if (::labelActive.isInitialized) labelActive else null,
                    if (::button.isInitialized) button else null,
                    if (::buttonBold.isInitialized) buttonBold else null,
                    if (::small.isInitialized) small else null,
                    if (::smallBold.isInitialized) smallBold else null,
                    if (::wallCount.isInitialized) wallCount else null,
                    if (::display.isInitialized) display else null,
                    if (::tileLabel.isInitialized) tileLabel else null,
                    // docBody may alias body
                    if (::docBody.isInitialized && !docBodySharesBody) docBody else null,
                    if (::docBodyBold.isInitialized) docBodyBold else null,
                    if (::docBodyItalic.isInitialized) docBodyItalic else null,
                    if (::docBodyBoldItalic.isInitialized) docBodyBoldItalic else null,
                    if (::docHeading1.isInitialized) docHeading1 else null,
                    if (::docHeading2.isInitialized) docHeading2 else null,
                )
            }
            if (primaryOwnsGenerators) {
                disposeGenerators(
                    primaryRegularGen, primaryBoldGen, primaryItalicGen, primaryBoldItalicGen,
                )
            }
        }
        primaryFamily = null
        primaryRegularGen = null
        primaryBoldGen = null
        primaryItalicGen = null
        primaryBoldItalicGen = null
        primaryCreated = false
        primaryDocsCreated = false
        docBodySharesBody = false
        primaryRegularLabel = null
        cjkCapable = false
        uiScale = 1f
    }

    private fun disposeGenerators(vararg gens: FreeTypeFontGenerator?) {
        for (g in linkedSetOfNotNull(*gens)) {
            try {
                g.dispose()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun disposeFontList(vararg fonts: BitmapFont?) {
        val seen = HashSet<Int>(fonts.size)
        for (f in fonts) {
            if (f == null) continue
            val id = System.identityHashCode(f)
            if (!seen.add(id)) continue
            try {
                f.dispose()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    override fun dispose() {
        disposeAllFonts()
        fallbackAttempted = false
        activeProfile = ScriptProfile.LatinCyrillic
    }

    private fun <T : Any> linkedSetOfNotNull(vararg items: T?): LinkedHashSet<T> {
        val set = LinkedHashSet<T>(items.size)
        for (item in items) if (item != null) set += item
        return set
    }
}
