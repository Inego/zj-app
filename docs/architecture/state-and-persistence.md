# State and persistence

Part of the [implementation](implementation.md) map. The stack itself is
[stack.md](stack.md).

Every persisted artifact is a versioned `StateKey` on a `StateStore`.

```kotlin
class StateKey<S : Any>(val namespace: String, val name: String, val schema: Schema<S>,
                        val default: () -> S? = { null }, val secret: Boolean = false)

interface Schema<S : Any> {
    val version: Int
    fun encode(value: S): ByteArray
    fun decode(bytes: ByteArray): S?
    val migrations: List<Migration> get() = emptyList()
}
```

`StateStore.read` never throws: corrupt, missing or forward-versioned data returns null and
the key's default applies. `StateSlot(store, key)` is the narrow accessor handed to the model
that owns a value. A migration step must emit the **next** version's layout, not the current
one.

The backends, and **the containers must not share a name**:

- `PreferencesStateBackend` — libGDX `Preferences`, container `zj-state`, for the small keys.
- `FileStateBackend` — byte-blob files in the keyed folder `zj-files` (`session.freeplay`,
  `session.tutorial`); `DesktopFileStateBackend` is plain `java.io` over the same folder
  because the desktop launcher reads the window geometry before `Gdx.files` exists.
- On the web `WasmStateBackend` replaces the preferences container with **one live
  `localStorage` key per state key** (`pref:zj_game:zj-state:<name>s`, the layout
  `WebPreferences` used, so an existing save survives). libGDX's web preferences are a
  **per-tab snapshot**: the constructor loads the whole container into a map, reads never
  look at `localStorage` again, and `flush()` deletes and rewrites every key from that
  snapshot — two tabs would neither observe each other's writes nor preserve them. The
  backend is selected through `PlatformStateBackend.factory`, which the web launcher
  installs before the preload screen resolves the locale.
- On desktop the launcher's `DesktopDataDir` owns the root (`~/.zj-app`, or the
  `--data-dir` / `-Dzj.dataDir` override) and hands it to every desktop consumer: the log,
  the crash flags, the window store, the libGDX preferences directory, and the core
  `FileStateBackend` via `FileStateBackend.rootOverride` (the external path cannot be moved).
- `InMemoryStateBackend` for tests. `SecretBox` seals any key marked `StateKey.secret`
  inside `DefaultStateStore`: no box (browser) = clear; a failing box = the write is dropped
  and the session stays memory-only; an unopenable blob = missing.

**Flush policy.** Two classes, split by what a lost write costs.

- **Batched.** The runtime flushes once, in `AppRuntime.pause`/`exit`/`dispose`. Between
  those edges a *changed* stack is captured at most once a minute (identity of the stack
  instance is the dirty check), so a killed run loses at most that. Everything whose owner
  can simply write it again is batched this way.
- **Committed.** A `StateKey.secret` value is durable before its write returns
  (`StateStore.commit`): the store seals it, publishes it to the container, and reads the
  bytes back from the live container — the browser's `localStorage`, the desktop/Android
  preferences map after `flush()`. The reason is the event that produces the value, not
  tidiness. The server
  consumes a refresh token the moment it answers, so a rotation still sitting in backend
  memory is a credential the next boot presents already burned — and a token two rotations
  behind is what the server reads as reuse, which revokes the whole family and forces a
  sign-in. `secret` therefore carries both meanings: seal it (its leak is unrecoverable) and
  commit it (its loss is too). `AuthSession` is the only owner of such a key; its
  `rotated: … STORE=ok|lost` line is the store's verdict, not a memory read-back.
  A desktop run is therefore still signed in after a kill (`taskkill /F`, an IDE Stop), which
  is exactly the case the batched policy never covered. It is not `fsync`-durable: the bytes
  reach the OS page cache, not the platter.

