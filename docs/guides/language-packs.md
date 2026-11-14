# Language packs

Startup on Wasm downloads every file under `assets/` before the game starts
(gdx-teavm bakes that walk into `TeaAssetManifest` and fetches five files at a
time). That walk is 17 languages × 85 markdown pages, plus every
`ui_*.properties`. Desktop and Android do not fetch them, but they still ship
the loose tree.

Authoring stays as it is: `assets/i18n/ui*.properties` and
`assets/reference/<tag>/**/*.md`. A Gradle task packs each language. The running
game opens one pack.

`assets/assets.txt` is not the preload list. TeaVM 1.6.2 does not read it.

## Pack contents

One zip per `AppLocale.tag`, path `lang/<tag>.zip` (so `lang/zh-HK.zip`).

| Entry | What it is |
|---|---|
| `pack` | That language’s resolved UI bundle (with English filled in for keys listed in `untranslated-keys.txt`) and every reference body — `about.md`, `rules/…`, `scoring/…` — in one blob. |

A pack is a **single** zip entry, not one entry per page. `ZipOutputStream`
deflates each entry on its own and resets its window, so ~85 tiny markdown files
barely compressed (measured ~1.6×); putting the bundle and all bodies in one
`pack` stream lets them share a dictionary and reaches ~3.7×. The entry is framed
as `<bundle-byte-count>\n<bundle-bytes>` followed by `<relative-path>\n<utf8-byte-count>\n<bytes>`
per page — counts, not delimiters, so any body (newlines, a line shaped like a
header) is unambiguous. `structure.json` does not go in the zip: it is one shared
file (~10 KB), identical for every language, and stays in the startup set. The
task also writes `lang/index.txt`, one tag per line, and that file stays in the
startup set so the game can see which packs exist without opening them.

The packed properties are the resolved bundle, not a copy of
`ui_<lang>.properties`. Chinese is the case that makes the distinction matter:
the reference folder and the zip are `zh-HK`, while the source bundle is
`ui_zh.properties` (`Locale("zh", "HK")` falls through to `ui_zh`). The task
uses that same lookup, then applies the English fallback. At runtime nothing
opens a second properties file.

Zip entries are deflated, ASCII-named, and written with size and CRC set.
TeaVM 0.15 has `ZipInputStream` and `Inflater`; `ZipFile` needs a real
filesystem path, which browser memory files and Android assets do not have.
`LanguagePack` reads the `pack` entry into memory on open, with `ZipInputStream`
over `Gdx.files.internal("lang/<tag>.zip").read()`.

## When a pack is opened

Only the active pack is opened.

- Cold start reads the persisted locale (`AppLocaleKey`). When nothing was
  saved, that is `AppLocale.DEFAULT` (`en-US`).
- That one zip is opened before the first localized frame. On Wasm the existing
  preload screen fetches it, after preferences are readable and before `Main`
  starts. That fetch runs from a browser timer after `main` has returned; the
  callback has to hop through `BrowserFiber`, and the preload animation
  callback has to stay queued until `Main` is installed. See
  [wasm-startup.md](wasm-startup.md). Desktop and Android already have the file
  locally; they still open only this zip.
- Switching language opens the newly selected zip. No other pack is fetched or
  parsed until then. The previously opened pack can be dropped.
- The reference pages ride along inside the same zip. Opening the reference
  book does not fetch again.

English is not opened as a parent pack. Its missing-key text is already inside
`bundle.properties`.

## Logging

Pack activity is logged on one tag, `LangPack`: `ensure` (source, bytes, ms),
`fetched` (web download, ms), `open` (bundle bytes, page count, ms), `index`
(tag count, ms), `bundle` (`L10n` build, source, ms), and `read:miss`. The locale
switch line (`Locale`) carries the open tag and total ms.

Filter: `adb logcat -s LangPack` on Android, or on desktop the `LangPack` lines
in `~/.zj-app/zj-desktop.log` — `DesktopLog` already stamps each line with
`yyyy-MM-dd HH:mm:ss.SSS` and the live stdout copy. No per-platform timestamp is
built into the messages; the desktop sink owns the clock.

## Owners

The task owns packing. `LanguagePack` owns reading a zip into memory (properties
plus page text). `LocaleController` already chooses the tag; it asks
`LanguagePack` to make that tag readable, then `L10n` and `ReferenceCatalog`
read from the open pack.

`L10n` loads `bundle.properties` through `I18NBundle.load(Reader)` with no parent
file. `ReferenceCatalog` keeps reading `structure.json` from internal assets and
takes page bodies from the pack. `referencePackExists` checks `lang/index.txt`
instead of probing `about.md`.

The web side of “make it readable” is the existing `AssetLoader.loadAsset`.
Desktop and Android complete that call immediately because the zip is already
an internal file. Launchers install the web fetcher the same way they install
the other platform factories. Game code calls one `ensure(tag)`.

## Packaging

A file under the directory passed to `gdxTeaVM { assets(...) }` is copied and
added to the startup manifest. The plugin has no “ship but do not preload”
switch. Zips written straight into `assets/lang/` would all download at startup.

