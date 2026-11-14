# Testing & verification

How to verify a change, what a test must earn, and the acceptance matrix the navigation model
is measured against. Rules for the design itself: [`principles.md`](principles.md).

---

## 1. Verify (from the repo root)

```
.\gradlew.bat :core:compileKotlin :core:compileTestKotlin --console=plain
.\gradlew.bat :core:test :lwjgl3:test --console=plain
.\gradlew.bat :core:test --tests "inego.zungjunggame.arch.*" --console=plain
.\gradlew.bat :teavm:compileKotlin :android:compileDebugKotlin :android:compileDebugJavaWithJavac --console=plain
```

Current baseline: all green, every ratchet allowlist empty. Failures are readable from
`core/build/test-results/test/TEST-*.xml`.

Desktop smoke — the only way to see a restart:

```
.\gradlew.bat :lwjgl3:run -PapiBaseUrl=https://play.zungjung.org --console=plain
```

Leave the app by the **Exit row or by closing the window**. A killed IDE run never reaches the
runtime's batched flush site, so no stack, session or preference claim can be judged from it.
The exception is `auth.session`: a credential is committed when it is written, so a kill must
not cost a sign-in — that is the `taskkill /F` check in the flush policy, not a smoke step.

## 2. The architecture ratchet

`core/src/test/kotlin/.../arch/ArchitectureRatchet.kt` is **shrink-only** and currently
**all-empty** — zero accepted IoC violations across every rule. A change that adds an
entry is rejected. If a rule's subject disappears, delete the rule; never allowlist.

Enforced boundaries, checked by the ratchet and the conformance test: `ui/` and `state/` pure
(no `app`, `runtime`, `features`, `session`, `render`, `online`, `tutorial`, `platform`,
`input`, `net`); `session/` free of concrete `render`/`online`/`tutorial`/`features`/`app`
types; `render/` free of the concrete hosts and coordinators; no `features/` → `app` import;
no settable `SessionKind`; no `Hooks` interface; no `Disposable` on a `Component`; a
`ProvidesSpace` route's component implements `SpaceProvider`; `tutorial/` does not import
the feature widgets; `layout()` does not write overlay fields.

## 3. What earns a test

**A test earns its place by exercising logic or the interaction of components.** A test that
restates the implementation it covers is a second source of truth, kept by hand, and says
nothing the code does not already say — it is deleted, not added.

- **Deleted on sight:** a mirror (a getter/setter round trip, a codec round-tripping a value
  the codec owns), a constant asserted against itself, a fake that reimplements the very seam
  under test.
- **Prefer one sweep over one case per instance.** The shape to copy is the placement sweep in
  `runtime/RouteSpecsTest`: every context × every layer that can stand over it × every content
  layer, checked against the production declarations. The live-vs-restored sweep in
  `ComponentHostTest` over every persisting route is the other worked example.
- **Drive the real seam, not a fake of it.** `TutorialRoutesTest` builds its router over the
  production specs precisely because the rule under test *is* the declaration.
- **Delete the cases whose subject a change removes.** A suite does not grow to compensate for
  a design that should make the answer obvious; that is a bug in the design.
- **Localized copy is asserted structurally, never by wording** — see
  [`../rules-and-ui-architecture.md`](../rules-and-ui-architecture.md).
- **A test must never encode a workaround.** If it only passes because the
  workaround exists, remove the workaround and delete that test; the design is
  the fix, never the assertion. See
  [`../../AGENTS.md`](../../AGENTS.md) → "When a change breaks a test".

## 4. Test layers

