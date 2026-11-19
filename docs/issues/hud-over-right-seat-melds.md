# The hand counter is painted over the Right seat's exposed melds

- **Status:** diagnosed; prototype implemented (see §11).
- **Reported:** 2026-11-19, desktop build, 16-hand free-play game.
- **Also affected:** online (the ping line under the counter), and the shutdown
  clock that shares its anchor.

Paths are under `core/src/main/kotlin/inego/zungjunggame/` unless stated. Line
numbers and symbols in §0–§10 describe the revision this note was written
against — `hudRightEdge` and `chromeScaleFor` have since been replaced; §11
records the prototype and its replacements.

---

## 0. What is observed

In the top-right corner of a **landscape** board (observed in a maximised
1920×1080 desktop window), the hand counter (`1/16`, from
`status.hand_of={0}/{1}` and `formatHandStatusLabel`) is drawn across the white
face of the Right seat's outermost meld and is almost unreadable. In online play
the ping line sits a few dp below it and has the same problem. The menu button in
the same corner appears to float to the right of the tiles, outside the hand.

Nothing is misplaced relative to its own anchor:

- the HUD is exactly where `renderHandStatusBadge` puts it,
- the meld is exactly where `PlayerAreaLayout` puts it,
- the button is exactly where `BoardMenuChrome.rect` puts it.

The defect is that the corner is budgeted three times by three different owners,
with two unrelated units of measurement, and the one existing piece of arbitration
between them is portrait-only.

---

## 1. In one paragraph

The Right seat's tiles occupy a vertical band whose outer face sits
`minGutter` from the window edge, and the seat's melds sit at the end of that band
that maps to **screen up**. A waiting hand — 13 concealed tiles plus the reserved
draw slot — is already wider than the edge, so the board (correctly) shrinks the
row to fill the edge exactly, which leaves the row's +x end, and therefore its
outermost meld, `alongEdgePad` below the top rim. Normally that end is a closed
tile back; with a meld it is an ivory face. The HUD is anchored to the **menu
button** (`button.left − 10 dp`), a fixed, density-scaled
inset from the window edge — about 60 dp — which on a 1080p landscape desktop is
strictly *inside* that tile band. The HUD is then painted after the board,
right-aligned into the tiles, using `Fonts.label`'s colour, which is
`AppPalette.seatInk`, a pale grey-green chosen to sit on the felt. Over an ivory
tile face the contrast falls from ≈4.1:1 to ≈1.4:1, and only the font's baked drop
shadow keeps the digits visible at all. `BoardMenuChrome.tableInsets`, the only
code that reserves the corner, deliberately does nothing in landscape.

---

## 2. Background the reader needs

### 2.0 The surface

A four-player Zung Jung mahjong game drawn as a 2-D table by a libGDX/OpenGL
core, hosted in an LWJGL3 desktop window (the same core also runs on Android and
in a browser). Seats are named relative to the human: **You** at the bottom, then
**Right**, **Opposite**, **Left**. Each seat owns a hand (faces up for the human,
backs for opponents), its exposed melds, and a discard river. The table paints in
`PlayTableView.render`; board chrome (the HUD) and the menu button are painted
after it by `BoardComponent.draw`.

### 2.1 A seat is laid out in its own frame, then rotated into place

