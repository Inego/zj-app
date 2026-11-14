# AGENTS.md — zj-app

> ## ⛔ NEVER WRITE NEW TESTS — UNLESS THE USER EXPLICITLY ASKS ⛔
>
> **Never author a new test — do not add one — unless the user explicitly asks for
> it in their message.** No exceptions, no "it earns its place", no tests in the
> same change. This rule outranks every test-related instruction in this file,
> `docs/engineering-principles.md`, and `docs/architecture/`.
>
> Maintaining existing tests is a different job, and the job is **deletion**:
> when your change breaks a test, **delete that test**. See
> [When a change breaks a test](#when-a-change-breaks-a-test) below — that
> section is binding and closes every loophole this paragraph used to leave.
> Running the existing suite is required.

Read **`docs/engineering-principles.md`** before changing code. It is binding.

## Docs map

| Doc | What it is |
|---|---|
| `docs/engineering-principles.md` | The short binding checklist: ownership model, anti-patterns, boundaries. |
| `docs/architecture/implementation.md` | The high-level map (app shape, dependency direction) and the index to the implementation docs: components, widgets/state, frame loop, stack, state/persistence, input, sessions, features. |
| `docs/architecture/ui-layers.md` | Gateway for UI changes: stack, persistence, state ownership, and the decision guide. |
| `docs/architecture/principles.md` | The full rules for design, documentation and testing. |
| `docs/architecture/testing.md` | How to verify, what earns a test, the restart acceptance matrix. |

## Non-negotiables

- **Find the owner before you write.** Search for the primitive/port/component
  that already owns a behavior and route to it. A new callback/seam injected into
  a component is a red flag until proven necessary.
- **No bandaids.** Prefer the proper refactor over a symptom patch. Do not
  optimize for smallest diff or fewest tool calls. State the ownership decision
  ("X owns Y; the component only does Z") before editing.
- **One source of truth.** Components are controlled: they render from the model
  and never keep a diverging authoritative copy. Continuous interactions (drag,
  park, text edit) **emit** through `frame.emit(ComponentAction)`; do
  not smuggle pointer-derived values through hit-region payloads.
- **Layer ownership.** Widgets own their state+render+input; components
  orchestrate; features map `ComponentAction` → `Intent`; effects apply intents
  and own persistence policy. Geometry is data.
- **`ui/` stays pure.** Components publish `ComponentAction`, never `input.Intent`.
- **UI is as narrow as its content.** A window, panel, table or list takes the
  width it needs and no more: size every column to the widest thing it must draw
  (a digit sample for numbers, the policy's own bound for a name), then center
  the result in the space available. Never let a column absorb leftover width to
  "fill" a panel — an empty column on a wide screen is a defect, and a viewport-
  sized row on a narrow one clips.
- **A table's geometry is stable while it is open.** Paging, scrolling, fetching
  a page, or switching the shown document must not re-measure or resize a column,
  move an edge, or reflow the rows. Derive widths from the bounds that cannot
  change mid-view (the snapshot's total, a digit budget, a policy limit) — never
  from the rows currently in hand. Re-measure only when the panel itself resizes,
  the locale changes, or the data's own bound does.
- **Proactive review.** Fix the same class of defect in code you are already in.
- **New tests require an explicit request first** (see the banner at the top — it
  outranks this bullet). Do not add a test unless the user asks. A change that
  breaks an existing test **deletes** it; the procedure and every loophole closed
  live in [When a change breaks a test](#when-a-change-breaks-a-test). Do not
  grow the suite to compensate. Only when the user asks: a test may exist to
  exercise complex logic or the interaction of components. A test that restates
  the implementation it covers — a mirror, a getter/setter round trip, a constant
  asserted against itself — is a second source of truth and is deleted, not added.
  Never add a test for a change that does not have such logic: fix the design
  instead. Prefer sweeping one invariant over the whole declaration set over one
  case per instance, and do not grow the suite to compensate for a design that
  should make the answer obvious.

## When a change breaks a test

Binding. This section decides the question for you; do not re-open it.

**The default is deletion.** If your change broke it, it goes.

**Delete, unambiguously, when any of these is true:**

- The test fails, or no longer compiles. *A test that will not compile is
  broken, not exempt.* Deleting a type, changing a signature, or moving a symbol
  deletes every test that named it — an unresolved reference is a compile error,
  not a technicality to be patched around.
- Its assertion, expected value, or subject now describes the old design.
- You renamed it, narrowed it, trimmed a case from it, or split it.

**A test's merit is not an input.** "It pins a valuable invariant", "coverage is
lost", "deleting it seems wasteful", "the suite is the last record of this
behavior" — none of these are considered, and you do not get to weigh them. The
suite is a consequence of the design, not an asset to be defended. If a
deletion loses coverage you think matters, **name the loss in your report** so
the user can decide — that is how the information flows, and it does not make
the decision yours.

**Do not seek permission, and do not narrate compliance as a deviation.**
Do not ask whether to delete, do not offer to reinstate or rewrite, do not present
a deletion for ratification, do not keep one alive "transparently". Transparency
is not a defense — a deviation you announce is still a deviation. Delete, list
what went in the report, move on.

**The one narrow exception**, and all three must hold: your change touched
nothing the test asserts, its subject is unchanged, and the sole edit is
mechanical — supplying a newly required argument, adding an import for a moved
symbol. Even then, name the edit in the report so it can be vetoed. Touch an
assertion, an expected value, or a name and the exception is void: delete.

**A suite that will not compile is not an emergency.** The compile error is the
deletion list reporting its own size. Fix the design, delete what named the
thing you removed, run the suite.

## Boundaries

- `core/src/test/.../arch/ArchitectureRatchet.kt` is **shrink-only** and
  currently **all-empty**. Never add an entry.
- `session/` is render-free. `ui/`, `state/` are pure. No `Hooks`, no settable
  `SessionKind`, no `features/ → app` imports.
- The architecture in the docs rooted at `docs/architecture/implementation.md` is the target:
  implement it, don't work around it. For **navigation** the model is one floor
  (the main menu), every other state a layer above it, the whole stack persisted,
  back popping to the root.

## Verify (from the repo root)

```
.\gradlew.bat :core:compileKotlin :core:compileTestKotlin --console=plain
.\gradlew.bat :core:test --console=plain
.\gradlew.bat :core:test --tests "inego.zungjunggame.arch.*" --console=plain
```

Tests must pin the intended design; a test that only passes because of a
workaround is a bug — and a test that fails against the design you were asked
for is deleted, not rewritten. One stage per change, verified green, then
committed.
