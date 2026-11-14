package inego.zungjunggame.render.fonts

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator

/**
 * Resolves FreeType faces for [Fonts] by [ScriptProfile].
 *
 * - **Primary** ([resolvePrimary]): locale script face — Roboto for
 *   Latin/Cyrillic; Hans / Hant / Jpan system or bundled CJK.
 * - **Fallback** ([resolveFallback]): second face for missing glyphs
 *   (CJK when primary is Roboto; optional Roboto when primary is CJK).
 *
 * Chrome and Reference share the same primary + fallback pair. CJK discovery
 * may open large system TTCs — call [resolveFallback] / CJK primary lazily,
 * never on the gameplay critical path for Latin locales.
 *
 * ## Where CJK comes from
 *
 * - **Desktop / Android / iOS:** OS fonts first (YaHei, DroidSans, PingFang, …).
 *   Bundled Noto is only a fallback so native packages stay small.
 * - **WebGL:** no filesystem fonts. Uses bundled
 *   `assets/fonts/NotoSansSC-Regular.otf` (TeaVM ships it; Android/Desktop jars
 *   exclude it).
 *
 * Prefer small fixed CJK faces (e.g. `DroidSansChinese.ttf`, ~8 MB) over the
 * multi-language `NotoSansCJK-*.ttc` (~30 MB+). Variable fonts (`*VF*`) are
 * skipped — FreeType support is incomplete and they tend to be huge.
 */
object FontFaceResolver {

    data class Face(
        val file: FileHandle,
        val faceIndex: Int = 0,
        /** Human-readable source for logs (path or asset name). */
        val label: String,
    )

    data class Family(
        val regular: Face,
        val bold: Face,
        val italic: Face,
        val boldItalic: Face,
        val cjkCapable: Boolean,
    )

    /** Han probe characters (common in Zung Jung pattern names). */
    private val CJK_PROBE = intArrayOf(
        '中'.code,
        '和'.code,
        '牌'.code,
    )

    /**
     * Glyphs FreeType uses for BitmapFont x-height / related metrics.
     * Pure CJK faces without Latin fail [com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.generateFont].
     */
    private val XHEIGHT_PROBE = intArrayOf(
        'x'.code,
        'X'.code,
        'v'.code,
        'V'.code,
        'm'.code,
    )

    /**
     * Prefer faces under this size on Android. Larger TTCs are still tried if no
     * smaller CJK face probes successfully.
     */
    private const val ANDROID_PREFERRED_MAX_BYTES = 12L * 1024L * 1024L

    /** Primary family for [profile] (Roboto or script CJK). */
    fun resolvePrimary(profile: ScriptProfile): Family {
        return when (profile) {
            ScriptProfile.LatinCyrillic -> robotoFamily()
            ScriptProfile.Hans -> resolveCjkFamily(CjkScript.Hans) ?: robotoFamily()
            ScriptProfile.Hant -> resolveCjkFamily(CjkScript.Hant) ?: resolveCjkFamily(CjkScript.Hans) ?: robotoFamily()
            ScriptProfile.Jpan -> resolveCjkFamily(CjkScript.Jpan) ?: resolveCjkFamily(CjkScript.Hans) ?: robotoFamily()
        }
    }

    /**
     * Optional second family for missing glyphs.
     *
     * - Latin primary → CJK face (Han in English/Russian reference).
     * - CJK primary → Roboto only if useful for rare Latin (always available).
     * - Returns null only when CJK cannot be found for a Latin primary.
     */
    fun resolveFallback(profile: ScriptProfile): Family? {
        return when (profile) {
            ScriptProfile.LatinCyrillic -> {
                try {
                    resolveCjkFamily(CjkScript.Hans)
                } catch (t: Throwable) {
                    Gdx.app.error(TAG, "resolveFallback CJK failed: ${t.message}", t)
                    null
                }
            }
            // CJK primaries already cover Latin poorly but adequately; Roboto fills gaps.
            ScriptProfile.Hans, ScriptProfile.Hant, ScriptProfile.Jpan -> robotoFamily()
        }
    }

