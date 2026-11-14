package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color

/**
 * The app's single style palette.
 *
 * Every chrome surface, rim, ink and accent reads from here: menus, overlays,
 * panels, tables, buttons, sliders and the felt. Change a value here and the
 * whole app follows; do not restate one of these colours at a call site.
 *
 * This is the only place in the UI that knows a specific colour. Components
 * ask for a role ([ink], [accent], [totalAccent], [danger], …) and never name
 * a hue. Role names describe what a colour is for, not which component uses
 * it, so they stay reusable across windows, dialogs and tables.
 *
 * **Gold is the accent.** Selection, ratings, focus and content emphasis use
 * [accent], a warm gold that reads against the felt; [totalAccent] is the same
 * gold under the name aggregate figures use. Warm brown is not part of the
 * chrome: surfaces and rims stay in the felt's greens, and hover takes the
 * accent rather than a separate brown plate.
 *
 * Artwork that is genuinely its own material (the tile-back pattern) is not
 * style and stays with the artwork.
 */
object AppPalette {
    // ── Surfaces ────────────────────────────────────────────────────────────

    val panel = Color(0.06f, 0.10f, 0.09f, 0.97f)
    val panelDeep = Color(0.04f, 0.07f, 0.06f, 0.98f)
    val plate = Color(0.10f, 0.16f, 0.13f, 0.92f)
    val plateDeep = Color(0.08f, 0.13f, 0.11f, 0.94f)
    val plateDarker = Color(0.10f, 0.12f, 0.11f, 0.92f)
    val plateMuted = Color(0.08f, 0.12f, 0.10f, 0.55f)
    val plateRaised = Color(0.14f, 0.22f, 0.18f, 0.55f)
    val plateHover = Color(0.22f, 0.38f, 0.28f, 0.96f)
    val plateSelected = Color(0.18f, 0.32f, 0.24f, 0.96f)
    val plateSelectedHover = Color(0.28f, 0.42f, 0.30f, 0.98f)
    /** A movable panel's grab plate. */
    val panelGrab = tint(plateHover, 0.97f)
    val scrim = Color(0f, 0f, 0f, 0.55f)
    val scrimLight = Color(0f, 0f, 0f, 0.40f)
    val scrimFaint = Color(0f, 0f, 0f, 0.28f)
    val scrimMid = Color(0f, 0f, 0f, 0.45f)
    val scrimDeep = Color(0f, 0f, 0f, 0.60f)
    val scrimHeavy = Color(0f, 0f, 0f, 0.65f)
    val shadow = Color(0f, 0f, 0f, 1f)

    /** A filled button at rest. */
    val buttonFill = Color(0.14f, 0.18f, 0.20f, 0.94f)
    /** A filled button that carries the primary action. */
    val buttonFillPrimary = Color(0.14f, 0.28f, 0.20f, 0.95f)
    val buttonFillHover = Color(0.22f, 0.28f, 0.32f, 0.96f)
    val buttonFillDisabled = Color(0.12f, 0.14f, 0.16f, 0.88f)

    // ── Rims ────────────────────────────────────────────────────────────────

    val rim = Color(0.75f, 0.85f, 0.70f, 0.40f)
    val rimStrong = Color(0.75f, 0.85f, 0.70f, 0.70f)
    val rimFaint = Color(0.75f, 0.85f, 0.70f, 0.25f)
    val rimInert = Color(0.55f, 0.58f, 0.50f, 0.22f)
    val rimDisabled = Color(0.55f, 0.55f, 0.50f, 0.55f)
    val panelRim = Color(0.93f, 0.90f, 0.80f, 0.55f)
    val panelRimGrab = tint(panelRim, 0.92f)
    /** A table rule: the quiet line between header, rows and footer. */
    val rule = tint(rim, 0.35f)

    // ── Ink ─────────────────────────────────────────────────────────────────

