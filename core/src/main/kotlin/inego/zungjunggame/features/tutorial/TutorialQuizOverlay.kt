package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.platform.UiCursor
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.reference.CatalogLinkPolicy
import inego.zungjunggame.render.richtext.RichDoc
import inego.zungjunggame.render.richtext.RichTextBlock
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.tutorial.*
import inego.zungjunggame.tutorial.slide.layout.SlideChromeLayout
import inego.zungjunggame.tutorial.slide.layout.SlideViewport
import inego.zungjunggame.ui.GestureSurface
import kotlin.math.max
import kotlin.math.min

/**
 * Orientation quiz **on the green table** — not a floating dialog.
 *
 * Layout is full-screen table zones: title + prompt at top, interactive tiles /
 * choices on the felt (tile hands top-anchored so bottom chrome never shifts
 * them), bins / confirm / result label as stable bottom chrome.
 * Escape hatch: Esc / system menu (same as free-play) — no dismiss × in content.
 *
 * Paint/hit widgets live under [inego.zungjunggame.render.quiz]; this shell owns
 * attempt state, pointer semantics, and layout orchestration.
 *
 * **Its gestures are a [GestureSurface].** The last frame's [QuizControlHit]s are
 * the only hit data: [onPointerDown] claims a press that starts on one of those
 * controls (or on a prompt link) and declines the empty felt, and [onPointerUp]
 * resolves the release — applying the attempt edit an option / bin toggle
 * performs — into a [PointerUpResult] the owning component maps to an action.
 * Nothing republishes the controls as hit regions, so the id of the option a
 * press landed on never has to travel through an action payload.
 */
class TutorialQuizOverlay : Disposable, GestureSurface<TutorialQuizOverlay.PointerUpResult> {
    private val glyphLayout = GlyphLayout()
    private var tileAssets: TileAssets? = null
    private var tileDrawer: TileDrawer? = null

    /** Quiz prompt: same rich pipeline as coach body. */
    private val promptBlock = RichTextBlock()
    private var lastPromptMarkup: String? = null

    private fun whitePixel(): Texture = WhitePixel.get()

    private fun drawer(): TileDrawer {
        tileDrawer?.let { return it }
        val assets = TileAssets()
        tileAssets = assets
        val d = TileDrawer(assets)
        tileDrawer = d
        return d
    }

    var visible: Boolean = false
        private set

    private var quiz: TutorialStep.Quiz? = null
    private var titleKey: String = ""
    private var failVisible: Boolean = false
    /** Correct confirm done; dock shows Next / Try again. */
    private var passed: Boolean = false
    /**
     * Multiselect review marks after Confirm. Cleared when the learner edits
     * the selection. See [QuizAnswerReviews].
     */
    private var answerReview: QuizAnswerReview? = null

    private val selectedIds = linkedSetOf<String>()
    private val binByOption = linkedMapOf<String, String>()
    private var selectedForBin: String? = null
    /** After Confirm: selection locked (no more edits). */
    private var answerRevealed: Boolean = false

    /** Show the Back control (previous slide / previous lesson). */
    var offersBack: Boolean = false
        private set

    /** Soft enter fade for the quiz surface (UI-only). */
    private var enterElapsedSec: Float = 1f
    /** Identity of the applied quiz content; a change resets the attempt / fade. */
    private var appliedContentKey: String? = null
    private val enterDurationSec: Float = 0.24f

    private val hits = ArrayList<QuizControlHit>(16)
    private val hitRegistrar = QuizHitRegistrar { kind, id, left, bottom, w, h ->
        hits += QuizControlHit(kind, id, left, bottom, w, h)
    }

    /**
     * What a completed press on the quiz resolved, in the quiz's own vocabulary:
     * the dock control or attempt edit it landed on, or the prompt hyperlink
     * under the release. [TutorialQuizActions.actionFor] maps it to the action.
     *
     * The default — every field false / null — is a release that resolved
     * nothing (the felt, or a locked attempt).
     */
    data class PointerUpResult(
        /** An option / bin toggle was applied to the attempt. */
        val selectionChanged: Boolean = false,
        /** Confirm was pressed. */
        val submit: Boolean = false,
        /** Advance past a passed retryable quiz. */
        val next: Boolean = false,
        /** New hand after a correct confirm. */
        val tryAgain: Boolean = false,
        /** Previous slide / previous lesson. */
        val back: Boolean = false,
        /** Prompt hyperlink under the release. */
        val linkHref: String? = null,
        /** The attempt edit a selection toggle produced (STATE_CHANGED payload). */
        val attempt: QuizInteractionState? = null,
        /** The answer a Confirm / multi-choice tap produced (SUBMIT payload). */
        val answer: QuizAnswer? = null,
    )

