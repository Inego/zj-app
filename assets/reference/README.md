# Reference content (localized)

In-game **Reference** browser pages are plain text assets, not Kotlin strings.
The catalog loader only understands structure; copy lives in page bodies, and
navigation labels live in the UI bundles.

## Layout

```
assets/reference/
  README.md                 ← this file
  structure.json            ← the ONE taxonomy: section/page ids, file paths, order, level, number
  en-US/                    ← default locale (BCP 47 tag)
    about.md               ← About / License (app attribution; Rules TOC)
    rules/
      overview.md          ← world context + how to read
      tiles.md             ← hub (TOC parent)
      suits.md             ← nested under Tiles (level: 1)
      honors.md            ← nested under Tiles (level: 1)
      winds.md             ← nested under Honors (level: 2)
      dragons.md
      sets.md              ← hub: set types + eyes
      sequence.md          ← nested under Sets (level: 1)
      triplet.md
      eyes.md
      kong-types.md
      hands.md
      concealed-open.md
      claims.md
      winning.md
      wall.md
      seats.md             ← seat wind / deal (not the wind tiles page)
    online/
      shadow-ranked.md     ← Shadow Ranked mode (Rules TOC)
    scoring/
      basics.md
      chicken-hand.md      ← unnumbered scoring topic (after Basics)
      patterns/
        1.md               ← category summary (clickable TOC parent)
        1.1.md             ← leaf pattern
        2.md
        2.1.md             ← series summary (clickable TOC parent)
        2.1.1.md
        …
  de/                       ← German (bodies only; ids stable from structure.json)
    …
  cs/  es/  fr/  it/  hu/  nl/  pl/  pt/  ru/  zh-HK/  ja/  sr/  fi/  uk/  el/  tr/    ← same shape, bodies only
```

There is **no per-locale manifest**. A locale folder holds only `.md` bodies;
paths are relative to the locale folder. The taxonomy — and therefore the
navigation contract — exists exactly once.

A page a locale has not translated yet may simply be absent from its folder: the
packer (`LanguagePacker.resolvePages`) and the authoring loader fall back to the
`en-US` body. List its `reference.page.<id>.title` key in
`assets/i18n/untranslated-keys.txt` so the title falls back to English too.

## `structure.json` schema

```json
{
  "sections": [
    {
      "id": "rules",
      "pages": [
        { "id": "rules-overview", "file": "rules/overview.md" },
        { "id": "rules-tiles", "file": "rules/tiles.md" },
        { "id": "rules-suits", "file": "rules/suits.md", "level": 1 }
      ]
    },
    {
      "id": "scoring",
      "pages": [
        { "id": "scoring-basics", "file": "scoring/basics.md" },
        { "id": "pattern-2", "file": "scoring/patterns/2.md", "number": "2" },
        { "id": "pattern-2.1", "file": "scoring/patterns/2.1.md", "number": "2.1" },
        { "id": "pattern-2.1.1", "file": "scoring/patterns/2.1.1.md", "number": "2.1.1" }
      ]
    }
  ]
}
```

| Field | Role |
|--------|------|
| `sections[].id` | Stable section key (`rules`, `scoring`). Must match a section the app knows. |
| `pages[].id` | Stable navigation id (shared across locales; used by sticky restore) |
| `pages[].file` | Markup path relative to each locale folder |
| `pages[].number` | Dotted TOC number; omit for unnumbered pages. Drives the muted number and default indent. |
| `pages[].level` | Optional indent override (`0` = flush left). Required for Rules nesting (no dotted numbers). |

The **page/section order in `structure.json` is the TOC order** (parents before
children).

### Conventions (scoring)

- **id:** `pattern-{number}` (e.g. `pattern-2.1.1`)
- **file:** `scoring/patterns/{number}.md`
- **number:** the dotted number (`2.1.1`); TOC labels carry no number
- **TOC order:** parents before children in the `pages` array
- **Indent:** derived from the number (`1` → 0, `2.1` → 1, `2.1.1` → 2)

### Conventions (rules)

- **id:** `rules-{topic}` (e.g. `rules-suits`, `rules-winds`)
- **file:** `rules/{topic}.md`
- **TOC order:** parents before children in the `pages` array
- **Indent:** set `"level": 1` (or deeper) on children — Rules titles are
  unnumbered, so level is **not** inferred from a number
