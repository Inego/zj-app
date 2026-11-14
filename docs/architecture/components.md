# Components

Part of the [implementation](implementation.md) map.

Every painted surface is a `Component`: it publishes hit-test geometry and paints that same
geometry. Widgets are the resources it draws through; scopes own their lifetime.

- **`ComponentId`** (`ui/ComponentId.kt`) — a `value class` over a stable string (`"board"`,
  `"gameMenu"`). It carries no ordering; order is the stack's job.
- **`Component`** (`ui/Component.kt`) — `id`; `motion(frame)` (inertia and reconcile; may
  emit); `layout(frame): Layout` (pure, no GL, safe headless); `draw(frame, canvas)` (must
  paint the geometry `layout()` published). There is no `attach`, `onShow`/`onHide`,
  `persist`/`restore`, or `dispose` on the interface. A component reads this frame's model
  from `frame.model` and never keeps a copy.
- **`Frame`** (`ui/Frame.kt`) is the per-entry, per-phase value: `constraints`, `env`,
  `space`, `emit`, `layouts` (read-only lookup of this frame's cached layouts), and
  `model: Any`. `emit(ComponentAction)` is the upward channel; the host maps it through the
  same route-action table the tap path uses. `model` is typed `Any` so `ui/` stays free of
  `session` / `features` / `render` (`ArchitectureRatchet` `UI_PURE`).
- **`AppModel`** (`runtime/AppModel.kt`) is the composition root's immutable per-frame
  snapshot — session, tutorial, table render inputs, board render input, score-breakdown
  content — assembled after input and host ticks and put on every `Frame` of that layout.
  Presenters read it through `Frame.appModel()`. A fixture that never reads the model may
  leave `Frame.model` at its `Unit` default; a presenter that calls `appModel()` on a
  non-`AppModel` is a wiring bug.
- **`Layout`** (`ui/Layout.kt`) is the published frame model: `hitRegions`, `panel` (the
  layer's *own* bounds — all it may own the wheel/drag over), `scrollable`, `scrollY`/
  `scrollMaxY`, `gestureRegions` (park chrome, sliders), `worldToLocal`, named `regions`,
  and `space` (what a `ProvidesSpace` layer hands *down*). `ComponentAction(id, payload)` is
  what a hit region resolves to. Overlays share the same `ui/HitRegions` type
  (`Rect` → action).
- **Draw model.** `Canvas`/`DrawOp` exist and are available, but the **enforced** model is
  immediate `draw(canvas)` over the geometry `layout()` published: every component — board
  included — owns its paint code and must consume the same rects it published. Promoting a
  surface to `DrawOp`s is an optimization, not a correctness requirement.
- **Space.** A layer with `LayerSpec.content = ContentPolicy.ProvidesSpace` publishes
  `Layout.space` and implements `SpaceProvider`. Its `paintSpace` paints backdrop, each
  band of children (`SpaceChildren.paint(band)`), in-space overlays, and chrome. The
  provider knows no route names. `ContentPolicy.InSpace(band)` layers are composed into the
  nearest lower provider; `Router.pushContent` places them in that context's content group.
  The tutorial shell is the provider; lesson / quiz / coach are `InSpace`.
- **Composition root.** `AppGraph` is the only place concretes are built. Widgets are
  obtained through `scope.own`. `runtime/FeatureRegistry.register` takes a factory, a
  `LayerSpec`, and `RouteActions` — no view type. The stack holds `Component`.

A feature that needs a derived value (TOC entries, a coach park target) computes it in the
component as a function of `frame.model` / `frame.layouts`. Those functions live next to
the component (`tutorialShellView`, `boardTableInputs`, `shellReconcile`,
`tutorialCoachContent`, `tutorialQuizContent`); they are not framework types.
