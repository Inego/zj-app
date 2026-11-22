# Implementation

What the client's `core/` is built on today. This page is the **high-level map**: the app's shape
and one-way dependency direction, plus the index of the dedicated docs that own each
mechanism. The binding rules are [`principles.md`](principles.md); verification is
[`testing.md`](testing.md).

---

## Shape of the app

```
app/            composition root + frame loop + effect ports (thin)
  AppGraph          builds every component and port; the only place concretes are constructed
  AppRuntime        frame loop: motion, input, host ticks, assemble, layout, paint
  AppDispatcher     the only connector: one branch per Intent kind → one effect port
  App*Effects       eight classes, one per effect family; they mutate the model and own
                    persistence policy
  ScreenTransitions the navigation vocabulary over Router (pushLayer/popToRoot and the
                    card-raise shapes: replaceMenuCardWith / raiseCardOverMenu)

ui/             component framework (libGDX-aware, feature-agnostic, pure)
  Component, ComponentId, Frame, Layout, HitRegions, Rect, Constraints, Canvas,
  DrawOp, GestureHost, GestureSurface, GestureBridge, TextFieldHost, ModalVerdict,
  SpaceProvider, Scope, RouteUiState, StatePort, RouteUiActions, UiEnvironment,
  PointerSample, FramePointers
ui/stack/       Route, RouteKey, RouteInstance, LayerSpec, LayerPolicy, ContentPolicy,
                SessionKind, Stack, Router, StackState, StackStateSchema, RouteCodec

state/          StateStore, DefaultStateStore, StateKey, Schema, StateSlot, SecretBox,
                the backends (Preferences / File / InMemory) and RecordCodec
persist/        the value types + schemas: SessionSnapshot, TutorialSessionSnapshot,
                GameConfig, UiPreferences, AppLocale, FindGamePreferences, OnlineTable,
                ReferenceNav, AuthState

input/          InputProfile, RawInput, InputNormalizer, FrameModel, HitResolver,
                GestureArbiter, KeyboardArbiter, ScrollRouter, Intent, IntentSink,
                Dispatcher, TouchDiagnostics

chart/          the rating chart's pure engine: ChartViewport (Double ms offsets from a
                local origin, pan/zoom/clamp), ChartGesture (the move vocabulary),
                ChartGestureRecognizer (device frames → pan/zoom, pinch, fling, wheel).
                No route, no GL, no clock; the feature owns how it is attached.

features/       one package per surface family: board, menu, local, tutorial, online,
                reference, settings, auth, score, history, confirm, away
session/        engine adapters: GameSession, PlayHost/LiveHand snapshots,
                SessionRegistry, SessionKindPolicy, hosts, and the one
                presentation pipeline both modes drive: board/LocalEventEmitter,
                online/OnlineStateMapper, online/PresentationSequencer
tutorial/       TutorialHost, TutorialRunner, director, progress — snapshot in, routes out
runtime/        AppModel, RouteIds, RouteSpecs, FeatureRegistry, feature registrations
render/         fonts, tiles, board/layout primitives, richtext, wall/seat-wind animations,
                render/ui widgets. NO screen.
platform/       SafeArea (cutout + navigation-bar + caption-bar pixels, and how
                deeply a cutout reaches the top-right corner), OverlayTextPrompt,
                clipboard. Filled in `AndroidLauncher`, whose window policy hides
                only the status bar and reserves the navigation bar.
engine/ engines/ hints/ shuffle/ net/   pure rules, policy, network — untouched by the UI model
```

Dependency direction is one-way: `app → features → ui + state + session + chart → ui` (`chart`
is a pure leaf beside `ui`; a feature attaches it). Nothing in
`ui`, `state`, `session`, `chart` or `features` imports `app`. `Main` is a ~226-line boot shell that
names no screen and imports only `AppGraph`/`AppRuntime`.

---

## Where the details live

Each detail doc is canonical for its mechanism; this page does not restate them.

| Doc | Answers |
|---|---|
| [components.md](components.md) | The component contract: `Component`, `Frame`, `Layout`, space, context and the composition root. |
| [widgets-and-state.md](widgets-and-state.md) | Widgets, scopes, ownership by construction, the three kinds of UI state, hosts and snapshots. |
| [text-in-space.md](text-in-space.md) | Text measurement, fit/cut/wrap/scroll moves, column budgets and bounds, script and input limits, and the prioritized non-uniform surfaces. |
| [frame-loop.md](frame-loop.md) | The per-frame order: assemble → motion → layout → input → host ticks → paint → flush. |
| [stack.md](stack.md) | Routes, `LayerSpec`, `Router`, back vs generic dismissal, `StackState`. |
| [state-and-persistence.md](state-and-persistence.md) | `StateStore`/`StateKey`/schemas, backends, flush policy, keys, route UI store, cold start. |
| [input.md](input.md) | Normalization, hit/gesture/keyboard/scroll resolution, `Intent`, `AppDispatcher`, the gesture seam. |
| [sessions.md](sessions.md) | Feature snapshots and ports, hosts, `SessionRegistry`, request ordering, feature boundaries. |
| [features/online.md](features/online.md) | The Online hub, live table, account surfaces, and the connect card. |
| [features/rating-history.md](features/rating-history.md) | The rating-history chart: tiles, resolution, drawing, failure states, chrome. |

Rules, orientation, and verification:

- [principles.md](principles.md) — the binding rules for design, documentation, and testing.
- [testing.md](testing.md) — how to verify, what a test must earn, the restart matrix, and the
  architecture ratchet.
- [ui-layers.md](ui-layers.md) — the UI gateway: stack, persistence, and state ownership.
- [../engineering-principles.md](../engineering-principles.md) — the short binding checklist.
- [../rules-and-ui-architecture.md](../rules-and-ui-architecture.md) — the engine/session/render
  split and the rules API.

The architecture ratchet is shrink-only and all-empty; see
[testing.md §2](testing.md#2-the-architecture-ratchet).