- Example tree: Tiles → Suits / Honors → Winds / Dragons; Sets & eyes →
  Sequence / Triplet / Kong / Eyes
- **About / License** is a Rules TOC page (`id`: `about-license`,
  `file`: `about.md`) so the in-game Reference browser is the license screen

No code change is required to add, rename, or reorder pages — only assets.

## Navigation strings

Everything visible in the browser chrome and TOC is a localized string in the
**UI bundle** for the pack's language, under `reference.*` keys:

| Key | Role |
|-----|------|
| `reference.title` | Full-screen browser chrome title |
| `reference.section.<sectionId>.label` | Tab label |
| `reference.page.<pageId>.title` | TOC row label (no number prefix) |

`ReferenceCatalog` reads these from the bundle for the **resolved pack's
language**, not the ambient UI locale, so a pack that falls back to `en-US`
still reads as one language (chrome translated, Reference in the pack's
language). Keys must exist: a missing `reference.*` key fails the build via the
coverage tests, not silently.

### What still lives in code (by design)

These are **app contracts**, not prose content:

- Default locale tag (`en-US`) until settings pick another
- Registered locale tags (`AppLocale`) — the picker list, and the set of
  reference packs that are reachable at all
- Known section ids (`rules`, `scoring`) so UI can address tabs type-safely
- The pack probe filename (`PACK_PROBE_FILE = about.md`) that tells the loader a
  `reference/<tag>/` folder is a real pack
- Rich-text dialect implementation

If a new **section id** is needed, code must learn that id (one enum entry). New
pages and labels do not.

## TOC hierarchy

**Hierarchy is the page list in `structure.json`** — not Kotlin maps. Each page
is one TOC row in listed order. Indent comes from:

1. optional `"level"` in `structure.json`, else
2. the dotted `number` (`2.1.1` → depth 2), else
3. depth 0

Scoring uses (2). Rules uses (1) for nested topic pages. The dotted `number` is
rendered as a muted prefix by the TOC chrome; the label itself is pure text.

```
Basics
Patternless hand        ← scoring/chicken-hand.md (unnumbered scoring topic)
1  Trivial              ← scoring/patterns/1.md
  1.1 All Sequences
  1.2 Concealed Hand
  1.3 No Terminals
2  One-Suit             ← scoring/patterns/2.md
  2.1 Mixed / Pure      ← scoring/patterns/2.1.md
    2.1.1 Mixed One-Suit
    2.1.2 Pure One-Suit
  2.2 Nine Gates
```

Category and series rows are ordinary clickable pages with summary markup.

## Markup dialect

Page bodies use the game’s rich-text dialect (see `RichMarkupParser`). Supported:

- `#` / `##` / `###` headings
- paragraphs (blank-line separated; soft line wraps join with a space)
- `-` / `*` bullets and `1.` numbered lists (continuation lines join)
- **No nested lists** — keep bullets flat (indent is not hierarchy)
- `**bold**`, `*italic*`, `__underline__`
- `[label](href)` markdown hyperlinks (label may nest emphasis / colors)
- `<href>` autolinks
- `[hl]…[/]`, `[muted]…[/]`, etc. named role tags (`hl` is the colour-neutral highlight)
- `{123m}` tile tokens
- `:::center` … `:::` centered blocks

### Hyperlinks

Idiomatic markdown links navigate inside the reference browser (and push a full
back-history frame: section, page, TOC scroll, article scroll). The history **←**
control (and **Backspace**) pops that stack and restores both scroll positions.
The separate top-right **×** (or **Esc**) exits the browser back to the table.

| Destination | Example | Resolves to |
|-------------|---------|-------------|
| Page id | `[Mixed](pattern-2.1.1)` | that page in any section |
| Hash id | `[Basics](#scoring-basics)` | same |
| File path | `[Mixed](scoring/patterns/2.1.1.md)` | page whose `file` matches |
| Pattern number | `[Mixed](2.1.1)` | `pattern-2.1.1` |
| External | `[Rules](https://…)` | system browser (`http`/`https`/`mailto`) |

Default link chrome is the `[link]` palette color only (no underline; overridable
with an explicit color on the label).

### Leaf pattern template

```markdown
# English Name

**2.1.1** · 混一色 · [hl]**40 points**[/]

Definition paragraph(s).

:::center
{123p}{456p}…
:::

[muted]Series note…[/]
```

### Category / series summary template

