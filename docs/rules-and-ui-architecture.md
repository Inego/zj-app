# Game rules and UI architecture

How Zung Jung play rules live in the client, how they are enforced, and how the table UI uses them.

This is the **client** architecture: one local table, human vs bots (and tutorial). Official pattern text lives under `docs/rules/official/`; this file is about **code**.

## Split of responsibility

Rules and presentation are stacked. Only the engine decides what is legal.

```
engine/          one-hand rules: state, legal moves, scoring, settlement
session/         apply path, multi-hand game, persist
engines/         policy among already-legal choices (bots, hints)
features/ + render/ + ui/   components, table, widgets, animation
tutorial/        optional extra filter on the human’s legal list
runtime/         route specs, component registry, feature deps
app/             composition root, dispatcher, effects
```

| Layer | Owns | Does not own |
|---|---|---|
| `GameState` | Wall, hands, melds, rivers, whose turn, legal `Choice`s, Hu score, terminal | Widgets, animation, “should I pung?” |
| `Scoring` / `ZjSettlement` | Pattern points; who pays whom | Whether Hu is offered (that is enumerate) |
| `GameConfig` / `GameSession` | Rounds, patternless-hand flag, East rotation, score table | Tile motion |
| `BoardController` | The only commit path: enumerate → optional gate → `applyChoice` | Drawing, narration |
| `LocalEventEmitter` | What an applied choice *was*: the transitions, in the server's own vocabulary | Deciding legality, cues |
| `PlayingEngine` (`DefaultEngine`) | Pick an index into a **legal** list | Inventing moves |
| `BoardComponent` / `BoardFeature` | Publish `Layout` (hit / gesture regions); map `ComponentAction` → `Intent` (`BoardController` commits) | Changing legality |
| `PlayTableView` / `ClaimActionPanel` | Draw the current list (widgets only) | Input mapping, legality |
| Tutorial `ChoiceGate` | Subset of legal moves the lesson allows | A second rules engine |
| `runtime/` | Route specs (`LayerSpec` per route), the component registry (`ComponentId` → factory), feature deps | Business rules |
| `app/` | Composition root (`AppGraph`), the dispatcher (`Intent` → effect port), the effects that apply intents and own persistence policy | Rendering, UI state |

Headless self-play (`GameplayLoop`) uses the same enumerate → pick → apply loop with no UI.

## Source of truth: one hand

A **hand** is a `GameState`. A **game** is a `GameConfig` plus a `SessionScoreTable` of finished hands (`GameSession`).

`GameState` is a mutable board:

- 136-tile wall, 14 dead, live remainder (`liveWallSize`)
- Four `PlayerState`s: concealed counts, the ordered exposure log (meld kind/value; for chows the claimed tile and discarder), plus the keyed rules arrays (open chows, pung/kong flags, who each set was called from)
- Discard rivers (finalized only; a pending discard is **not** in the river yet)
- Phase: `DecisionType.ActOnHand` or `ReactToDiscard`
- `currentPlayer` — whose decision is collected **now**
- `handScore` — `0` until Hu or exhaustive draw (`liveWallSize <= 0`)
- `allowChickenHands` — copied from `GameConfig` at deal; fixed for the hand

Tile types are `0..33` (man / pin / sou / winds / dragons). Counts, not ordered lists: the engine does not care about hand sort order. `lastDrawnTile` / `lastDrawnPlayer` exist so the UI can park a just-drawn tile on the right until discard; they are not a second rules model.

Deal: shuffle a canonical 4-of-each wall, deal 13 each, extra tile to East, East acts first. Scripted deals use `fromWallOrder` / `fromLayout` (tutorials, tests). Free-play restore hydrates the same fields without re-validating the multiset.

## The rules API: enumerate, then apply

Nothing in the UI (or a bot) is allowed to mutate the board except through this:

1. `state.enumerateChoices()` — every legal `Choice` for `currentPlayer`
2. Pick one of those objects (human click, engine index, tutorial match)
3. `state.applyChoice(choice)`

`applyChoice` assumes the choice is currently legal. It checks a few invariants (`check`) and then mutates. It does **not** re-run enumerate. Callers that skip the list can corrupt the hand.

`BoardController` is the UI/bot choke point:

