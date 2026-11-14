package inego.zungjunggame.net

/**
 * One listed account on the leaderboard wire.
 *
 * `rating` is the displayed integer the server computed, so the client neither
 * rounds nor sorts. `historyId` is the opaque public handle that addresses the
 * player's rating history — never an account id, email, or public tag.
 */
data class LeaderboardRow(
    val place: Int,
    val displayName: String,
    val historyId: String,
    val rating: Int,
    val liveGames: Int,
    val shadowRankedGames: Int,
)

/** The caller's own place and offset in the pinned snapshot. */
data class LeaderboardSelf(
    val offset: Int,
    val place: Int,
)

/**
 * One page of `GET /leaderboard`.
 *
 * [generation] is the snapshot the page belongs to. [replaced] is true when the
 * requested generation was not retained and this body is a window of the newest
 * reading instead. [self] is null when anonymous or not listed.
 */
data class LeaderboardPage(
    val generation: Long,
    val replaced: Boolean,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val rows: List<LeaderboardRow>,
    val self: LeaderboardSelf?,
)

/**
 * One immutable reading of the board: a pinned [generation], the [windows] of it
 * the client has fetched (keyed by their start offset), and the viewer's [self]
 * row. Opening takes a new reading; scrolling and the jumps stay on this one.
 *
 * A window is a slice of the snapshot at a `PAGE_SIZE`-aligned offset, so a row's
 * index in the whole snapshot is `windowOffset + positionInWindow`. The overlay
 * places every window at its absolute position, which is what lets a jump to a
 * far [self] land without first fetching every window between.
 */
data class LeaderboardReading(
    val generation: Long,
    val total: Int,
    val self: LeaderboardSelf?,
    val windows: Map<Int, List<LeaderboardRow>>,
) {
    /** The first page of the reading; the window offset 0, once fetched. */
    val rows: List<LeaderboardRow> get() = windows[0].orEmpty()

    /** True when no fetched window carries a row yet. */
    val isEmpty: Boolean get() = windows.values.all { it.isEmpty() }

    /** This reading with [rows] stored at [offset], replacing any window already there. */
    fun withWindow(offset: Int, rows: List<LeaderboardRow>): LeaderboardReading =
        copy(windows = windows + (offset to rows))

    /** True when the snapshot [index] falls inside one of the fetched windows. */
    fun covers(index: Int): Boolean = rowAt(index) != null

    /**
     * The row at snapshot [index], or null when no fetched window holds it — a
     * row whose window has not landed, or an index past the snapshot.
     */
    fun rowAt(index: Int): LeaderboardRow? {
        for ((offset, rows) in windows) {
            if (index >= offset && index < offset + rows.size) return rows[index - offset]
        }
        return null
    }
}

/** The reading a freshly fetched [LeaderboardPage] establishes. */
fun LeaderboardPage.toReading(): LeaderboardReading =
    LeaderboardReading(
        generation = generation,
        total = total,
        self = self,
        windows = mapOf(offset to rows),
    )