The task publishes a staged asset directory used by desktop `run`, the desktop
jar, Android, and the Wasm asset input:

- Startup set: tiles, audio, fonts, UI images, `structure.json`, `lang/index.txt`.
  No loose markdown, no `ui*.properties`, no zips.
- The zips are local files for desktop and Android (`lang/<tag>.zip`).
- On Wasm the zips are copied into the web build’s `assets/lang/` after the
  manifest is baked, so `loadAsset("lang/<tag>.zip")` can fetch one of them and
  the preloader does not queue the rest.

Android’s asset `exclude` is a no-op (the CJK font is already dropped with
`ignoreAssetsPattern`). The staged directory is the filter. Desktop `run` uses
`assets/` as its working directory today; it has to point at the staged
directory, or the staged files have to be what that working directory contains.

## Tests

`BundleCoverageTest` and `ReferenceLocaleCoverageTest` keep walking the
authoring files. They guard the task’s inputs. The zip reader is tested with a
zip built in the test, so unit tests do not depend on the task having run. One
test pins the packer rule that matters: a missing key listed in
`untranslated-keys.txt` is present in `bundle.properties`, and a `zh-HK` pack
carries `ui_zh`’s strings.

## Order

1. ~~Reader and `ensure(tag)`, with an in-test zip. No Gradle yet.~~ **Done.**
2. ~~Gradle task: one zip per `AppLocale.tag`, plus `lang/index.txt`.~~ **Done.**
   See [Stage 2 API](#stage-2-api-later-stages-depend-on-these-names).
3. ~~`L10n` and `ReferenceCatalog` read the open pack. `LocaleController` calls
   `ensure` before applying a locale.~~ **Done.** See [Stage 3 API](#stage-3-api-later-stages-depend-on-these-names).
4. ~~Staged assets for desktop, Android, and Wasm. Wasm preloads the index and
   fetches only the active zip.~~ **Done.** See [Stage 4 API](#stage-4-api-staged-trees-and-platform-wiring).
5. ~~Point `docs/guides/adding-a-language.md` at the task. Authors still edit
   the loose properties and markdown; they do not edit the zips.~~ **Done.**

### Stage 1 API (later stages depend on these names)

`LanguagePack` (in `i18n/`) owns the open pack and all zip parsing. Stage 3 uses:

- `ensure(tag)` — idempotent, never throws; reads internal `lang/<tag>.zip` or
  defers to the `@Volatile var fetcher: Fetcher`, whose `onReady` re-reads the
  now-readable file. Default fetcher completes synchronously.
- `openTag: String?`, `isOpen(tag)`, `readText(relativePath)` (pack-relative page
  body), `availableTags()` / `isAvailable(tag)` from `lang/index.txt`,
  `parseIndex(text)`.
- `bundleBytes(): ByteArray?` — the open pack's `bundle.properties` bytes; stage 3
  feeds these to the public `I18NBundle.createBundle` (see [Stage 3 API](#stage-3-api-later-stages-depend-on-these-names)).
- `bundleReader(): Reader?` — a fresh UTF-8 reader over those same bytes.
- `addOpenedListener` / `removeOpenedListener`; `resetForTests()`.

The Gdx-free seam is `openBytes(tag, bytes): Boolean` (the tag travels with the
bytes because `openTag` is part of the opened state; the zip itself does not
carry it). Decoded pages and bundle bytes are cached at open; a corrupt or
absent zip closes the pack and returns rather than throwing.

### Stage 2 API (later stages depend on these names)

`LanguagePacker` (in `i18n/`) owns packing and is Gdx-free (it never initializes
`Gdx`; `AppLocale` was checked and imports only `ScriptProfile`, which imports
only `AppLocale`). Stage 4 runs `:core:packLanguagePacks` (a `JavaExec` task in
`core/build.gradle`) and consumes its output directory
`core/build/langpacks/`:

- `main(args)` / `pack(assetsRoot, outRoot)` — reads `assets/i18n` and
  `assets/reference`, writes `<outRoot>/lang/<tag>.zip` for every `AppLocale.tag`
  plus `<outRoot>/lang/index.txt` (one sorted tag per line). Nothing under
  `assets/` is written.
- `sourceSuffixes(tag): List<String>` — the `I18NBundle` fall-through, most
  specific first, base last: `zh-HK` → `["zh_HK", "zh", ""]`.
- `pickLanguageBundle(suffixes, exists): String?` — the first candidate file
  name the predicate accepts (`zh-HK` → `ui_zh.properties`), else null.
- `resolveBundle(language, base): Map<String, String>` — every base key, the
  language value where declared, the English base value otherwise; sorted.
- `writeProperties(map): ByteArray` — sorted keys, `\n` endings, no comment or
  timestamp line, `\uXXXX` for non-ASCII, ISO-8859-1 (pure ASCII) bytes.
- `writePages(pages): ByteArray` — every body in one blob, sorted by path, each
  framed as `<relative-path>\n<utf8-byte-count>\n<bytes>` (the count, not a
  delimiter, keeps bodies unambiguous).
- `writePack(bundle, pages): ByteArray` — the single zip entry:
  `<bundle-byte-count>\n<bundle-bytes>` then [writePages]' output. `LanguagePack`
  inverts it with `parsePack`/`parsePages`.
- `writeZip(entries): ByteArray` — sorted ASCII entry names, deflated, fixed
  1980-01-01 local entry time (TZ-independent). A pack holds exactly one entry,
  `pack`.

The libGDX loader `I18NBundle.load(Reader)` actually calls
`PropertiesUtils.load(ObjectMap, Reader)` (there is no `(Reader, Properties)`
overload in gdx 1.14.2); the writer stays compatible with it.

### Stage 3 API (later stages depend on these names)

- `AppLocale.fromJavaLocale(locale): AppLocale?` — the picker entry whose
  `javaLocale` is exactly `locale`, or null for a locale the app does not ship.
- `LanguagePack.bundleBytes()` and `LanguagePack.readText(file)` are what the
  readers consume; `LanguagePack` still owns the pack and does no loading or
  formatting itself.
- `L10n` loads through `I18NBundle` only. With an explicit `bootstrap(base = …)`
  override it is unchanged (tests/dev). When `base` is null it prefers the open
  pack: for the requested locale's `AppLocale` entry it builds a private
  in-memory `FileHandle` over the pack's `bundle.properties` (`name()` is a
  stable base, `sibling(…)` returns itself, `read()`/`reader(enc)` serve the
  bytes) and passes it to `I18NBundle.createBundle(handle, locale)`. Otherwise
  it falls back to `i18n/ui` (`Gdx.files.internal`, or the headless `assets`
  walk). `findBundle(locale)` — used for the Reference strings — resolves the
  same way, so reference strings and bodies come from the same open pack.
- `ReferenceCatalog` takes a page body from `LanguagePack.readText(sp.file)` when
  `LanguagePack.isOpen(loc)` and from the `ReferenceAssetSource` otherwise;
  `structure.json` always comes from the asset source.
- `LocaleController.apply` calls `LanguagePack.ensure(locale.tag)` before it
  touches `L10n` or `ReferenceCatalog`. `referencePackExists` answers from
  `LanguagePack.availableTags()` when that index is non-empty and otherwise
  probes `reference/<tag>/about.md`. This index-vs-probe split is deliberate:
  the shipped `lang/index.txt` is a stage-4 artifact, so the folder probe keeps
  authoring and headless runs — and
  `L10nTest.referenceLocaleResolvesEveryShippedPack` — meaningful without the
  Gradle task.

**Deviation from the earlier sketch.** `I18NBundle.load(Reader)` is `protected`
but does not initialize the bundle's `TextFormatter`: only the private
`setLocale(Locale)` does, at the end of `createBundle`. A subclass that calls
`load(reader)` alone therefore NPEs in `format(key, args)` (used by
`menu.signed_in_as` and the reference counters). The public
`createBundle(baseHandle, locale)` path runs the real `load(Reader)` and then
`setLocale`, so Stage 3 uses it and reimplements no formatter and no
`PropertiesUtils`.

### Stage 4 API (staged trees and platform wiring)

Two `Sync` tasks in `core/build.gradle` publish the runnable trees (Sync, not
Copy, so a removed authoring file leaves the staged tree):

- `:core:stageAssets` → `core/build/staged-assets`. The startup set: every file
  under `assets/` except `reference/**/*.md`, `i18n/ui*.properties`, `assets.txt`,
  and any `lang/**`, plus the packer's `lang/index.txt`. No zips. This is the
  directory passed to `gdxTeaVM { assets(...) }`, so the manifest preloads the
  index and never a zip.
- `:core:stageRuntimeAssets` → `core/build/staged-runtime`: `staged-assets` plus
  `lang/<tag>.zip` from `core/build/langpacks/`. Desktop
  `sourceSets.main.resources` and the `run` task's `workingDir`
  point here; Android `assets.setSrcDirs` points here. Every consumer declares the
  producer: the run tasks depend on `:core:stageRuntimeAssets`, and
  `stageRunSnapshot` copies `:lwjgl3:processResources` as a task output, so the
  pack chain cannot fall out of the run graph.

The web side: `TeaLauncher` installs `LanguagePack.fetcher` from the
`WebAssetPreloadListener`'s loader
(`loader.loadAsset("lang/$tag.zip", AssetType.Binary, FileType.Internal, listener)`,
`onSuccess → onReady()`), and `copyTeavmWebExtras` copies the zips into
`build/dist/{js,wasm}/webapp/assets/lang/` after the manifest is baked.
`WebLanguagePackPreloadListener` (a `WebPreloadApplicationListener` subclass,
passed via `WebApplication(Main(), preloadListener, config)`) holds the progress
screen until the active pack opens: after `super.create()` it reads
`AppLocaleKey`, `addQueue()`s, registers `LanguagePack.addOpenedListener`, and
`ensure`s the tag; `subtractQueue()` runs when the pack opens or when
`assetLoader.isAssetFailed` reports the fetch failed (so the tab never hangs).
`Main.clearToFelt` calls `ensure(locale.tag)` before `L10n.bootstrap` — a no-op
on Wasm, the actual open on desktop/Android.