```
legal = enumerateChoices()
allowed = gate.filter(legal)     // free play: identity
commit only if requested kind+payload is in allowed
always apply the engine Choice instance, not a caller-built copy
```

Hu is scored **at enumerate time**. A `Choice.hu(score)` carries the pattern total in `payload`. Applying Hu only writes `handScore = payload`. Wrong scoring at enumerate would ship the wrong win.

### What enumerate offers

**ActOnHand** (14-tile stint: dealer open, wall draw, or after a claim):

- Self-draw Hu only if this seat just drew (or dealer’s opening 14). After chow/pung, Hu is **not** offered even if the shape is complete — they declined Hu on the discard.
- Sequential concealed-kong gate: if four concealed of a type remain undeclared and not `Keep`’d this stint, the **only** choices are `ConcealedKong` and `Keep` for the lowest such type. Discards wait.
- Otherwise: one `Discard` per concealed type, plus `PromoteKong` when holding the fourth of an open pung and the live wall can supply a replacement.

**ReactToDiscard** (everyone except the discarder, one seat at a time):

- Always `Pass`
- Hu if claiming the discard (or robbed kong tile) completes a scoring hand. A 9.x incidental (win on kong, rob, final draw/discard, blessing) is a scoring pattern: it is enough to Hu even when patternless hands are off, and it is not stacked on the 1-point patternless residual.
- Pung / Kong from concealed copies (Kong needs a live replacement tile) — **not** during a kong-rob window
- Chow only for the next seat in turn order, suited tiles only, one `Choice` per legal 3-tile shape — **not** during a kong-rob window

**Promote kong / rob window:** `PromoteKong` peels the 4th tile off the hand (pung stays a pung) and opens `kongRobWindow` (`ReactToDiscard`, Hu/Pass only) if any other seat can Hu on that tile. If nobody can, the window is skipped and the kong completes immediately. All-pass upgrades the pung to an open kong and draws the supplement for the promoter. Hu is a rob: treated as win-on-discard for payoff; promoter keeps the pung. Concealed kong and big exposed kong (claiming the 4th from a discard) cannot be robbed.

Pass-only reactions are a first-class legal list of size 1. The UI treats them as **not** a human decision (see below).

`Keep` is a real engine kind, not a UI fake: it marks that four-of-a-kind declined for this ActOnHand stint so enumerate can move on.

### Applying a discard reaction

A discard does not resolve immediately. `applyChoice` on `ReactToDiscard` **records** that seat’s bid in `pendingReactions`, then advances `currentPlayer` to the next seat that has not bid.

When all three have bid, one winner is chosen by rank:

| Rank | Kind |
|---:|---|
| 3 | Hu |
| 2 | Pung, Kong |
| 1 | Chow |
| 0 | Pass |

Equal rank: earlier in seating order after the discarder (strict `>`). Then pending bids are cleared.

- All pass → tile goes on the discarder’s river; next seat draws; `ActOnHand`. **Kong-rob window:** all-pass completes the promoted kong and the **promoter** draws the supplement (`lastDrawWasKongSupplement`)
- Hu → `handScore` set; hand over (winner is `currentPlayer`). Rob keeps `kongRobWindow` so scoring sees 9.3
- Pung / Chow → meld built from concealed + claimed tile; `ActOnHand` **without** a wall draw (must discard)
- Kong (from discard) → open kong + replacement draw (`lastDrawWasKongSupplement`); `ActOnHand`

There is no simultaneous-bid protocol. Collection is sequential around the table. Bots therefore fill their reactions in the same apply loop as the human, just on other seats.

Undo (`applyChoiceWithUndo`) exists for Monte Carlo playouts, not for the player.

## Scoring and settlement (after a legal Hu)

`Scoring.computeHandScoreDetails` is Zung Jung additive scoring:

- Fast paths: Thirteen Terminals, Nine Gates, Seven Pairs
- Regular: every concealed decomposition (`HuCheck`); **freedom of count** = take the best total
- Compound cap 320; a single over-cap pattern replaces the additive sum
- Patternless hand (complete shape, 0 pattern points) → 1 point only if `allowChickenHands`; otherwise the hand is a false win (no Hu)
- Then table-context bonuses on the live `GameState`: Final Draw / Final Discard, Win on Kong, Robbing a Kong, Blessing of Heaven / Earth. Win on Kong stacks with Final Draw when the supplement is the seabed. Rob is not Final Discard and is not Blessing of Earth.

