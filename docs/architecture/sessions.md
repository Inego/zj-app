# Sessions and boundaries

Part of the [implementation](implementation.md) map. The stack is [stack.md](stack.md);
state ownership is [state-and-persistence.md](state-and-persistence.md).

A feature exposes **immutable snapshots and effect ports**; it never touches another feature
or the view.

- `PlayHost` publishes `snapshot: TableSnapshot` (tableView, decision, clocks, connection
  status, human seat, hand counts, match phase, settlement, names/bot flags — engine/data
  types only) plus `submitChoice`/`submitDiscard`/`requestNextHand`/`advance`/`leave`.
  `LiveHand` publishes `state`/`active`/`humanSeat`/`choiceRng`, with `HandSnapshot.from`.
  `LocalPlayHost`, `OnlinePlayHost`, `GameSession`, `TutorialRunner`/`TutorialPlayHost`
  implement them. `ConnectionFailure` is a top-level session type carried in `TableSnapshot`.
- Hosts receive narrow ports (`OnlineTablePort`, `OnlineAwayPort`, `TutorialRoutesPort`,
  `RouteAccountView`, `FreePlaySurfaces`, an `IntentSink`), never the app graph. There are no
  `Hooks` objects.
- `TutorialHost` owns the director, runner, progress, and `snapshot()`. It announces
  `routes.showCoach()` / `showQuiz()` / `hideCoach()` / `hideQuiz()` and a reference
  target; it does not hold the shell, quiz, coach, or reference-browser widgets. Leave,
  wall-deal, quiz submit, quiz attempt, and wall-handoff arrive as emitted intents with
  payloads.
- `SessionRegistry` is the derived session authority. `kind` = an attached online host →
  `Online`; else the topmost stack entry whose `contextKind` is non-null; else `Menu`. It is
  never set. The Online hub is a context layer that declares **no** `contextKind`, so
  browsing it (`[root, hub]`) reads `Menu` — the hub has no hand and no host, and an account
  surface stays free over it — and only `ONLINE_TABLE` declares `Online`. Hosts are read
  through lazy providers, so a host change is visible the same frame. `SessionKind`
  lives in `ui/stack/`; its policy (`allowsFreePlayPersist`,
  `allowsScoreTable`) in `session/SessionKindPolicy.kt`. `sessionOwnsDisplay`
  (`kind != Menu`) gates account surfaces. `AppRuntime.syncOnlineConnect` covers an
  online table with `ONLINE_CONNECT` while `kind` is `Online` and the seat is
  unresolved, including with no attached host. Other modals occlude the card
  (`suppressesConnectCard`). Online `boardTableView` uses only the host table
  snapshot.
- `net/` clients order asynchronous responses against newer intent with `RequestGate`:
  the request that started last is the only one allowed to apply, and starting a request
  supersedes whatever is in flight — so a stale read can never overwrite a newer local
  edit (`NotificationPrefsClient`, `NoticeListClient`, `ShadowRankedAttemptsClient`). A local
  mutation that sends nothing says so with `supersede()`. `PresenceClient` still keeps its
  own counter (its heartbeats mix request ordering with session lifecycle).
- `engine/`, `engines/`, `hints/`, `shuffle/`, `net/` are untouched by this model; their
  adapters live in `session/`.

Feature-specific architecture lives with the feature:

- [Online hub, live table, account surfaces, and the connect card](features/online.md)
- [The rating-history chart](features/rating-history.md)

The architecture ratchet is shrink-only and all-empty; its rules are in
[testing.md](testing.md#2-the-architecture-ratchet).
