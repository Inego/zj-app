# Text in space — fitting variable-length strings

Every string in this UI can grow: a translation, a stored alias, a five-digit
rating, a wall count crossing 99, a CJK name, a narrower phone. This page is the
map of how the UI answers that growth. It is orientation, not law: the binding
rules are [`AGENTS.md`](../../AGENTS.md) and [`principles.md`](principles.md).
This page names the mechanism that owns each answer, the inputs a width may be
derived from, and the places where the model is not yet uniform.

Paths are under `core/src/main/kotlin/inego/zungjunggame/` unless stated.

> **In one paragraph.** Measure with the face that paints. Shrink a one-line
> control caption to a 0.6 floor, and when it is still too wide there, change
> the layout rather than the floor; ellipsize an identifier; wrap prose and
> scroll it; reserve a digit column for a number; scroll editable text
> horizontally around the caret. Derive every slot from the safe box, the
> scrollbar gutter, or a policy limit — never from the rows in hand.

---

## 0. The model

```
measure with the face pair that will paint
        │
        ├─ one-line identifier ────────▶ cut          (TextFit.ellipsize)
        ├─ one-line control caption ───▶ fit          (FittedLabel, floor 0.6)
        ├─ prose ──────────────────────▶ wrap → scroll
        ├─ number ─────────────────────▶ fixed digit budget
        ├─ table cell ─────────────────▶ fixed column + cut or wrap
        ├─ editable value ─────────────▶ horizontal scroll around the caret
        └─ tag on a tile ──────────────▶ proportional fit
```

Two **intended** invariants span the moves below; §7 lists the places where
the code does not yet meet them:

1. **Measure the ink where it will land.** The width used for layout and the
   face used for paint are the same primary + fallback pair. A fit multiplies
   both faces and the geometry by one scale
   (`render/fonts/Fonts.kt:548-601`). Violated today by raw paints and by
   rich-text line boxes (§7.1, §7.2).
2. **Derive the slot from an input that cannot move while the surface is open.**
   A body width is the panel minus its insets minus the scrollbar strip; a
   column is a digit budget, a snapshot total or an alias-policy budget, never
   the rows currently in hand.
   Re-measure on a panel resize, a locale/script change, or a change in the
   data's own declared bound — not on paging, scrolling or fetching
   (`AGENTS.md:50-55`, `render/ui/OverlayBodyScroll.kt:105-118`). Violated by
   the three row-measured tables (§7.4).

Different surfaces need not share one move. What should be uniform is that every
surface has a **width source**, a **legible outcome when that width is
insufficient**, and **paint and interaction geometry derived from the same
layout**.

---

## 1. The toolbox

| Move | Primitive | Contract | Used for |
|---|---|---|---|
| Measure | `Fonts.textWidth` / `textHeight` / `drawText` | Per-code-unit split into primary and fallback runs, summed; `drawText` mirrors the measure | Everything that paints outside rich text |
| Measure (wrapped) | `Fonts.wrappedHeight` / `drawWrapped` | One face for the whole block — primary when it covers it, else the fallback, else whichever covers more with its own gaps boxed — so wrap and ink agree | Status copy over the felt |
| Measure (doc) | `GdxTextMeasurer` | Adapts the same faces to `RichTextLayout`'s `TextMeasurer` | Reference, notices, coach |
| Fit | `Fonts.fitScale` + `Fonts.withFaceScale` | Shrink uniformly, never above 1, clamp at `MIN_FIT_SCALE = 0.6` | Buttons, plaque rows, banners, chart labels |
| Fit (one line) | `FittedLabel.draw` | Measure → fit → re-measure within `withFaceScale`, then paint; left or centred | Plate captions, setup rows |
| Cut | `TextFit.ellipsize` | Single line, U+2026, binary search, code-point boundary, `""` when nothing fits | Names, page titles, identity lines |
| Wrap | `LineBreaker` / `RichTextLayout` | Whitespace + CJK + kinsoku break opportunities; greedy fill to width | Documents, settings rows, hub prose |
| Scroll | `OverlayBodyScroll`, `VerticalScroll`, `RichTextView` | Clip a viewport, paint a scrollbar, reserve its strip always | Any body that can outgrow its card |
| Scroll (input) | `OverlayTextField` | Horizontal window that follows the caret | The alias editor |
| Clip | `ScissorStack` | Cut paint at a rect | Lists and tables — a safety net, not a fitting strategy |

### 1a. Measurement is fallback-aware

`Fonts.textWidth` scans the string, lazily opens the fallback face when a
character needs it, then delegates to `FallbackText`, which walks UTF-16 code
units, picks primary or fallback per glyph, and sums the runs
(`render/fonts/Fonts.kt:446-472`, `render/fonts/FallbackText.kt:30-55`).
Cross-face kerning is deliberately dropped (`FallbackText.kt:12-14`).