`PlayerAreaLayout` lays out one seat in local coordinates: **+x along the seat
edge, in the direction of that player's right; +y toward the table centre**
(`PlayerAreaLayout.kt:15-27`). `SeatSide.localToWorld` then rotates the frame into
world space with two parameters: an edge inset (distance from the screen edge to
the row's centre line) and an along-edge centre.

For the Right seat (`SeatSide.kt:37-49`):

```
Right -> (screenW - edgeInset - ly) to (alongCenter + lx)
```

so **local +x maps to screen up**, and local +y maps to screen left. Left is the
mirror (its +x maps to screen down), and bottom/opposite are the horizontal rows.

### 2.2 Melds are at the +x end of the row

`PlayerAreaLayout.layout` composes the row as one centred block
(`PlayerAreaLayout.kt:329-456`):

```
visualHandW + bottomGap + meldsW            // total width
rightEdge = totalVisualW * 0.5f
xCursor   = rightEdge - meldsW              // melds occupy [rightEdge - meldsW, rightEdge]
```

Groups run in exposure order (`expandMelds`, `MeldGroups.kt:17-59`), so for the
Right seat the **most recently exposed meld is the topmost thing on the edge**,
with the hand at the bottom and the river inside the felt.

Every open meld tile is `open = true` (`MeldGroups.kt:140-168`); only the two
outer tiles of a concealed kong are closed (`MeldGroups.kt:129-135`). That is the
"white tile surface" in the report.

### 2.3 The along-edge budget is the whole edge

`PlayTableView.collectSeatSlots` asks `PlayerAreaLayout.availableAlongEdgeSpan`
for the seat's span (`PlayTableView.kt:1425-1465`). A left/right seat is on the
*short* side of a landscape window, so it gets the whole edge minus the pad
(`PlayerAreaLayout.kt:157-181`):

```
lo = alongFloor + pad
hi = screenAlong - pad
half = (hi - lo) / 2
centre = (lo + hi) / 2
```

with `pad = alongEdgePad = shortSide * 0.02f` (`PlayTableView.kt:1389`).

`layoutFittingAlongEdge` (`PlayerAreaLayout.kt:193-252`) shrinks the metrics **only
until the row fits that span**. It never asks a row to leave room for chrome;
a row that is too long ends up flush with both pads by construction.

### 2.4 Paint order

`BoardComponent.draw` (`BoardComponent.kt:284-318`):

1. `board.render(...)` — felt, tiles, seat labels;
2. `chrome.draw(...)` — the HUD, via `BoardChrome` in `AppGraph.kt:831-857`;
3. `menuButton.render(...)` — the menu plate and glyph.

The HUD is above the table deliberately. There is no scissor or knockout between
it and the tiles.

---

## 3. The three owners of the corner

| What | Owner | Anchor | Band |
|---|---|---|---|
| Right seat's tiles | `PlayerAreaLayout` + `availableAlongEdgeSpan` | the hand's size, proportional to the short side | gutter → gutter + row |
| Hand counter, ping, shutdown clock | `FreePlayPresenter.renderHandStatusBadge` + `BoardMenuChrome.hudRightEdge` | the menu button | right-aligned at `button.left − 10 dp` |
| Menu button | `BoardMenuChrome.rect` | the window corner | `14 dp … 14 + side dp` |
| Seat-wind label (not implicated) | `SeatLabelDrawer` | hand face ↔ window edge | the gutter, at the row's along-centre |

The seat-wind label is the control case: `SeatLabelDrawer.labelLocalY`
(`SeatLabelDrawer.kt:470-491`) centres it in the gutter between the hand's outer
face and the window edge, and the tiles never enter the gutter. That is why the
rotated `西 · Right · 0` label is readable even in the same corner column — it
lives in a band the tiles cannot occupy. The HUD lives in the tiles' band.

---

## 4. The numbers (1920 × 1080, `uiScale = 1`)

From `PlayTableView.computeTableLayout` (`PlayTableView.kt:1298-1392`),
`CloseButton` (`CloseButton.kt:146-155`) and `BoardMenuChrome`
(`BoardMenuChrome.kt:44-62`):

```
short            = 1080
handH            = 0.085 × short                   =  91.8
gutter           = 0.05 × handH + 0.045 × short    =  53.2   (from the right edge)
tile column      = 53.2 … 53.2 + handH  (≈145)
button           = 14 … 14 + side, side ≥ 36
hudRight         = button.left − 10               ≥  60
alongEdgePad     = 0.02 × short                    =  21.6   (from the top rim)
HUD text band    = 16 … ≈40 from the top
top tile box     = 21.6 … 21.6 + handH (≈113)
```

`side` is `max(px(36), button line height + px(14))`, so the figures below use
60 dp as the *minimum* HUD anchor; a taller button font only moves it further
into the tiles.

The gutter is exact, not approximate. On desktop `safe.right = 0`, and
`baseSide − handH/2` equals `minGutter` algebraically:

```
baseSide        = 0.55·handH + 0.045·short
minGutter       = 0.05·handH + 0.045·short
edgeInsetRight  = handH/2 + minGutter          (the two max() terms coincide)
outer face      = edgeInsetRight − handH/2 = minGutter
```

So the tile column starts 53.2 dp from the right edge, and the HUD's right edge
is at least 60 dp: the text is **at least 7 dp inside the tiles before it extends
a single glyph to the left**. On a 1.25× display, `Fonts.px` scales the button and
its gap while the table does not, moving the HUD anchor to ≈75 dp and deepening
the penetration. The same arithmetic on a touch profile (`buttonSide()` becomes at
least 44 dp, `hudRight` ≥ 68 dp) reproduces it on a tablet in landscape.

---

## 5. Why the row reaches the rim in every live state — and what changes with a meld

Along-edge width of the row, as a fraction of the short side, with
`handW = 0.75·handH = 0.06375·short`, `tileGap = 0.08·handW`,
`drawnGap = 0.55·handW`:

| Row | Width | Fits `0.96·short`? | Outermost element |
|---|---|---|---|
| 13 concealed + reserved draw slot (`13 % 3 == 1`) | ≈ **0.99·short** | no → slight shrink | closed back at 21.6 dp |
| 7 concealed + reserved slot + two 3-tile melds | ≈ **1.09·short** | no → shrink ≈0.88 | **open meld tile** at 21.6 dp |

Two things follow.

1. The row is **always flush with `alongEdgePad`** while a hand is live, with or
   without melds. The reserved draw slot (`PlayerAreaLayout.kt:329-343`) is
   counted by `alongRowHalfSpan`, so a waiting hand measures as a 14-slot row
   even before the tile arrives. The corner is never spare felt on the Right
   edge.
2. What a meld changes is not the row's length but **what sits at its +x end**:
   a closed (blue) back normally, an ivory face once the seat has an exposed
   meld. `PlayerAreaLayout` orders the meld block after the hand and the parked
   draw, so the exposed tile is exactly the one at the rim.

That is exactly the boundary the report describes: *"in case the Right's
rightmost meld — meaning the white tile surface is rendered"*. With no melds the
top of the row is a closed back and the counter stays readable against it; with
an open meld the top of the row is ivory and the counter is not. The reported
hand (7 concealed tiles, two melds) is the second case.

---

## 6. Why the glyphs disappear

`Fonts.label` is built as `generate(regular, 20, AppPalette.seatInk, …)`
(`Fonts.kt:682`). The palette (`AppPalette.kt:80,236,252`):

```
seatInk      = (0.78, 0.82, 0.74)   // pale grey-green, designed for the felt
felt         = (0.12, 0.42, 0.28)
tileFaceInk  = (0.961, 0.941, 0.922) // the ivory face
```

Relative-luminance contrast: **≈4.1:1 on felt, ≈1.4:1 on a face**. The draw call
passes `font.color` through unchanged (`FreePlayPresenter.kt:202-209`); nothing
in that path samples or reasons about the background.

What keeps the digits faintly visible is the drop shadow baked into every chrome
face (`Fonts.kt:887-917`, 1 dp, `AppPalette.shadow` at 55%). `tileLabel` is the
deliberate exception. So the digits read as grey ghosts on ivory, which is
exactly how they appear.

The ping line inherits the same anchor and the same `font.color` mechanism, with
`LatencyLabel.color` (green / gold / red) as an extra hue clash; the shutdown
clock uses `danger` / `warning` at the same anchor
(`FreePlayPresenter.kt:211-246`).

---

## 7. Why the menu button sits *outside* the tiles

`BoardMenuChrome.rect` places the button at `cornerInset = px(14)` and
`side = max(px(36), …)` (44 on touch) from the window corner — i.e. 14…50 dp and
out. The seat gutter is 0…≈53 dp. So the button is in the **gutter**, the same
band as the rotated seat label, not the band of the tiles; it is correctly outside
the hand.

What makes it look wrong is scale: at 1080p the gutter is 53 dp and the button is
at least 50 dp, so the button consumes essentially the whole gutter and the
nearest painted face is only a few dp away. The two numbers come from unrelated inputs — the
gutter from the short side, the button from the UI density — so their
relationship changes with window size and OS scaling. On a wider gutter the
button reads as chrome sitting in empty felt; here it reads as chrome hanging off
the hand.

Note the button is drawn last (`BoardComponent.kt:310-318`), so if the two bands
did overlap it would cover the tile, not the other way round.

---

## 8. Why the existing corner reservation does not catch it

`BoardMenuChrome.tableInsets` is the only code that tries to keep the corner
free (`BoardMenuChrome.kt:64-84`):

```kotlin
if (reserveCorner && height - safe.top - safe.bottom > width - safe.left - safe.right) {
    safe.copy(top = height - rect(width, height, safe).bottom)
} else {
    safe
}
```

The doc comment states the assumption: *"Portrait hands span the board's width,
so the corner is not spare felt. … Wide boards, and boards whose pane ends below
the menu, retain their existing corner space."*

In portrait the branch works — indirectly. The board's top inset becomes the
button's underside, which pushes the top hand's inner edge down, which pushes the
side seats' `nearEdge`/`farEdge` band down, which shortens the Right row. In
landscape the condition is false, `safe` is returned untouched, and the side seat
on the *short* side is budgeted the whole edge. On a wide board the corner is
**not** spare for the side hands; it is only spare for the opposite hand.

---

## 9. The general shape of the defect

This is an ownership gap, not a slip in one layout routine:

- **Three rulers for one rectangle.** Fixed dp (`board chrome`), short-side
  proportion (`PlayerAreaLayout`), and "the button minus a gap"
  (`hudRightEdge`). None of them derives from the others.
- **Published geometry is not consumed.** The table already publishes per-seat
  bounds (`SeatSlots.seatBounds`, `PlayTableView.kt:1404-1518`, fed to
  `ActiveSeatFelt`). It is measured from the placements themselves and would
  answer "is the corner occupied?" — the HUD never reads it.
- **A shared size rule without a shared placement rule.** The HUD deliberately
  adopts the table's label scale (`chromeScaleFor` → `SeatLabelDrawer.tableLabelScale`,
  documented as *"every fixed-size label on the felt shares this one coefficient"*),
  so it shrinks with the tiles but is still positioned from the button and
  coloured for the felt. It is simultaneously treated as felt chrome (size) and
  as panel chrome (position, colour).
