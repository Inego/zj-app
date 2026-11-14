package inego.zungjunggame.tutorial.slide

/** Easing curves for [SlideEffect] timelines (pure; no render dependency). */
enum class SlideEase {
    Linear,
    OutCubic,
    InOutCubic,
    ;

    fun apply(t: Float): Float {
        val u = when {
            t <= 0f -> 0f
            t >= 1f -> 1f
            else -> t
        }
        return when (this) {
            Linear -> u
            OutCubic -> {
                val inv = 1f - u
                1f - inv * inv * inv
            }
            InOutCubic -> {
                if (u < 0.5f) {
                    4f * u * u * u
                } else {
                    val x = -2f * u + 2f
                    1f - (x * x * x) / 2f
                }
            }
        }
    }
}
