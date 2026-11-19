# Architecture docs

[`principles.md`](principles.md) and [`testing.md`](testing.md) are the canonical rules and
verification docs. [`implementation.md`](implementation.md) is the root of the
current-design record: it holds the app's shape and indexes one dedicated doc per
mechanism. [`ui-layers.md`](ui-layers.md) is a **gateway** over stack, persistence, and state
ownership for UI changes.

| Doc | Answers |
|---|---|
| [implementation.md](implementation.md) | The high-level map: app shape, dependency direction, and where every detail lives. |
| [components.md](components.md) | The component contract: `Component`, `Frame`, `Layout`, space, context and the composition root. |
| [widgets-and-state.md](widgets-and-state.md) | Widgets, scopes, ownership by construction, the three kinds of UI state, hosts. |
| [text-in-space.md](text-in-space.md) | How variable-length text is measured, fitted, cut, wrapped, scrolled, and the prioritized places where the model is not uniform. |
| [frame-loop.md](frame-loop.md) | The per-frame order from assemble to paint and flush. |
| [stack.md](stack.md) | Routes, `LayerSpec`, `Router`, back vs generic dismissal, `StackState`. |
| [state-and-persistence.md](state-and-persistence.md) | `StateStore`, schemas, backends, flush policy, keys, route UI store, cold start. |
| [input.md](input.md) | Normalization, hit/gesture/keyboard/scroll resolution, `Intent`, dispatcher, gesture seam. |
| [sessions.md](sessions.md) | Snapshots, hosts, `SessionRegistry`, request ordering, feature boundaries. |
| [features/online.md](features/online.md) | The Online hub, live table, account surfaces, and the connect card. |
| [features/rating-history.md](features/rating-history.md) | The rating-history chart's tiles, resolution, drawing, failure states, and chrome. |
| [principles.md](principles.md) | The binding rules for **design, documentation and testing**. |
| [testing.md](testing.md) | How to verify, what a test must earn, and the restart acceptance matrix. |

Related, outside this folder:

- [`../engineering-principles.md`](../engineering-principles.md) — the short binding checklist (ownership model, anti-patterns). Kept separate because `AGENTS.md` points every change at it.
- [`../rules-and-ui-architecture.md`](../rules-and-ui-architecture.md) — the engine/session/render split and the rules API.
- [`../tutorial-authoring-guide.md`](../tutorial-authoring-guide.md), [`../ui-icons.md`](../ui-icons.md), [`../shuffle-sim-model.md`](../shuffle-sim-model.md).

**Reading order for a new contributor:** principles → implementation root → the detail doc
for the area you are touching → testing. For a UI change, start at
[ui-layers.md](ui-layers.md), then follow its links.
