package inego.zungjunggame.tutorial

/**
 * How far a campaign lesson has been played (nav tree chrome).
 *
 * Independent of mid-lesson the `session.tutorial` key:
 * partial only means the player entered the lesson without finishing it.
 */
enum class LessonPlayStatus {
    /** Never entered. */
    Never,

    /** Entered at least once, not completed. */
    Partial,

    /** Finished successfully (wins over [Partial]). */
    Completed,
}
