# Widgets, scopes, and the three kinds of state

Part of the [implementation](implementation.md) map.

A widget is a controlled view over GL resources. It owns the resources it constructs, and
it keeps only view-local state. Sharing a resource across widgets is normal; a widget never
disposes a resource it did not construct.

**Ownership equals construction.** A `Disposable` is obtained through `Scope.own`. The
composition root holds the app scope; `Router` holds one child scope per stack entry and
closes it when the entry leaves; `AppRuntime.dispose()` is `scope.dispose()`. Components
never declare disposal. A widget may back several components: the game-menu card backs
`ROOT_MENU`, `LOCAL_MENU`, and `GAME_MENU`. Identity is off on `GAME_MENU`, and
only that chrome home draws a close control — the root and local play menus are
the screen's own content, so they need no dismiss of their own. Parked
`LOCAL_MENU` keeps identity, draws Continue, and still pushes the board; the
chrome card's ✕ is that same dismiss, so Continue is not drawn there.
`Intent.LogIn` / `Intent.LogOut` always reach the account /
confirm ports; the chrome card not drawing the badge is the live-board gate.

Every UI value is one of three kinds:

| Kind | Owner | Lifetime | Flow |
|---|---|---|---|
| **Player-set** | the route UI store / `ui.prefs` | per the layer's retention | `StatePort` read in; `emit` write out |
| **Model-derived** | the model | recomputed every frame | `frame.model` and `frame.layouts` |
| **View-local** | the widget | gesture / frame | internal; leaves as a verdict or emitted action |

A widget that eases toward a target snaps the first target after construction and eases
later changes. The flag is view-local, next to the animation phase. Restore reconstructs
widgets, so first-apply and restore-snap are the same fact for a new instance. An
app-lifetime widget (`AppGraph` `scope.own`s it) snaps again when its identity changes:
the coach snaps on a new scene (later lesson, Back onto a lesson, reappear after hide)
and eases a target change inside the same scene. The score-breakdown fold still snaps
once per instance.

A **host** exposes `snapshot()` returning an immutable value. It mutates session/director
state and may announce routes (`showCoach` / `hideQuiz` change the stack). It does not
stuff the widgets the stack paints. `TutorialHost` publishes `TutorialSnapshot` (nested
coach, quiz, and table chrome) from the runner; `AppGraph` owns the shell / quiz / coach
widgets and the table. `TableRenderInputs` carries scores, hints, discard origin, interact,
highlights, revealed seats, and the consume-once hand apply; `PlayTableView.applyFrom`
consumes that snapshot each frame. `PlayTableView` advances `claimPanelLatched` in
`motion` after idle reconcile (`layout` reads the flag); HINT measure uses
`HintButtonRenderInput`. TutorialHost announces a reference target;
`AppNavigationEffects` writes `ReferenceNavState` and pushes the route; the overlay
paints from the store. Settlement content is `AppModel.scoreBreakdown`;
`ScoreBreakdownComponent` applies it each layout and draw. Fold / park / scroll stay
on `ui.prefs`. Presenters announce the breakdown route; the same card also reopens a
finished Shadow Ranked attempt's comparison, rebuilt from the attempts list keyed by
the `SCORE_BREAKDOWN` route's `attempt` arg.