    val ink = Color(0.96f, 0.94f, 0.88f, 1f)
    val inkStrong = Color(0.99f, 0.98f, 0.94f, 1f)
    val inkSoft = Color(0.94f, 0.92f, 0.86f, 1f)
    val inkMuted = Color(0.76f, 0.80f, 0.73f, 0.90f)
    val inkInert = Color(0.55f, 0.58f, 0.52f, 1f)
    val inkQuiet = Color(0.60f, 0.62f, 0.55f, 1f)
    val inkDisabled = Color(0.70f, 0.68f, 0.62f, 0.85f)
    val inkWarm = Color(0.90f, 0.88f, 0.80f, 1f)
    val inkWarmMuted = Color(0.70f, 0.68f, 0.58f, 1f)
    val inkSage = Color(0.80f, 0.90f, 0.78f, 1f)
    val seatInk = Color(0.78f, 0.82f, 0.74f, 1f)
    val shadowInk = Color(0.85f, 0.85f, 0.82f, 0.95f)
    /** Table header labels: the brightest ink on a row band. */
    val headerInk = Color(1f, 1f, 1f, 1f)
    /** A table row's primary ink. */
    val rowInk = Color(0.92f, 0.92f, 0.88f, 1f)
    /** A table row's secondary ink. */
    val rowInkMuted = Color(0.85f, 0.86f, 0.80f, 1f)
    /** A table row's detail ink. */
    val rowInkSoft = Color(0.90f, 0.90f, 0.84f, 1f)

    // ── Accent ──────────────────────────────────────────────────────────────

    /** The app accent: the warm gold of a lit highlight. */
    val accent = Color(1f, 0.92f, 0.55f, 1f)
    val accentBright = Color(1f, 0.97f, 0.72f, 1f)
    val accentSoft = tint(accent, 0.95f)
    val accentFaint = tint(accent, 0.22f)
    /** Aggregate and total figures: the accent, named for what it marks. */
    val totalAccent = accent
    val linkInk = Color(0.75f, 0.92f, 1f, 1f)
    val linkInkSoft = Color(0.72f, 0.82f, 0.90f, 1f)
    val onlineInk = Color(0.55f, 0.90f, 0.60f, 1f)
    val onlineInkHover = Color(0.70f, 0.96f, 0.72f, 1f)

    // ── Semantic ────────────────────────────────────────────────────────────

    val success = Color(0.62f, 0.90f, 0.66f, 1f)
    val danger = Color(0.95f, 0.48f, 0.42f, 1f)
    val info = Color(0.55f, 0.82f, 0.95f, 1f)
    val warning = Color(0.95f, 0.68f, 0.45f, 1f)
    val warningInk = Color(1f, 0.80f, 0.25f, 1f)

    /** Destructive/error plate and its brighter pressed state. */
    val dangerPlate = Color(0.28f, 0.08f, 0.07f, 0.94f)
    val dangerPlateBright = Color(0.42f, 0.12f, 0.10f, 0.96f)
    val dangerRim = Color(0.72f, 0.32f, 0.28f, 0.55f)
    val dangerRimBright = Color(0.92f, 0.42f, 0.36f, 0.95f)
    val dangerInk = Color(1f, 0.62f, 0.52f, 1f)
    val dangerInkSoft = Color(0.96f, 0.78f, 0.74f, 1f)
    val dangerInkBright = Color(1f, 0.86f, 0.82f, 1f)
    val dangerInkStrong = Color(1f, 0.35f, 0.30f, 1f)
    /** The rim a hovered but non-semantic control takes. */
    val hoverRim = tint(accentBright, 0.95f)

    /** Affirmative plate and its glow/ink family. */
    val successFill = Color(0.06f, 0.20f, 0.11f, 0.92f)
    val successFillStrong = Color(0.25f, 0.55f, 0.35f, 0.95f)
    val successFillHover = Color(0.12f, 0.36f, 0.20f, 0.96f)
    val successRim = Color(0.35f, 0.92f, 0.48f, 0.95f)
    val successRimHover = Color(0.70f, 1f, 0.78f, 1f)
    val successGlow = Color(0.10f, 0.78f, 0.28f, 0.48f)
    val successGlowHover = Color(0.12f, 0.88f, 0.32f, 0.65f)
    val successGlowStrong = Color(0.20f, 0.85f, 0.38f, 0.55f)
    val successGlowStrongHover = Color(0.30f, 0.95f, 0.45f, 0.75f)
    val successInk = Color(0.85f, 0.98f, 0.88f, 1f)
    val successInkBright = Color(0.92f, 1f, 0.95f, 1f)
    val successScore = Color(0.95f, 1f, 0.95f, 1f)
    val successScoreHover = Color(1f, 1f, 0.92f, 1f)

    val infoFill = Color(0.10f, 0.28f, 0.34f, 0.94f)
    val infoFillStrong = Color(0.20f, 0.40f, 0.60f, 0.95f)
    val infoRim = Color(0.45f, 0.95f, 1f, 1f)
    val infoInkSoft = Color(0.85f, 0.98f, 1f, 1f)