- The fallback is **direction-flipped by locale**: Roboto fills Latin for a CJK
  primary; a CJK face fills Han for a Latin primary
  (`render/fonts/FontFaceResolver.kt:75-104`, `render/fonts/ScriptProfile.kt:13-29`).
- A grapheme cluster neither face can draw becomes **one substitute glyph** before
  measure or paint — `□` when a face has it, else `�`, else `?` — so a name can
  never hold an invisible zero-width character (`FallbackText.kt`, §5). One box
  per cluster, so a flag or an emoji sequence is a single stand-in.
- Height uses the whole-string fallback line box, so one Han character in a
  Latin run adopts the CJK line height (`FallbackText.kt:57-64`).
- A **wrapped block** takes one face for the whole block instead: wrapping is
  per glyph, so a paragraph cannot change faces mid-line. `Fonts.wrappedHeight`
  / `Fonts.drawWrapped` use the primary when it covers every character, else
  the fallback (which carries Latin beside Han). When neither covers the block,
  the face that covers more of it paints the block and boxes the clusters *that
  face* cannot draw — substituting against "either face" would leave the chosen
  face painting tofu for the other face's script (`FallbackText.block`).
- Design pixels become world pixels through `uiScale = density` (phone web
  ×`160/96`), clamped to `[1, 3]`; faces are rasterized at
  `designSize × uiScale` (`Fonts.kt:743-751`, `:873`).

A raw `BitmapFont.draw`, or a private `GlyphLayout.setText`, bypasses this
split. It measures or paints the primary alone — correct for a fixed tag,
wrong for a name or a translation — and it does not get the substitute glyph
either, so an undrawable character there is still invisible (§7.1).

### 1b. Fit has a readability floor, not a fit guarantee

```
fitScale(naturalW, availableW, min = 0.6)
    = 1f                         if it already fits or a slot is degenerate
    = max(available / natural, min)   otherwise
```

`withFaceScale` runs a block with the primary **and its paired fallback**
multiplied by the same scale and restores both in `finally`
(`Fonts.kt:565-601`). `FittedLabel.draw` is the one-line package: it measures,
fits, re-measures the ink inside the scaled face, centres or left-aligns it, and
paints (`render/ui/FittedLabel.kt:42-66`).

The floor is intentional: past 0.6 the run **overflows its slot** rather than
shrinking into illegibility (`Fonts.kt:548-570`, `FittedLabel.kt:8-19`). A
caller that hands `FittedLabel` a multi-line string will paint it as one line.

### 1c. Cut removes the tail, never the line

`TextFit.ellipsize` returns:

- `""` for empty text, `maxWidth <= 0`, or a slot too narrow for the ellipsis;
- the text unchanged when it fits;
- `text[0, end).trimEnd() + "…"` for the widest prefix that fits, snapping down
  off a low surrogate so a supplementary code point is not split.

It has no newline handling and does not wrap (`render/ui/TextFit.kt:10-40`).
Cut only what may lose its tail: a player name, a page title, an identity line.
A control caption that loses its tail stops being a control.

### 1d. Wrapping needs a break opportunity

`LineBreaker` is the document-grade breaker: whitespace runs collapse, CJK is
one token per character (surrogate-aware), opening punctuation glues forward,
closing punctuation glues back, and a hyphen followed by text allows a soft
break (`render/fonts/LineBreaker.kt:20-141`). `wrap` preserves hard `\n`
(`:147-184`). `RichTextLayout` turns spans into atoms and fills lines greedily to
the column width, dropping leading/trailing spaces and aligning each line
(`render/richtext/RichTextLayout.kt:352-464`).

Overlay and table code that does not use a document often calls
`GlyphLayout(wrap = true)` directly. That wraps correctly for one face but not
for mixed script; if you use it, reserve the measured height and paint the same
width you measured (`features/settings/SettingsOverlay.kt:544-550`,
`features/online/hub/LiveTabPanel.kt:531-560`).

**No breaker can split an atom.** A long URL, an unhyphenated German compound,
a three-digit list marker or a wide tile run is placed on its own line and
overflows (`RichTextLayout.kt:403-409`).

### 1e. Scroll is the answer for content, not for labels

`OverlayViewport` fits sticky chrome plus a hugging body, then
`OverlayBodyScroll` clips the body and paints a scrollbar
(`render/ui/OverlayViewport.kt:113-134`, `render/ui/OverlayBodyScroll.kt:131-194`).
The body's width is computed with the scrollbar strip **always** subtracted, so
prose cannot re-wrap the moment the bar appears
(`OverlayBodyScroll.kt:105-118`).

Use scroll when the content itself matters and may be arbitrarily long
(reference articles, notices, coach copy). Use fit or cut for a one-line label;
scrolling a label is a symptom of a missing move.

### 1f. Clip is not a fitting strategy

`ScissorStack` is used by lists, tables and rich-text hosts to keep paint inside
a viewport (`render/ui/ScrollableItemList.kt:372-386`,
`render/richtext/RichTextView.kt:270-304`). It hides the symptom. A label that
reaches the scissor without being fitted or cut has already failed — the user
sees a word cut mid-glyph, with no ellipsis.

