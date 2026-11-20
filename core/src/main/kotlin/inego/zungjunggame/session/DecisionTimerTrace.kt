package inego.zungjunggame.session

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.TimeUtils

/**
 * One-shot instrumentation for the visible decision clock.
 *
 * Prompt receipt is `t = 0`; every event is stamped with the same monotonic
 * clock. Only the first timed `ActOnHand` prompt that offers a discard starts a
 * trace, and it stops after the first paint whose clock text differs from the
 * first painted one. This exists to compare the documented grace timeline with
 * one real discard; it is not a general-purpose timer.
 */
object DecisionTimerTrace {
    private const val TAG = "Online"

    private var startNs = 0L
    private var decisionId = 0L
    private var fullThinkMs = 0L
    private var firstPaintText: String? = null
    private var belowCapLogged = false
    private var changeLogged = false
    private var finished = false

    /**
     * Starts the one trace when [decisionType] is an act-on-hand decision with a
     * discard and the prompt carries a clock. Later prompts are ignored.
     */
    fun onPrompt(
        decisionId: Long,
        decisionType: String,
        deadlineMs: Long?,
        slackMs: Long?,
        baseMs: Long?,
        hasDiscard: Boolean,
    ) {
        if (finished || startNs != 0L) return
        if (deadlineMs == null || !hasDiscard) return
        if (decisionType != "ActOnHand" && decisionType != "act_on_hand") return
        startNs = TimeUtils.nanoTime()
        this.decisionId = decisionId
        fullThinkMs = (baseMs ?: 0L) + (slackMs ?: 0L)
        log(
            "timer-trace prompt decision=$decisionId deadline_ms=$deadlineMs " +
                "base_ms=${baseMs ?: -1} slack_ms=${slackMs ?: -1} elapsed_ms=0.0",
        )
    }

    /** Logs the first advisory correction below the full think cap. */
    fun onCorrection(decisionId: Long, remainingMs: Long) {
        if (startNs == 0L || decisionId != this.decisionId) return
        if (belowCapLogged || fullThinkMs <= 0L || remainingMs >= fullThinkMs) return
        belowCapLogged = true
        log(
            "timer-trace first_below_cap decision=$decisionId " +
                "remaining_ms=$remainingMs elapsed_ms=${elapsedMs()}",
        )
    }

    /**
     * Called from the banner's draw path with the clock line it is about to
     * paint. The first call is the first visible clock; the next call whose text
     * differs is the first changed paint.
     */
    fun onClockPaint(text: String) {
        if (startNs == 0L) return
        val first = firstPaintText
        if (first == null) {
            firstPaintText = text
            log("timer-trace first_paint decision=$decisionId text=\"$text\" elapsed_ms=${elapsedMs()}")
            return
        }
        if (!changeLogged && text != first) {
            changeLogged = true
            finished = true
            log(
                "timer-trace first_changed_paint decision=$decisionId text=\"$text\" " +
                    "from=\"$first\" elapsed_ms=${elapsedMs()}",
            )
        }
    }

    private fun elapsedMs(): String {
        val tenths = (TimeUtils.nanoTime() - startNs) / 100_000L
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun log(message: String) {
        try {
            Gdx.app?.log(TAG, message) ?: println("[$TAG] $message")
        } catch (_: Throwable) {
            println("[$TAG] $message")
        }
    }
}
