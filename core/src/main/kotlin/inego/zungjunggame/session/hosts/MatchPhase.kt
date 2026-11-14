package inego.zungjunggame.session.hosts

/**
 * Lifecycle phase of the match a [PlayHost] is running.
 *
 * This is the single authority for "is this match over". Anything that has to change
 * behaviour at the end of a match — menu rows, the settlement card's
 * headline — reads it from the host instead of re-deriving it from wire messages or
 * from a bag of boolean flags that can disagree with each other.
 *
 * A phase is monotone within a match: once [Finished] or [Aborted], the host stays
 * there for the rest of its life. A rematch is a new table and therefore a new host.
 */
enum class MatchPhase {
    /** At least one hand is still to be played (or the table is seated, waiting to start). */
    Live,

    /** The match reached its final hand; the result is final. */
    Finished,

    /** The match was abandoned before its final hand. */
    Aborted;

    /** True for [Finished] and [Aborted]: no further hands will be played. */
    val isOver: Boolean get() = this != Live
}
