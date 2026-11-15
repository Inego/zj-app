# UI gateway — stack, persistence, state ownership

Orientation for anyone fixing or changing UI. This is the **map, not the law**:
the binding rules live in [`principles.md`](principles.md) and the current design
in [`implementation.md`](implementation.md). This page answers one question —
*which layer owns this behaviour, so where does the change go?* — and links to
the doc that owns each fact.

Open it when you ask:

- "Where does this value live, and who may write it?"
- "Why did this reset on relaunch / on the next hand?"
- "Who removes this layer, and what happens to its state?"
- "Where do I add a screen, a tap, a drag, or a persisted preference?"

---

## 0. The one-sentence model

Every surface is a **route on one stack**. The stack decides what is painted,
what receives input, what back does, and what survives a relaunch. Every UI value
is exactly one of three kinds: **model-derived** (recomputed each frame),
**player-set state owned by a store** (the route UI store or `ui.prefs`), or
**view-local** (a live gesture, unsaved text, an animation).

```
 input ─▶ component ─▶ ComponentAction ─▶ feature ─▶ Intent ─▶ effect
                                                                  │
                    next frame: model / stores  ◀────────────────┘

 STACK            what is up, in what order, for how long
 STATE OWNERSHIP  who owns each value and where it is read/written
 PERSISTENCE      which values outlive the process, under which key
```

The three layers are separate on purpose: a value can be on the stack without
being persisted, and persisted without being route-scoped. Pick each independently.

---

## 1. The stack layer — navigation and lifetime