---

## 2. Where the width comes from

### 2a. Cards

Every card built on `OverlayViewport` starts from the safe inner area minus a
12 dp margin (`render/ui/OverlayViewport.kt:35-51`). Width is then:

```kotlin
max(1f, min(desiredFraction, available.width, screenW - minGap))
```

so the fraction is the *desire*, the safe box the truth
(`OverlayViewport.kt:183-184`). A card hugs its chrome and body, then is clamped
and centred (`OverlayViewport.kt:53-63`). The common caps are 0.88 portrait /
0.72 landscape for menu, settings and language; 0.90 / 0.72 for setup; the hub
caps landscape at 560 dp (`features/menu/GameMenuOverlay.kt:611-616`,
`features/settings/SettingsOverlay.kt:527-533`,
`features/online/hub/OnlineHubOverlay.kt:583-587`).

A body measured through `OverlayBodyScroll.measureWidth` gets
`panelW - insetX - 12` on **every** frame, whether or not it is overflowing
(`OverlayBodyScroll.kt:105-118`). This is the single most important width
invariant in the UI: it makes the wrap width independent of the overflow state.
`fitSticky` keeps chrome fixed above a clipped body (tables, history);
`fitWholeCard` scrolls the whole column and insets the viewport by the rim
(settings, the hub) (`OverlayViewport.kt:113-180`).

### 2b. Tables

A table's columns are sized from what cannot change mid-view:

- a **digit budget** for every numeric column, measured from a repeated-digit
  sample (`"0000"`, `"-9999"`, `"99"`);
- a **policy budget** for a name (`AliasPolicy.STORED_GRAPHEMES = 24`, measured
  as the wider of 24 `W` and 12 `中`; a display budget, not proof that every
  legal alias fits — §7.4);
- a **snapshot bound** for a place (`digits(total)`), not the page in hand;
- the **header**, measured into the same column so translation widens the column
  instead of clipping the word — unless the panel cannot hold the widened table
  (§7.4).

The leaderboard is the reference implementation for stable measurement: it
measures with `Float.MAX_VALUE` so the card cannot feed back into its own
measurement, sizes the place column from the snapshot total, and centres both
the card and the grid
(`features/leaderboard/LeaderboardOverlay.kt:335-351`, `:403-408`, `:780-812`).
Match history states the same rule in words: the loaded pages are deliberately
not an input (`features/history/MatchHistoryOverlay.kt:347-365`).

The numeric anchors are **budgets, not bounds**. The leaderboard's rating column
is a four-digit sample and its count columns three, because the client knows no
server cap; a value wider than the sample — a five-digit rating, for example —
is right-aligned into a cell that cannot hold it
(`LeaderboardOverlay.kt:821-833`). `RatingChangesTable` and `ScoreBreakdownLayout`
instead measure the loaded rows and floor at `"0000"` / `"+0000"`, which adapts
to the values on screen but makes the sample a minimum, not a proof
(`features/score/RatingChangesTable.kt:90-94`,
`features/score/ScoreBreakdownLayout.kt:357-361`). A budget is a display
decision; a bound is a proof (§7.4).

**The accepted exceptions:** the hands table lets Patterns absorb the slack when
the table is narrower than the 320 dp panel floor (`render/ui/HandsTable.kt:228-236`);
the score breakdown lets a name column absorb slack so numeric columns keep the
card's right edge (`features/score/ScoreBreakdownLayout.kt:738-749`). Both are
deliberate, and neither is permission for a generic column to fill a panel:
numbers need an edge, prose does not.

### 2c. Buttons and rows

- A plate hugs its label with a fixed pad, then a floor
  (`OverlayChrome.drawButton`, `render/ui/OverlayChrome.kt:83-149`).
- A dock sizes each button from `label + 48`, then falls back to an equal share
  when the row cannot fit. The plate's height comes from one line plus padding,
  but the caption may wrap: a longer translation has no reserved height
  (`tutorial/slide/layout/SlideChromeLayout.kt:156-196`,
  `features/tutorial/TableChip.kt:81-94`; §7.3).
- A row with a control wraps its label and grows its height when the label
  cannot fit the slot at the readability floor
  (`SettingsOverlay.kt:544-550`, the chicken row in
  `features/local/NewGameSetupOverlay.kt`). The hub's cycle row shrinks the
  label but never the value (`features/online/hub/HubDraw.kt:188-208`).

### 2d. Cached measurements

A cached width or height must key on every input that can change the answer:

- **available width** — a panel resize, not a scroll;
- **locale / copy revision** — a translated header, status or rule line;
- **font-metrics revision** — `Fonts.uiScale`, or a face rebuild;
- **the declared data bound** — the snapshot total, not the page in hand.

The keys are independent. Two locales in the same script profile (en ↔ ru)
change copy without rebuilding a face (`Fonts.kt:190-200`), so "same face" does
not mean "same text"; a face rebuild changes metrics without new copy. Paging is
never a key.

