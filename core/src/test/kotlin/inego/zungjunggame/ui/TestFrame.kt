package inego.zungjunggame.ui

/**
 * Headless [Frame] for component tests: fixed constraints, an optional model
 * snapshot, and a recording emit. A presenter that derives nothing from the model
 * leaves [model] at its [Unit] default; a board/shell test passes an `AppModel`.
 * [emit] is last so a trailing lambda still records actions.
 */
fun testFrame(
    constraints: Constraints = Constraints(0f, 0f, 0f, 0f, 0f),
    model: Any = Unit,
    emit: (ComponentAction) -> Unit = {},
): Frame = Frame(constraints, TestUiEnvironment, null, emit, model = model)

/** UI services a headless test needs; all answers are identity/zero. */
object TestUiEnvironment : UiEnvironment {
    override fun dp(value: Float): Float = value
    override val safeArea: Rect? = null
}
