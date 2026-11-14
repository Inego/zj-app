# Input

Part of the [implementation](implementation.md) map. Navigation semantics are in
[stack.md](stack.md).

```
PlatformInputSource (libGDX touch/key/mouse/scroll)
   → InputNormalizer        stateless; profile → ordered InputEvents
   → resolver               GestureArbiter | HitResolver | KeyboardArbiter | ScrollRouter,
                            reading Stack + FrameModel (per-frame cached layouts)
   → AppDispatcher          Intent → one effect port
   → Router / session / account mutate; paint layouts come from a post-tick assemble
```

`InputNormalizer`'s emit order is pointer (cancel, or down/move), up/tap, scroll, keys, back.
A cancelled frame never emits up/tap, and `RawInput`'s one-shot frame samples — the
wheel's vertical and horizontal deltas — are read **once each** (the getters are pure
because the platform source captures the sample in `snapshot()`).

`HitResolver.resolve(stack, x, y)` walks top→down: the first layer with a hit region wins, an
input-owning layer with no hit consumes the point as `ResolvedHit.Outside` and stops the walk,
and non-owning layers without a hit are transparent. Coordinates are reversed through
`Layout.localPoint`, generically, with no route-id knowledge.

The same ownership rule gates **hover**, because a highlight is a preview of the hit path:
`ComponentHost.beginFrame` delivers the pointer only to the topmost input-owning layer (and to
transparent layers above it); every layer below is laid out with no pointer. A covered menu
therefore cannot highlight a row behind the card that covers it, while a press there could not
reach it either.

**`Intent`** is one sealed type grouped by effect family, not by surface: navigation/stack,
chrome, board/session, online/account, chrome/prefs, reference, confirm, and the tutorial set.
A surface's `Layout` maps its hit regions to intents via a small `ComponentAction → Intent`
table owned by the feature — no global `when` over surfaces. The **host** owns that
translation, so `ui/` never imports `input/Intent`. Continuous interactions emit through
`frame.emit(ComponentAction)` on the same table.

**Dispatcher.** `AppDispatcher` is the only connector: one branch per intent kind, each
delegating to an effects port. The seven ports are one class each —
`AppNavigationEffects`, `AppChromeEffects`, `AppPrefsEffects`, `AppSessionEffects`,
`AppTutorialEffects`, `AppOnlineEffects`, `AppAccountEffects` — each
taking only what it needs. Shared modal push/pop/remove shapes live in `RouteModals.kt`,
which is also the one declaration of the game-menu raise policy (`replaceMenuCardWith`
for a surface that takes the card's place, `raiseCardOverMenu` for a card that stands
over it). `Intent.Back` is the one context-sensitive case: `AppDispatcher.back()` resolves
it to the topmost route's `RouteActions.back` and applies that intent (see
[the stack](stack.md)).

**The gesture seam.** A widget-side `GestureSurface<V>` (`onScroll` returns whether it
scrolled, `onPointerDown` whether it claimed the press, `pressVerdict(): V?` the value a
claimed press resolved, `onPointerDrag(x, y): V?` a per-step verdict or null, `onPointerUp(): V`,
`scrollOffset(): Float?`, `cancelGesture`) and a component-side `GestureBridge<V> : GestureHost`
whose five methods are one forward each. The bridge emits a release verdict through `actionFor`
and returns `consumeFor(verdict)` (default true), so the pipeline cannot also read the same lift
as a tap unless the surface says it did not consume it; a non-null press verdict and a non-null
drag verdict emit through the same `actionFor`; a consumed scroll whose `scrollOffset()` is
non-null emits through `scrollActionFor`. A component uses it by *being* it. `onScroll` has a
**two-axis** form beside the vertical-only one (the default forwards, so the five forwards above
are unchanged); the pipeline calls the two-axis form, which is how a chart that pans on wheel X
sees the axis.

**Other input facts.**

- `GestureArbiter` owns the pointer state machine: press → slop (12 design px) → drag, a
  release verdict, and OS touch-cancel handling (abort + swallow the rest of that touch).
  It suppresses the empty-felt menu for a multi-finger press, a press that started on a
  target, and a swipe or lift past slop; a suppressed release still acts on the control it
  lands on. `AppRuntime.handleTap` dispatches `On` to the component, an `Outside` press
  inside a dismissible layer to **that layer's** outside intent, and the empty-felt tap
  to the floor's outside intent on touch profiles only.
- `KeyboardArbiter` walks top→down and descends past `Screen` and `Context` layers, so a
  context cannot swallow the board's `T`/`N`/`H`/Space/Ctrl+Space/Ctrl+R`. `Enter`
  accepts the single non-pass, non-keep, non-discard button the open panel leaves (a lone
  claim, a concealed kong beside Keep, or a self-drawn win beside discards), or deals the next
  hand (never abandons a live hand) at hand end. Space still discards the drawn tile, and now
  also declines an ordinary claim or gate (Pass or Keep); it never passes a Hu.
- Text: `ui/TextFieldHost` is implemented by the login/alias components and
  published on the route that owns keyboard; the platform source forwards keys to the topmost
  stacked `TextFieldHost`. Display name is `AliasPrompt`: a stack card
  (`StackAliasPrompt`) when the profile has a keyboard, the OS dialog
  (`NativeAliasPrompt` / `OverlayTextPrompt`) on touch — no in-app card, IME up,
  seed selected. Login fields still type in-canvas on desktop and ask the OS on a
  tap on touch.
- Scroll/drag on a card gate on the layer's own `Layout.panel` + `scrollable`.
- The wheel arrives as **one sample with two axes** (`RawInput.scrollAmountX` /
  `scrollAmount`, both on `InputEvent.Scroll`), and the two travel together down to
  the top layer that opts in with `Layout.scrollable`: `ScrollRouter` calls the
  host's **two-axis** `onWheel`, whose default is the one-axis form, so a surface
  that reads one axis is unchanged and one that pans on X and zooms on Y overrides
  the two-axis `onScroll`. The runtime's gate admits a sample when **either** axis
  is non-zero, so an X-only trackpad swipe reaches the chart; every other scrollable
  surface answers the zero-vertical sample with its unchanged offset, and
  `GestureBridge` emits the settled-offset action only when the offset actually
  changed, so that sample writes nothing.
- `RawInput.pointers` is the frame's multi-pointer snapshot — `PointerSample` ids and
  world positions, `primary`/`secondary`, iterable — populated from
  `Gdx.input.isTouched(i)`/`getX(i)` by all three backends. The pointer *events* keep
  their single-pointer shape; the whole set reaches a component on the frame itself
  (`Constraints.pointers`, in that frame's own space, empty for a layer an
  input-owning layer occludes), which is what `chart/ChartGestureRecognizer` reads
  for a pinch. `ui/` owns the value type because `Constraints` carries it.
- Parked chrome: `BoardComponent` publishes a gesture region, hands the stored places in when
  it appears, and emits the offset a moved block was left at; `ui.prefs` owns the value and the
  prefs effect is the one writer.