Match history keys on `Fonts.uiScale` and `L10n.currentLocale`; its widths come
from formatter bounds, never the loaded pages
(`features/history/MatchHistoryOverlay.kt:354-365`). `HandsTable` keys on the
document list, width and `uiScale` but not the locale
(`render/ui/HandsTable.kt:182-199`); the shadow-attempts table keys on the
attempts and `uiScale` while measuring localized rules and status lines
(`features/challenges/ShadowRankedAttemptsOverlay.kt:382-418`). A cache that
survives a language switch with stale headers is the same defect as a column
that moves on page two (§7.8).

---

## 3. The moves by surface

This section records today's behavior. A move that is a gap is called out in §7
rather than normalized here; the matrix is a map, not a prescription.

### 3a. Chrome and cards

| Surface | String | Move | Width anchor |
|---|---|---|---|
| Modal buttons | caption | fit to 0.6 | plate width |
| Settings row | label | wrap | fixed body width |
| New-game row | label | wrap and grow the row | row slot |
| Menu identity | alias + rating | ellipsis the alias, keep the rating; whole-string ellipsis as last resort | badge budget |
| Hub header | alias, latency, rating | ellipsis alias after latency; rating fit into a 52 dp cell | live values |
| Hub body | prose | wrap | body width with gutter |
| Quiz dock | button caption | natural width; equal share if the row overflows; caption may wrap | row width |
| Card title | title | **raw** (no fit) | natural width |
| Menu / TOC row | label | **raw** | panel / viewport |
| Notification body | rich document | wrap + scroll | viewport minus gutter |

The menu identity line is the only place where one string is split into "the
part that may be cut" and "the part that must survive": ellipsize the alias to
`maxWidth - ratingWidth`, rebuild `"name - rating"`, and only cut the rating when
even the suffix cannot fit (`GameMenuOverlay.kt:1295-1318`).

### 3b. Tables, documents, felt

| Surface | Width source | Overflow | Re-measures when |
|---|---|---|---|
| Leaderboard | snapshot total + digit budgets + alias budget | name ellipsized; numbers right-aligned in fixed cells | never mid-view |
| Match history | formatter bounds + locale | none by design; measurement is exhaustive | never mid-view |
| Hands table | all docs in hand + headers + longest token | Patterns wraps; whole table zooms | a new doc adds a wider token |
| Score breakdown | frozen hand-end snapshot | name absorbs slack; compact title fits/cuts | not within a hand |
| Shadow attempts | loaded attempts | status/score wrap; first lines raw | a page arrives |
| Shadow comparison | current comparison rows + header | caption wraps; columns scale when capped | rows change |
| Reference / notices | viewport | wrap, then scroll | never mid-view |
| Coach / quiz copy | copy band | wrap, then shrink to fit the band | never mid-view |
| Seat label (bottom) | screen span minus safe edges | alias ellipsized; wind/score kept | every frame |
| Seat label (side/opposite) | gutter height only | gutter scale; **no horizontal budget** | every frame |
| Turn banner | 42 % of screen width | wrap, then height-fit to 0.6 | every frame |
| Felt stamp | safe width | credit wraps via `LineBreaker` | every frame |
| Tile tag | tile fraction | proportional fit; dropped below 3 px ink | every tile |

Numbers are deliberately locale-free: ASCII digits, `+`, U+2212 by hand, and no
grouping, so a rating never widens with a locale
(`net/ShadowRankedFormat.kt:91-120`, `render/ui/HandsTable.kt:75`). The one
deviation in wording is the score breakdown's `"..."` truncation
(`features/score/ScoreBreakdownPainter.kt:218-227`); every other cut uses `…`.

### 3c. Text and hit geometry

A caption may shrink or be cut; the control does not. A plate button's tap
target is its plate rect, not its ink: the caller publishes the same rect it
hands the painter (`features/tutorial/QuizActionDock.kt:152-158`;
`render/ui/OverlayChrome.kt:83-149` draws the plate). Inside a scrolled body,
published control rects are intersected with the card viewport, so a tab pushed
under the rim stops being tappable (`features/online/hub/OnlineHubComponent.kt:121-134`).
A list row is resolved by stride from the scroll offset, not by measuring the
painted label, so a label clipped at the viewport edge is still tappable on its
row (`render/ui/ScrollableItemList.kt:258-275`).

---

## 4. Script, locale and translation length

There are 18 complete bundles (`assets/i18n/ui*.properties`, 702 keys) and no
length budget anywhere — not in the translator guide, not in the packer, not in
the bundle tests. A missing key returns the key text and is logged once
(`i18n/L10n.kt:207-216`); every base key is present in every pack today. The
only length policy in the app is for player names
(`net/AliasPolicy.kt:21-40`).

Everything else is fit at paint. That works because:

- **Script drives the face, not the key.** `ScriptProfile` gives Latin, Cyrillic
  and Greek a Roboto primary, and `zh`/`ja` a CJK primary with Roboto as
  fallback; a locale switch rebuilds faces only when the profile changes
  (`render/fonts/ScriptProfile.kt:13-29`, `Fonts.kt:194-200`).
- **CJK is not "three times shorter".** A Han glyph is full-width — two
  East-Asian columns against one Latin column — while the CJK face is generated
  at the same design size (`net/AliasPolicy.kt:95-111`, `Fonts.kt:653-665`). A
  Chinese string is fewer characters, but each one spends about twice the
  advance of a Latin glyph, so the character-count saving overstates the room.
- **Translation growth is real and measurable.** Over 540 shared keys the
  average character count is ×1.16 in German (max ×3.25), ×1.20 in Finnish
  (max ×3.00), ×1.08 in Russian (max ×2.83), and ×0.37 in Chinese — the last is
  the character saving, not the ink saving. `settings.tile_labels` grows
  16 → 28 characters in German; `shadowRanked.description` 245 → 294 in German,
  271 in Finnish.

Use `python tools/i18n/width.py` before choosing a slot. It measures the real
advance widths of the shipped Roboto/NotoSansSC stack, strips rich-text markup
that occupies no ink, and reports em fractions (multiply by the face's design
px). `--all` ranks keys by robust z-score, which is how to find the outlier
translation rather than the longest source string.

Examples from that audit:

- `setup.chicken`: English 10.66 em; Greek 19.71 em (×1.85). At the `button`
  face (18 design px) that is 192 px against 355 px. The row now measures a
  wrapped caption before it lays out the panel, so the Greek string wraps and
  the row grows instead of shrinking past the floor.
- `menu.title`: Serbian 10.29 em. At the 28 px title face that is 288 design px;
  a 320 dp card's inner width is smaller, and the title is drawn raw, so it
  clips at the rim.

Two script limits are unfixed and worth knowing before designing a slot:

- **No RTL.** No bidi reordering and no RTL locale; alignment is only
  left/centre/right (`i18n/AppLocale.kt:19-133`, `RichTextLayout.kt:417-421`).
- **Cutting is grapheme-blind; painting is not.** `TextFit.ellipsize` snaps to
  code-point boundaries, not grapheme clusters, so a base + combining mark can
  still be cut apart (`TextFit.kt:31-40`). The painter no longer judges an
  astral glyph by its surrogate halves: `FallbackText` replaces a whole grapheme
  cluster with one substitute box when any of its code points is undrawable,
  and an astral code point is never drawable by the BMP-only layout
  (`FallbackText.kt:187-206`). Editing sits on `Graphemes` boundaries
  throughout: carets, selections, deletions and the visible slice (§5).

Locale is also a cache key, and it is not the same key as a face rebuild.
`Fonts.applyLocale` disposes and rebuilds faces only when the script profile
changes — en ↔ ru keeps the same faces and swaps the copy — and it notifies no
measurement (`Fonts.kt:190-200`). Any cache that survives either event must key
on `L10n.currentLocale` and `Fonts.uiScale`; §2d lists the full set. Match
history and the tutorial coach do (`MatchHistoryOverlay.kt:354-365`,
`features/tutorial/TutorialCoachOverlay.kt:737-751`).

---

## 5. Editable text is a separate contract

A field holds the only live, authoritative string outside the model:
`OverlayTextBuffer` owns text, caret and selection privately
(`render/ui/OverlayTextBuffer.kt`). Its rules differ from painted text:

- **Never shrink or ellipsize the value being edited.** The field scrolls
  horizontally: `viewStart` is walked so the caret sits one pad inside the right
  edge, then the visible slice is cut at grapheme boundaries by measured width,
  not character count (`render/ui/OverlayTextField.kt`). The window's
  `start`/`end` are UTF-16 indices, but always on a `Graphemes` boundary, so a
  surrogate pair or a base-and-mark cluster is never split.
- **Caret, selection and deletion move by grapheme; storage counts UTF-16.**
  `Graphemes` is the shared boundary walker (`text/Graphemes.kt`):
  `moveLeft`/`moveRight`, `backspace`/`deleteForward` and word jumps stop on a
  cluster edge, while `MAX_ALIAS`/`MAX_SPECIAL_KEY` still bound the stored
  units. A caret set into the middle of a cluster snaps to its start.
- **Typed input carries the code point it was typed with.** Desktop replaces
  libGDX's character callback (`lwjgl3/src/main/kotlin/inego/zungjunggame/lwjgl3/CodepointInput.kt`)
  because the default casts every GLFW code point to `char`: 😊 (U+1F60A)
  becomes the private-use U+F60A, and a supplementary Han code point can become
  a *different* BMP character. The full code point is split into UTF-16 units on
  the render thread. Where a backend hands over UTF-16 units one at a time, the
  buffer holds a high surrogate until its low half arrives, and any other action
  abandons it rather than storing half a code point.