    /** @deprecated Use [resolvePrimary] / [resolveFallback]. */
    @Deprecated("Use resolvePrimary(ScriptProfile)", ReplaceWith("resolvePrimary(ScriptProfile.LatinCyrillic)"))
    fun resolveUi(): Family = robotoFamily()

    private enum class CjkScript { Hans, Hant, Jpan }

    private fun resolveCjkFamily(script: CjkScript): Family? {
        // Native platforms already have CJK in the OS; prefer that so Desktop
        // `run` (workingDir = assets/) does not switch to bundled Noto, and so
        // we never open an 8 MB asset when YaHei / DroidSans is right there.
        // WebGL has no absolute/external files — bundled Noto is the only path.
        val preferSystem = Gdx.app.type != Application.ApplicationType.WebGL
        if (preferSystem) {
            val systemRegular = firstCjkFace(*systemRegularCandidates(script))
            if (systemRegular != null) {
                val systemBold = firstCjkFace(*systemBoldCandidates(script)) ?: systemRegular
                return familyFrom(systemRegular, systemBold)
            }
        }
        val bundledRegular = firstCjkFace(*bundledRegularCandidates(script)) ?: return null
        val bold = firstCjkFace(*bundledBoldCandidates(script)) ?: bundledRegular
        return familyFrom(bundledRegular, bold)
    }

    private fun familyFrom(regular: Face, bold: Face): Family =
        Family(
            regular = regular,
            bold = bold,
            italic = regular,
            boldItalic = bold,
            cjkCapable = true,
        )

    private fun bundledRegularCandidates(script: CjkScript): Array<Candidate> =
        when (script) {
            CjkScript.Hans -> arrayOf(
                asset("fonts/NotoSansSC-Regular.otf"),
                asset("fonts/NotoSansSC-Regular.ttf"),
                asset("fonts/NotoSansCJKsc-Regular.otf"),
                asset("fonts/ui-cjk-regular.ttf"),
                asset("fonts/ui-cjk-regular.otf"),
                asset("fonts/NotoSansCJK-Regular.ttc"),
            )
            CjkScript.Hant -> arrayOf(
                asset("fonts/NotoSansTC-Regular.otf"),
                asset("fonts/NotoSansTC-Regular.ttf"),
                asset("fonts/NotoSansCJKtc-Regular.otf"),
                asset("fonts/NotoSansHK-Regular.otf"),
                asset("fonts/ui-cjk-regular.ttf"),
                asset("fonts/ui-cjk-regular.otf"),
                asset("fonts/NotoSansCJK-Regular.ttc"),
            )
            CjkScript.Jpan -> arrayOf(
                asset("fonts/NotoSansJP-Regular.otf"),
                asset("fonts/NotoSansJP-Regular.ttf"),
                asset("fonts/NotoSansCJKjp-Regular.otf"),
                asset("fonts/ui-cjk-regular.ttf"),
                asset("fonts/ui-cjk-regular.otf"),
                asset("fonts/NotoSansCJK-Regular.ttc"),
            )
        }

    private fun bundledBoldCandidates(script: CjkScript): Array<Candidate> =
        when (script) {
            CjkScript.Hans -> arrayOf(
                asset("fonts/NotoSansSC-Bold.otf"),
                asset("fonts/NotoSansSC-Bold.ttf"),
                asset("fonts/NotoSansCJKsc-Bold.otf"),
                asset("fonts/ui-cjk-bold.ttf"),
                asset("fonts/ui-cjk-bold.otf"),
                asset("fonts/NotoSansCJK-Bold.ttc"),
            )
            CjkScript.Hant -> arrayOf(
                asset("fonts/NotoSansTC-Bold.otf"),
                asset("fonts/NotoSansTC-Bold.ttf"),
                asset("fonts/NotoSansHK-Bold.otf"),
                asset("fonts/ui-cjk-bold.ttf"),
                asset("fonts/ui-cjk-bold.otf"),
                asset("fonts/NotoSansCJK-Bold.ttc"),
            )
            CjkScript.Jpan -> arrayOf(
                asset("fonts/NotoSansJP-Bold.otf"),
                asset("fonts/NotoSansJP-Bold.ttf"),
                asset("fonts/ui-cjk-bold.ttf"),
                asset("fonts/ui-cjk-bold.otf"),
                asset("fonts/NotoSansCJK-Bold.ttc"),
            )
        }