`PatternIds` are stable reference-page ids (and L10n keys), never English labels. The hand-end UI deep-links each line with those ids.

`ZjSettlement.computeDeltas` is payment, not patterns:

- Winner nets `3 × handValue` (each of the other three pays `handValue`)
- Exception, **win on discard only**, `handValue > 25`: non-discarders pay 25 each; discarder pays the rest
- Self-draw: no cap, full share from all three
- Exhaustive draw: all zeros (`handScore` stays 0)

`GameState.computeScoreDeltas()` uses `currentPlayer` as winner and, on `ReactToDiscard`, `lastDiscarder` as discarder. `SessionScoreTable.recordTerminal` appends one row and running totals by **absolute seat** `0..3`.

### Game-level rules (`GameConfig`)

House knobs for a whole game, not a hand:

- `rounds`: 1 / 2 / 4 (default) or unlimited (`null`). Four hands per round; East rotates every hand
- **Allow patternless hands** (`allowChickenHands`, New Game checkbox, **off by default**): when off, a patternless regular win cannot declare Hu. That is the official **5-point minimum** variant — a patternless hand is a false win, and with no flowers every numbered pattern in this engine is at least 5, so there is no separate numeric floor. When on, it scores 1 (standard Zung Jung, no minimum).

Finite game ends when `scores.handCount` reaches `rounds * 4`. Next-hand / New Game is session + UI, not `GameState`.

### Display preferences (`UiPreferences`) — deliberately not `GameConfig`

`persist/UiPreferences` (the versioned `UiPreferencesKey` on the `zj-state`
preferences container) holds choices about how the app *looks*, kept out of
`GameConfig` on purpose: rules are per game and settable only before a deal,
while a display aid has to apply to every board — online and tutorial included —
with no game in progress.

- **Show tile labels** (`showTileLabels`, Settings panel off the root menu,
  **off by default**): draws a small tag on every face-up tile — the digit for
  number tiles, a localized tag for honors. The tag **text** lives only in the
  bundle (`tilelabel.*` in `assets/i18n/ui.properties` / `ui_ru.properties`,
  which is also where the deliberate Запад/Зелёный `З` sharing is explained);
  `render/TileLabels.kt` owns the key order, the digit mapping and the
  preference gate, and no document repeats the values.
  The render path reads the process-global `render/TileLabels.enabled`, which
  `AppGraph` seeds from the restored `AppState` at boot and `AppPrefsEffects`
  mirrors on toggle. Tags are drawn on the play surface (`TileBoard`), the
  claim-choice tiles, the tutorial diagrams, and the orientation quiz — all
  following the setting like the board does, so turning the aid on shows the
  same tags everywhere. The tag lesson's forced on/off rows are the deliberate
  exception. Reference figures and the touch magnifier stay untagged: they are
  already enlarged.

## How the table UI uses the engine

The live board is a `LiveHand` (`GameSession` or the tutorial runner), reached from
the board component through the session snapshot. Widgets never keep a parallel
rules model.

### Human decisions

`PlayTableView` asks the engine what the human may do **this frame**:

| Predicate | Meaning |
|---|---|
| `isHumanDiscardTurn` | Not terminal, `ActOnHand`, `currentPlayer == humanSeat` |
| `isHumanClaimTurn` | `ReactToDiscard` on the human seat **and** the list is not pass-only |
| `isHumanInteractiveTurn` | Either of the above — AI stepper must wait |
| `hasActOnHandSpecials` | List includes Hu / ConcealedKong / PromoteKong / Keep |

Input:

- Click a hand tile → the board component emits its discard action → `ComponentAction` → `Intent` → `BoardController.tryApplyDiscard` (tutorial: runner + gate)
- Click a claim / special button → the board component resolves the press → `ComponentAction` → `Intent` → `BoardController.tryApply` with that `Choice` (tutorial: the `TutorialHost` gate)
- Keyboard → `KeyboardArbiter`. `Enter` accepts the panel's single non-pass, non-keep, non-discard button (a lone claim, a concealed kong beside Keep, or a self-drawn win beside discards) and deals the next hand at hand end; `Space` declines an ordinary claim or gate with Pass or Keep (never one offering Hu) and still discards the drawn tile.