- **Private use never enters a value.** `TextEdit` drops every cluster that
  contains a private-use code point before the cap or the policy sees it
  (`text/TextEdit.kt`). No face can draw one, and a truncated code point is the
  only way such a character reaches the field. A value that already holds one
  still opens whole and can be shortened.
- **Length is policed in two currencies.** Storage counts UTF-16 units
  (`MAX_ALIAS = 96`, `MAX_SPECIAL_KEY = 128`, `net/AuthModels.kt`);
  policy counts graphemes and East-Asian columns (write: 2–16 graphemes, ≤16
  columns; read: 24 stored graphemes, `net/AliasPolicy.kt`). There is no
  font-width admission rule: the editor scrolls, so a legal string wider than
  the plate stays legal.
- **A stored long name may be shortened but not grown.** `allowsEdit` accepts
  edits inside the write limits, and outside them only edits that add neither a
  grapheme nor a column (`AliasPolicy.kt`). `TextEdit.replacement`
  (`text/TextEdit.kt`) holds an already-over-cap value at its current length,
  cuts the incoming text at a grapheme boundary, and then asks `allowsEdit`, so
  shortening a legacy name is never refused merely because the result is still
  over the cap. A native edit range that starts or ends inside a cluster is
  refused too, so a field that reports a deletion in UTF-16 units cannot split a
  pair or detach a mark.
- **Both editors use the field kind's cap.** `Kind.maxLength` is 96 units for
  a display name and 128 for the special key (`features/auth/AliasPrompt.kt`);
  the in-game card installs it on each `edit()`
  (`features/auth/AliasCardOverlay.kt`), and Android receives the same value in
  `LineRequest`. Android's filter calls the same `TextEdit.replacement` as the
  buffer (`android/src/main/kotlin/inego/zungjunggame/android/AndroidOverlayTextPrompt.kt`),
  so cap cuts and policy shortening agree on both platforms.

---

## 6. Invariants and edge cases

- **Empty and non-positive slots.** `TextFit.ellipsize` returns `""`; `fitScale`
  returns `1f` (full-size overflow) for `maxWidth <= 0`, not `0`. The two
  primitives disagree on a degenerate slot; treat it as a layout defect, not as
  a state to design around.
- **The floor is a stopping point, not a fit guarantee.** At 0.6 the run is
  drawn wider than its slot; a centred `FittedLabel` bleeds symmetrically. Do
  not "fix" this by lowering the floor — treat it as a layout result: grow or
  stack the control, use a short caption, or give prose more height or scroll.
  Cut an identifier; never cut a control caption (§7.3).
- **Cut is single-line and code-point aware.** It does not understand `\n`,
  combining marks or grapheme clusters.
- **Wrapping cannot split an atom.** Reserve extra width for URLs, compounds and
  tile runs, or accept a visible overflow.
- **Gutter is unconditional for text bodies.** Measuring with and painting
  without the strip (or vice versa) re-wraps the column when the bar appears.
- **A table's width comes from a stable input, not its rows.** The exceptions
  are called out in §2b; they are deliberate, not precedent.
- **Numbers get a column, not a fit.** Reserve the widest digit budget; do not
  shrink a score to fit a slot it will outgrow at the next hand.
- **A digit budget is not a bound.** A repeated-digit sample keeps a column
  stable; it does not prove the column holds every value the server can send
  (§2b, §7.4).
- **Clip never substitutes for fit.** If a string reaches a scissor unfitted,
  the user sees a mid-glyph cut with no ellipsis.

---

## 7. Where the model is not yet uniform

These are observations about today's code, not rules. They are ordered by damage:
1–3 can leave ink unreadable or a tap off its target; 4 can reflow a table
already on screen; 5–8 are lower-severity gaps in specific surfaces,
consistency, duplication and invalidation.

The name-bearing surfaces now pass end to end and are the pattern for the rest.
**Editing** (§5) stores UTF-16 units, edits on grapheme boundaries, and gives
both editors one per-kind cap. The **leaderboard name**, the **attempts card**
and the **Shadow Ranked hands viewer** measure, ellipsize and paint through the
same primary + fallback pair. The **turn banner** uses the wrapped-block pair,
because a paragraph cannot change faces mid-line. The **settlement tables**
(`ScoreBreakdownPainter`, `RatingChangesTable`, `ShadowRankedComparisonTable`),
the **account card** and the **rating-history title** paint names the same way.
The **`setup.chicken` row** measures a wrapped caption before paint, grows the
row to hold it, and derives the toggle and info rects from that grown row.
**Undrawable text** becomes one substitute glyph before measure and paint, and
desktop typed input keeps its code point, so a name can no longer hide an
invisible character or store a truncated one. Do not re-solve those with a new
abstraction; reuse the primitives they established (`text/Graphemes.kt`,
`text/TextEdit.kt`, `Fonts.drawText`, `Fonts.drawWrapped`,
`FallbackText.substituteUndrawable`, `GlyphLayout` before `fitAndPlace`).

