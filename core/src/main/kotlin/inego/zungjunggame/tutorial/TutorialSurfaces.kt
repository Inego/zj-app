package inego.zungjunggame.tutorial

/**
 * Stack-membership surface for the tutorial: the lesson shell context and the
 * content layers it hosts (the lesson board, the orientation quiz, the coach /
 * complete panel).
 *
 * Production backs each call with the route (`push` / `remove`), and the stack's
 * paint range owns what is up. The host owns the tutorial session and announces
 * the shell's membership with it, so the layer's lifetime is the session's — a
 * lesson that is torn down takes the context it was hosted in down with it — and
 * no overlay `.visible` flag drives stack membership.
 *
 * The shell goes directly above the floor ([showShell]) and the lesson directly
 * above the shell ([showLesson]), so the lesson stays inside the shell's local
 * space whatever chrome sits above it.
 */
interface TutorialRoutesPort {
    /** Raise the tutorial shell context above the floor. No-op while it is up. */
    fun showShell()

    /** Take the shell context down, with the lesson/quiz/coach inside it. */
    fun hideShell()

    /** Bind the live lesson board to the shell, as the shell's content layer. */
    fun showLesson()

    fun hideLesson()

    fun showQuiz()
    fun hideQuiz()
    fun showCoach()
    fun hideCoach()
}
