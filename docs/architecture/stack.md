# The stack

Part of the [implementation](implementation.md) map.

`Route` (`ui/stack/Route.kt`) is the navigation vocabulary, and routes are typed keys rather
than enum ordinals. Construction parameters travel in `Route.args` (`Map<String,String>`).

| Kind | Meaning | Presentation |
|---|---|---|
| `Screen(id)` | **The root only.** `Screen(Root)` is the floor; `Router` never removes it. | Opaque |
| `Layer(id, args, policy?)` | A **context**: local menu/board, online hub/table, tutorial shell. | from policy, default Opaque |
| `Modal(id, args)` | Chrome card: game menu, settings, picker, confirm, score table, match history, setup, account cards. | Below |
| `Overlay(id, args)` | Chrome that is not a card: away, connect, hint button, breakdown, lesson/quiz/coach. | Below |
| `Reference(returnTo)` | The browser, with a recorded return `StackState`. | Below |
| `Tooltip(id)` | Never persisted, never modal. | Below |

`LayerSpec` (`ui/stack/LayerSpec.kt`) declares once per route kind: `role` (`LayerRole` =
Screen/Context/Modal/Overlay/Tooltip), `ownsInput`, `dismissOnBack` (the generic stack
dismissal — see below), `blocksAutomation`, `contextKind: SessionKind?`, `policy:
LayerPolicy(presentation, persists)`, `menuSurface`, `onFelt`, `content: ContentPolicy`,
`suppressesConnectCard`, `state: RouteStateCodec<*>?`, `remembersState: Boolean`.
`defaultsFor(role)` gives the
baseline (Screen/Context `Opaque`; Modal/Overlay/Tooltip `Below`; only `Tooltip`
non-persisting). Per-route declarations live in `runtime/RouteSpecs`; ids in
`runtime/RouteIds`.

**Two backs, two jobs.** The app's back is `AppDispatcher.back()`, which walks the stack
top-down and applies the first route whose `RouteActions.back(route)` returns an `Intent`
(wired through `FeatureRegistry.back`). It is an `Intent` because back means different things
per route — `HideMenu` on a menu surface, `ConfirmNo` on a confirm, `ShowMenu` on a board (a
*raise*, not a dismissal). `LayerSpec.dismissOnBack` + `Router.popDismissible()` are the
**generic stack-dismissal primitive** ("pop the topmost dismissable layer and everything
above it"); it backs the outside-tap gate and the stack tests, and it is **not** the app's
back. The two
declarations are independent and may disagree: the board and the menu surfaces answer back
while `dismissOnBack = false`, and the announced `STARTING_LEVEL` / `ONLINE_CONNECT` modals are
`dismissOnBack = true` but declare no back intent. Never read `dismissOnBack` as "does this
route use back?".

`LayerPolicy` is the one deliberate exception to "declared once": a **push site may override
it** so the same surface presents differently depending on how it was reached, and the
resolved policy is stored **on the entry** (`RouteInstance.spec`), so paint and the snapshot
read one value.

`Router` is the only thing that mutates the stack (components never hold it):

- `push` (rejects a duplicate key or a second `Screen`), `pushAbove(key, route)`,
  `pushAboveFloor`, `pushContent(route)`.
- `pop` (false at the floor), `popTo(key)`, `remove(key)` (that key and everything above),
  `removeExact(key)` (that key alone; for unowned layers such as content overlays),
  `popDismissible()` (the generic stack dismissal: pop the topmost `dismissOnBack` layer and
  everything above it; **not** the app's back — see above).
- `isOpen`, `topSpec`, `snapshot(): StackState`, `restore(state): Boolean` (false and
  untouched when the state is empty or cannot be materialized; never throws), `addObserver`.

`pushContent` is the placement rule for `ContentPolicy.InSpace` layers: insert into the
nearest lower `LayerRole.Context`'s content group — above that context's own content and any
content already announced into it, below every layer standing over it. Whether the context
provides space is a layout fact and does not decide placement.

**Everything derives from the stack:** paint is `entries` bottom→top; input walks top→down;
`Router.popDismissible()` (the generic dismissal) pops the topmost `dismissOnBack`; automation is
`stack.anyBlocksAutomation`; the menu surface is `stack.menuSurface`; persistence is every
entry whose policy persists.

`StackState` is the persisted form: a list of `RouteEntryState(routeKey, kind, id, args,
policy, componentState)` under the `stack.state` key. `componentState` is the route UI
store's text for that entry, not a value read off the component.