    /**
     * Pure hit-test against last-frame quiz controls (options / bins / confirm) and prompt hyperlinks.
     * Does not mutate selection — safe for press tracking and free-space menu gates.
     */
    private fun hitsControl(worldX: Float, worldY: Float): Boolean {
        if (!visible || quiz == null) return false
        for (i in hits.lastIndex downTo 0) {
            if (hits[i].contains(worldX, worldY)) return true
        }
        return linksLive() && promptBlock.linkAt(worldX, worldY) != null
    }

    private fun linkAt(worldX: Float, worldY: Float): String? =
        if (visible && linksLive()) promptBlock.linkAt(worldX, worldY) else null

    private fun linksLive(): Boolean = enterElapsedSec >= enterDurationSec

    /**
     * Claims a press that **starts** on one of this surface's own controls or
     * prompt links. The empty felt is declined: it is not a control, so the
     * free-space menu and the layers below still see the miss.
     */
    override fun onPointerDown(x: Float, y: Float): Boolean =
        hitsControl(x, y)

    /**
     * Apply one frame's [TutorialQuizSnapshot]: bind the quiz content and paint the
     * attempt it carries. The presenter calls this with the ticking `dt` from
     * `motion` (advance the enter fade) and with `dt == 0` from `draw` (paint the
     * post-tick scene). Keyed, so an unchanged quiz does not restart the fade.
     */
    /** The enter fade has not reached its settled alpha. */
    fun needsFrames(): Boolean = visible && enterElapsedSec < enterDurationSec

    fun apply(values: TutorialQuizSnapshot, dtSec: Float) {
        val quiz = values.quiz
        if (!values.visible || quiz == null) {
            if (visible) hideInternal()
            return
        }
        this.quiz = quiz
        this.titleKey = values.titleKey
        this.offersBack = values.offersBack
        if (values.contentKey != appliedContentKey || !visible) {
            appliedContentKey = values.contentKey
            if (!visible) {
                // Restore snaps; a fresh attempt fades in.
                enterElapsedSec = if (isFresh(values.attempt)) 0f else enterDurationSec
            }
        }
        presentAttempt(quiz, values.attempt)
        visible = true
        if (dtSec > 0f && enterElapsedSec < enterDurationSec) {
            enterElapsedSec = (enterElapsedSec + dtSec).coerceAtMost(enterDurationSec)
        }
    }

    /** Bind the attempt the snapshot carries; review marks recompute from it. */
    private fun presentAttempt(quiz: TutorialStep.Quiz, attempt: QuizInteractionState) {
        applyAnswerPayload(attempt.answer)
        selectedForBin = attempt.selectedForBinId
        passed = attempt.passed
        answerRevealed = attempt.answerRevealed
        failVisible = attempt.failVisible
        answerReview = quiz.reviewFor(attempt)
    }

    private fun isFresh(a: QuizInteractionState): Boolean =
        a.answer.selectedIds.isEmpty() &&
            a.answer.binByOptionId.isEmpty() &&
            a.selectedForBinId == null &&
            !a.answerRevealed && !a.passed && !a.failVisible

    private fun contentAlpha(): Float {
        if (enterElapsedSec >= enterDurationSec) return 1f
        val t = (enterElapsedSec / enterDurationSec).coerceIn(0f, 1f)
        val inv = 1f - t
        return 1f - inv * inv * inv // ease-out cubic
    }

    /** Full overlay teardown; the host drives it by publishing an absent quiz. */
    private fun hideInternal() {
        visible = false
        quiz = null
        titleKey = ""
        offersBack = false
        failVisible = false
        passed = false
        answerReview = null
        selectedIds.clear()
        binByOption.clear()
        selectedForBin = null
        answerRevealed = false
        hits.clear()
        enterElapsedSec = 1f
        appliedContentKey = null
        lastPromptMarkup = null
        promptBlock.setDoc(RichDoc(emptyList()))
    }