    /** Positive, negative or neutral ink for a signed change. */
    fun deltaInk(v: Int): Color = when {
        v > 0 -> success
        v < 0 -> danger
        else -> inkMuted
    }

    fun deltaInk(v: Double): Color = when {
        v > 0.0 -> success
        v < 0.0 -> danger
        else -> inkMuted
    }

    // ── Controls ────────────────────────────────────────────────────────────

    val controlHover = Color(0.14f, 0.24f, 0.18f, 0.85f)
    val controlGroove = Color(0.04f, 0.07f, 0.05f, 0.95f)
    val controlRim = Color(0.70f, 0.78f, 0.65f, 1f)
    val controlLabel = Color(0.85f, 0.88f, 0.80f, 1f)
    val trackMuted = Color(0.22f, 0.35f, 0.26f, 1f)
    val track = Color(0.28f, 0.56f, 0.38f, 1f)
    val trackHover = Color(0.35f, 0.68f, 0.46f, 1f)
    val tick = Color(0.65f, 0.72f, 0.60f, 1f)
    val tickLabel = Color(0.68f, 0.72f, 0.65f, 1f)
    val knob = Color(0.92f, 0.90f, 0.82f, 1f)
    val knobHover = Color(0.98f, 0.96f, 0.88f, 1f)
    val knobActive = Color(0.90f, 0.97f, 0.86f, 1f)
    val knobMuted = Color(0.38f, 0.40f, 0.35f, 1f)
    val knobNotch = Color(0.40f, 0.45f, 0.38f, 1f)
    val knobNotchActive = Color(0.20f, 0.34f, 0.24f, 1f)
    val knobNotchMuted = Color(0.25f, 0.28f, 0.24f, 1f)
    val knobRim = Color(0.60f, 0.68f, 0.55f, 1f)

    val scrollTrack = Color(1f, 1f, 1f, 0.10f)
    val scrollTrackHover = Color(1f, 1f, 1f, 0.18f)
    val scrollThumb = Color(1f, 1f, 1f, 0.32f)
    val scrollThumbHover = Color(0.86f, 0.94f, 0.84f, 0.58f)
    val scrollThumbActive = Color(0.92f, 0.97f, 0.90f, 0.72f)

    // ── Navigation lists ────────────────────────────────────────────────────

    val indexInk = Color(0.62f, 0.78f, 0.70f, 0.88f)
    val indexInkHover = Color(0.72f, 0.86f, 0.76f, 0.95f)
    val indexInkSelected = Color(0.85f, 0.94f, 0.72f, 1f)
    val indexInkHeader = Color(0.72f, 0.86f, 0.78f, 0.95f)
    val statusInk = Color(0.55f, 0.60f, 0.58f, 0.75f)
    val statusInkHover = Color(0.70f, 0.74f, 0.72f, 0.90f)
    val statusSuccess = Color(0.45f, 0.82f, 0.55f, 0.95f)
    val labelInk = Color(0.90f, 0.90f, 0.86f, 0.92f)
    val labelInkHover = Color(0.98f, 0.97f, 0.90f, 1f)
    val groupLabelInk = Color(0.82f, 0.88f, 0.80f, 0.95f)

    // ── Table bands ─────────────────────────────────────────────────────────

    val rowStripe = Color(1f, 1f, 1f, 0.05f)
    /** The plate behind an emphasized table column. */
    val rowHighlight = Color(1f, 1f, 1f, 0.08f)
    /** The quiet wash behind a settled or summary row. */
    val rowWash = Color(1f, 1f, 1f, 0.06f)

    // ── Charts ──────────────────────────────────────────────────────────────

    val chartAxis = Color(0.82f, 0.82f, 0.74f, 0.55f)
    val chartRising = Color(0.60f, 0.86f, 0.62f, 1f)
    val chartFalling = Color(0.92f, 0.52f, 0.46f, 1f)
    val chartBand = Color(0.96f, 0.93f, 0.82f, 0.15f)
    val chartRisingHighlight = Color(0.86f, 1f, 0.88f, 1f)
    val chartFallingHighlight = Color(1f, 0.76f, 0.70f, 1f)
    val chartCarryHighlight = Color(1f, 0.96f, 0.80f, 1f)

    // ── Diagrams and chips ──────────────────────────────────────────────────

