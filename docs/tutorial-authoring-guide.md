# Tutorial authoring (beats & slides)

Playbook for coach slides, quizzes, and live-table beats. Copy an existing sample; don’t invent structure.

**Sources of truth**

| What | Where |
| --- | --- |
| Campaign / unlocks | `TutorialCatalog` |
| Tiles & wall | `OrientationScenarios` |
| Move order | `MoveOrderScenarios` |
| Claiming | `ClaimScenarios` (units) + `ClaimScenarioStates` (their tables) |
| Hands / scoring | `HandsScenarios` (units) + `HandsScenarioStates` (their waiting hands) |
| Fixtures | `SampleScenarios` / `ScenarioStateKit` / `TutorialTestFixtures` |
| Steps | `TutorialStep` |
| Diagrams | `TutorialDiagram` |
| Body-key diagrams | `WallBuildBeats` + `SeatWindsBeats` |
| Copy | `assets/i18n/ui.properties` (EN; no new RU curriculum strings) |

---

## Mental model

```
TutorialCatalog → LessonDef → ScenarioDef(steps) → TutorialStep*
```

- **Headless FSM**: `TutorialDirector` / `TutorialRunner` walk `steps`. UI is optional.
- **User-visible slides**: only `Coach` and `Quiz`. Everything else is engine glue.
- **`Complete`**: progress marker, not a slide. Wrap-up that teaches → another `Coach`.
- Headless (`blockOnCoach = false`, default on `start`): coaches auto-ack, quizzes auto-solve; still drive `WaitChoice`. Live UI blocks on coach/quiz.

`showsBoard` is **two flags**:

| Flag | Effect |
| --- | --- |
| `ScenarioDef.showsBoard` | Draw `PlayTableView` for the whole unit |
| `Coach.showsBoard` | **Board overlay**: table under coach chrome (dim bands, coach owns the center) |

A whole-lesson table lecture needs **both**. Scenario-only `showsBoard` draws the table then covers it with felt. Mid-lesson reveal (wall → table): scenario `false`, later coaches `showsBoard = true`.

Dummy `Random(0)` layouts are runner glue — never visible content.

---

## Tests: tools only, not content

Tests cover machinery (runner, gates, quiz review, slide-nav math, progress store, session codec, wall geometry, quiz *factories* as generators). They do **not** lock curriculum (campaign order, lesson ids, body keys, step lists).

Need a board/wait/quiz path → a **probe** scenario with arbitrary ids. Do not import live lessons to assert teaching structure.

A new beat/lesson does **not** need a unit test unless you change a **tool**. There is no catalog-wide headless walk; play new content in the app.

```kotlin
val runner = TutorialRunner.start(TutorialTestFixtures.discardWaitScenario())
// Drive WaitChoice / quiz via submit*; coaches auto-ack when blockOnCoach=false.
```

---

## Add a lesson

1. Scenario builder in the chapter’s samples object.
2. Register in `TutorialCatalog` (`requires` = previous lesson id(s)).
3. Every new key in `ui.properties` (curriculum may stay EN-only).
4. End `steps` with `TutorialStep.Complete`.
5. Leave `completeSummaryKey = null` unless the unit earns a summary panel.
6. No content unit tests.

Diagram unit (board never drawn):

```kotlin
// Diagram unit — showsBoard = false, dummy Factory layout
TutorialStep.Coach(
    "tutorial.orient.….body",
    diagram = TutorialDiagram.…,
    presentation = SlidePresentation.DiagramIntro, // first beat; then Standard / FocusBeat
)
```

