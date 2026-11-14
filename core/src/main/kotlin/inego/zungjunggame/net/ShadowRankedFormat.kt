package inego.zungjunggame.net

import inego.zungjunggame.i18n.L10n
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display strings for one [ChallengeEntry]. Uses [L10n]; unit-testable once
 * bootstrapped.
 */
object ShadowRankedFormat {
    /** The two axes, the same wording match history uses. */
    fun rulesLabel(rounds: String, allowChicken: Boolean): String =
        MatchHistoryFormat.rulesLabel(rounds, allowChicken)

    fun statusLabel(status: ChallengeStatus): String = when (status) {
        ChallengeStatus.Started -> L10n.get("shadowRanked.status_started")
        // A finished Shadow Ranked attempt reads the same as a finished match.
        ChallengeStatus.Finished -> L10n.get("history.status_finished")
        ChallengeStatus.Abandoned -> L10n.get("shadowRanked.status_abandoned")
        ChallengeStatus.Voided -> L10n.get("shadowRanked.status_voided")
    }

    /** The running distinct-player count. */
    fun playersLabel(players: Int): String =
        if (players == 1) {
            L10n.get("shadowRanked.players_one")
        } else {
            L10n.format("shadowRanked.players_many", players.toString())
        }

    /** "rating +5.33" — the net rating this account has moved on the attempt. */
    fun ratingLabel(delta: Double): String =
        L10n.format("shadowRanked.rating_line", signedRating(delta))

    /**
     * The finished attempt's score list: "Bot +32 · Maria +80 · You +48".
     *
     * The bot and the listing player's own row are named by the bundle; every other
     * human keeps the display name the server sent.
     */
    fun scoreLine(scores: List<ChallengeScoreRow>): String =
        scores.joinToString(" · ") { row -> "${scoreName(row)} ${signedScore(row.score)}" }

    fun scoreName(row: ChallengeScoreRow): String {
        val name = row.name
        return when {
            row.isBot -> L10n.get("shadowRanked.score_bot")
            row.isYou -> L10n.get("shadowRanked.score_you")
            name.isNullOrEmpty() -> L10n.get("shadowRanked.name_unspecified")
            else -> name
        }
    }

    /**
     * The name of an attempt's seat-0 player: the bot, the viewer, or the
     * human's display name. Never matched on the name to find the viewer.
     *
     * A deleted account's name is the empty string, which the server treats as
     * "not specified"; `null` and `""` both render the localized fallback.
     */
    fun attemptName(isBot: Boolean, isYou: Boolean, name: String?): String = when {
        isBot -> L10n.get("shadowRanked.score_bot")
        isYou -> L10n.get("shadowRanked.score_you")
        name.isNullOrEmpty() -> L10n.get("shadowRanked.name_unspecified")
        else -> name
    }

    /**
     * An absolute rating with trailing cents dropped: `1214`, `1219.33`.
     * Locale-free, same as [signedRating], so a grouping `NumberFormat` cannot
     * turn it into `1,219.33`.
     */
    fun ratingValue(value: Double): String {
        val cents = (abs(value) * 100.0).roundToInt()
        val whole = cents / 100
        val frac = cents % 100
        val body = if (frac == 0) whole.toString() else "$whole.${frac.toString().padStart(2, '0')}"
        return if (value < 0.0 && cents != 0) "\u2212$body" else body
    }

    /** A match total, signed: "+48", "−50", "0". */
    fun signedScore(score: Int): String = signed(score)

    /**
     * A rating change with two decimals, signed and locale-free: "+5.33", "−2.67",
     * "0.00". Built by hand so the number reads the same in every locale and never
     * goes through the bundle's grouping `NumberFormat`.
     */
    fun signedRating(delta: Double): String {
        val cents = (abs(delta) * 100.0).roundToInt()
        val text = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
        return when {
            cents == 0 -> "0.00"
            delta > 0 -> "+$text"
            else -> "\u2212$text"
        }
    }

    private fun signed(value: Int): String = when {
        value > 0 -> "+$value"
        value < 0 -> "\u2212${-value}"
        else -> "0"
    }
}