    private fun applyAnswerPayload(answer: QuizAnswer) {
        selectedIds.clear()
        selectedIds.addAll(answer.selectedIds)
        binByOption.clear()
        binByOption.putAll(answer.binByOptionId)
    }

    private fun currentAnswer(): QuizAnswer {
        val q = quiz ?: return QuizAnswer()
        return when (q.kind) {
            QuizKind.MultiChoice, QuizKind.TileSelect ->
                QuizAnswer(selectedIds = selectedIds.toSet())
            QuizKind.BinAssign ->
                QuizAnswer(binByOptionId = binByOption.toMap())
        }
    }

    /**
     * Resolve a release this surface owns: the control under it — computing the
     * attempt edit an option / bin toggle produces — or the prompt hyperlink it
     * landed on. Controls are resolved first, in the order the dock / bins /
     * options were drawn, so a control outranks copy it overlaps.
     *
     * The widget does not store the edit: the attempt rides [PointerUpResult] to
     * the host, whose snapshot hands it back next frame.
     */
    override fun onPointerUp(x: Float, y: Float): PointerUpResult {
        if (!visible) return PointerUpResult()
        val q = quiz ?: return PointerUpResult()
        for (i in hits.lastIndex downTo 0) {
            val hit = hits[i]
            if (!hit.contains(x, y)) continue
            when (hit.kind) {
                QuizControlKind.Confirm ->
                    return PointerUpResult(submit = true, answer = currentAnswer())
                QuizControlKind.Next -> return PointerUpResult(next = true)
                QuizControlKind.TryAgain -> return PointerUpResult(tryAgain = true)
                QuizControlKind.Back -> return PointerUpResult(back = true)
                QuizControlKind.Option -> {
                    if (passed || answerRevealed) return PointerUpResult()
                    when (q.kind) {
                        QuizKind.MultiChoice -> return PointerUpResult(
                            submit = true,
                            answer = QuizAnswer(selectedIds = setOf(hit.id)),
                        )
                        QuizKind.TileSelect -> {
                            val next = linkedSetOf<String>().apply {
                                addAll(selectedIds)
                                if (!add(hit.id)) remove(hit.id)
                            }
                            return PointerUpResult(
                                selectionChanged = true,
                                attempt = QuizInteractionState(
                                    answer = QuizAnswer(selectedIds = next),
                                ),
                            )
                        }
                        QuizKind.BinAssign -> return PointerUpResult(
                            selectionChanged = true,
                            attempt = QuizInteractionState(
                                answer = QuizAnswer(binByOptionId = binByOption.toMap()),
                                selectedForBinId = hit.id,
                            ),
                        )
                    }
                }
                QuizControlKind.Bin -> {
                    if (passed || answerRevealed) return PointerUpResult()
                    if (q.kind == QuizKind.BinAssign) {
                        val opt = selectedForBin
                        if (opt != null) {
                            val nextBins = LinkedHashMap(binByOption).apply { this[opt] = hit.id }
                            return PointerUpResult(
                                selectionChanged = true,
                                attempt = QuizInteractionState(
                                    answer = QuizAnswer(binByOptionId = nextBins),
                                    selectedForBinId = selectedForBin,
                                ),
                            )
                        }
                    }
                    return PointerUpResult()
                }
            }
        }
        // No control under the release. The prompt link is the last thing this
        // surface owns; the empty felt is a miss, not "dismiss modal".
        return PointerUpResult(linkHref = linkAt(x, y))
    }

