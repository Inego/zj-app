# Principles

Binding. These are not style suggestions — a change that violates one is rejected in review.

The short checklist lives in [`../engineering-principles.md`](../engineering-principles.md)
(the ownership table, the anti-pattern list, the boundaries); this doc is the reasoning and
the full set of rules. Where they overlap, both are binding.

---

## 1. Ownership

A behaviour belongs to the layer that owns the **kind** of thing it is, not the layer that
happens to be editing it.

| Layer | Owns | Never owns |
|---|---|---|
| Model (`session/`, `engine/`, `presence`) | authoritative state, legality, persistence | rendering, hit tests, transient drag state |
| Primitive / widget (`render/ui/*`, `Slider`, `OverlayBodyScroll`) | its own geometry, render, input reaction, transient interaction state | global intents, persistence, other widgets |
| Presentation (`features/*Overlay`, beside the feature) | turning a measured frame into pixels; publishing a GL-free geometry snapshot | business rules, navigation |
| Component (`features/*Component`) | orchestration: publish `Layout`, translate feature actions, attach ports | duplicating primitive state or mapping |
| Feature (`features/*Feature`) | `ComponentAction` → `Intent`, outside/back policy | rendering |
| Effect (`app/App*Effects`) | applying an `Intent` to the model, deciding persistence policy | UI state |

Rules:

1. **Find the owner before you write.** Before adding a field, callback, seam or adapter,
   search for the thing that already owns that behaviour and route to it. A new lambda
   injected into a component is a red flag until proven necessary.
2. **State the ownership decision out loud before editing** — one sentence: "X owns Y; the
   component only does Z." If you cannot name the owner, stop.
3. **No bandaids.** If the clean fix is a refactor, do the refactor (or propose it) instead of
   a symptom patch. Do not optimize for the smallest diff or fewest tool calls.
4. **Proactive review.** When touching a file, fix the same class of defect already in front
   of you. Do not wait for a bug report to notice a duplicated mapping or a reach-in.
5. **Do not be timid about refactors.** A missing mechanism (an intent channel, a context, a
   port) is a reason to build the mechanism, not to work around it.

---

## 2. Component contract

> **A component declares what it is and what it needs; it never reaches into another
> component or its owner.**

1. **Typed, not named.** A component has a `ComponentId` value, not an enum ordinal. There is
   no `when (id)` over surfaces anywhere.
2. **Layout is data.** A component produces a `Layout`; the renderer and the input layer read
   it. No component is interrogated by name, and no hit test reaches into another component.
3. **Ports down, actions up.** Read-only frame data arrives as `frame.model` and
   `frame.layouts`; widget instances arrive as constructor ports; user actions leave
   as `ComponentAction`s that the host maps to `Intent`s. Nothing points back up the ownership
   graph: no router, no session type, no parent component.
4. **`ui/` is pure.** Components publish `ComponentAction` (`ui/`), never `Intent` (`input/`);
   the host does the translation. `ui/` imports neither `input` nor `session`.
5. **Draw consumes published geometry.** `layout()` publishes the frame model; `draw()` must
   paint the same geometry, so a highlight and its hit region cannot disagree. Or the
   component publishes exactly the geometry `draw` uses.
6. **Components are controlled.** They render from the model and never keep a diverging
   authoritative copy.
7. **Geometry is data.** A component reads a value object (rects, track, range), not a live
   reference into a widget.

---

## 3. State, persistence and resume

1. **One source of truth per value.** If a value is persisted or model-owned, every dependent
   renders from the model. No second "live" copy that only some dependents read.
2. **Continuous interactions emit; they do not shortcut the model.** A drag, park or text
   edit reports each change through `frame.emit(ComponentAction)`; the host maps it
   to an `Intent` via the feature's route-action table.
3. **Discrete taps go through `Layout.hitRegions`; continuous input goes through
   `GestureHost` + `emit`.** Never encode a pointer-derived value into a hit-region payload to
   smuggle it through the tap path. A control whose press *mutates the widget* cannot travel
   as a hit-region payload at all — the widget resolves it and reports a release verdict.
4. **Persistence policy lives in the effect, not the widget.** The widget emits every value
   change; the component does not decide when to commit. If per-step commits are expensive,
   debounce/flush in the effect.
