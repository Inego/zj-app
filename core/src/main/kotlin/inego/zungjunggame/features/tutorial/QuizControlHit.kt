package inego.zungjunggame.features.tutorial

/** Interactive control kinds registered while drawing the quiz surface. */
enum class QuizControlKind {
    Option,
    Bin,
    Confirm,
    Next,
    TryAgain,
    Back,
}

data class QuizControlHit(
    val kind: QuizControlKind,
    val id: String,
    val left: Float,
    val bottom: Float,
    val w: Float,
    val h: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + w && y >= bottom && y <= bottom + h
}

/** Collects hit rects while widgets paint. */
fun interface QuizHitRegistrar {
    fun add(kind: QuizControlKind, id: String, left: Float, bottom: Float, w: Float, h: Float)
}