1. **Raw paint still skips the fallback face.** Match-history rows, segmented
   tabs, the TOC list and the hub's wrapped prose still paint raw, and match
   history's own `textWidth` also measures raw, so its columns are sized for
   the primary (`features/history/MatchHistoryOverlay.kt:425-428`, `:466-495`,
   `render/ui/SegmentedTabs.kt:120-133`,
   `render/ui/ScrollableItemList.kt:372-386`,
   `features/online/hub/HubDraw.kt:248-269`); the quiz dock's action labels do
   the same in both measure and paint (`features/tutorial/TableChip.kt:81-94`).
   Those rows carry dates, scores and rules rather than player names, so the
   failure is a translated string measured with the fallback and painted as
   tofu, or an undrawable code point left invisible instead of showing the
   substitute glyph `FallbackText` would give it. **Next:** pair measure and
   draw helpers per surface, taking `Fonts.textWidth`/`Fonts.drawText` as the
   reference pair, and `Fonts.wrappedHeight`/`drawWrapped` for a wrapped block.
2. **Rich text can lay out and paint at different scales or line heights.** Line
   boxes reserve the primary line height while paint can use the taller
   fallback, so a mixed-script body can clip vertically; and link hit boxes are
   computed outside the draw-time document scale, so a scaled link is tappable
   somewhere other than where it appears
   (`render/richtext/GdxTextMeasurer.kt:19`,
   `render/richtext/RichTextBlock.kt:150-156`). The face or scale that decided
   the geometry is not the one that paints. **Next:** derive line metrics,
   painted positions and link rectangles from one layout and one scale. The
   acceptance test for a scaled link is that tapping its **visible edges**
   works — a correct calculated width is not enough.
3. **The fit floor has no next step.** The 0.6 floor is a readability decision,
   not an overflow policy. The chicken row now wraps and grows rather than
   shrinking past the floor, but the same decision is still missing elsewhere. A
   title drawn raw fails without even the floor: Serbian `menu.title` is 10.29
   em, 288 design px at the 28 px face, and a 320 dp card clips it at the rim
   (§4). A fixed-height plate whose caption wraps has no height outcome either:
   `TableChip` wraps into a plate sized for one line
   (`features/tutorial/TableChip.kt:81-94`,
   `tutorial/slide/layout/SlideChromeLayout.kt:156-196`). Rich text adds another
   form: the coach's `fitBodyScale` has no legible floor and no truncation
   fallback (`features/tutorial/CoachCopyText.kt:158-183`). Separately, no
   breaker can split an unbreakable atom, so a long URL or compound overflows
   rather than wrapping (§1d). **Next:** make "still
   too wide at the floor" an explicit layout result handled before paint — grow
   the row, stack the controls, use an approved short caption, or give prose
   more height or scrolling. Ellipsize an identifier; never scroll a control
   label (§1e).
4. **Table widths can be unstable, or stable but insufficient.** Shadow
   attempts, the comparison table and the hands table size columns from loaded
   rows, so a later page or a longer pattern can reflow a table already on
   screen (`features/challenges/ShadowRankedAttemptsOverlay.kt:382-418`,
   `features/score/ShadowRankedComparisonTable.kt:276-310`,
   `render/ui/HandsTable.kt:265-323`); that is the case `AGENTS.md:50-55`
   forbids. Stable is not the same as sufficient: the leaderboard's rating
   column is a four-digit sample and its count columns three, but the client
   knows no server cap, so those are **display budgets, not bounds**
   (`LeaderboardOverlay.kt:821-833`); a value wider than the sample — a
   five-digit rating, for example — is right-aligned into a cell that cannot
   hold it. `RatingChangesTable` and `ScoreBreakdownLayout`
   take the wider of the loaded rows and a `"0000"` sample, so the sample never
   proves a bound either (`features/score/RatingChangesTable.kt:90-94`,
   `features/score/ScoreBreakdownLayout.kt:357-361`). The leaderboard's
   degenerate branch adds another failure: a translated header can widen the
   fixed columns past a narrow panel, and `scaled()` then shrinks the cells but
   not the face, so numbers and header labels keep their natural ink and
   overlap their neighbours (`LeaderboardOverlay.kt:764-797`). **Next:** size
   numeric columns from the formatter or snapshot bound, sign and widest
   permitted digits included; treat `W`/`中` alias samples as display budgets,
   not proof that every legal alias fits; specify the compact layout for when
   the budgets together exceed the safe width.
5. **Some visible strings still get no move at all.** Card titles are drawn raw
   in most overlays (`GameMenuOverlay.kt:860-864`,
   `SettingsOverlay.kt:593-599`, `LeaderboardOverlay.kt:367-369`); TOC rows are
   raw inside a scissor; left/right/opposite seat names have a gutter-scale but
   no horizontal budget; the patternless notice only clamps its left edge
   (`ScrollableItemList.kt:372-387`, `render/seats/SeatLabelDrawer.kt:136-138`,
   `render/table/PatternlessNotice.kt:50-58`). The wall count and seat score
   re-centre their whole run every frame instead of reserving a digit cell
   (`render/table/LiveWallMarker.kt:131-150`, `SeatLabelDrawer.kt:198`). This
   last one is mostly cosmetic — a count that gains a digit shifts the run, it
   does not make it illegible — unlike items 1–4.