5. **Resume ⇒ the layer persists.** Every layer persists by default, so transience is a
   *declaration* — an owner that announces the layer and supplies its content again — never a
   role. A confirm card the player opened comes back asking the same question. There is one
   floor (`Screen(Root)`, the main menu) and everything else is a layer above it. Canonical
   bug: a surface whose identity lives outside the stack (a lobby in an overlay `visible`
   flag, a confirm's question in an effect field), so a relaunch lands somewhere else.
6. **Resume ⇒ the player's display state persists too.** Anything a control carries that the
   *player* set — a fold, a parked card, an open drawer, a scroll offset, a setup draft — is
   model state, recovered because the model is. A persisting layer carries it in the
   route UI store (`LayerSpec.state` / `StatePort`, snapshotted with the stack); an announced
   layer, whose route is not in the snapshot, keeps it in a model key. A persisted value's
   widget stops *owning* it: the owner hands the value in, the widget reports the press or the
   settle and keeps only its own appearance default — or the two owners cancel each other out.
   Canonical bugs: a fold that reset every hand *and* every relaunch, and the same fold
   flipped back by a second owner, so the control did nothing at all.
7. **Only what no snapshot can describe stays volatile:** a live gesture (finger down, drag in
   flight), unsaved text in a field, state the server supplies, and the animation chasing it.
   If the player can set it, it is not volatile.
8. **The stack is the only authority.** Overlay `.visible` flags are *derived* state; they
   never drive navigation, stack membership or persistence. Effects push/pop the route; a
   component paints when its entry is in the paint range.
9. **The display range is one stack fact.** The entries a frame paints — the topmost `Opaque`
   entry and everything above it — are exactly the entries that are up. `Below` chrome
   never takes down what it dims, and a restored stack lights up exactly as the same stack
   does when built live.
10. **No migrations for client-local state.** A schema change bumps the key's version and an
    older value is unreadable; the store yields the key's default. Migrations are for data
    that cannot be regenerated — none of this qualifies.
11. **A migration step emits the next version's layout**, not the current one.

---

## 4. Anti-patterns (each of these shipped once)

| Anti-pattern | Why it is wrong | Correct form |
|---|---|---|
| Injecting `sliderValueAt: (Float) -> Int` into a component | second source of truth; duplicates the widget's mapping | publish the track as geometry, or emit the widget's value |
| A "live" getter in an overlay to fake propagation | the model lagged because the widget never emitted | emit on change; render dependents from the model |
| Reaching into `overlay.onSliderPointerDown` from a tap payload | the component duplicates the widget's input reaction | make the component a `GestureHost`, forward to the widget, emit the result |
| Adding a getter to make a test pass | test-driven workaround | fix the ownership; test the intended channel |
| Reusing a misleading field name (`Layout.parkRegions` for a slider) | names encode a mistaken model | a general field (`Layout.gestureRegions`) or a new one |
| An effect holding a route's content beside the route | two copies of one question; the layer could not be restored | the route carries the content; the effect reads it off the stack |
| A "covered" lifecycle rule that takes down whatever a new layer covers | disagrees with paint (which skips only `Opaque`), so a live run and a restart differ | read the display range off the stack; drive paint and lifecycle from it |
| A player-set control state kept in the widget | the player set it, so it is screen state; no relaunch can recover it | the value lives in the model; the owner hands it in, the widget reports |
| A test that restates the rule it covers (a mirror round trip, a constant against itself, a fake that reimplements the seam) | two copies of one decision, both kept by hand; it says nothing the code does not | see [`testing.md`](testing.md) |

---

## 5. Navigation (this model is not negotiable)

From [`stack.md`](stack.md#the-stack):

- **One floor, forever:** `Screen(Root)`, the main menu. Never replaced, never popped.
- **Every state is traceable from the root:** back/pop walks down one layer at a time; no
  non-stack hops.
- **The one exception:** a session that no longer exists pops **its own layer** and lands on
  its parent — never on the root.
- **A context menu may cover the main menu visually, but never in the stack.**
- **Presentation is a property of the route entry**, persisted with it, so a restored stack
  looks like the stack that was left.
- **Nothing derived comes from the floor:** `SessionRegistry` reads the topmost context layer.
- The invariants I1–I9 (floor never removed, one `Screen`, kind from the top context, display
  range, input ownership, whole-stack snapshot, boot may only pop, `Router.popDismissible()` pops the
  topmost `dismissOnBack` layer and above, `ContentPolicy.InSpace` placement via
  `pushContent`) are enforced by `Router`, `ComponentHost` and `SessionRegistry`, and swept
  by tests. See [`testing.md`](testing.md).

---

## 6. Documentation

1. **Docs describe the current state.** No changelogs, no stage codes, no commit hashes, no
   war stories. If a fact is only interesting because of how it changed, delete it.
2. **One home per fact.** If two docs state the same rule, one of them links instead. The
   implementation docs (the map and its detail docs), principles and testing are deliberately
   disjoint.
3. **Rules are stated where they are enforced.** A code comment may repeat a binding rule; a
   second design doc may not restate it.
4. **A stale path or a dead type name is a bug** — fix it in the same change that kills it.
5. **When a rule conflicts with an enforced check, resolve the docs first**, then the code.