    fun render(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        if (!visible) return
        val q = quiz ?: return
        Fonts.ensureCreated()
        hits.clear()

        val px = whitePixel()
        val touch = InputProfile.detect() == InputProfile.Touch
        val vp = SlideViewport(
            width = screenW,
            height = screenH,
            touch = touch,
            uiScale = Fonts.uiScale,
        )
        val alpha = contentAlpha()

        drawFeltBackground(batch, px, screenW, screenH)

        val titleFont = Fonts.labelActive
        val bodyFont = Fonts.button
        val padX = Fonts.px(if (touch) 18f else 28f)
        val padTop = Fonts.px(if (touch) 12f else 18f)
        val gap = Fonts.px(12f)
        // Accent lesson title → ivory prompt needs more air than inter-body gaps.
        val titlePromptGap = max(Fonts.px(if (touch) 20f else 24f), titleFont.lineHeight * 0.55f)

        val contentW = screenW - padX * 2f
        val portrait = screenH > screenW

        val title = if (titleKey.isEmpty()) "" else L10n.get(titleKey)
        val prompt = L10n.get(q.promptKey)
        val fullyCorrect = answerReview?.fullyCorrect == true ||
            (passed && q.isCorrect(currentAnswer()))
        val statusText = when {
            passed && fullyCorrect -> L10n.get("tutorial.quiz.correct")
            passed && !fullyCorrect -> L10n.get("tutorial.quiz.incorrect")
            failVisible -> L10n.get("tutorial.quiz.incorrect")
            else -> ""
        }
        val statusIsCorrect = passed && fullyCorrect
        val confirmLabel = L10n.get(q.confirmKey)
        val nextLabel = L10n.get(q.nextKey)
        val tryAgainLabel = L10n.get(q.tryAgainKey)
        val backLabel = if (offersBack) L10n.get("tutorial.back") else ""
        // Multi-choice: tap option submits. Tile-select / bin-assign: Confirm.
        // After Confirm on retryable quizzes: Next + Try again only.
        val needsConfirm = !passed && q.kind != QuizKind.MultiChoice
        val showPostPassDock = passed
        // Multi-choice has no Confirm until post-pass; still offer Back alone.
        val showBackOnlyDock = offersBack && !needsConfirm && !showPostPassDock
        val binHint = if (q.kind == QuizKind.BinAssign && !answerRevealed && !passed) {
            L10n.get("tutorial.quiz.bin_hint")
        } else {
            ""
        }

        val playTop = drawTitlePrompt(
            batch = batch,
            titleFont = titleFont,
            bodyFont = bodyFont,
            title = title,
            prompt = prompt,
            binHint = binHint,
            contentLeft = padX,
            contentW = contentW,
            screenH = screenH,
            padTop = padTop,
            gap = gap,
            titlePromptGap = titlePromptGap,
            alpha = alpha,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
        )

        // Shared content alpha for options / bins / confirm (tiles use batch color).
        batch.setColor(AppPalette.tint(AppPalette.headerInk, alpha))

        // ── Bottom chrome: dock + fixed result band + bins ───────────────
        // Result label is dock chrome, not play content. Band height is probed
        // from both generic outcome strings so empty ↔ Correct!/Not quite never
        // reflows the felt (playTop/playBottom stay put across Confirm).
        val btnH = SlideChromeLayout.buttonHeight(vp, bodyFont.lineHeight)
        // The dock sits on the same pad as the coach's Back / Next row, and is
        // the same height: one control family, one geometry.
        val dockBottom = SlideChromeLayout.buttonPadY(vp)
        val hasBottomDock = showPostPassDock || needsConfirm || showBackOnlyDock
        val dockH = if (hasBottomDock) btnH else 0f
        val includeStatusBand = hasBottomDock || statusText.isNotEmpty()
        val stackTop = drawStatusBand(
            batch = batch,
            bodyFont = bodyFont,
            contentLeft = padX,
            contentW = contentW,
            dockBottom = dockBottom,
            dockH = dockH,
            gap = gap,
            includeStatusBand = includeStatusBand,
            statusText = statusText,
            statusIsCorrect = statusIsCorrect,
            alpha = alpha,
        )

        // Bin row is attempt chrome: keep its slot while the quiz is interactive
        // (bins hide after pass intentionally; tile-select has no bins).
        val binRowH = if (q.kind == QuizKind.BinAssign && q.bins.isNotEmpty() && !passed) btnH else 0f
        val binBottom = if (binRowH > 0f) stackTop + gap else stackTop
        val playBottom = binBottom + binRowH + (if (binRowH > 0f) gap else 0f)
        val playH = (playTop - playBottom).coerceAtLeast(Fonts.px(80f))

        // ── Diagram (optional) in upper play area ─────────────────────────
        val diagram = q.diagram
        val diagramH = diagramHeight(diagram, playH, portrait)
        val diagramW = min(contentW, Fonts.px(if (portrait) 360f else 480f))
        if (diagramH > 0f) {
            val dLeft = padX + (contentW - diagramW) * 0.5f
            val dBottom = playTop - diagramH
            QuizTableDiagrams.draw(
                batch, px, diagram, dLeft, dBottom, diagramW, diagramH, q.spotlight,
            )
        }

        val optionsTop = if (diagramH > 0f) playTop - diagramH - gap else playTop
        val optionsH = (optionsTop - playBottom).coerceAtLeast(Fonts.px(64f))

        QuizOptionPresenter.draw(
            batch = batch,
            px = px,
            drawer = drawer(),
            q = q,
            left = padX,
            bottom = playBottom,
            areaW = contentW,
            areaH = optionsH,
            touch = touch,
            pointerX = pointerWorldX,
            pointerY = pointerWorldY,
            selectedIds = selectedIds,
            selectedForBin = selectedForBin,
            binByOption = binByOption,
            markOf = { optionMark(it) },
            hits = hitRegistrar,
            alpha = alpha,
        )

        if (binRowH > 0f) {
            QuizBinBar.draw(
                batch, px, q.bins,
                padX, binBottom, contentW, btnH,
                pointerWorldX, pointerWorldY,
                alpha,
                hitRegistrar,
            )
        }

        if (dockH > 0f) {
            when {
                showPostPassDock -> QuizActionDock.drawPostPass(
                    batch, px, vp, padX, contentW, dockBottom, btnH,
                    backLabel, tryAgainLabel, nextLabel,
                    pointerWorldX, pointerWorldY, alpha, hitRegistrar,
                )
                needsConfirm -> QuizActionDock.drawConfirm(
                    batch, px, vp, padX, contentW, dockBottom, btnH,
                    backLabel, confirmLabel,
                    pointerWorldX, pointerWorldY, alpha, hitRegistrar,
                )
                else -> QuizActionDock.drawBackOnly(
                    batch, px, vp, padX, contentW, dockBottom, btnH,
                    backLabel,
                    pointerWorldX, pointerWorldY, alpha, hitRegistrar,
                )
            }
        }
        batch.setColor(Color.WHITE)
        updateDesktopCursor(pointerWorldX, pointerWorldY)
    }