`ClaimActionPanel` is built from the current `ChoiceList`. Each legal chow is its own button with a 3-tile preview. Discards are **not** buttons; they are tile hits. During the concealed-kong gate, enumerate returns only Kong/Keep, so the hand is not a discard UI.

Pass-only reactions: `isHumanClaimTurn` is false, so the human never sees a Pass button. `NonHumanTurnStepper` applies the trivial pass (including for the human seat) once animations are idle. That is why the game “only waits for your real choices.”

`TableInteract.ReadOnly` still **draws** the same panel the engine would (tutorial lectures) but drops hits. Leaving overlay must restore `Play` or the next live hand is frozen.

### Animation is not rules

`NonHumanTurnStepper` waits until `PlayTableView.isAnimating()` is false, then applies:

- `ActOnHand` (bot): one choice, then wait for discard/draw motion
- `ReactToDiscard`: apply each timed seat as its simulated reaction time comes due (pending bids have no visuals) until a claim resolves, someone draws, or the human must bid

A reaction window opens by asking whether any seat can take the tile. If none
can, the table spends one dwell — the slowest of the opponents' checks — before
play moves on. If someone can, each seat that can take it samples its own think
time, the seats that cannot pass at once, and the window resolves when the
slowest claimer is in. Checks and think times start when the window opens
(locally, once the discard has settled), not when the serial walk reaches a
seat, so bots think while the human does, as they do online; local human seats
remain untimed.

`pauseUntilNextIdle` skips one frame after an apply so the table can start easing.

### Bots and hints

`DefaultEngine` is policy. It receives an already-legal `ChoiceList` and returns an index (`rank` = all co-best, no RNG; `select` RNG-breaks ties). HINT highlights `rank`; on desktop UCB flat MC playouts refine it to the best average score delta (avgΔ / n labels), and a second press plays a unique co-best. Ctrl+Space AI assist calls the same picker on the human seat.

MC playouts need a preemptive worker thread, so they are desktop only (`platform.McHints`: off on Android — the worker competes with the GL thread and the search is slow there — and off on WasmGC, where `Thread.start` is a cooperative fiber that a playout loop would never yield, freezing the tab). Everywhere else HINT is `rank` only.

Bots do not bypass rules. They cannot pung if enumerate did not offer Pung.

### Scores on screen

On a terminal board — a win or an exhaustive draw — `FreePlayHost` asks `Scoring.computeHandScoreDetails(state)` and `computeScoreDeltas()` and feeds `ScoreBreakdownOverlay` (headline, pattern lines, per-seat payments). The score table is `SessionScoreTable` history. An exhaustive draw is the zero case of the same card: the headline is the draw verdict, there are no pattern rows, and the settlement shows every seat's unchanged running total. The board no longer draws a separate draw banner/button row; the card owns the whole hand-end summary (win or draw), and the center banner is suppressed at any terminal hand.

The card's **headline is always the hand's verdict** — who won *this* hand (or the draw). The finished match's result is a separate line the card can carry (`gameResult`, fed by `OnlineSettlementPresenter` from `MatchHistoryFormat.placementText`); it is not a second headline, because on the last hand of a match the two are different facts and the hand's winner would otherwise be nowhere on screen. A place shared by several seats names how many share it, since `placement` (client and server alike) gives tied seats the best place and skips the rest.

When the card also carries **rating deltas**, the place is that table's caption, and nothing else. `RatingChangesTable` paints it directly above its own rows; the hand table (winner, pattern rows, total) does not contain it. The caption is the place alone — not `place · Rating changes`. `ScoreBreakdownLayout.resolveResult` is the one decision (`Result.slot`): the body height counts a place row only for `ResultSlot.Body` (a finished game with no rating table), and the painter draws it only in that same case. `ResultSlot.RatingCaption` hands the place to the rating table, which spends the caption row the rating block reserved.

### Movable chrome