- **The repo's own rules name the missing piece:** "Draw consumes published
  geometry" and "One source of truth per value"
  (`docs/engineering-principles.md:88-96`).
- **No test covers the corner.** Nothing under `core/src/test` references
  `BoardMenuChrome`, `hudRightEdge`, `tableInsets` or `renderHandStatusBadge`,
  so the collision has no way to fail the suite.

The defect is also aspect-dependent, which is why it reads as an intermittent
rendering glitch: portrait is safe by construction, and on a landscape window
whose short side is large relative to the density-scaled chrome (≈1220 dp at
1×, i.e. above 1440p) the HUD's 60 dp anchor falls outside the tile band by
arithmetic alone.

---

## 10. Scope

**Not claimed:** tile art, font rasterisation, z-order, or the menu button's hit
region. The HUD is meant to be above the board. The counter and ping render
exactly where their code says.

**Not observed, but the same rule implies it:** the Left seat's melds are at the
*bottom* of the left edge (its +x maps down), so any future chrome anchored at
the bottom-left corner would collide with them in the same way.

**A fix was out of scope for the diagnosis.** The question it left for the
reviewer was which component should own the corner — the seat row, the board
chrome, or a single published "occupied" rect that both read — and, whichever it
is, that the anchor, the font colour and the available space be derived from that
one owner. §11 records the prototype that followed, built on that rule.