    private fun updateDesktopCursor(pointerWorldX: Float, pointerWorldY: Float) {
        if (!InputProfile.detect().usesHover) return
        if (pointerWorldX.isNaN() || pointerWorldY.isNaN()) {
            UiCursor.arrow()
            return
        }
        if (hitsControl(pointerWorldX, pointerWorldY)) {
            UiCursor.hand()
        } else {
            UiCursor.arrow()
        }
    }

    private fun drawFeltBackground(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        screenH: Float,
    ) {
        // Full felt, edge to edge: the quiz is the table, not a card on it.
        batch.setColor(AppPalette.felt)
        batch.draw(px, 0f, 0f, screenW, screenH)
        batch.setColor(Color.WHITE)
    }

    /** Draws title, prompt, optional bin hint. Returns play-zone top (y). */
    private fun drawTitlePrompt(
        batch: SpriteBatch,
        titleFont: BitmapFont,
        bodyFont: BitmapFont,
        title: String,
        prompt: String,
        binHint: String,
        contentLeft: Float,
        contentW: Float,
        screenH: Float,
        padTop: Float,
        gap: Float,
        titlePromptGap: Float,
        alpha: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ): Float {
        glyphLayout.setText(titleFont, title, Color.WHITE, contentW, Align.center, true)
        val titleH = glyphLayout.height
        val titleTop = screenH - padTop
        titleFont.color = Color(AppPalette.accent.r, AppPalette.accent.g, AppPalette.accent.b, alpha)
        titleFont.draw(batch, title, contentLeft, titleTop, contentW, Align.center, true)
        titleFont.color = Color.WHITE

        ensurePromptMarkup(prompt)
        val promptH = promptBlock.measure(contentW)
        val promptTop = titleTop - titleH - titlePromptGap
        promptBlock.draw(
            batch = batch,
            left = contentLeft,
            top = promptTop,
            maxWidth = contentW,
            alpha = alpha,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
        )

        var yCursor = promptTop - promptH - gap
        if (binHint.isNotEmpty()) {
            glyphLayout.setText(bodyFont, binHint, Color.WHITE, contentW, Align.center, true)
            val hintH = glyphLayout.height
            bodyFont.color = AppPalette.tint(AppPalette.inkSage, alpha)
            bodyFont.draw(batch, binHint, contentLeft, yCursor, contentW, Align.center, true)
            bodyFont.color = Color.WHITE
            yCursor -= hintH + gap
        }
        return yCursor
    }

