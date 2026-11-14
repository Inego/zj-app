# Engineering principles (hard requirements)

**Status:** Binding. These are not style suggestions.
**Audience:** everyone writing `core/`, especially agents.
**Scope:** how to change this codebase and how to decide where a behavior lives.

This document exists because the component-architecture work repeatedly shipped
local workarounds where the design had a proper home. Each rule below is written
so it can be checked in review.

---

## 1. How to work (process requirements)

1. **Find the owner before you write.** Before adding a field, callback, seam, or
   adapter, search for the thing that already owns that behavior (a primitive, a
   port, a `Component`, an effect). Route to it. A new lambda injected into a
   component is a red flag until proven necessary.
2. **No bandaids.** If the clean fix is a refactor, do the refactor (or propose
   it explicitly) instead of a symptom patch. Do not optimize for the smallest
   diff or fewest tool calls.
3. **State the ownership decision out loud before editing.** One sentence:
   "the X owns Y; the component only does Z." If you cannot name the owner, stop.
4. **Proactive review.** When touching a file, fix the same class of defect in
   the code already in front of you. Do not wait for a bug report to notice a
   duplicated mapping, a stale model read, or a reach-in.
5. **Design beats green tests.** Tests must pin the *intended* design. A test
   that encodes a workaround is a bug. Remove the workaround, then delete the
   test that leaned on it — the design is the fix, never the assertion. See
   [`../AGENTS.md`](../AGENTS.md) → "When a change breaks a test".
6. **Do not be timid about refactors.** Missing mechanisms (an intent channel, a
   context, a port) are reasons to build the mechanism, not to work around it.

## 2. Ownership model (MVC)

Five layers, each with exactly one job. A behavior belongs to the layer that
owns the *kind* of thing it is, not the layer that happens to be editing it.

| Layer | Owns | Never owns |
|---|---|---|
| Model (`session/`, `engine/`, `presence`) | authoritative state, legality, persistence | rendering, hit tests, transient drag state |
| Primitive / widget (`render/ui/*`, `Slider`, `OverlayBodyScroll`) | its own geometry, its own render, its own input reaction and transient interaction state | global intents, persistence, other widgets |
| Presentation (`features/*Overlay`, beside the feature that owns the screen) | turning a measured frame into pixels; publishing a GL-free geometry snapshot | business rules, navigation |
| Component (`features/*Component`) | orchestration: publish `Layout`, translate feature actions, attach ports | duplicating primitive state or mapping |
| Feature (`features/*Feature`) | `ComponentAction` → `Intent`, outside/back policy | rendering |
| Effect (`app/App*Effects`) | applying an `Intent` to the model, deciding persistence policy (debounce/flush) | UI state |

Corollaries:

- **Geometry is data.** A component reads a value object (rects, track, range),
  not a live reference into a widget.
- **Components are controlled.** They render from the model snapshot. They do
  not hold authoritative state that can diverge from the model.
- **Layout is data; input is actions; intents are global.** (See
  `docs/architecture/implementation.md`.)
- **Resume ⇒ the layer persists.** A surface the user can be on when the app
  closes and must return to is a stack layer whose policy persists. Every layer
  persists by default, so transience is a *declaration* — an owner that announces
  the layer and supplies its content again — never a role: a confirm card the
  player opened comes back asking the same question. There is one floor
  (`Screen(Root)`, the main menu) and everything else is a layer above it; see
  `docs/architecture/stack.md#the-stack`. The canonical bug: a surface whose identity lives
  outside the stack — a lobby in an overlay's `visible` flag, a confirm's question
  in an effect field — so a relaunch lands on something else, or on nothing.
- **Resume ⇒ the player's display state persists too.** Anything a control
  carries that the *player* set — a fold, a parked card, an open drawer, a scroll
  offset, a draft in a setup card — is model state, recovered because the model is,
  not because a widget happened to keep it alive. Only what no snapshot can
  describe stays in the widget: a live gesture (a finger down, a drag in flight),
  unsaved text in a field, and the animation chasing it. Where it lives follows the
  layer: a persisting layer's route carries it in the route UI store
  (`LayerSpec.state` / `StatePort`, snapshotted with the stack); an announced layer,
  whose route is not in the snapshot, keeps it in a model key. A persisted value's
  widget stops *owning* it — the owner hands the value in, the widget reports the
  press or the settle and keeps only its own appearance default — or the two owners
  cancel each other out. Canonical bugs: a fold that reset on every hand *and* on
  every relaunch, and the same fold flipped back by a second owner, so the chevron
  did nothing at all.
- **The stack is the only authority.** Overlay `.visible` flags are *derived*
  state. They never drive navigation, stack membership, or persistence; effects
  push/pop the route and a component paints when its entry is in the paint range.
- **The display range is one stack fact.** The entries a frame paints — the
  topmost `Presentation.Opaque` entry and everything above it — are exactly the
  entries that are up. So a chrome layer (`Below`) never takes down what it dims,
  and a restored stack lights up exactly as the same stack does when it is built
  live. Canonical bug: the host hid whatever it covered, so a confirm card over
  the main menu blanked the card in a live run and left it standing after a restart.
- **Draw consumes published geometry.** `layout()` publishes the frame model
  (rects, hit regions, panel, track); `draw()` must paint the same geometry it
  published, so a highlight and its hit region cannot disagree.

