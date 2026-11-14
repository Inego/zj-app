package inego.zungjunggame.app

/**
 * A destination named by the play page URL fragment: the mail deep link.
 * Opening `https://play.zungjung.org/#my_challenges` opens the
 * Online hub's Shadow Ranked tab once the online session exists.
 *
 * This holds the **launch input** only. The platform launcher reads the page
 * fragment before the app runs and [publish]es it here; every target but the web
 * build leaves it null, so a desktop or phone launch is unchanged. The
 * composition root reads it once with [consume], turns the target into the one
 * startup intent, and the fragment is never consulted again. What the screen
 * *does* is the intent's, not this object's.
 */
object DeepLink {
    /** The screens a play-origin fragment can name. */
    enum class Target {
        MY_CHALLENGES,
    }

    /** The destination the platform launcher captured, or null. */
    var launch: Target? = null
        private set

    /** Publish the page fragment the launcher read; unknown fragments are no link. */
    fun publish(fragment: String?) {
        launch = targetOf(fragment)
    }

    /** Read and clear the launch destination: a boot fires it at most once. */
    fun consume(): Target? {
        val target = launch
        launch = null
        return target
    }

    /**
     * The destination [fragment] names, or null. Accepts the name with or without
     * the leading `#` and with a trailing slash; matching is case-insensitive
     * because the fragment arrives exactly as the mail link authored it.
     */
    fun targetOf(fragment: String?): Target? {
        val name = fragment.orEmpty().trim().removePrefix("#").trim('/').lowercase()
        return when (name) {
            "my_challenges" -> Target.MY_CHALLENGES
            else -> null
        }
    }
}
