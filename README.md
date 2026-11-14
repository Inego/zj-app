# zj-app

The client for **Zung Jung Mahjong** (中庸麻將) — a cross-platform mahjong game built with Kotlin and [libGDX](https://libgdx.com/). It runs on desktop, Android, and the Web, and plays both a full local game against computer seats and live ranked tables against other people.

## What it does

Four session kinds, all sharing one rules engine:

| Session | Needs a server | Notes |
|---|---|---|
| **Free play** | No | You against 1–3 computer seats, fully offline. Writes the local save. |
| **Tutorial** | No | Scripted lessons with a guided choice filter. Saved separately from free play. |
| **Online** | Yes | Server-authoritative tables, ranked with Elo. |
| **Menu** | No | Entry screen; shown on a fresh launch. |

Free play and the tutorial are entirely local. Online play is server-authoritative: the client renders a privacy-filtered snapshot and submits choices, while the server owns the wall, legality, bots, settlement, and ratings. The backend lives in a separate repository and is not needed to play offline.

## Platforms and modules

| Module | Platform | Notes |
|---|---|---|
| `core` | shared | All game logic, rendering, input, and networking. |
| `lwjgl3` | desktop | Primary platform (Windows, macOS, Linux). |
| `android` | Android | Needs the Android SDK (see `local.properties` or `ANDROID_SDK_ROOT`). |
| `teavm` | Web | TeaVM, built either to JavaScript or to WebAssembly. |

Inside `core`:

| Package | Role |
|---|---|
| `app/` | Application lifecycle, screens, and `Main`. |
| `engine/` | One-hand rules: state, legal moves, scoring. |
| `session/` | Multi-hand free play, save/restore, the online host, animation sequencing. |
| `engines/`, `hints/` | Computer-seat play and hint policy, choosing among already-legal moves. |
| `online/`, `net/` | Lobby, presence heartbeat, table ticket, WebSocket. |
| `input/`, `render/` | Table UI, overlays, pointer and keyboard routing. |
| `tutorial/` | Scripted lessons. |
| `persist/`, `platform/`, `i18n/` | Saves, platform services, translations. |
| `shuffle/` | Wall shuffle model. |

## Requirements

- **JDK 21.** The build targets Java 21; Gradle can fetch a matching toolchain automatically.
- **Android SDK** — only for the `android` module.
- Everything else is resolved by Gradle from Maven Central and Google.

## Build and run

Use the wrapper (`gradlew.bat` on Windows, `./gradlew` elsewhere).

```bash
# Desktop
./gradlew.bat lwjgl3:run

# Web — WebAssembly on http://localhost:8081, JavaScript on http://localhost:8082
./gradlew.bat :teavm:runWasm
./gradlew.bat :teavm:runWeb

# Android — device or emulator must be connected
./gradlew.bat android:run
```

Distribution builds:

```bash
./gradlew.bat lwjgl3:jar           # runnable desktop jar → lwjgl3/build/libs/
./gradlew.bat :teavm:buildWasm     # Web dist → teavm/build/dist/wasm/webapp/
./gradlew.bat :teavm:debugWasm     # same dist, readable names + TeaVM debug info
./gradlew.bat android:assembleDebug # debug APK → android/build/outputs/apk/
```

Build-time options are Gradle properties (`-Pname=value`); defaults live in `gradle.properties`.

| Property | Default | Effect |
|---|---|---|
| `apiBaseUrl` | `http://127.0.0.1:8080` | API/WebSocket base for Web builds. Empty means same origin. |
| `googleWebClientId` | set | Google sign-in audience for Web and Android. Empty disables those sign-ins. |
| `googleDesktopClientId` | empty | Google sign-in audience for desktop. Empty disables desktop sign-in. |
| `teavmObfuscated` | `false` | TeaVM name obfuscation. Set `true` for production Wasm. |
| `teavmDebugInformation` | `false` | TeaVM debug info: Wasm function names in stack traces. The `:teavm:debugWasm` / `:teavm:runDebugWasm` tasks set it. |
| `projectVersionMajor` / `projectVersionMinor` | see `gradle.properties` | Marketing version. The third component is the git commit count plus `projectBuildOffset` (`git rev-list --count HEAD`), shown on the main menu. Override the total with `-PbuildNumber=N`. |

A production Web build obfuscates the output and leaves the API base empty so the app talks to its own origin:

```bash
./gradlew.bat :teavm:buildWasm -PteavmObfuscated=true -PapiBaseUrl=""
```

For a field-diagnostics build with readable Wasm stack traces, use the debug toggle (same output path, so deploy tooling needs no change):

```bash
./gradlew.bat :teavm:debugWasm       # build → teavm/build/dist/wasm/webapp/
./gradlew.bat :teavm:runDebugWasm    # build and serve on http://localhost:8081
```

A black canvas after the splash is not one bug. The preload screen clears to black, and a dropped animation frame, a TeaVM fiber trap, or the preload loop being replaced all look the same. See [docs/guides/wasm-startup.md](docs/guides/wasm-startup.md).

## Game and hand

A **game** is a configured sequence of **hands**. Default is **4 rounds × 4 hands = 16 hands**; you can also choose **1** or **2** rounds, or **Unlimited**. On a fresh launch (no restored session), the **main menu** opens first. Choose **New game** to open setup:

- **Number of rounds** — 1, 2, 4 (default), or Unlimited
- **Allow patternless hands** — off by default (a 1-point patternless win is illegal when off)

After a restored or started game, setup starts from that game’s rules. Edits you make then Cancel stay in memory for the next open (not written until Start). The status badge shows **N/M** for a finite game (**2/4**, e.g. M = 16 when rounds = 4) and **Hand N** in an Unlimited game, where there is no M to anchor a bare number.

**Session restore (desktop and Android):** leaving mid-game saves scores and the current hand; the next launch resumes that board. A mid-lesson tutorial is saved separately and is restored when you open Tutorial again, even after a free-play game. Desktop stores the files under `~/.zj-app/` (Windows: `%USERPROFILE%\.zj-app\`).

**Alternate data folder (desktop only):** `--data-dir <path>` (or `--data-dir=<path>`) redirects the whole `~/.zj-app/` folder — preferences, session save, window state, logs and crash dumps — to `<path>`. Use it to run several clients with independent sign-ins. From Gradle, pass it as `-PdataDir=<path>` (forwarded to the app's `--data-dir` and to the native crash flags), e.g. `./gradlew.bat :lwjgl3:run -PapiBaseUrl=https://play.zungjung.org -PdataDir=C:\tmp\userA`. The equivalent `-Dzj.dataDir=<path>` system property also works and is inherited by the crash-JVM relaunch.

**Window restore (desktop only):** the last window size, position, maximized state, and fullscreen mode are saved on close (and pause) and reapplied on the next launch (`~/.zj-app/window.properties`).

**Desktop logs:** each run writes `~/.zj-app/zj-desktop.log` (stdout/stderr, flushed). Java crashes dump the stack to `~/.zj-app/last-crash.txt`. JVM native crashes go to `~/.zj-app/hs_err_pid*.log`.

## Controls

Computer seats play automatically (paced by tile animations); the game only waits for your choices. AI seats and hints use **DefaultEngine**, the built-in heuristic policy: it scores every legal choice deterministically, breaks ties randomly only when actually playing a seat, and hints show every co-best option without RNG.

**Mouse / touch**

- **Click hand tile** — discard on your turn
- **Click claim buttons** — Pass / Hu / Pung / Kong / each Chow (and Hu / Declare Concealed Kong / Promote Kong / Keep on your turn)
- **Menu → New game** — setup dialog and clear scores (replaces the old “Reset scores”)

**Keyboard — desktop, and Web when the primary pointer is a mouse**

| Key | Action |
|---|---|
| **Ctrl+Space** | AI assist for the current decision |
| **H** | Highlight all co-best engine choices (no RNG); the **HINT** button does the same on touch. If exactly one is highlighted, press again to play it; if several are highlighted, the button greys immediately and you must choose. Desktop additionally runs Monte Carlo playouts and shows avgΔ / playout-count labels; Android and Web keep the plain co-best highlight (see `platform/McHints`). |
| **T** | Toggle the game score table (scroll for history; footer shows running totals); **Esc** closes it |
| **N** | Next hand (seat winds rotate; finished hands are scored into the table). On the last hand of a finite game, opens New Game setup instead. |
| **Space** | Decline a claim or Keep, or discard the drawn tile on your turn |
| **Enter** | Accept the only available claim, confirm a dialog, or deal the next hand when ready |
| **Y** / **N** | Confirm / cancel a dialog |
| **Left** / **Right** | Step through the shadow-ranked hand list |
| **Backspace** | Go back in the Zung Jung reference |
| **Esc** or **×** | Close the open overlay |
| **F11** | Toggle fullscreen (desktop; on Web, F11 is the browser's fullscreen key) |
| **Ctrl+R** | Open the Zung Jung reference (desktop; on Web, Ctrl+R reloads the page) |

**Enter** or **Space** resumes an away table; **Enter** starts a new game in setup. Touch-primary Web and Android use the on-screen buttons instead of these keys.

## Scoring (Zung Jung payments)

Each finished hand is settled into the score table:

- Winner receives **3 × hand value** in total (each of the other three pays the hand value).
- **Exception (win on discard only):** if the hand value is **greater than 25**, the two non-discarder losers pay **25** each, and the discarder pays the rest so the winner still nets **3 × hand value**.
- Exhaustive draws score **0** for everyone.
- **Patternless hands** (complete shape, no pattern points) score **1** only when enabled in game setup.

## Tests

```bash
./gradlew.bat :core:test                       # whole suite; prints only failures and skips
./gradlew.bat :core:test --tests "pkg.Class"   # one class/area while iterating
```

Iterate with `--tests`, run the full suite before finishing, and skip `-i`: a green run prints nothing, so exit 0 is the pass signal and only failures need reading (`--rerun-tasks` forces a cached task).

The suite is Kotlin/JUnit 4 and covers the rules engine, scoring, settlement, Elo, and the session and online layers.

## Documentation

- [`docs/rules-and-ui-architecture.md`](docs/rules-and-ui-architecture.md) — how the rules and UI layers fit together.
- [`docs/tutorial-authoring-guide.md`](docs/tutorial-authoring-guide.md) — writing lessons.
- [`docs/guides/adding-a-language.md`](docs/guides/adding-a-language.md) — adding a new localization language.
- [`docs/ui-icons.md`](docs/ui-icons.md) — icon sources and rasterisation.
- [`docs/app-icons.md`](docs/app-icons.md) — the application logo, and regenerating it for all platforms.
- [`docs/russian-terminology.md`](docs/russian-terminology.md) — translation glossary.
- In-game on desktop, **Ctrl+R** opens the bundled Zung Jung reference.

## License and attribution

**Zung Jung Mahjong Scoring System (v3.3) by Alan Kwan, licensed under [CC BY-NC-SA 3.0 HK](https://creativecommons.org/licenses/by-nc-sa/3.0/hk/). Reference section reproduced under that license. Tutorial, client code, and server are original work. Free and non-commercial. Not affiliated with or endorsed by Alan Kwan.**

This client is licensed **CC BY-NC-SA 3.0 HK** — see [`LICENSE`](LICENSE) for the full legal code and [`NOTICE`](NOTICE) for the Alan Kwan credit and for third-party components (tiles: CC0; icons: Lucide ISC / Feather-derived MIT; fonts: Roboto Apache 2.0, Noto Sans SC OFL 1.1). It is licensed under those terms because it bundles Alan Kwan's Reference text, so the ShareAlike condition covers the whole client.

**Non-commercial:** no ads, paid features, cosmetics-for-sale, supporter tiers, or commercial sponsorship.

**The server is not in this repository.** The `zj-server` backend is a separate work: it is proprietary, closed source, and not covered by this licence. This client interoperates with it; interoperation does not make the two works one work.