    fun robotoFamily(): Family {
        val regular = Face(Gdx.files.internal("fonts/Roboto-Regular.ttf"), 0, "assets/fonts/Roboto-Regular.ttf")
        val bold = Face(Gdx.files.internal("fonts/Roboto-Bold.ttf"), 0, "assets/fonts/Roboto-Bold.ttf")
        val italic = Face(Gdx.files.internal("fonts/Roboto-Italic.ttf"), 0, "assets/fonts/Roboto-Italic.ttf")
        val boldItalic = Face(
            Gdx.files.internal("fonts/Roboto-BoldItalic.ttf"),
            0,
            "assets/fonts/Roboto-BoldItalic.ttf",
        )
        return Family(regular, bold, italic, boldItalic, cjkCapable = false)
    }

    private fun asset(path: String): Candidate =
        Candidate(lazyHandle = { Gdx.files.internal(path) }, faceIndex = 0, label = "assets/$path")

    private fun absolute(path: String, faceIndex: Int = 0): Candidate =
        Candidate(lazyHandle = { Gdx.files.absolute(path) }, faceIndex = faceIndex, label = path)

    private data class Candidate(
        val lazyHandle: () -> FileHandle,
        val faceIndex: Int,
        val label: String,
    )

    private fun firstCjkFace(vararg candidates: Candidate): Face? {
        val preferSmall = Gdx.app.type == Application.ApplicationType.Android
        if (preferSmall) {
            for (c in candidates) {
                val face = tryCandidate(c, maxBytes = ANDROID_PREFERRED_MAX_BYTES) ?: continue
                return face
            }
        }
        for (c in candidates) {
            val face = tryCandidate(c, maxBytes = Long.MAX_VALUE) ?: continue
            return face
        }
        return null
    }

    private fun tryCandidate(c: Candidate, maxBytes: Long): Face? {
        val handle = try {
            c.lazyHandle()
        } catch (_: Throwable) {
            return null
        }
        if (!handleExists(handle)) return null
        if (looksLikeVariableFont(c.label)) {
            Gdx.app.debug(TAG, "Skip variable font candidate ${c.label}")
            return null
        }
        val len = handleLength(handle)
        if (len >= 0L && len > maxBytes) {
            Gdx.app.debug(TAG, "Defer large font ${c.label} (${len}B > $maxBytes)")
            return null
        }
        if (!probeCjk(handle, c.faceIndex)) return null
        return Face(handle, c.faceIndex, c.label)
    }

    private fun handleExists(handle: FileHandle): Boolean =
        try {
            handle.exists()
        } catch (_: Throwable) {
            false
        }

    private fun handleLength(handle: FileHandle): Long =
        try {
            handle.length()
        } catch (_: Throwable) {
            -1L
        }

    private fun looksLikeVariableFont(label: String): Boolean {
        val n = label.lowercase()
        return n.contains("vf") ||
            n.contains("variable") ||
            n.contains("vfcn") ||
            n.contains("vftc")
    }