6. **One visual role still has several answers.** A row label wraps and grows in
   Settings and in new-game setup, shrinks its label only in the hub, and is raw
   in the menu and TOC; a button shrinks in `OverlayChrome`, wraps in
   `TableChip`, and equal-shares then wraps in the quiz dock. The identity line
   is one ellipsized string; the hub header is two cells with different moves.
7. **Some primitives are duplicated.** The score breakdown hand-rolls `"..."`
   truncation with `substring` (`ScoreBreakdownPainter.kt:218-227`); MC hint
   labels mutate `font.data.setScale` directly, so a fallback face would not
   follow (`render/hint/McHintLabels.kt:85-119`); four routes shrink a face
   (`FittedLabel`, `fitScale` + `withFaceScale`, `withDocFontScale`, raw
   `setScale`).
8. **Cache invalidation is manual.** Only match history keys on both the locale
   and `uiScale`; `HandsTable` and the shadow-attempts table key on scale and
   data but not the locale. `Fonts.applyLocale` disposes faces without notifying
   any cache, and two locales in the same script profile change the copy without
   rebuilding a face (`MatchHistoryOverlay.kt:354-365`,
   `features/reference/ReferenceBrowserOverlay.kt:498-519`,
   `Fonts.kt:190-200`). §2d lists the inputs a cache must key on. Fetching
   another page must change neither the column budget nor the position of
   existing columns.

---

## 8. Where to look

- **Toolbox:** `render/fonts/Fonts.kt`, `FallbackText.kt`, `LineBreaker.kt`,
  `ScriptProfile.kt`; `render/ui/TextFit.kt`, `FittedLabel.kt`,
  `OverlayChrome.kt`, `OverlayViewport.kt`, `OverlayBodyScroll.kt`.
- **Documents:** `render/richtext/` (`RichDoc`, `RichMarkupParser`,
  `RichTextLayout`, `RichTextView`, `RichTextBlock`, `RichTextLinkHit`);
  `render/richtext/GdxTextMeasurer.kt`.
- **Tables:** `features/leaderboard/LeaderboardOverlay.kt`,
  `features/history/MatchHistoryOverlay.kt`, `render/ui/HandsTable.kt`,
  `features/score/ScoreBreakdownLayout.kt`,
  `features/challenges/ShadowRankedAttemptsOverlay.kt`.
- **Felt:** `render/seats/SeatLabelDrawer.kt`, `render/table/TurnStatusBanner.kt`,
  `render/tiles/TileLabelGeometry.kt`, `render/FeltStamps.kt`.
- **Input:** `render/ui/OverlayTextBuffer.kt`, `OverlayTextField.kt`,
  `text/Graphemes.kt`, `text/TextEdit.kt`, `net/AliasPolicy.kt`,
  `features/auth/AliasCardOverlay.kt`, `features/auth/AliasPrompt.kt`,
  `android/src/main/kotlin/inego/zungjunggame/android/AndroidOverlayTextPrompt.kt`,
  `lwjgl3/src/main/kotlin/inego/zungjunggame/lwjgl3/CodepointInput.kt`.
- **Strings and script:** `i18n/`, `assets/i18n/`, `tools/i18n/width.py`.
- **Tests that pin the mechanics:** `render/fonts/LineBreakerTest.kt`,
  `FallbackTextTest.kt`, `render/richtext/RichTextLayoutTest.kt`,
  `RichMarkupParserTest.kt`, `RichTextLinkHitTest.kt`,
  `render/ui/OverlayTextBufferTest.kt`, `OverlayTextFieldTest.kt`,
  `text/TextEditTest.kt`, `net/MatchHistoryFormatTest.kt`.

GL-dependent painting is outside the pure suite
([`testing.md`](testing.md) §4), so fit regressions are verified by inspection
and on-device, not by the unit tests. A width calculation can pass while the
paint still fails, so check these on device:

- 320 dp and a short landscape band;
- every locale that is longest for the key (`width.py` finds it);
- a stored 24-grapheme alias, a CJK alias, and a 5-digit rating;
- a paste across the 96/128-unit cap, and a legacy over-limit alias shortened
  in place, in both the in-game and native editors;
- a pasted emoji (one substitute glyph, not an invisible gap) and a typed
  supplementary Han character, on desktop and on the phone;
- a Han name in a Latin locale (the leaderboard row is the reference; the
  remaining raw-paint rows are the ones to check);
- the longest translated action caption on a 320 dp screen (the fit floor);
- a scaled rich-text link tapped at its visible edges;
- the exact point where a body starts to overflow (the scrollbar must not move
  an edge or re-wrap a line);
- a paged table after the second page arrives.
