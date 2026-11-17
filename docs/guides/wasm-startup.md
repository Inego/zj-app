# Wasm startup

A black canvas after the HTML splash is the preload screen's clear color
(`0, 0, 0, 1`). It is also what you see when startup never reaches `Main`.
Desktop and Android do not run this path: they have no browser callback, no
TeaVM fiber, and no animation-frame slot. A desktop build that boots says
nothing about the web client.

Three different failures have produced this picture. Two of them log nothing.

## Why the console stays empty

`WebApplication.step` catches exceptions from `create` and `render` and prints
`Fatal Error`. Browser callbacks do not run inside that try. A JS-invoked
method (`AnimationFrameCallback`, `setTimeout`, XHR, `EventListener`) is a
TeaVM export. If that export is compiled as a coroutine, its prologue reads
`Fiber.current()`. After `main` returns, that is null, and the callback traps
with `RuntimeError: dereferencing a null pointer` at `Fiber.isResuming`. If the
export is not a coroutine and simply finds nothing to run, there is no
exception at all.

`graphics.render` is skipped until `frameId` passes 60. Until the preload
listener's own queue hits 0, `render` only clears. `LangPack` lines come from
the preload listener. `ZjBoot` lines come from `Main`. Pack open without
`ZjBoot` means the zip finished and the game was never installed.

There are two queues. `WebApplication.initQueue` waits for `gdx.wasm.js`.
`WebPreloadApplicationListener.initQueue` waits for the logo, the asset
manifest, and (on web) the active language zip. Holding the second queue keeps
the canvas black with no error.

## Failures that look the same

**Dropped frame.** `WebWindow` used to register a new browser animation
callback on every `requestAnimationFrame` while storing only one runnable. A
spare callback ran after dispatch had cleared the slot, so the frame that
should start the game ran empty and the loop never armed again. No exception.
Any script injected at document start, including an extension or DevTools
attached before navigation, shifts timing enough for the menu to appear.
Opening DevTools after the loop is dead shows an empty console.

The current `WebWindow` keeps one callback. A later request replaces the
pending runnable and does not register another callback. That replacement is
the next failure.

**Boot callback replaced.** Only `bootLoop` watches `setPreloadReady` and
installs `Main`. Resize and pointer events call `wakeFrame` between preload
frames. `wakeFrame` schedules `appLoop`. With one animation slot, that
overwrites `bootLoop`. `appLoop` then runs the preload listener forever.
When the asset queue drains, the listener clears to black and sets a flag
nobody reads. `LangPack: open` is already on the console. `ZjBoot` never
appears. Language-pack fetch makes this window long enough for a resize to
land. `wakeFrame` now returns while `appListener` is still waiting, and
`appLoop` hands the slot back to `bootLoop` if it was already overwritten.

**Coroutine export.** `AssetDownloadImpl.loadBinary` defers the real fetch
with `Window.setTimeout`. The language zip is requested from preload
`create()`, which runs on the animation loop after `main` has returned. TeaVM
marks that timer export as a coroutine because the body reaches `LanguagePack`.
The timer traps in `Fiber.isResuming`. The same rule covers XHR handlers,
script load/error, `pagehide`, `visibilitychange`, and `resize`. The exported
method may only call `BrowserFiber.enter`. `enter` must stay a single
`Fiber.start(work, true)`. Calling the work from `enter` makes `enter` itself
a coroutine, and every callback that uses it traps again.

A Java exception thrown inside that fiber (for example
`preloadManifest` rejecting a manifest line that is not five colon-separated
tokens) also escapes `step`. It will not show up as `Fatal Error`.

**`@JSBody` re-emitted by the Wasm backend.** A bridge body must be a plain
statement list. TeaVM parses it and prints it again, and that printer drops the
grouping parentheses of an immediately-invoked function expression:
`(function() { ... })();` reaches the browser as `function() { ... }();`, and the
module dies before `Main` with `SyntaxError: Function statements require a
function name` from `new Function` inside `teavm@initializer`. The stack points
at `jsoImports` in `app.wasm-runtime.js`, not at the bridge, and the app logs
nothing. Write statements, and declare helpers with `function name() {}`: the
body is already a function, so declarations and `return` are legal there.

## What to read off the console

| Console | Where startup stopped |
|---|---|
| Empty, canvas black | Animation callback ran with no runnable, or the boot callback was replaced before `frameId` passed 60. |
| `Fiber.isResuming` / `dereferencing a null pointer`, often `onTimer` or `*exported*` | A browser callback was compiled as a coroutine. Hop through `BrowserFiber.enter`. |
| `LangPack: open`, no `ZjBoot` | Zip opened. `Main` was not installed, or the preload queue never hit 0. |
| `ZjBoot: create()` then silence | `Main` started. The present loop stopped after that. |
| `Fatal Error` | Exception inside `step`. This is the ordinary path. |

`ZjBoot` is the tag in `BootDiagnostics`. The menu version string with a
trailing `+` means the git tree was dirty when the client was built.

## How to load a page without hiding the bug

Use an unobfuscated build when you need stacks. From the repo root:

```bash
./gradlew.bat :teavm:buildWasm -PapiBaseUrl=
```

Production adds `-PteavmObfuscated=true`. `:teavm:debugWasm` is the diagnostics
build (readable names, TeaVM debug info). Serve
`teavm/build/dist/wasm/webapp` yourself with `application/wasm` for `.wasm`.
`:teavm:runWasm` is a different server and the wrong artifact to compare with
play.zungjung.org.

Launch a normal headed Chrome: fresh user-data dir, no remote-debugging port,
no extension, no script at document start. Attach DevTools only after the
canvas has already settled, and treat a menu that appears only when DevTools
was open beforehand as the dropped-frame bug, not as a pass.

On this Windows host, Chrome started as a child of a short-lived shell is
killed when that shell exits (the process job). A window that flashes and
vanishes was not a browser crash. `CopyFromScreen` / `PrintWindow` of the GPU
page often captures white or the window behind. A CDP screenshot has to target
the page whose URL is the game. The first `type === "page"` target can be
DevTools.

Do not edit `index.html`'s boot script while chasing this. Its CSP hashes are
pinned. A client deploy does not restart zj-server or reload nginx. The
response is `Cache-Control: no-cache`; a normal reload can keep the previous
`app.wasm`.

## Where it lives

| Piece | File |
|---|---|
| One animation slot | `teavm/.../dom/impl/WebWindow.java` |
| Preload loop vs game loop | `teavm/.../WebApplication.java` (`bootLoop`, `appLoop`, `wakeFrame`) |
| Fiber hop | `teavm/.../BrowserFiber.java` |
| Download callbacks | `teavm/.../assetloader/AssetDownloadImpl.java` |
| Zip held on the preload queue | `teavm/.../WebLanguagePackPreloadListener.kt` |
| When `Main` is allowed to start | `WebPreloadApplicationListener` in gdx-teavm, shadowed only where this tree replaces it |

`AssetDownloadImpl` and `WebWindow` shadow the copies in gdx-teavm 1.6.2 by
package and class name. A new JS callback added in game code needs the same
hop. A new shadow of a library class should copy the library method and change
only the callback boundary.

## What has already been ruled out

An Edge interpreter `CompileError` (`exnref` not supported) is a real
separate failure when Wasm JIT is off. It prints a compile error. It did not
cause the empty-console black screen in Chrome. Do not start there unless
that `CompileError` is on the console.
