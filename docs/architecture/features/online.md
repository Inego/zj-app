# Online hub, live table, and account surfaces

Part of the [implementation](../implementation.md) map. Session authority and host boundaries
are in [sessions.md](../sessions.md); route mechanics are in [stack.md](../stack.md).

## The connect card

- **The connect card reads the online model, and never hangs.** Its status is
  `OnlineConnectOverlay.statusFor(onlineHost?.connectionStatus, onlineHost?.connectionFailure)`
  — the online host the coordinator owns, not `SessionRegistry.playHost` (whose local
  fallback reports `ConnectionStatus.Local`, which is not a rejoin). With no host
  attached the card reads `Connecting`. `OnlineCoordinator.tick`
  owns the **adoption deadline**: an `Online` context that stands without a host for
  `ADOPTION_TIMEOUT_MS` (four 5s presence heartbeats) is taken down to the hub with
  `online.table` kept, so the hub's Rejoin band is the Retry — a dead seat cannot hold
  the card forever. The status→copy mapping is one function
  (`OnlineConnectOverlay.titleKey`); the component publishes the status in `layout()`
  before it measures, and `draw()` only paints.

## The hub

- **The Online hub is the one online base context.** Its **Live** tab is the live
  matchmaking surface, closing with a **Past games** link to the match-history card.
  Its **Shadow Ranked** tab is *play the same deal other players
  played; you're ranked against their results* — a Start section and a
  **Past attempts** link that opens the player's attempts in their own card above
  the hub, whose finished rows reopen a `ShadowRankedComparison`. The attempts are
  a card, not a tab section, so a long list is culled to the rows it shows rather
  than laid out in full.
- **The Online hub loads from its own appearance, not from a command.**
  `app/RouteSurfaceLoad` observes the `ONLINE_HUB` route key entering the stack —
  pushed or restored — and runs `AppOnlineEffects.hubEnter()` once per appearance:
  `presenceClient.requestNow()`, `refreshAccountSurfaces()` and the notice refresh.
  It is not a cold-start special case in the composition root, and the hub's two
  cards load from their own appearance the same way: `MATCH_HISTORY` runs
  `matchHistoryEnter()` and `SHADOW_RANKED_ATTEMPTS` runs
  `shadowRankedAttemptsEnter()`, so a restored card is never empty and a mere visit
  to the hub fetches neither list. A session refresh
  (`AccountController.applyRefreshedSession`) reloads the attempts list under the
  rotated token; log-out and expiry clear it. Concurrent 401s from different
  surfaces join the one `AuthRefreshGate` refresh (`onUnauthorized` starts it or
  joins it) instead of each sending — or late ones returning early — so they share
  one rotated session and one reload. A 401 carries the credential that was
  refused: when it is **not** the token now held, the request merely outlived the
  rotation that replaced it, so `onUnauthorized` re-reads the up surfaces under the
  held token (once per token) instead of consuming a second refresh token —
  otherwise a revalidation and the scheduled refresh race and each rotates. The
  rotation itself is **durable before it is used**: `auth.session` is a
  `StateKey.secret` key, so `AuthSession.applyRefreshed` commits it
  (`StateStore.commit`) instead of leaving it for the runtime's batched flush.
  The server consumes the presented token the moment it answers, so a rotation
  still in backend memory is a credential the next boot presents already burned;
  a token two rotations behind is read as theft and revokes the family, which is
  a forced sign-in. The `rotated: … STORE=ok|lost` line is that verdict. An
  explicit **log-out** also resets
  the account-scoped online state: the Shadow Ranked starter
  (`ShadowRankedStarter.reset()`), the find model's matchmaking selection
  (`PresenceClient.resetFindSelection()`), and the hub's remembered tab
  (`AppOnlineEffects.resetHubTabForAccountChange()`, which keeps the non-account
  Shadow Ranked draft). `expireSession` (a dropped credential) clears only the
  cached clients, not those three — an open account-leak gap.
- **A Live search lives only while the hub does.** Switching the hub tab keeps it
  running (the Live tab label shows an in-progress dot, derived from
  `OnlineHubView.searching`, so it stays visible from Shadow Ranked); leaving the hub
  cancels it (`AppOnlineEffects.hubLeave` → `OnlineCoordinator.cancelSearch`), because
  the hub is the only surface that shows it and there is no root-menu indicator.

## A live table

- **A live table is the hub's own child layer.** `OnlineTablePort.tableJoined(origin)`
  has `app/OnlineTableSurfaces` push `ONLINE_TABLE` above `ONLINE_HUB`, recording the
  origin tab in the table route's args (the stack is the record); `tableExited` — a
  remote abort, a server shutdown, or an explicit exit — pops the table and returns
  the hub to that tab. `OnlineCoordinator` owns only *that* a table is live. A Shadow
  Ranked start enters with `origin = ShadowRanked`; a queue match or rejoin defaults
  to `Live`; the hub is the one online base context.
- **Play again is a rematch on the table's origin tab.** `AppOnlineEffects.playAgain`
  reads `ScreenTransitions.tableOrigin()` *before* the table leaves (the route's args are
  its record): a `ShadowRanked` table starts another attempt from the remembered draft
  via `shadowRankedStart`, anything else searches again for a live game. The origin, not
  a fixed guess, decides the rematch, so a finished challenge never starts a Live search.
- **A table enter without a session is visible, not silent.**
  `OnlineCoordinator.enterOnlineTable` needs an access token; when there is none it
  writes `HomeNoticeKind.SessionExpired` on the hub's notice channel rather than
  returning silently. The coordinator owns its `NoticeListClient`, so the inbox's
  change callback (`onNoticesChanged`) is injected through the constructor and wired
  in `init`; no caller reaches into the client after construction.
- **Return home from a table leaves through the table port**, so it lands on the hub
  tab the table was entered from.

## Account surfaces

- **The account payload is the only writer of its own rating.** `refreshAccount()`
  reads `GET /me`; `refreshAccountSurfaces()` is that plus the notification
  settings, and runs wherever presence starts or the access token
  rotates, so a restored Settings layer reads them without a route visit. The
  reader is a **required** constructor dependency (no inert default): a
  composition root cannot omit the wiring and silently leave the account stale. A
  settlement is a **trigger**, not a value: `GameFinished` carries the match's own
  prediction (taken before the settlement commits) and a Shadow Ranked finish
  carries none at all, while presence's `self.last_match` replays the last
  settlement the server still remembers — possibly days old. Both therefore ask
  for `refreshAccount()`; neither can write. Because the replay's answer is a read
  of the same row, it is a no-op rather than a second application of a stale
  number.
- A table that **leaves** is reconciled through the port (`tableExited` →
  `refreshAccountSurfaces`). A table that is **parked** is never reported through
  the port, so the session effect's `exitToMainMenu` asks for `refreshAccount()`
  itself; without it the floor would keep the rating the player carried into the
  match.
- `NotificationPrefsClient` keeps the last **server-confirmed** value in
  `notification.prefs`: the restored card renders it before the boot read answers (no
  off→on flash) and the read reconciles. An optimistic edit is never stored.