---

## 11. Prototype implemented (2026-11-19)

The prototype applies the rule §10 arrives at: **the table publishes where the
tiles can go, and the chrome fits the remaining space.**

- **The HUD anchors to the published band, not the button.**
  `PlayTableView.feltChrome` returns the shared label scale and
  `rightBandInnerX`, the Right seat's *nominal* band inner face
  (`screenW − (edgeInsetRight + handH/2)`). The status line stops 10 dp short of
  it. The anchor derives from the layout constants, not from the fitted
  placements, so it cannot move when a chow is claimed.
- **The status is one line.** `FreePlayPresenter.renderHandStatusBadge` draws
  `Ping 42 ms  1/16`; while a shutdown is announced the clock takes the detail
  slot (`Restart 0:59  1/16`), in warning/danger colour. The counter keeps
  `Fonts.label` and `seatInk`; the detail keeps `Fonts.small` and its own colour,
  sharing the counter's baseline. The line is right-aligned as a whole with the
  counter last and flush against the anchor — the ping's digit count changes
  often, and putting it first keeps it from shifting the counter sideways. The
  detail is dropped only if it would reach the rule badge.
- **The button gets a corner reserve only where it really intrudes.**
  `BoardMenuChrome.footprint` publishes the square (`cornerInset + side`).
  `PlayTableView.computeTableLayout` compares it with the Right seat's gutter
  and, when the square crosses the column by more than 3 dp, feeds
  `topRightChromeReserve` into a new `alongCeiling` on
  `PlayerAreaLayout.availableAlongEdgeSpan`. The reserve lowers only the Right
  row's top endpoint; the bottom endpoint and the right-edge inset are
  unchanged. At 1920×1009 client (a maximized window on a 1080p display) the
  52 dp square lands 2.3 dp into a 49.7 dp gutter — inside the graze band — so
  the hand keeps its size. At 1216×938 and 1024×768 it reserves and fits the
  row into the shorter span.