Three blocks are **parkable** — pressed and dragged out of the way on a small screen: the hand-end score card, the board's HINT button, and the claim/action row. They share one tool: `ParkableChrome` (the contract), `ParkDrag` (press → slop → park, and the single clamp in `ParkGeometry.place`), and `ParkChromeGroup` (which of the board's blocks owns a press — first in tap order). The owning component publishes the blocks as `Layout.gestureRegions` and is the `GestureHost` for them, so a release the block does not consume falls back to an ordinary tap — tapping a claim or HINT still acts. Parking never changes what a control *does* — only where it is. The place is the player's, so each block's committed offset is model state (`ui.prefs`, beside the score card's): the board hands the stored places in when it appears and reports the offset a moved block was left at; the widget keeps only the in-flight drag.

New Game setup writes `GameConfig`; Cancel keeps an in-memory draft. Start picks a random dealer (`nextEastSeat`), so the human's first wind is random; East then rotates one seat per hand.

### Persist

Free-play snapshots the live `GameState` plus score rows, `nextEastSeat`, and config
(`SessionSnapshotSchema`, the `session.freeplay` key on the `zj-files` container).
That is a dump of engine fields, not a move log. Tutorial has its own key
(`session.tutorial`). The two contexts keep **separate boards and separate
saves**: opening the tutorial leaves a parked local game's board and
`session.freeplay` key untouched, and the lesson save is kept when a local game
starts.

## Tutorial: same engine, tighter gate

Lessons build or hydrate a `GameState`, then install a `ChoiceGate` so wrong gestures never reach `applyChoice`. Computer seats keep an identity gate (or a scripted `NonHumanPicker`). The director matches the human’s apply against the expected `Choice`; it does not reimplement pung/chow/Hu.

`GameplayLoop` / MC hints copy `GameState` and playout with the same apply function.

## Presentation: one pipeline, two producers

A match decouples immediate authoritative state from client visual pacing, and
both modes pace the same queue:

```text
server  SeatSnapshotWire ──(OnlineStateMapper)──┐
                                              ├──> PresentationSequencer ──> SoundCues ──> SoundPlayer
local    GameState ──(LocalEventEmitter)───────┘
```

- **Authoritative model (`OnlinePlayHost`):** Updates immediately so human choices validate against true server state.
- **`PresentationFrame`** is the transport-neutral input the sequencer paces: the projected `TableViewState`, the hand number, and the transitions this update announces, in order. `OnlineStateMapper.toPresentationFrame` builds one from a wire snapshot (with fallback diffing against the previous snapshot when the server sent no events); `LocalEventEmitter` builds one from the apply it just committed.
- **Transitions** (`session/online/PresentationSequencer.kt`): `Draw`, `Discard`, `Claim`, `DeclareKong`, `ReactionHu`, `HandEnd`, `SnapshotSync`. One transition per *moment*: a Hu resolved off a discard is a `ReactionHu` and a later `HandEnd`, which the queue spaces into two.
- **`ClaimBidder`** (seat + bid kind) is the participant list a resolved window carries. Both producers build it, so the stake rule has one input.
- **Pacing & latching:** Paces moves at 0.3s (accelerating to 0.08s/0.04s under backlog). Decision prompts latch to the UI only once preceding moves finish displaying.
- **Fast-forward:** Flushes immediately if remaining decision time $\le 2.5$s, on user submission, or on hand boundaries.
- **Discard origin & privacy:** Discard submissions include `(slotIndex, isDrawn)`. The server relays this origin in `PresentationEvent.Discard`. Observer clients pluck the exact face-down tile from the opponent's rack without leaking concealed tile identities.
- **Hand vacancy reconciliation (`TileBoard`):** Phase 0 claims the preferred discard visual before type matching. Phase 3c matches remaining visual hand tiles to remaining desired hand slots for that seat, ensuring adjacent tiles slide cleanly rather than spawning missing tiles from the wall.
- **Win source:** a snapshot's `win_source` is authoritative and already distinguishes the two hand ends — the server fills it from `last_discarder` only while the hand is a pending discard (`protocol.rs`) and leaves it null for a self-draw. `last_discarder` on its own is *stale* (after a self-draw it still names whoever discarded before the winner drew), so a client that prefers it titles the hand "wins on discard from …" and then renders a board with no discard to point at — no connector curve, no orange tile glow, and the winner's 14th tile parked in the drawn slot. Only a hand whose snapshot says `is_pending_discard` may borrow `last_discarder`.

### Contested claims: the viewer's own stake

Both producers report the window's participants, and one rule reads them.
`ClaimBidder(seat, kind)` rides `claim` and `reaction_hu` on the wire: the server
builds it from the bids that resolved the window (rank >= Chow, losers included)
and `LocalEventEmitter` builds it from the engine's own `pendingReactions` plus
the applied choice. `SoundCues.stakeOf(winnerSeat, bidders, mySeat)` then answers
`ClaimStake.Stolen` / `ClaimStake.Thief` / `ClaimStake.None` from that list alone.

No verdict is precomputed per recipient and no cue concept crosses the wire. The
*backend* states what happened at the table; the *client* decides what it sounds
like — the same rule for a server event and for a local apply, which is what stops
free play and online from answering "what just happened" differently.

- **A lone bid is not a contest.** `stakeOf` returns `None` below two bidders, so
  an uncontested window is silent and an absent `bidders` reads as a lone bid.
  Never infer the contest from a count of anything else, from what the viewer was
  prompted with, or from decision state.
- **The sting rides the resolved moment.** A meld claim carries it ahead of the
  claim sample (`SoundCue.Claim`); a Hu window has no claim sample, so it is a
  standalone `SoundCue.Sting` and the win band arrives later, with the `HandEnd`
  that settles the hand.
- **`hand_end` is settlement only.** A Hu off a discard is a window outcome
  (`reaction_hu`) *and* a hand end, and they are separate moments precisely so
  the doubled win cue cannot happen.
- **Pass is not a bid.** Only reaction bids of rank >= Chow appear in `bidders`.

Privacy. Claims are called aloud, so a participant list on a *resolved* window is
table state, not a leak: the snapshot filter still withholds concealed tiles, an
unplayed hand, and every bid while its window is open. A client cannot correlate
a resolution with the prompt it answered (there is no window id), and the decision
that advanced past the window does not label it — which is why the resolved event
has to carry the list at all.

Two producer divergences are known and load the parity fixtures: local play has
no settlement step, so one apply yields both the window outcome and the hand end
(the queue spaces them), and an exhaustive draw is announced by settlement alone
on both sides (the client's snapshot diff no longer synthesises a second one).

## Canonical state digest and cross-language fixtures

The same games are replayed by the Rust server's `zj-rules` engine in the private
`zj-server` work for the replay corpus, so this engine and that one are kept in
lockstep by a fixture corpus under `fixtures/rules/`. Kotlin is the behavioural
reference: only the client authors fixtures, and the server mirrors them.

| Fixture | Author | Contents |
|---|---|---|
| `game_traces.json` | client | Full decision scripts and expected outcomes. |
| `hu_patterns.json` | client | Pattern scoring cases. |
| `reaction_resolution.json` | client | Claim-window resolution cases. |
| `state_canon_vectors.json` | **server** | Recorded games with their canonical state bytes and digests. |

The fourth is the exception and the reason this section exists. A replay digest is computed
over `STATE_CANON` — a documented, language-neutral byte layout of a `GameState`, specified
in the private `zj-server` work and implemented by its `zj-rules` crate. Two states with the
same canonical bytes are the same position, which is what makes
"the rules engine changed" a hard failure instead of a silent corpus corruption.

Consequences for changes here:

- **The digest follows this `GameState`.** `STATE_CANON` mirrors these fields one for one —
  the wall and the pending-reaction pads excepted. Adding a state field that the server must
  mirror means extending the layout and bumping `canon_version` in the server repo; the
  vectors are regenerated there, never here.
- **Regenerating the three client-authored fixtures** is `ParityFixtureExportTest`, and it
  needs `--rerun-tasks`: an UP-TO-DATE `:core:test` leaves them stale, and the server's copy
  is only checked against the committed files, so stale output passes silently.
- **No Kotlin implementation of the digest exists yet** (a server-side stretch goal),
  so this repo consumes `state_canon_vectors.json` as a mirror only — nothing in `:core:test`
  validates it.

Frozen by the layout, and therefore not free to change casually: tile type ids `0..33`
(man / pin / sou / winds / dragons), chow type ids `0..20`, counts rather than ordered lists,
`-1` as the "none" sentinel for signed fields, and a 34-bit `declinedConcealedKong` bitmap.

## Intentional engine gaps (relative to official text)

Useful when reading `docs/rules/official/` against this codebase:

- No flowers
- No prevailing wind (seat wind + dragons only)
- Dead wall is reserved size only; no dora-from-dead-wall
- Reaction resolution is sequential collection + rank, not a wall-clock simultaneous call

## Testing localized copy: assert structure, never wording

`assets/i18n/ui.properties` (en) and `ui_ru.properties` (ru) are the **only** place UI copy lives,
and editing them is a translation job, not a code change. So no test asserts *what* a string says.
What is asserted instead:

| Guard | Where | Fails when |
|---|---|---|
| every locale declares every key | `BundleCoverageTest.everyShippedLocaleDeclaresEveryKey` | a key is added to one language only, so the others silently show English |
| no blank or key-echoing value | `BundleCoverageTest.noValueIsBlankOrEchoesItsKey` | a value is emptied, or a key is renamed on one side |
| templates keep their `{0}` placeholders | `BundleCoverageTest.templatesKeepTheirPlaceholdersInEveryLocale` | a translation drops a placeholder and loses the number it showed |
| the bundle is reachable from the picker | `BundleCoverageTest.everyShippedBundleIsReachableFromThePicker` | a `ui_<tag>.properties` ships that no `AppLocale` maps to |
| `L10n` serves a locale from **its own** file | `TestBundle.assertServedFromOwnFile`, used throughout `L10nTest` | a locale falls back to English, or the wrong bundle is loaded |

Feature tests then assert **composition** — the same key the production code reads, with the same
arguments, in the same order:

```kotlin
assertEquals(L10n.format("status.hand_of", 1, 16), FreePlayHost.formatHandStatusLabel(…))
assertEquals(L10n.get("seat.right"), PlayerLabels.nameForAbsoluteSeat(1, humanSeat = 0))
```

That still fails on the wrong key, wrong arguments or wrong template — the code's actual job — and
never on a reworded translation. (`test/…/i18n/TestBundle.kt` holds the helpers; use them instead of
re-deriving any of the above.)

How this was established, and why it is worth remembering: prefixing **every** bundle value with
`Zz` once turned **27 tests** red across 9 classes, and every one of them was asserting copy. So if
a test fails when only a `.properties` file changed, that test is the bug. Fix it by comparing
against the key, or by moving the invariant into the table above — never by pasting the new wording
into the assertion.

## File map

| Path | Role |
|---|---|
| `engine/GameState.kt` | Hand state, enumerate, apply, deal, terminal |
| `engine/Choice.kt` | `DecisionType`, `ChoiceKind`, `Choice` |
| `engine/PlayerState.kt` | Concealed + open meld encoding |
| `engine/HuCheck.kt` | Shape decomposition |
| `engine/Scoring.kt` / `ScoringDetail.kt` | Pattern points |
| `engine/ZjSettlement.kt` | Payments |
| `engine/GameConfig.kt` | Game-level knobs |
| `engine/SessionScoreTable.kt` | Multi-hand totals |
| `engine/GameplayLoop.kt` | Headless four-engine game |
| `session/board/BoardController.kt` | Enumerate / gate / apply |
| `session/board/LocalEventEmitter.kt` | Narrate a local apply as the transitions the server would emit |
| `session/GameSession.kt` | Deal, rotate East, record, persist |
| `session/board/NonHumanTurnStepper.kt` | Pace bots around animation |
| `session/online/OnlinePlayHost.kt` | WebSocket client host for online tables |
| `session/online/OnlineStateMapper.kt` | Wire snapshot → `TableViewState` / `PresentationFrame` |
| `session/online/PresentationSequencer.kt` | The one transition queue, pacing, and prompt latching both modes drive |
| `engines/DefaultEngine.kt` | Table AI / hint policy |
| `render/PlayTableView.kt` | Board from `GameState`; human-turn predicates |
| `render/ClaimActionPanel.kt` | Buttons from `ChoiceList` |
| `features/score/ScoreBreakdownOverlay.kt` | Pattern + settlement card |
| `render/ParkableChrome.kt` | Movable-block contract (`ParkRelease`) for the board's HINT button / claim row and the hand-end card |
| `render/ui/ParkDrag.kt` | Press → slop → park state machine + the shared park clamp |
| `render/ui/ParkChromeGroup.kt` | One press routed to the first of several movable blocks |
| `app/AppRuntime.kt` | Input pipeline → `ComponentAction` → `Intent`; the site that resolves input and dispatches intents |