Live-table units: see [Live table](#live-table). Copy an existing scenario in the same chapter.

---

## Steps

| Step | Use for |
| --- | --- |
| `Coach(textKey, diagram, focus, presentation, showsBoard, layout)` | Teaching slide; overlay and/or sticky layout override |
| `Quiz(…)` | Recognition check |
| `WaitChoice(ChoiceMatch.…)` | Block until that human action (installs `ChoiceMatchGate`) |
| `Gate.kinds(…)` | Optional extra kind filter; usually skip — wait already gates |
| `ScriptChoice(Choice.…)` | Instant non-human (or forced) action; overlay chrome drops |
| `AssertState(msg) { … }` | Headless invariant; fail → lesson Failed |
| `LinkRef(pageId)` | Mandatory jump to a Reference page (headless no-op advance) |
| `Complete` | Always last |

`Coach.spotlight` is a legacy target-id-only alias; prefer `focus = TutorialFocus(...)`.

`ChoiceMatch`: `discard`, `pung`, `chow`, `kong`, `hu`, `pass`, or `kind(…)`.

---

## Live table

Host infers `TableInteract` from the step list. Authors do not set it.

| Pattern | Steps | Table |
| --- | --- | --- |
| **Lecture overlay** | `Coach(showsBoard = true)` *not* followed by a wait | Frozen engine chrome (`ReadOnly`): Hu 160 / Pung·Pass **shown**, not clickable. Center turn-status off. |
| **Action preamble** | Overlay coach, then `WaitChoice(…)` | Live table (`Play`). Click the expected action **or** Next both complete the wait. Turn-status stays. Discard: leader balloon on the tile if `calloutKey` is set; no prompt outline. Claim: Pung / Pass panel is live; `calloutKey` parks on the matching claim button (pair in hand still gets the prompt outline). |
| **Play wait** | `WaitChoice` with overlay chrome already down | Normal play. |

A live-table win (`handScore > 0`) shows the same hand-end breakdown as free play (patterns + settlement) in the table center. Tutorial omits Scores / Next hand — coach **Next** continues. Pattern info still opens Reference.

```kotlin
// Lecture overlay — not followed by WaitChoice; chrome frozen
TutorialStep.Coach("tutorial.….body", showsBoard = true)

// Action preamble — overlay, then the wait
TutorialStep.Coach(
    "tutorial.….offer",
    showsBoard = true,
    focus = TutorialFocus(
        TutorialTargets.tile(tile),
        calloutKey = "tutorial.claims.click_callout", // shared claim balloon
    ),
)
TutorialStep.WaitChoice(ChoiceMatch.pung(tile)) // discard / pung / chow / kong / hu
TutorialStep.ScriptChoice(Choice.pass()) // remaining reactors
TutorialStep.AssertState("…") { … }
TutorialStep.Coach("tutorial.….after", showsBoard = true)

// Sticky layout override from this beat onward
TutorialStep.Coach(
    "tutorial.….contrast",
    showsBoard = true,
    focus = TutorialFocus("river.last"),
    layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(otherState)),
)
```

`ScriptChoice` applies immediately. There is no “script under overlay”; a scripted Right discard sits **between** two overlay coaches (tile may still be flying when the next coach lands).

### Layouts

| Spec | When |
| --- | --- |
| Dummy `Factory { canonicalWall(Random(0)) }` | Board never drawn |
| `WallOrder` | Opening deal |
| `Layout(GameStateLayout.from(state))` | Mid-hand snapshot (pending discard, reactors already passed, …) |
| `Coach(..., layout = …)` | Sticky override from that beat onward |
| Parked wall deal | Any coach in the lesson is `WallBuild` — overlay binds the park, not the dummy layout |

Park is per lesson id (survives Back). Forgotten on a pre-roll beat, fresh start, or Restart. Leaving for a free-play game keeps the mid-lesson save (park included) so Tutorial can restore.

### Hand-built board states

`TileStock` owns dealing a fixture: it starts from a full set, `take`s scripted tiles, `takeRandom`s the rest (weighted by what remains, one `rng` value per pick), `dealConcealed`s an opponent, and `completeWall`s the live wall from the ordered draw log. `ScenarioStateKit` owns the seat names and the unknown-tile table. Chapters keep their own `build…State` and assemble their own `GameStateLayout`; don’t re-derive a stock, a wall tail, or an unknown-tile table inside a scenario.

The layout stays in the chapter because the decision phase and the pending-reaction seat are per-scenario facts — the engine requires `currentPlayer != lastDiscarder`, so which seat is marked as already reacted is a teaching choice, not a shared default.

When a chapter outgrows one file, split it **unit / board state**, not arbitrarily: a chapter’s `…Scenarios` object holds the `ScenarioDef`s the catalog registers, and a `…ScenarioStates` object holds the hand-built boards behind them. The catalog only ever references the former. Seat names (`YOU`/`RIGHT`/`OPPOSITE`/`LEFT`) live on `ScenarioStateKit`, not per chapter.

### Overlay focus

Play-engine tile **halos** (pending claim discard, parked wall-draw, hover-match on the river, winning discard / hand) still run in a live-table lesson. Overlay `focus` adds independent **outlines** on top; it does not replace those halos.

Live-table outlines are `BoardHighlight`: one or more groups of tile types in a `HighlightWhere`. Adjacent copies **in the same group** share one outline; sparse / disconnected copies each get their own. Different groups never merge.

```kotlin
// Default: `tile.N` prompts your copies of N (hand + drawn + melds). `{0}` injects N.
TutorialFocus(TutorialTargets.tile(type))

// Several types — chow companions, a whole sequence, anything mixed:
TutorialFocus(
    TutorialTargets.tile(discarded), // still `{0}`
    highlight = BoardHighlight.prompt(heldA, heldB),
)

// Anywhere face-up (rivers, opponent melds, your hand):
BoardHighlight.prompt(type, where = HighlightWhere.Open)

// Red Deny on an unclaimable river copy (not the pending discard):
BoardHighlight.deny(type, where = HighlightWhere.Rivers)

// Several groups (Deny pending discard + Prompt matching You copies):
BoardHighlight.denyPendingAndPromptYou(type)
```

`HighlightWhere`: `You` (default), `YouHand`, `YouMelds`, `PendingDiscard`, `Rivers`, `Open`.

Null `highlight` still derives from `targetId`: `tile.N` → prompt You copies of N; `wall.live` → live-wall marker plus a parked wall-draw; `river.last` → deny pending + prompt matching You copies. Pass an explicit spec when `{0}` and the outlined tiles are not the same set.

`calloutKey` parks a **leader balloon** on [targetId] (diagram tile, or live table: `tile.N`, `river.last`, `wall.live`). Independent of `highlight` — outlines and the balloon can name different tiles. Claim-wait preambles still park on the matching claim button (the control the learner clicks). Discard-wait preambles park on the prompted hand tile. Identical balloon copy shares one key (`tutorial.claims.click_callout`); do not mint a per-lesson key for the same string.

```kotlin
// Lecture: balloon on the pending discard; outlines on a different river tile.
TutorialFocus(
    targetId = "river.last",
    calloutKey = "tutorial.claims.last_discard.callout",
    highlight = BoardHighlight(
        BoardHighlight.deny(stale, where = HighlightWhere.Rivers).groups +
            BoardHighlight.prompt(stale).groups,
    ),
)
```

Other board targets: `wall.live` (live-wall marker), `wall.dead`, `seat.you`, `river.last` (pending discard), `hand`, `turn.next`.

---

## Diagrams & presentation

`diagram = TutorialDiagram.X` on `Coach` / `Quiz`. Body key selects phase for multi-beat diagrams — do not add an enum value per micro-beat. **Append** new enum entries; never reorder (mid-lesson save stores ordinals).

| Diagram | Notes |
| --- | --- |
| `None` | Text-only, or overlay (host forces `None` while the table is up) |
| `TileTypes` / `ThreeSuits` / `Honors` | Recognition |
| `SequenceExample` / `NotASequenceRun` | Sequence; peel phases follow body keys |
| `SeatWinds` | You / Right / Opposite / Left. Body key → `SeatWindsBeats` (still vs looping deal-pass) |
| `WallBuild` | Wall lesson. Body key → `WallBuildBeats` (below) |
| `RegularHand` / `SetKinds` | Winning shape / what counts as a set |
| `KongAsTriplet` | Reserved (later kong lesson) |
| `WallDead` / `SeatsTurns` / `TableOverview` | Legacy; do not use |

| `SlidePresentation` | When |
| --- | --- |
| `DiagramIntro` | **First** beat of a diagram, or a deliberate re-entrance — not every beat |
| `FocusBeat` | Pulse rim + callout pop |
| `Standard` | Default (later beats of the same diagram) |
| `None` | No motion: the beat swaps instantly (host complete/fail chrome) |

The **copy, the felt and the dock never animate**: a beat's title, body, Back /
Next row and the felt under them change with the beat, in one frame, on Next and
Back alike. Only the subject animates — diagram shell and tiles, focus rim,
callout.

`preserveContinuity` defaults **true**: same diagram keeps tiles settled; only changed channels animate.

### `WallBuild` / `SeatWinds`

One diagram, many coaches. `*.present(bodyKey)` is a pure function of the beat table + lesson state.

**Do not inventory beats here.** Order lives in the scenario + beat object + `ui.properties`.

Add a wall beat: body key → `WallBuildBeats.of` **and** `phaseFor` → `Coach(…, diagram = WallBuild)`. Reuse a flag combo for coach-only keys (must not re-ease tiles). New visuals go in `WallBuildView`. No beat-table unit test.

`DicePolicy` is the re-roll knob:

| Policy | Typical beats | Effect |
| --- | --- | --- |
| `ClearRoll` | Pre-roll (`deal`, shuffle, …) | Drop the park. Diagram mapping stays. |
| `RollOrRestore` | Dice beat | Tumble if no park; else restore pips. |
| `ShowSettled` / `KeepRollHidden` | Count / break / hands / overlay | Recapture. Overlay rematerializes from the park. |

**deal → dice** tumbles a new result (same wall map). Later beats and the overlay keep it. Back to dice restores pips; Back to deal then Next tumbles again. Do not special-case one Next; the park is the source of truth.

Wall → table handoff: `Coach(showsBoard = true, focus = TutorialFocus("wall.live"))`. Host binds the park (`WallBuildToGameState`), eases hands, collapses leftover stacks into `LiveWallMarker`.

`SeatWinds` is the same pattern (`SeatWindsBeats.of`; unknown key → `null`, not a silent still). Copy an existing `SeatWinds` scenario.

---

## Copy & i18n

- Keys: `tutorial.<area>.…`. Title = `titleKey`; body = coach `textKey`; quiz = `promptKey`.
- Rich text: `**bold**`, `*italic*`, `<br>` (soft newlines still join with a space), `[hl]…[/]` for highlighted values.
- Inline tiles: `{5s}`, `{E}`, `{C}` (`TileNotation`; suits `m`/`p`/`s`, honors `E S W N C F P`).
- Dynamic tile in copy: body contains `{0}` **and** focus is a tile → overlay injects `TileNotation.markup`. (`tutorial.orient.wall.count` is the dice special case: `{0}+{1}={2}`.)
- Avoid Han in English curriculum strings (loads CJK fallback on first paint).
- EN in `ui.properties`. Do **not** add new Russian curriculum strings while authoring.

Reference links in coach/quiz/summary copy: `[Blessing of Heaven](pattern-9.4.1)`. Also `#scoring-basics`, dotted `9.4.1`, or a relative `.md` path. Fail-closed: external URLs and unknown ids strip to plain text.

| | Inline `[label](href)` | `TutorialStep.LinkRef(pageId)` |
| --- | --- | --- |
| Fires | Learner tap (optional) | Director lands on the step (mandatory) |
| After close | Same beat | Next step |

---

## Quizzes

```kotlin
TutorialStep.Quiz(
    promptKey = "tutorial.….prompt",
    kind = QuizKind.TileSelect,          // MultiChoice | TileSelect | BinAssign
    contentFactory = QuizContentFactories.honorsHand, // or static options/correctIds
    retryAfterCorrect = true,            // review + Next / Try again
)
```

| Kind | Author |
| --- | --- |
| `MultiChoice` | options + exactly one `correctIds` |
| `TileSelect` | options (or factory) + non-empty `correctIds` |
| `BinAssign` | options + `bins` + `correctBins` |

**Static** — `options` / `correctIds` / `correctBins` at authoring time.  
**Dynamic** — `contentFactory` on enter/retry/restore (`honorsHand`, `oneSequenceHand`). Option ids must be stable and unique. Tile-select feedback is selection review (hit / FP / miss).

---

## Navigation & restore

- **Back** walks previous **Coach/Quiz** only (`TutorialSlideNav`). Same leave/land path as Next; unchanged title / diagram / board do not blink.
- Hands follow the park / authored / `Coach.layout` spec — not a dummy rebuild.
- Mid-lesson save: step index, board, parked deals by lesson id, dynamic quiz, interaction. Survives leaving for a free-play game; reopening Tutorial restores that beat.

---

## Do / don’t

| Do | Don’t |
| --- | --- |
| Copy an existing scenario in the same chapter | Invent a parallel scenario style |
| Set **both** `showsBoard` flags for a whole-lesson overlay | Scenario-only `showsBoard` (felt covers the table) |
| Lecture overlay to *show* Hu / Pung·Pass frozen | `Gate → WaitChoice` when the learner must not act yet |
| `WaitChoice(…)` right after the overlay coach for click-through (tile or Pung) | Extra `Gate` unless you need a kind filter beyond the wait |
| `Layout(fromLayout)` for a mid-hand claim offer | Treat dummy `Random(0)` as a visible hand |
| `DiagramIntro` on the first beat of a diagram; `Standard` after | `DiagramIntro` on every coach-only wall/chow/seat beat |
| Body-key tables (`WallBuildBeats`, `SeatWindsBeats`, `NotASequenceRun`) | New diagram enum per micro-beat; reorder `TutorialDiagram` |
| `{5s}` / `{0}` + tile focus for a named live tile | Han in English curriculum copy |
| Test tools with `TutorialTestFixtures` | Assert campaign order, body keys, or live step lists |
| Ship lesson copy in EN `ui.properties` | New RU curriculum strings while authoring |