    val diagramFill = Color(0.06f, 0.28f, 0.18f, 0.55f)
    val diagramRim = Color(0.85f, 0.90f, 0.70f, 0.28f)
    val diagramPlate = Color(0.20f, 0.48f, 0.32f, 0.95f)
    val diagramHighlight = Color(0.55f, 0.75f, 0.62f, 0.98f)
    val diagramMuted = Color(0.22f, 0.26f, 0.24f, 0.90f)
    val diagramHot = Color(0.22f, 0.50f, 0.30f, 0.98f)
    val diagramCold = Color(0.10f, 0.28f, 0.18f, 0.90f)

    val chipFill = Color(0.08f, 0.28f, 0.18f, 0.72f)
    val chipFillHover = Color(0.14f, 0.36f, 0.24f, 0.88f)
    val chipFillEmphasized = Color(0.16f, 0.38f, 0.24f, 0.90f)
    val chipFillEmphasizedHover = Color(0.20f, 0.44f, 0.28f, 0.94f)
    val chipSuccessFill = Color(0.12f, 0.40f, 0.22f, 0.96f)
    val chipDangerFill = Color(0.42f, 0.14f, 0.12f, 0.94f)
    val chipInfoFill = Color(0.10f, 0.30f, 0.42f, 0.94f)
    val chipRim = Color(0.80f, 0.88f, 0.70f, 0.40f)

    // ── Board and felt ──────────────────────────────────────────────────────

    val felt = Color(0.12f, 0.42f, 0.28f, 1f)
    val feltDeep = Color(0.05f, 0.10f, 0.06f, 1f)
    val feltShade = Color(0.06f, 0.28f, 0.18f, 1f)
    val feltScrim = Color(0.05f, 0.12f, 0.08f, 0.78f)
    val veil = Color(0.01f, 0.02f, 0.015f, 0.92f)
    val liveMarker = Color(1f, 0.48f, 0.08f, 1f)
    val promptGlow = Color(1f, 0.48f, 0.08f, 1f)
    val readyGlow = Color(0.50f, 1f, 0f, 1f)
    val secondaryGlow = Color(0.22f, 0.82f, 1f, 1f)
    val matchGlow = Color(0.35f, 0.92f, 1f, 0.48f)
    val denyGlow = Color(0.92f, 0.16f, 0.14f, 1f)
    val winDiscardGlow = Color(1f, 0.40f, 0.05f, 0.92f)
    val focusWash = Color(1.15f, 1.12f, 0.75f, 1f)
    val tileHoverWash = Color(1.12f, 1.12f, 0.92f, 1f)
    val tileRecommendWash = Color(0.55f, 1.05f, 1.2f, 1f)
    val tileRiverDim = Color(0.70f, 0.72f, 0.76f, 1f)
    val tileFaceInk = Color(0.961f, 0.941f, 0.922f, 1f)
    val tileInk = Color(0.10f, 0.08f, 0.06f, 0.96f)
    val diceEdge = Color(0.22f, 0.20f, 0.18f, 1f)
    val dicePip = Color(0.10f, 0.09f, 0.09f, 1f)
    val sheen = Color(1f, 1f, 0.95f, 1f)
    val sheenBright = Color(1.08f, 1.06f, 0.96f, 1f)
    val waitInk = Color(1f, 0.94f, 0.72f, 0.98f)
    val faceTint = Color(1.05f, 1.02f, 0.95f, 1f)

    // ── Connectors ──────────────────────────────────────────────────────────

    val connectorClaim = Color(1f, 0.96f, 0.70f, 0.48f)
    val connectorWin = Color(1f, 0.42f, 0.06f, 0.85f)
    val connectorWinForYou = Color(0.35f, 1f, 0.50f, 0.88f)
    val connectorWinForYouOuter = Color(0.12f, 0.85f, 0.32f, 0.38f)
    val connectorWinAgainstYou = Color(1f, 0.28f, 0.22f, 0.88f)
    val connectorWinAgainstYouOuter = Color(0.90f, 0.15f, 0.12f, 0.40f)

    // ── Callouts ────────────────────────────────────────────────────────────

    /** A callout balloon's body, dark enough for a tile face to read through. */
    val calloutFill = tint(panel, 0.96f)

    /** [base] at another alpha, for a token that fades. */
    fun tint(base: Color, alpha: Float): Color = Color(base.r, base.g, base.b, alpha)
}