```markdown
# English Name

**2** · 清一色類

Short intro for this branch.

## Patterns

- [**2.1.1** Mixed One-Suit](pattern-2.1.1) — [hl]**40**[/]
- [**2.1.2** Pure One-Suit](pattern-2.1.2) — [hl]**80**[/]

[muted]Series / stacking note…[/]
```

Summary pages intentionally restate child names and points for at-a-glance
reading. When a leaf’s value changes, update the leaf page **and** any parent
lists that mention it.

## Adding a locale

Adding a language means touching **four** places. The folder alone is not
enough: content for a tag the app has not registered is unreachable, so it ships
without ever being shown.

1. Copy `en-US/` to a new tag folder (e.g. `zh-CN/`) — bodies only. Do **not**
   copy or edit `structure.json`; the taxonomy is shared.
2. Translate every `.md` body.
3. Add the navigation strings for that language to its UI bundle
   (`assets/i18n/ui_<lang>.properties`): `reference.title`,
   `reference.section.<id>.label`, and `reference.page.<id>.title` for every id
   in `structure.json`.
4. Register the tag by adding one entry to the `AppLocale` enum —
   `core/src/main/kotlin/inego/zungjunggame/i18n/AppLocale.kt`. That is the
   **single registry**; the language picker lists `AppLocale.entries`
   automatically, and `LocaleController` then resolves
   `assets/reference/<tag>/` (falling back to language-only, e.g. `ru`, then to
   `en-US`). Open browsers rebind via `LocaleBus`.

Bundles are keyed by **language**, packs by **tag**. Today every tag maps to a
distinct language (`zh-HK` → `ui_zh.properties`), so the mapping is one-to-one.
If a second pack ever shares a language (e.g. `zh-CN` alongside `zh-HK`), the
shared bundle is wrong for one of them; add a region-specific bundle
(`ui_zh_HK.properties`) and point the pack at it. libGDX's `I18NBundle` prefers
the region-specific file automatically.

A language registered in `AppLocale` with **no** reference folder still works:
the UI is translated and the Reference falls back to `en-US`. The reverse is
not true — an unregistered folder is dead content.

### Enforced by tests, not review

`core/src/test/kotlin/inego/zungjunggame/render/ReferenceLocaleCoverageTest.kt`
walks `assets/reference/` and fails the build when:

- a locale folder is not registered in `AppLocale` (dead content), or
- a locale folder has no `about.md` (so `LocaleController` will not see it as a
  pack), or
- the base bundle's `reference.*` key set does not equal the ids in
  `structure.json` (a missing or stale key), or
- an `AppLocale` entry has no `assets/i18n/` bundle.

The `reference.*` bundle keys are compared against `structure.json` instead of
against another locale, because there is now a single taxonomy — there is no
second copy to drift. Cross-language key parity is
`BundleCoverageTest`'s job.

`core/build.gradle` declares `assets/reference/` and `assets/i18n/` as inputs of
the `test` task, so an asset-only change re-runs these tests instead of being
skipped as UP-TO-DATE.

### Attribution

`NOTICE` states coverage as a rule — *every* locale directory under
`assets/reference/`, not a list — so adding a language never requires editing a
legal document, and the claim can never be narrower than what actually ships.

## Shipped locales

Informational only; `AppLocale` is authoritative, and the guard test above keeps
this honest.

| Folder | Notes |
|--------|-------|
| `en-US/` | Default / source of truth |
| `cs/` | Czech; Latin script |
| `de/` | German; Latin script |
| `es/` | Spanish; Latin script |
| `fr/` | French; Latin script |
| `it/` | Italian; Latin script |
| `hu/` | Hungarian; Latin script |
| `nl/` | Dutch; Latin script |
| `pl/` | Polish; Latin script |
| `pt/` | Portuguese; Latin script |
| `ru/` | Russian; MCR-aligned names — see `docs/russian-terminology.md` |
| `zh-HK/` | Chinese Traditional (HK); Alan Kwan's authentic Zung Jung text |
| `ja/` | Japanese; Alan Kwan's authentic Zung Jung Japanese translation |
| `sr/` | Serbian; Latin script |
| `fi/` | Finnish; Latin script |
| `uk/` | Ukrainian; Cyrillic script |
| `el/` | Greek; Greek script |
| `tr/` | Turkish; Latin script |

## Default locale

Code loads `en-US` until a different locale is selected. Missing packs fall back
to `en-US` via `LocaleController.resolveReferenceLocaleTag`.
