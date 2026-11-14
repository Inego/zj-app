package inego.zungjunggame.tutorial

/**
 * One campaign entry: stable id, copy key, optional prerequisites, scenario factory.
 *
 * Scenarios stay Kotlin builders for now ([inego.zungjunggame.tutorial.samples]);
 * JSON/asset loaders can map into the same shape later.
 */
data class LessonDef(
    val id: String,
    val titleKey: String,
    /**
     * Lesson ids that must be completed before this one unlocks for
     * [TutorialCatalog.resumeTarget]. Nav jump ignores unlock.
     * Empty = always unlocked (campaign openers).
     */
    val requires: List<String> = emptyList(),
    /**
     * Nav chapter id ([TutorialCatalog.chapters]). Same chapter = one header group.
     */
    val chapterId: String = CHAPTER_ORIENT,
    val scenario: () -> ScenarioDef,
) {
    init {
        require(id.isNotBlank()) { "lesson id blank" }
        require(titleKey.isNotBlank()) { "lesson $id titleKey blank" }
        require(chapterId.isNotBlank()) { "lesson $id chapterId blank" }
    }

    companion object {
        const val CHAPTER_ORIENT = "orient"
        const val CHAPTER_MOVES = "moves"
        const val CHAPTER_CLAIMS = "claims"
        const val CHAPTER_KONGS = "kongs"
        const val CHAPTER_HANDS = "hands"
    }
}