**One session, several tabs.** The browser shares `auth.session` across every tab of the
origin, but each tab holds its own `AuthSession` in memory. A rotation is therefore a
critical section over the shared key: `SessionCoordination.withRefreshLock` takes an
origin-wide `navigator.locks` lock (installed by the web launcher), and inside it the
controller re-reads the key (`AuthSession.reloadStored`), presents the token it found, and
writes the result before releasing. A tab that waited out another tab's rotation presents
the newer token instead of replaying the one it remembers; a tab that signed out or signed
in during the wait is caught by the generation guard. A result that would overwrite a key
another tab changed is **dropped** and the tab follows the store
(`AuthSession.applyRefreshed` compares the presented token), never merged — and never
revoked, because the only other writer is a legitimate sibling rotation. A `storage`
event, a visibility change, and a bfcache `pageshow` poke the other tabs to re-read. The
rotation also consumes the session row every sibling's access JWT names, so the tab that
wrote it announces the JWT it received over a same-origin `BroadcastChannel`
(`SessionCoordination.publishLiveSession`); a sibling that adopts that refresh token takes
the announced JWT and keeps heartbeating instead of exchanging the token again. Without
that handoff two visible tabs consume each other's tokens once per heartbeat. Nothing new
is stored: the access JWT stays memory-only and crosses only between same-origin tabs. A
hidden tab runs the section unlocked: its frame loop is paused, so the callback that would
release the lock cannot run until the tab is visible again, and the reload plus
compare-and-swap make the unlocked exchange a sibling, which the server recovers from,
rather than a replay.

A tab that signs out — or follows another tab's sign-out — also gives up the key: a
sign-in another tab performs afterwards does not sign it back in, so that tab stays on the
signed-out landing until the player signs in there or the page is reloaded. And a sign-out
that lands after another account took the key leaves that account's session alone instead
of deleting it.

| Key | Holds |
|---|---|
| `stack.state` | `StackState` — routes, args, resolved policy, per-route UI text |
| `session.freeplay` / `session.tutorial` | `SessionSnapshot` / `TutorialSessionSnapshot` |
| `game.prefs` / `ui.prefs` / `ui.locale` | `LocalGameConfig` / `UiPreferences` / `AppLocale` |
| `online.find` / `online.table` | `FindGamePreferences` / last table id (empty = none) |
| `ui.remembered` | last value of each remembering route |
| `notification.prefs` | last server-confirmed notification settings |
| `tutorial.progress` | campaign progress |
| `auth.session` (secret) | refresh token + profile (the access JWT is not in the type) |
| `auth.firstLogin` | answered prompt users |
| `platform.window` | desktop window geometry |

**Route UI store.** `RouteUiState` is in-memory and route-keyed; the stack snapshot is its
persistence form. `LayerSpec.state` names the codec; a `StatePort` reads; writes go through
`RouteUiActions` → `Intent.WriteRouteUi`. Absence is `null`, never a sentinel. Three
lifetimes: **entry** (`LayerPolicy.persists = false`, the value lives only while its entry
is up); **session** (`persists = true`, captured with the stack and restored with it); and
**remembered** (`LayerSpec.remembersState`, every write mirrored into `ui.remembered` by
route and read back when the route is created again — surviving the entry and the process;
`remove` never clears it). The Online hub remembers its state, so it opens on its last tab,
scrolls, and Shadow Ranked draft. A value that is a display
preference rather than route state is a field of `ui.prefs` instead, written by its prefs
effect and read by whoever renders it (the score-breakdown fold, park and scroll; the
board's movable chrome). This is how a persisting layer keeps player-set state: the
reference browser's navigation
(`ReferenceNavCodec`), body scroll on the score table / match history /
notices / language picker (`RouteScrollCodec`), the menu card's body offset + open
drawer (`GameMenuCardCodec`), the setup card's draft + scroll (`SetupCardCodec`), the
Online hub's tab, two scrolls, and Shadow Ranked draft (`OnlineHubCodec`), the rating
chart's target, visible window, and rung (`RatingHistoryCodec`), the tutorial
shell's TOC (`TutorialShellCodec`).

**Cold start** (`app/AppState.kt`) is one read and one order:

1. `AppGraph` builds the registry and an empty `Router`.
2. `AppStateSource.read()` — one pass over the artifacts the boot decides from (`stack`,
   `session`, `tutorialSession`, `account`, `game`, `ui`, `locale`).
3. `Router.restore(state.stack)` — instantiate routes bottom→top.
4. Seed `RouteUiState` from each restored entry's `componentState` through that route's
   declared codec. Presenters read lazily.
5. Restore the session the floor names (topmost context layer first). A context layer whose
   save is gone is **removed**, which removes the chrome above it and reveals its parent.
6. Account surfaces last — the credential is adopted and presence starts. This step never
   moves the floor, and `View.isEntrySurfaceFree()` keeps an account surface off a live board.

`online.find`, `online.table`, `ui.remembered`, `notification.prefs`, `tutorial.progress`
and `auth.firstLogin` are deliberately **not** read at boot; their owners read them lazily.
A read-counting test fails if the boot reads another key.