    private fun ensurePromptMarkup(prompt: String) {
        if (prompt == lastPromptMarkup) return
        lastPromptMarkup = prompt
        if (prompt.isEmpty()) {
            promptBlock.setDoc(RichDoc(emptyList()))
        } else {
            promptBlock.setMarkup(prompt) { doc ->
                CatalogLinkPolicy.stripNonCatalogLinks(doc) { href, reason ->
                    Gdx.app.log("tutorial", "strip href $reason=$href")
                }
            }
        }
    }

    /**
     * Status band above the dock. Returns stack top y (above dock + optional band).
     * Band height is constant so Confirm does not reflow play content.
     */
    private fun drawStatusBand(
        batch: SpriteBatch,
        bodyFont: BitmapFont,
        contentLeft: Float,
        contentW: Float,
        dockBottom: Float,
        dockH: Float,
        gap: Float,
        includeStatusBand: Boolean,
        statusText: String,
        statusIsCorrect: Boolean,
        alpha: Float,
    ): Float {
        glyphLayout.setText(
            bodyFont, L10n.get("tutorial.quiz.correct"), Color.WHITE, contentW, Align.center, true,
        )
        val correctStatusH = glyphLayout.height
        glyphLayout.setText(
            bodyFont, L10n.get("tutorial.quiz.incorrect"), Color.WHITE, contentW, Align.center, true,
        )
        val failStatusH = glyphLayout.height
        val statusBandH = max(bodyFont.lineHeight, max(correctStatusH, failStatusH))

        var stackTop = dockBottom + dockH
        if (includeStatusBand) {
            stackTop += gap
            if (statusText.isNotEmpty()) {
                glyphLayout.setText(
                    bodyFont, statusText, Color.WHITE, contentW, Align.center, true,
                )
                val drawnH = glyphLayout.height
                val statusBaseline = stackTop + (statusBandH + drawnH) * 0.5f
                val statusColor = if (statusIsCorrect) {
                    Color(AppPalette.success.r, AppPalette.success.g, AppPalette.success.b, alpha)
                } else {
                    Color(AppPalette.danger.r, AppPalette.danger.g, AppPalette.danger.b, alpha)
                }
                bodyFont.color = statusColor
                bodyFont.draw(
                    batch, statusText, contentLeft, statusBaseline, contentW, Align.center, true,
                )
                bodyFont.color = Color.WHITE
            }
            stackTop += statusBandH
        }
        return stackTop
    }

    private fun diagramHeight(
        diagram: TutorialDiagram,
        playH: Float,
        portrait: Boolean,
    ): Float = when (diagram) {
        TutorialDiagram.None -> 0f
        TutorialDiagram.WallDead -> min(playH * 0.32f, Fonts.px(if (portrait) 100f else 88f))
        TutorialDiagram.SeatsTurns, TutorialDiagram.TableOverview ->
            min(playH * 0.48f, Fonts.px(if (portrait) 200f else 180f))
        // Coach-primary diagrams; not used on quizzes yet.
        TutorialDiagram.ThreeSuits,
        TutorialDiagram.Honors,
        TutorialDiagram.TileTypes,
        TutorialDiagram.SequenceExample,
        TutorialDiagram.NotASequenceRun,
        TutorialDiagram.WallBuild,
        TutorialDiagram.RegularHand,
        TutorialDiagram.SetKinds,
        TutorialDiagram.KongAsTriplet,
        TutorialDiagram.SeatWinds,
        TutorialDiagram.TileTags,
        -> 0f
    }

    /**
     * Review mark for drawing. Only while Confirm is done ([passed]) or a wrong
     * attempt is latched ([failVisible]) — never paint hits while Confirm is up.
     */
    private fun optionMark(optionId: String): QuizOptionMark? {
        if (!passed && !failVisible) return null
        return answerReview?.markOf(optionId)
    }

    override fun dispose() {
        tileDrawer = null
        tileAssets?.dispose()
        tileAssets = null
        promptBlock.dispose()
        lastPromptMarkup = null
    }
}