- **Removed:** `BoardMenuChrome.hudRightEdge` and
  `PlayTableView.chromeScaleFor`; their jobs belong to `feltChrome`.

**Verified (desktop, this machine):** `:core:compileKotlin`,
`:core:compileTestKotlin`, `:core:test` and the architecture tests pass. A
running 16-hand free-play board was captured at client sizes 1920×1009,
1216×938 and 1024×768: in all three the counter sits on felt, clear of the Right
column and the Opposite row; the Right row keeps its size at 1920 and clears the
menu button at the two narrower sizes.

**Not verified:** the online detail (`Ping … ms`, `Restart …`) — and therefore the
one-line ordering between detail and counter, which needs a live server to
capture — touch landscape, the longest locale strings, and the device build.
Touch landscape is the case expected to exercise the largest corner reserve.

---

## Appendix A — verification checklist (code reading only)

Rows marked *(pre-fix)* describe the revision §0–§10 diagnosed; §11 records the
prototype that replaced them.

| Assertion | Where |
|---|---|
| Right seat's local +x maps to screen up | `SeatSide.localToWorld`, `Right ->` |
| melds occupy the row's +x end | `PlayerAreaLayout.layout`, `rightEdge - meldsW` |
| a side seat on the short side gets the whole edge, centred | `availableAlongEdgeSpan` |
| an over-long row is scaled until it is flush with the pads | `layoutFittingAlongEdge` |
| `pad` is 2% of the short side | `PlayTableView.kt:1389` |
| *(pre-fix)* the HUD's right edge is the button's left minus 10 dp | `BoardMenuChrome.hudRightEdge` |
| the button is at least 14 dp + 36 dp (44 dp on touch) from the corner | `BoardMenuChrome.rect`, `buttonSide` |
| the gutter is `0.05·handH + 0.045·short` | `computeTableLayout`, `minGutter` |
| the HUD is right-aligned to the band, top-padded by 16 dp | `renderHandStatusBadge` |
| the HUD is painted after the board, the button after both | `BoardComponent.draw` |
| the label colour is `AppPalette.seatInk` | `Fonts.kt:682` |
| the corner reservation is portrait-only | `BoardMenuChrome.tableInsets` |

## Appendix B — files

| File | Role in this issue |
|---|---|
| `render/seats/PlayerAreaLayout.kt` | seat row: hand, parked draw, melds; the +x meld end |
| `render/seats/SeatSide.kt` | local → world; Right's +x is up |
| `render/table/PlayTableView.kt` | tile metrics, edge insets, along-edge span, published felt band, paint order |
| `render/seats/SeatLabelDrawer.kt` | the gutter label (the control case) |
| `features/board/BoardMenuChrome.kt` | button rect, `footprint`, portrait-only `tableInsets` |
| `features/board/BoardComponent.kt` | draw order: board → chrome → button |
| `features/board/FreePlayPresenter.kt` | the HUD: counter, ping, shutdown clock |
| `app/AppGraph.kt` | wires `BoardChrome` to the HUD |
| `session/table/MeldGroups.kt` | meld order and which tiles are open |
| `session/snapshots/HandStatusLabel.kt` | `N/M` formatting |
| `render/fonts/Fonts.kt` | label face, colour, baked shadow; `px()` |
| `render/ui/AppPalette.kt` | `seatInk`, `felt`, `tileFaceInk` |
| `render/ui/CloseButton.kt` | `cornerInset`, `defaultSide` |
| `assets/i18n/ui.properties` | `status.hand_of={0}/{1}` |

## Appendix C — glossary

| Term | Meaning here |
|---|---|
| seat | one player's area of the table: hand, drawn tile, melds, discards |
| meld | an exposed set or chow claimed from a discard (or a declared kong) |
| river | a seat's discards, inside the felt |
| gutter | the band between a hand's outer face and the screen edge; holds the seat label |
| dp | design pixel; `Fonts.px(n) = n × uiScale` (UI density) |
| short side | `min(window width, window height)`; tile sizes are fractions of it |
| `alongEdgePad` | 2% of the short side; the minimum clearance at each end of a seat edge |
| chrome scale | the shared felt-label scale, published by `PlayTableView.feltChrome` |