    private fun probeCjk(file: FileHandle, faceIndex: Int): Boolean {
        var gen: FreeTypeFontGenerator? = null
        return try {
            gen = FreeTypeFontGenerator(file, faceIndex)
            val hasHan = CJK_PROBE.any { code ->
                try {
                    gen.hasGlyph(code)
                } catch (_: Throwable) {
                    false
                }
            }
            if (!hasHan) return false
            val hasMetrics = XHEIGHT_PROBE.any { code ->
                try {
                    gen.hasGlyph(code)
                } catch (_: Throwable) {
                    false
                }
            }
            if (!hasMetrics) {
                Gdx.app.debug(
                    TAG,
                    "Skip ${file.path()} face=$faceIndex: Han present but no x-height Latin",
                )
            }
            hasMetrics
        } catch (t: Throwable) {
            Gdx.app.log(TAG, "Skip font ${file.path()} face=$faceIndex: ${t.message}")
            false
        } finally {
            try {
                gen?.dispose()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun systemRegularCandidates(script: CjkScript): Array<Candidate> {
        val list = ArrayList<Candidate>(32)
        when (Gdx.app.type) {
            Application.ApplicationType.Android -> {
                when (script) {
                    CjkScript.Hans -> {
                        list += absolute("/system/fonts/DroidSansChinese.ttf")
                        list += absolute("/system/fonts/DroidSansFallback.ttf")
                        list += absolute("/system/fonts/DroidSansFallbackFull.ttf")
                        list += absolute("/system/fonts/NotoSansSC-Regular.otf")
                        list += absolute("/system/fonts/NotoSansSC-Regular.ttf")
                        list += absolute("/system/fonts/NotoSansCJKsc-Regular.otf")
                        list += absolute("/system/fonts/NotoSansHans-Regular.otf")
                        list += absolute("/system/fonts/HyphenFallback.ttf")
                        list += absolute("/system/fonts/NotoSansCJK-Regular.ttc", 0)
                        list += absolute("/system/fonts/NotoSansCJK-Regular.otf")
                    }
                    CjkScript.Hant -> {
                        list += absolute("/system/fonts/NotoSansTC-Regular.otf")
                        list += absolute("/system/fonts/NotoSansCJKtc-Regular.otf")
                        list += absolute("/system/fonts/NotoSansHK-Regular.otf")
                        list += absolute("/system/fonts/DroidSansFallback.ttf")
                        list += absolute("/system/fonts/DroidSansFallbackFull.ttf")
                        list += absolute("/system/fonts/NotoSansCJK-Regular.ttc", 0)
                    }
                    CjkScript.Jpan -> {
                        list += absolute("/system/fonts/NotoSansJP-Regular.otf")
                        list += absolute("/system/fonts/NotoSansCJKjp-Regular.otf")
                        list += absolute("/system/fonts/DroidSansJapanese.ttf")
                        list += absolute("/system/fonts/NotoSerifCJK-Regular.ttc", 0)
                        list += absolute("/system/fonts/DroidSansFallback.ttf")
                        list += absolute("/system/fonts/NotoSansCJK-Regular.ttc", 0)
                    }
                }
            }
            Application.ApplicationType.Desktop -> {
                when {
                    isWindows -> when (script) {
                        CjkScript.Hans -> {
                            list += absolute("C:/Windows/Fonts/msyh.ttc", 0)
                            list += absolute("C:/Windows/Fonts/msyhl.ttc", 0)
                            list += absolute("C:/Windows/Fonts/simhei.ttf")
                            list += absolute("C:/Windows/Fonts/simsun.ttc", 0)
                            list += absolute("C:/Windows/Fonts/NotoSansSC-Regular.otf")
                            list += absolute("C:/Windows/Fonts/NotoSansCJKsc-Regular.otf")
                            list += absolute("C:/Windows/Fonts/SourceHanSansSC-Regular.otf")
                        }
                        CjkScript.Hant -> {
                            list += absolute("C:/Windows/Fonts/msjh.ttc", 0) // Microsoft JhengHei
                            list += absolute("C:/Windows/Fonts/mingliu.ttc", 0)
                            list += absolute("C:/Windows/Fonts/kaiu.ttf")
                            list += absolute("C:/Windows/Fonts/NotoSansTC-Regular.otf")
                            list += absolute("C:/Windows/Fonts/NotoSansHK-Regular.otf")
                            list += absolute("C:/Windows/Fonts/msyh.ttc", 0) // last-resort SC
                        }
                        CjkScript.Jpan -> {
                            list += absolute("C:/Windows/Fonts/yugothic.ttf")
                            list += absolute("C:/Windows/Fonts/YuGothR.ttc", 0)
                            list += absolute("C:/Windows/Fonts/msgothic.ttc", 0)
                            list += absolute("C:/Windows/Fonts/meiryo.ttc", 0)
                            list += absolute("C:/Windows/Fonts/NotoSansJP-Regular.otf")
                            list += absolute("C:/Windows/Fonts/msyh.ttc", 0)
                        }
                    }
                    isMac -> when (script) {
                        CjkScript.Hans -> {
                            list += absolute("/System/Library/Fonts/PingFang.ttc", 0)
                            list += absolute("/System/Library/Fonts/STHeiti Light.ttc", 0)
                            list += absolute("/System/Library/Fonts/Hiragino Sans GB.ttc", 0)
                            list += absolute("/Library/Fonts/Arial Unicode.ttf")
                        }
                        CjkScript.Hant -> {
                            list += absolute("/System/Library/Fonts/PingFang.ttc", 0)
                            list += absolute("/System/Library/Fonts/STHeiti Light.ttc", 0)
                            list += absolute("/Library/Fonts/Arial Unicode.ttf")
                        }
                        CjkScript.Jpan -> {
                            list += absolute("/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc", 0)
                            list += absolute("/System/Library/Fonts/Hiragino Sans GB.ttc", 0)
                            list += absolute("/System/Library/Fonts/PingFang.ttc", 0)
                            list += absolute("/Library/Fonts/Arial Unicode.ttf")
                        }
                    }
                    else -> {
                        list += absolute("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 0)
                        list += absolute("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", 0)
                        list += absolute("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc", 0)
                        list += when (script) {
                            CjkScript.Hans ->
                                absolute("/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf")

                            CjkScript.Hant ->
                                absolute("/usr/share/fonts/opentype/noto/NotoSansCJKtc-Regular.otf")

                            CjkScript.Jpan ->
                                absolute("/usr/share/fonts/opentype/noto/NotoSansCJKjp-Regular.otf")
                        }
                        list += absolute("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc", 0)
                        list += absolute("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf")
                    }
                }
            }
            Application.ApplicationType.iOS -> {
                list += absolute("/System/Library/Fonts/Core/PingFang.ttc", 0)
                list += absolute("/System/Library/Fonts/LanguageSupport/PingFang.ttc", 0)
                list += absolute("/System/Library/Fonts/Cache/PingFang.ttc", 0)
            }
            else -> Unit
        }
        return list.toTypedArray()
    }

    private fun systemBoldCandidates(script: CjkScript): Array<Candidate> {
        val list = ArrayList<Candidate>(16)
        when (Gdx.app.type) {
            Application.ApplicationType.Android -> {
                list += absolute("/system/fonts/DroidSansChinese.ttf")
                list += absolute("/system/fonts/DroidSansFallback.ttf")
                list += absolute("/system/fonts/NotoSansSC-Bold.otf")
                list += absolute("/system/fonts/NotoSansCJKsc-Bold.otf")
                list += absolute("/system/fonts/NotoSansCJK-Bold.ttc", 0)
            }
            Application.ApplicationType.Desktop -> {
                when {
                    isWindows -> when (script) {
                        CjkScript.Hans -> {
                            list += absolute("C:/Windows/Fonts/msyhbd.ttc", 0)
                            list += absolute("C:/Windows/Fonts/simhei.ttf")
                            list += absolute("C:/Windows/Fonts/NotoSansSC-Bold.otf")
                        }
                        CjkScript.Hant -> {
                            list += absolute("C:/Windows/Fonts/msjhbd.ttc", 0)
                            list += absolute("C:/Windows/Fonts/msyhbd.ttc", 0)
                        }
                        CjkScript.Jpan -> {
                            list += absolute("C:/Windows/Fonts/YuGothB.ttc", 0)
                            list += absolute("C:/Windows/Fonts/msgothic.ttc", 0)
                        }
                    }
                    isMac -> {
                        list += absolute("/System/Library/Fonts/PingFang.ttc", 1)
                        list += absolute("/System/Library/Fonts/STHeiti Medium.ttc", 0)
                    }
                    else -> {
                        list += absolute("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", 0)
                        list += absolute("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 0)
                        list += absolute("/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc", 0)
                    }
                }
            }
            else -> Unit
        }
        return list.toTypedArray()
    }

    private val osName: String
        get() = System.getProperty("os.name").orEmpty().lowercase()

    private val isWindows: Boolean
        get() = osName.contains("win")

    private val isMac: Boolean
        get() = osName.contains("mac") || osName.contains("darwin")

    private const val TAG = "FontFaceResolver"
}