| Layer | Where | What it must cover |
|---|---|---|
| Pure state / schema | `state/`, `persist/`, `ui/stack/StackState*Test` | round trip, migration chains, corrupt/forward-version → default, `StateSlot` semantics |
| Stack / routing | `ui/stack/RouterTest`, `RouterStackPersistenceTest` | order, the one-floor invariant, `pushContent` placement, snapshot→restore fidelity, `popDismissible()` |
| Derivation | `runtime/StackDerivationTest`, `StackPolicyTest`, `RouteSpecsTest`, `session/KindDerivationMatrixTest` | every `RouteKey` → one `ComponentId` + one `LayerSpec`; the registry is exhaustive; the placement sweep; the derived `SessionKind` across stack × host sources |
| Resolvers (headless) | `input/GestureArbiterTest`, `HitResolverTest`, `InputNormalizerTest`, `KeyboardArbiterTest`, `ScrollRouterTest` | the pointer state machine, tap vs drag vs cancel, hit walking, key ownership — against a fake platform source |
| Components | `features/**/*Component*Test` | `layout()` purity and hit regions; the action→intent mapping; persistence of player-set state |
| Effects / dispatch | `app/AppDispatcherTest` | one branch per intent kind reaches the right port |
| Conformance / arch | `arch/ArchitectureConformanceTest`, `ArchitectureRatchetTest` | the boundaries above |
| Parity with the rules engine | `ParityFixtureExportTest` | regenerates the client-authored fixtures under `fixtures/rules/`; needs `--rerun-tasks` |

**GL-dependent work** (fonts, tiles, real drawing) is not covered by the pure suite; it stays
behind the desktop build and manual checks and does not gate the pure layers.

## 5. Restart acceptance matrix

The product requirement, enumerated. Every row must hold **twice**: (a) a restart lands on the
top layer shown, with its own state; (b) popping reaches `[Root]` with no non-stack hop.

| # | State left | Restart lands on | Pop chain |
|---|---|---|---|
| 1 | Main menu | main menu | — |
| 2 | Main menu + Settings open | Settings | Settings → Main |
| 3 | Local play menu | local play menu | local menu → Main |
| 4 | Local play menu + Add game | setup card | setup → local menu → Main |
| 5 | … + Reference, ToC scrolled | Reference, same item + scroll | Reference → setup → local menu → Main |
| 6 | Local game running | board | board → local menu → Main |
| 7 | Local game + game menu open | board + menu | menu → board → local menu → Main |
| 8 | Local game + score table | board + score table | score → board → local menu → Main |
| 9 | Online hub | hub, last tab | hub → Main |
| 10 | Online table | table | table → hub → Main |
| 11 | Online table closed remotely | hub | hub → Main |
| 12 | Tutorial shell (mid-lesson) | shell, same lesson step | shell → Main |
| 13 | Online table whose seat presence never adopts | hub, offering Rejoin | hub → Main |

Rows 2, 8 and 12 are chrome-over-context cases: they pin that the entry carries its
presentation, and that back pops the chrome layer before the context.

Row 12 also pins the two-context rule: a lesson owns its own board and its own save, so
`session.freeplay` is untouched when a lesson opens, when it restores at boot, or when it
exits — the local menu's Continue picks a parked board back up afterwards.

Row 13 is the adoption deadline: after `OnlineCoordinator.ADOPTION_TIMEOUT_MS` an
`Online` context with no host is taken down, keeping `online.table`, so the hub's Rejoin
band appears. Rejoin is a hub-level band above the tab strip, not a Live-primary state,
because a Shadow Ranked table's way back has to be reachable from the Shadow Ranked tab;
it is mechanism-verified by `OnlineCoordinatorTest` and the route half by
`OnlineConnectSyncTest`; it is not yet clicked through on a device.

**Explicitly not required:** restoring an *in-flight* interaction — a drag in progress, unsaved
text in a field, the Reference's in-session ← history. Those are frames of a live gesture, not
stack state. A control the player *set* is display state and does ride the layer's own
component state.

## 6. Manual smoke checklist

Run on desktop, then Android, then web. Cold start each context; exercise back/pop to the root
from each; background/partition restore; language switch; tutorial restore mid-lesson; online
table join / reconnect / settlement. A row of the matrix is not "covered" until a real restart
has shown it, not only a unit test.

The rating-history chart has its own device pass — desktop wheel/pinch anchoring (including the
edge-stick rule at either end of the history) and fling, Android
pinch/back/state restoration, TeaVM touch/back/request-deadline behaviour, and the known
`PresenceScheduler`/gesture-end/anchoring caveats — listed step by step in the private
zj-server repository's operator runbook. Because
the chart is a full-screen surface, a pass also has to see that its plate reaches every edge on
a phone while the name, controls and axis labels stay inside the display cutout, that back and
`×` are the only exits (there is no outside to press), and that a drag is still claimed over
the plot and nowhere else.
