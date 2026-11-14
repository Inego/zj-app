# App icons (logo)

How the **application** icon is produced. For in-game chrome icons (menus,
reference UI) see [`ui-icons.md`](ui-icons.md) — that is a different set with a
different pipeline and a different licence.

## One render in, every platform out

The icon set is not hand-made per platform. A single rendered mahjong tile is
the only input, and [`resources/generate_icons.py`](../resources/generate_icons.py)
derives everything from it:

```
py -3 resources/generate_icons.py --source <path-to-mahjong-render>
```

The render is produced by the private `zj-server` work's tile-rendering helper
(Blender + a Rust stroke helper); that repository's README explains it.
`render/mahjong.png` there is the chosen picture and the only hand-made input.

Regenerating after a re-render is therefore one command. Do not hand-edit any
file listed below; they are outputs.

| Output | Target |
|---|---|
| `resources/icon-source.png` | 1024 master, desktop framing |
| `lwjgl3/icons/logo.png`, `.ico`, `.icns` | Desktop (jpackage: Win + Mac) |
| `lwjgl3/src/main/resources/icon{16,32,64,128}.png` | Desktop window / taskbar icon |
| `teavm/webapp/favicon.ico` | Wasm browser tab |
| `android/res/drawable-nodpi/ic_splash.png` | Android cold-start splash |
| `android/res/mipmap-*/ic_launcher_foreground.webp` | Adaptive launcher icon |
| `android/res/mipmap-*/ic_launcher_monochrome.webp` | Themed icon (Android 13+) |
| `android/res/mipmap-*/ic_launcher.webp`, `_round.webp` | Legacy plate (pre-26) |
| `android/ic_launcher-playstore.png` | Store listing art, uploaded by hand |

`android/ic_launcher-playstore.png` sits outside `res/` deliberately: it is not
packaged, and the store console is the only consumer.

## Why the framing differs per target

Each target gets the tile sized for the room it actually has. One crop for all
of them is wrong in both directions — too tight for a masked launcher icon,
too loose for a taskbar.

| Target | Tile height as a share of the canvas |
|---|---|
| Desktop `.png` / `.ico` / `.icns`, favicon | 0.879 — no mask, so fill the space |
| Android adaptive foreground | 0.480 — sized to the **66dp guaranteed-safe circle** |
| Android splash | 0.511 |
| Legacy / store plate | 0.762 / 0.781 of the *board*, not the canvas |

The adaptive foreground figure is the one that looks arbitrary and is not: an
adaptive icon is authored on a 108dp canvas but a third-party launcher may mask
as small as a 66dp circle, so the art is fitted to **that** circle. Sized to the
canvas instead, a rounded-square or circular launcher mask clips the glyph.

The tile's aspect is never distorted to fit; every placement is a uniform scale.

## Desktop has two separate icons

Updating `lwjgl3/icons/logo.ico` is **not** enough to change what the running
app looks like:

- `lwjgl3/icons/logo.ico` — baked into the `.exe` / `.app` by jpackage. This is
  the Explorer / Finder icon.
- `lwjgl3/src/main/resources/icon{16,32,64,128}.png` — loaded at runtime by
  `Lwjgl3Launcher.setWindowIcon(...)`. This is the window and **taskbar** icon.

A Desktop run that shows the old art is almost always the second set having
been missed. Both are generated.

## Two details that are easy to get wrong

**Alpha is premultiplied while resampling.** The tile is bright ivory next to a
near-black glyph. A plain RGBA Lanczos blends ivory into the glyph edge and
leaves a pale halo, which is invisible at 256px and obvious at 16px.
`resize_pm` premultiplies, resizes, then unpremultiplies.

**The themed icon is a flat silhouette.** `ic_launcher_monochrome` is solid
black with the shape carried entirely in the alpha channel, because Android
tints that layer with the user's palette. It is also written as **lossless**
WebP — a lossy encode adds colour noise to a layer whose colour is discarded.
Both adaptive XMLs point `<monochrome>` at this file, not at the colour
foreground.

## Legacy plates

`ic_launcher.webp` / `ic_launcher_round.webp` are pre-API-26 only: `minSdk` is
28, so `mipmap-anydpi-v26` always wins and these are never displayed on a
supported device. They are still regenerated so the tree never carries a stale
brand. Their felt board and grid were reconstructed from the parameters
measured off the previous set (`BOARD_RGB`, `GRID_*`, `PLATE_INSET` in the
script), so they are not a byte-for-byte match.

The round plate sits closer to the canvas edge than the square one, so the tile
reads larger under a round mask — that asymmetry is intentional and was
measured, not guessed.

## Verifying

After regenerating, check the containers rather than eyeballing them:

* `.ico` — entry count and sizes; PNG-compressed entries (Vista+ and all browsers).
* `.icns` — `ic07`/`ic08`/`ic09`/`ic10` present, and the declared length equals
  the file length.
* Adaptive foreground — every corner of the tile inside the 66dp safe circle.
* Monochrome — taller than wide, single flat colour, inside the safe circle.

Then build the targets that consume them:

```
.\gradlew.bat :android:assembleDebug
.\gradlew.bat :teavm:buildWasm -PapiBaseUrl=
.\gradlew.bat :lwjgl3:packageWinX64
```

`:teavm:build` also runs `copyTeavmWebExtras` (it is `finalizedBy` it, alongside
the `gdx_teavm_web_*_build` tasks), so the webapp copy of `favicon.ico` is
refreshed on that path too. It still does not *compile* the Wasm dist — the
TeaVM compile tasks are not part of `assemble` — so `buildWasm` remains what
produces the dist, and it is also what `deploy-web-client.ps1` runs.