Canonical detail: [`stack.md#the-stack`](stack.md#the-stack);
rules: [`principles.md §5`](principles.md#5-navigation-this-model-is-not-negotiable).

**One floor, everything else a layer.** `Route.Screen(Root)` is the main menu and
never leaves. Every other state is a route pushed above it. There is no second
screen and no non-stack hop.

| Route kind | Meaning |
|---|---|
| `Screen(id)` | The root only. The floor. |
| `Layer(id, args, policy?)` | A **context**: local board/menu, online hub/table, tutorial shell. |
| `Modal(id, args)` | Chrome card: menu, settings, picker, confirm, score table, setup, account cards — or a full-screen surface when the route draws one: the rating chart is declared `Opaque` and `onFelt = false`, so it replaces the hub or leaderboard it was opened from rather than floating over it. |
| `Overlay(id, args)` | Chrome that is not a card: away, connect, hint button, breakdown, lesson/quiz/coach. |
| `Reference(returnTo)` | The rules browser, carrying a return stack. |
| `Tooltip(id)` | Declared vocabulary; currently unused. |

**Declaration vs entry.** `LayerSpec` (`runtime/RouteSpecs.kt`) declares each
route once: `role`, `ownsInput`, `dismissOnBack`, `blocksAutomation`,
`contextKind`, `policy: LayerPolicy(presentation, persists)`, `menuSurface`,
`onFelt`, `content`, `suppressesConnectCard`, `state`, `remembersState`. A push may
override a `Layer`'s policy; the **resolved** spec is stored on the entry
(`RouteInstance.spec`), so paint and the snapshot read one value.

**Everything derives from the stack** — never from a parallel flag:

- **Paint / layout / motion / input range** = `Stack.paintRange`: the topmost
  `Opaque` entry and everything above it. Covered entries are not modelled at all.
- **Input walk** = the same range, top→down; the topmost `ownsInput` layer takes
  the pointer and everything below it is occluded (hover included).
- **Generic dismissal** = `Router.popDismissible()` pops the topmost `dismissOnBack`
  layer and above. This is **not** the app's back. The app's back is
  `AppDispatcher.back()`, which resolves the topmost route's `RouteActions.back`
  to an `Intent` (a menu *hides*, a confirm answers *No*, a board *raises* the
  menu). Never read `dismissOnBack` as "does this route use back?".
- **Session kind** = an attached online host, else the topmost layer with a
  non-null `contextKind`, else `Menu` (`SessionRegistry`). The Online hub is a context
  that declares no kind, so it reads `Menu` until `ONLINE_TABLE` (which declares
  `Online`) stands over it; an account surface may therefore cover the hub.
- **Automation block / menu surface / on-felt** = stack scans.

**Removal is lifetime.** `Router` owns every entry's child `Scope` and its
route-scoped UI state. On removal, in order: the new stack is published, observers
run, then each leaving entry's scope is disposed and `onEntryRemoved(key)` fires
(wired to `RouteUiState.remove`). A kept entry keeps its instance and scope.
Consequence: a route's own state dies with it, so take it with
`RouteUiState.consume` (read-and-remove in one step) when you close the route — a
plain `read` after removal sees nothing and a plausible default will hide the
mistake. The local setup's consumer does exactly this (`FreePlaySetupPort.consumeDraft`).

**Placement.** `pushContent` inserts an `InSpace` layer into the nearest lower
`Context`'s content group. `ProvidesSpace` means the layer publishes a
`Layout.space` for the layers composed above it (the tutorial shell does).

`Router` API: `push` · `pushAbove` · `pushAboveFloor` · `pushContent` · `pop` ·
`popTo` · `remove` (key + everything above) · `popDismissible` (generic dismissal) ·
`snapshot` · `restore` · `addObserver`.

---

## 2. The persistence layer — durability

Canonical detail: [`state-and-persistence.md#state-and-persistence`](state-and-persistence.md#state-and-persistence);
rules: [`principles.md §3`](principles.md#3-state-persistence-and-resume).

**One versioned key per artifact.** `StateKey<S>(namespace, name, schema, default,
secret)` on a `StateStore`. `read` never throws: corrupt, missing, or
forward-versioned data yields the key's default. `StateSlot(store, key)` is the
narrow handle owned by the model that holds the value.

| Key | Holds | Scope |
|---|---|---|
| `stack.state` | `StackState` — routes, args, resolved policy, per-route UI text | route stack |
| `session.freeplay` / `session.tutorial` | live board + scores / mid-lesson progress | session |
| `game.prefs` / `ui.prefs` / `ui.locale` | `GameConfig` / `UiPreferences` / `AppLocale` | app-wide |
| `online.find` / `online.table` | find-game prefs / last table id | lazy |
| `ui.remembered` | last value of each remembering route | lazy |
| `notification.prefs` | last server-confirmed notification settings | lazy |
| `tutorial.progress` / `auth.firstLogin` | campaign progress / answered prompts | lazy |
| `auth.session` (**secret**) | refresh token + profile (never the access JWT) | app-wide |
| `platform.window` | desktop window geometry | desktop only |

**Backends.** `PreferencesStateBackend` container `zj-state` (small keys);
`FileStateBackend` folder `zj-files` (`session.freeplay`, `session.tutorial`);
`InMemoryStateBackend` for tests. The containers deliberately do not share a name.
`SecretBox` seals secret keys; a failed seal **drops** the write (the session stays
memory-only) rather than looking durable.

**Flush.** `AppRuntime` flushes once at `pause`/`exit`/`dispose`, and captures a
changed stack at most once a minute (the dirty check is stack-instance identity).
Persistence policy lives in the effect, never the widget.

**Cold start** (`app/AppState.kt`) is one read and one order: `stack` → `session`
→ `tutorialSession` → `account` → `game` → `ui` → `locale`; then `Router.restore`,
then seed `RouteUiState` from each entry's text through its declared codec, then
restore the session the floor names. Keys whose owners read
lazily (`online.find`, `online.table`, `ui.remembered`, `notification.prefs`,
`tutorial.progress`, `auth.firstLogin`) are deliberately absent from the boot read.

**The stack snapshot is the route store's persistence form.** There is no separate
save of route UI state: `Router.snapshot` projects each persisting entry's state
text into `StackState`; restore rebuilds the stack and re-seeds the store. This is
why the two can never desync, and why an *announced* layer opts out of the stack.

---

## 3. State ownership — who owns a value

This is the decision that causes most UI defects. Classify the value first, then
place it. The single **player-set** kind splits by owner into the first two rows:
route-scoped state belongs to the entry, stack-independent state belongs to the app.

| Kind | Owner | Lifetime | Flow |
|---|---|---|---|
| **Player-set, route-scoped** | the **route UI store** (`LayerSpec.state`) | the entry's life; snapshotted with the stack, or kept in `ui.remembered` when the route remembers | `StatePort` read in; `emit(RouteUiActions.write(...))` out |
| **Player-set, stack-independent** | **`ui.prefs`** | app-wide, survives everything | slot read in; `emit` an intent; `AppPrefsEffects` writes |
| **Model-derived** | the **model** (`AppModel`, snapshots) | recomputed every frame | `frame.model` / `frame.layouts` |
| **View-local** | the **widget** | a gesture, a frame | internal; leaves as a verdict or emitted action |

Rules of thumb:

- If the **player set it** and it should survive leaving the surface, it is *not*
  view-local: it is player-set state. A persisted value's widget stops owning it —
  the owner hands the value in, the widget reports the press.
- If it is **recomputed from the session/model**, it is model-derived; never cache
  a second copy that can diverge.
- If no snapshot can describe it (finger down, drag in flight, unsaved text, the
  animation chasing a target, server-supplied state), it stays in the widget.

### 3a. The route UI store (player-set, tied to one entry)

`RouteUiState` (`ui/RouteUiState.kt`) is an in-memory, route-keyed store,
cleared exactly when its entry leaves the stack and persisted only as the entry's
`componentState` — unless the route **remembers** its state (below).

- **Declare** the value: add the route to `STATE_CODECS` in `runtime/RouteSpecs.kt`
  with a `RouteStateCodec<T>` (stable `type`, `encode`, `decode`). Decode returning
  null is the one corrupt/unknown-version path; absence is `null`, never a sentinel.
- **Read** through `StatePort` (built by the presenter's factory from the store,
  the route key, and the codec). Null means unset — the widget's own default stands.
- **Write** by emitting
  `RouteUiActions.write(codec, value)`; `FeatureRegistry` intercepts it to
  `Intent.WriteRouteUi`, and `AppPrefsEffects.writeRouteUi` is the sole writer.
- **Remember** (opt-in): a route in `RouteSpecs.REMEMBERED_STATES` sets
  `LayerSpec.remembersState`, and every write is mirrored into the `ui.remembered` key
  by route. `read`/`consume` fall back to it once the entry is gone, so the value
  survives the removal and the process; `remove` never clears it. The Online hub
  opts in, so it opens on its last tab, scrolls, and Shadow Ranked draft. The tab
  resets to Live on an explicit log-out (a new account must not inherit it), while
  the draft and scrolls stay — they are display state, not account state. The
  tutorial shell and the reference browser opt in too, so closing either and
  reopening it lands where the player left it (the shell's TOC/drawer/pull tab,
  the browser's section, page, scrolls, and drawer).

Current route state:

| Codec | Routes | Stores |
|---|---|---|
| `GameMenuCardCodec` | root/local/game menu | card body scroll + account drawer |
| `SetupCardCodec` | new-game setup | draft `GameConfig` + scroll |
| `TutorialShellCodec` | tutorial shell | TOC scroll, drawer, pull-tab |
| `OnlineHubCodec` | Online hub | tab, both tab scroll offsets (the card scrolls as one column), Shadow Ranked draft |
| `RatingHistoryCodec` | rating history | target (a public handle or self), visible window, rung |
| `RouteScrollCodec` | score table, history, Shadow Ranked attempts, notices, language picker, settings | scroll offset — sticky-chrome body, or the whole card column for Settings |
| `ReferenceNavCodec` | reference | section, page, TOC/content scroll, drawer |

### 3b. `ui.prefs` (player-set, stack-independent)

`UiPreferences` on the `ui.prefs` key: fold, parks, breakdown scroll, tile labels,
sound. It is **not** tied to a route, so it is the home for an announced layer's
player-set state and for app-wide display choices. `AppPrefsEffects` is the only
writer; readers take the slot directly and keep no copy. Canonical bug this
prevents: a fold parked in the widget, forgotten on the next hand.

### 3c. Model-derived and view-local

Model-derived values are assembled once per frame into `AppModel` (session,
tutorial, table inputs, board render, score breakdown) and read via
`Frame.appModel()`; derivation helpers live beside the component, not in the
framework. View-local state is private component/widget state (snap flags,
animation phases, live gesture) and never authoritative.

---

## 4. The pipeline beneath the layers

Canonical: [`engineering-principles.md`](../engineering-principles.md) §2, and
[`implementation.md`](implementation.md) with its detail docs. One sentence per layer:

| Layer | Owning code | Job |
|---|---|---|
| Model | `session/`, `engine/`, `state/`, `persist/` | authoritative state, legality, persistence |
| Primitive / widget | `render/ui/*` | its own geometry, render, input reaction, transient gesture state |
| Presentation | `features/*Overlay` | measured frame → pixels; publishes GL-free geometry |
| Component | `features/*Component` | orchestration: publish `Layout`, adapt widgets, attach ports |
| Feature | `features/*Feature` | `ComponentAction` → `Intent`, back/outside policy |
| Effect | `app/App*Effects` | apply `Intent` to the model; own persistence policy |

**Frame loop** (in `AppRuntime.renderFrame`): assemble → `motion` → layout (input
authority) → input (relayout on stack change) → host ticks → assemble + layout
(paint authority) → paint → post-paint host ticks → flush-if-dirty.

**The action channel.** Discrete taps resolve through `Layout.hitRegions` to a
`ComponentAction`; continuous input (drag, park, scroll, slider) goes through
`GestureHost` + `GestureBridge` and also emits a `ComponentAction`. Both paths
reach the same per-feature `RouteActions` table — `ui/` never emits an `Intent` and
never imports `input/`. `FeatureRegistry.actionToIntent` applies the table, with
one global intercept for `RouteUiActions.write`.

**Adding a surface** means: an id in `RouteIds`, a `LayerSpec` entry (role,
policy, context, state codec) in `RouteSpecs`, a component factory + `RouteActions`
registered in `runtime/GameFeatures.kt`, and a feature package that maps its
actions. `AppGraph` constructs the concrete overlay/widget and owns its scope.

---

## 5. Decision guide — common tasks

| Task | Owner / place |
|---|---|
| Add a screen, modal, or overlay | `RouteIds` + `RouteSpecs` + `GameFeatures` + a `features/` package |
| Make a card remember a player-set value while up | route UI store: `LayerSpec.state` codec + `StatePort` in, `RouteUiActions.write` out |
| Persist a value across relaunch | if a card's state should outlive its entry → route UI store with `remembersState`; if the surface is on the stack → route UI store; if stack-independent → `ui.prefs` |
| A value resets on relaunch | it is view-local but player-set — move it to the route store or `ui.prefs` |
| A value resets every hand | same; likely should live in `ui.prefs` (parks, folds) |
| React to a tap | add a hit region in `layout()`; map its `ComponentAction` in the feature |
| Add a drag / slider / scroll | be a `GestureHost` (`GestureBridge`); emit the verdict; keep the value in the model |
| A scrolling body that holds a sideways-only control (a slider) | arbitrate with `render/ui/GestureAxis.kt`: a dominant sideways drag takes the control, any other pan scrolls the body; the press arms both, and `scrollGrabbed` lets the scrollbar win |
| A pan/zoom gesture (two fingers, fling, 2D wheel) | feed `chart/ChartGestureRecognizer` from `frame.constraints.pointers` (per frame) and its wheel form from `onScroll`; apply the returned `ChartGesture` through `ChartViewport.applying`, and persist the resulting `[from, to]` the way any player-set range is persisted |
| Cover or raise a card | `ScreenTransitions` / `RouteModals` push/pop routes; never a `visible` flag |
| A route's state read is null right after `remove` | read **before** the removal; ordering is the caller's contract |
| A restored stack looks different from the live one | check `Presentation`/`persists` resolved on the entry, not a component flag |

---

## 6. Hazards worth memorising

- **Removal kills state and scope.** `Router.onEntryRemoved` → `RouteUiState.remove`.
  Read a route's own state before removing it; a default hides the inversion.
- **Absence is `null`, never a sentinel.** "Unset" and "set to zero" are different.
- **A codec-type mismatch reads as absence.** Swapping a route's codec silently
  discards old text instead of failing.
- **Every layer persists by default.** Transience is a declaration in
  `RouteSpecs.ANNOUNCED_LAYERS`, owned by the announcer — never a role.
- **Paint range is not `entries`.** It is the topmost `Opaque` layer and above;
  what it covers is not painted, laid out, or interactive.
- **A gesture-claimed (`scrollable`) layer's `hitRegions` only resolve for presses
  the widget declines.** A widget that claims the whole panel makes its regions
  dead; `ScoreBreakdownComponent` is the counter-example — it publishes both and
  its gesture surface hands pattern-row presses back to the tap path. The Online
  hub is the same shape at the shell: its card — header, tabs and body — is one
  scrolled column, so `OnlineHubComponent` declines the presses that land on the
  tab/close/link rects it published, and those taps resolve against the regions
  while every other press pans the column.
- **A control inside a scroll body must not claim the press on touch-down.** A
  press is a pan until its direction says otherwise; a slider that takes the
  gesture immediately makes every scroll that starts on it dead. Arbitrate with
  `render/ui/GestureAxis.kt` instead of testing the control first.
- **`motion` runs before input and carries no `frame.layouts`**; it is for inertia
  that may emit, not a lifecycle event.
- **Post-paint host ticks surface next frame.** Input geometry also lags a
  non-navigation host mutation within the frame.
- **`RouteKey` ignores `args`.** One factory per id; content such as a confirm
  question travels in `route.args`.
- **`StackState` has no migrations.** A version bump makes old stacks unreadable
  and the client starts at the floor.

---

## 7. Where to look

- Sources: `ui/stack/` (routes, specs, router, stack) · `ui/RouteUiState.kt`,
  `Frame.kt`, `Layout.kt`, `GestureBridge.kt` · `state/`, `persist/` ·
  `runtime/AppModel.kt`, `RouteIds.kt`, `RouteSpecs.kt`, `FeatureRegistry.kt` ·
  `app/AppState.kt`, `AppRuntime.kt`, `App*Effects.kt` · `features/*`.
- Style: `render/ui/AppPalette.kt` is the UI's only palette and the only place
  that knows a specific colour. It exports logical roles (surfaces, rims, ink,
  accent, totals, semantic colours, felt); components read those roles and
  never restate a hue. Change a token there, not at a call site.
- Canonical docs: [`principles.md`](principles.md) (rules),
  [`implementation.md`](implementation.md) (today's design and its detail docs),
  [`testing.md`](testing.md) (how to verify, restart matrix),
  [`../engineering-principles.md`](../engineering-principles.md) (checklist).
