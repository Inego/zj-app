package inego.zungjunggame.net

enum class MatchStatus {
    Finished,
    Left,
    Aborted,
}

data class MatchSummary(
    val id: String,
    val endedAt: String,
    val rounds: String,
    val allowChicken: Boolean,
    val status: MatchStatus,
    val placement: Int,
    val finalScore: Int,
    val oldRating: Double?,
    val newRating: Double?,
    /**
     * What the match did to the rating, as reported by the server. Display this;
     * never subtract [oldRating] from [newRating] to obtain it — each is rounded
     * for display independently, so the difference is not the match's delta.
     */
    val delta: Double?,
)

data class MatchList(
    val matches: List<MatchSummary>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)