## 3. Interaction and state propagation

1. **One source of truth per value.** If a value is persisted/model-owned, every
   dependent renders from the model. Do not introduce a second "live" copy that
   only some dependents read while others read the lagging model.
2. **Continuous interactions emit; they do not shortcut the model.** A drag,
   park, or text edit reports each change through `frame.emit(ComponentAction)`;
   the host maps it to a global `Intent` via the feature's `RouteActions`. See
   `core/.../ui/Frame.kt`.
3. **Discrete taps go through `Layout.hitRegions`; continuous input goes through
   `GestureHost` + `emit`.** Do not encode a pointer-derived value into a hit
   region payload to smuggle it through the tap path.
4. **Persistence policy lives in the effect, not the widget.** If per-step
   commits are expensive, debounce/flush in the effect. The widget emits every
   value change; the component does not decide when to commit.
5. **`ui/` stays free of `input`.** Components publish `ComponentAction`
   (`ui/`), never `Intent` (`input/`); the host does the translation. This keeps
   the architecture ratchet satisfied.

## 4. Anti-patterns (each of these shipped once)

| Anti-pattern | Why it is wrong | Correct form |
|---|---|---|
| Injecting `sliderValueAt: (Float) -> Int` into a component | second source of truth; duplicates the widget's own mapping; hidden dependency | publish the track as geometry data, or emit the widget's value |
| `liveMinOtherHumans()` in the overlay to fake live propagation | the model lagged because the widget never emitted; the "fix" encoded the lag | emit on change; render dependents from the model |
| Reaching into `overlay.onSliderPointerDown` from a component's tap payload | component mapping duplicated the widget's input reaction | make the component a `GestureHost`, forward to the widget, emit the result |
| Adding a getter to make a test pass | test-driven workaround | fix the ownership; test the intended channel |
| Reusing a misleading field (`Layout.parkRegions` for a slider) | names encode a mistaken model | general field (`Layout.gestureRegions`) or a new one |
| An effect holding a route's content beside the route (the confirm's pending action next to the route's title key) | two copies of one question: the persisted card could not be reconstructed, so its layer was left out of the snapshot and the question was lost on a restart | the route carries the question; the effect reads it off the stack and owns only what the answer does |
| A covered rule that takes down whatever a new layer covers (`ComponentHost.onStackChanged`) | it disagreed with paint: `draw` skips only what an `Opaque` layer covers, so a `Below` chrome card blanked the surface it was drawn over — and only on the live path, so a restart showed something else than the run before it | read the display range off the stack, and drive both paint and lifecycle from it |
| A player-set control state kept in the widget (`ScoreBreakdownOverlay.collapsed`) | it is screen state — the player set it — so the card forgot the fold on the next hand and no relaunch could recover it, while the park of the same card was already model state | the value lives in `ui.prefs` beside the park: the owner hands it in every frame and the widget reports the press instead of flipping it |
| A test that restates the rule it covers (a mirror: a round trip of a value the codec already owns, a constant asserted against itself, a fake that reimplements the seam under test) | two copies of one decision, both kept by hand, and the suite says nothing the code does not already say — it is how a suite reaches 1700 cases with no consequence for deleting most of them | exercise the interaction or the non-obvious logic; sweep one invariant over the whole declaration set instead of one case per instance, and drive the real seam rather than a fake of it |

## 5. Boundaries and verification

- `core/src/test/.../arch/ArchitectureRatchet.kt` is **shrink-only** and is
  currently **all-empty** (zero accepted violations across every rule). A change
  that adds an entry is rejected.
- Enforced boundaries: `ui/` and `state/` pure; `session/` render-free; no
  `Hooks`; no settable `SessionKind`; `features/` must not import `app`; no
  `Disposable` on a `Component`; a `ProvidesSpace` route's component implements
  `SpaceProvider`; `tutorial/` does not import the feature widgets; `layout()`
  does not write overlay fields.
- Verify before claiming done, from the repo root:

  ```
  .\gradlew.bat :core:compileKotlin :core:compileTestKotlin --console=plain
  .\gradlew.bat :core:test --console=plain
  .\gradlew.bat :core:test --tests "inego.zungjunggame.arch.*" --console=plain
  ```

- One stage per change, verified green, then committed.
- **A test earns its place by exercising logic or an interaction**, never by
  restating the implementation it covers: a mirror test is a second source of truth
  for the same rule, maintained by hand, and it says nothing the code does not
  already say. Sweep one invariant over the whole declaration set (the placement
  sweep in `RouteSpecsTest` is the shape: every context × every layer that can stand
  over it × every content layer) instead of one case per instance, prefer driving
  the real seam over a fake that reimplements it, and delete a case whose subject a
  change removes. A test added to compensate for a design that should make the
  answer obvious is a bug in the design.
- The blueprint is `docs/architecture/`. Implement it; do not work around it. If a
  blueprint item conflicts with an enforced rule, resolve the conflict in the docs
  first.

## 6. References

- `docs/architecture/` — the design record: `implementation.md` (the root map) and its
  detail docs, `principles.md`, `testing.md`.
- `docs/rules-and-ui-architecture.md` — engine/session/render split and the rules API.
