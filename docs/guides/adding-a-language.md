# Adding a New Language (Localization Guide)

This guide outlines the standard procedure for adding a new language to the application.

---

## Architecture & Sources of Truth

Localization is split into four distinct layers:

| Layer | Location | Role |
|---|---|---|
| **Locale Registry** | `core/.../i18n/AppLocale.kt` | **Sole source of truth** for supported languages, tags, and native endonyms. |
| **UI Chrome Bundle** | `assets/i18n/ui_<lang>.properties` | UI labels, buttons, turn banners, and error messages. |
| **Reference Catalog** | `assets/reference/<tag>/` | Offline rulebook and scoring pattern articles (Markdown bodies only). |
| **Typography & Fonts** | `core/.../render/fonts/` | Script profiles (`ScriptProfile`), font face resolution, and fallback rendering. |

> [!IMPORTANT]
> **Single Source of Truth for Endonyms:**
> Native language names (endonyms) live **exclusively** in `AppLocale.endonym` in Kotlin code.
> Do **not** create `lang.<locale>` keys in `.properties` files.

---

## Build Artifacts (Generated — Do Not Edit)

The loose files above are the **only** localization source you author. The
`lang/<tag>.zip` packs are build outputs:

- `:core:packLanguagePacks` packs one zip per `AppLocale.tag` into
  `lang/<tag>.zip` (holding that locale's `bundle.properties` plus its
  reference markdown), and writes `lang/index.txt`. It reads `assets/i18n` and
  `assets/reference` and writes nothing under `assets/`.
- `:core:stageAssets` (`core/build/staged-assets`) and
  `:core:stageRuntimeAssets` (`core/build/staged-runtime`) assemble the trees
  desktop, Android, and Wasm actually run. Wasm preloads the startup set and
  fetches only the active zip.

So once the locale is registered in `AppLocale` (step 2), its
`ui_<lang>.properties` and `assets/reference/<tag>/**/*.md` flow into
`lang/<tag>.zip` automatically on the next build. **Never edit a generated
`lang/<tag>.zip` or the staged trees**: they are overwritten every build. See
[`language-packs.md`](language-packs.md) for the pack format and task details.

---

## Fast-Track Procedure

### 1. Author Assets from English (Sole Source of Truth)

**English is the sole authoritative source of truth in this project.**
Do **not** copy or clone reference files or UI bundles from existing language directories, and do not copy files as placeholders. Instead, author each localized file from scratch directly based on the English source:

- **UI Chrome Bundle (`assets/i18n/ui_<lang>.properties`):**
  Write directly from scratch using `assets/i18n/ui.properties` as the specification. Translate every key, preserving identical key names and placeholder tokens (`{0}`, `{1}`, etc.). Ensure files are encoded in UTF-8 without BOM.
- **Reference Catalog (`assets/reference/<tag>/`):**
  Write all **85 articles** from scratch based on `assets/reference/en-US/` (1 `about.md`, 17 under `rules/`, 2 under `scoring/`, and 65 under `scoring/patterns/`). Maintain identical directory layout, tile tokens (`{123m}`), and navigation hyperlinks (`pattern-X.Y.Z`). Do **not** copy or edit `assets/reference/structure.json` — the taxonomy (ids, file paths, order, `number`, `level`) is shared by every locale.
- **Reference Navigation Strings (`assets/i18n/ui_<lang>.properties`):**
  Add `reference.title`, `reference.section.<id>.label`, and `reference.page.<id>.title` for every id in `assets/reference/structure.json`, translated from the base bundle.

*(Note: `<lang>` matches `javaLocale.language`, e.g. `ui_de.properties`, `ui_fi.properties`, `ui_fr.properties`, `ui_sr.properties`, `ui_uk.properties`; `<tag>` is the BCP 47 directory name, e.g. `de`, `fi`, `fr`, `sr`, `uk`, `zh-HK`.)*

---

### 2. Register the Locale in Kotlin

Add the entry to [`AppLocale.kt`](../../core/src/main/kotlin/inego/zungjunggame/i18n/AppLocale.kt),
in the **script-grouped autonym ordering** (the picker lists `AppLocale.entries`
in declaration order):
1. **Latin script**: `English` is always first (`DEFAULT`), followed by strict `A–Z` ordering by **native endonym string** (`Deutsch`, `Español`, `Français`, `Italiano`, `Magyar`, `Nederlands`, `Polski`, `Português`, `Srpski`, `Suomi`, `Türkçe`).
2. **Cyrillic script** (А–Я by native endonym): `Русский`, `Українська`, …
3. **CJK ideographs / East Asian** (stroke count / native order): `日本語`, `繁體中文`, …

```kotlin
    @Suppress("DEPRECATION")
    German(
        tag = "de",
        javaLocale = Locale("de"),
        endonym = "Deutsch",
    ),
```

No test edits are needed. [`AppLocaleTest.kt`](../../core/src/test/kotlin/inego/zungjunggame/i18n/AppLocaleTest.kt)
sweeps `AppLocale.entries`, and [`L10nTest.kt`](../../core/src/test/kotlin/inego/zungjunggame/i18n/L10nTest.kt)
sweeps the packs under `assets/reference/`. Both derive their cases from the
registry and the shipped assets, so once the entry exists (and its pack is
authored) they cover it automatically — never add a case per language.

---

### 3. Typography & Fonts (`ScriptProfile.kt`)

> [!NOTE]
> **European Languages (Latin & Cyrillic): Skip this step.**
> The bundled `Roboto` primary font already covers the full Latin character set (all European accents/diacritics) and Cyrillic. `ScriptProfile` defaults to `LatinCyrillic` for any tag without an explicit CJK rule.

**Only for CJK or new writing systems** (e.g. Greek, Arabic, Thai):
1. Check or add a profile in [`ScriptProfile.kt`](../../core/src/main/kotlin/inego/zungjunggame/render/fonts/ScriptProfile.kt) under `forTag(tag)`.
2. Configure primary family and fallbacks in [`FontFaceResolver.kt`](../../core/src/main/kotlin/inego/zungjunggame/render/fonts/FontFaceResolver.kt).

---

### 4. Author Localized Content

Author the localized files directly from the English authoritative source:

1. **UI Bundle (`assets/i18n/ui_<lang>.properties`):**
   - Translate all string values from `ui.properties`.
   - Keep placeholder tokens (`{0}`, `{1}`, etc.) intact.
   - Translate `menu.rules_credit` for the main-menu felt attribution.
2. **Reference Catalog (`assets/reference/<tag>/`):**
   - **Navigation strings (in `ui_<lang>.properties`):** translate `reference.title`, every `reference.section.<id>.label`, and every `reference.page.<id>.title`. Keys must match the ids in `assets/reference/structure.json` exactly.
   - **`about.md`:** Translate game attribution and license text.
   - **`rules/*.md` (17 articles):** Translate rules prose (keep link targets `(rules-...)` and tile tokens `{...}`).
   - **`scoring/basics.md` & `chicken-hand.md`:** Translate scoring explanations.
   - **`scoring/patterns/*.md` (65 articles):** Translate category summaries and leaf patterns (points: e.g. `[hl]**80 pistettä**[/]`, keep `(pattern-...)` links and tile tokens).
3. **Catalog Index (`assets/reference/README.md`):**
   - Add `<tag>/` in **both** locations:
     1. The directory layout tree diagram near the top.
     2. The **Shipped locales** table near the bottom.
     (`structure.json` needs no change.)

> [!TIP]
> **Authoring large batches on Windows:**
> If automating file generation via helper scripts, use dedicated UTF-8 file-writing modules (e.g. Node.js `fs.writeFileSync(..., 'utf8')` or Python with explicit `encoding='utf-8'`) rather than shell inline eval strings in PowerShell or CMD, avoiding default codepage (CP1251/CP1252) character corruption or escape errors.

---

### 5. Automated Verification

Run automated tests to verify key coverage, placeholder retention, and reference parsing:

```bash
# Focused check during development (fast):
.\gradlew.bat :core:test --tests "*Locale*" --tests "*Bundle*" --console=plain

# Full test suite before committing:
.\gradlew.bat :core:test --console=plain
```

- `BundleCoverageTest` fails on missing keys, blank values, or dropped placeholders.
- `ReferenceLocaleCoverageTest` fails if files are missing, headings are absent, a folder lacks `about.md`, or the `reference.*` bundle keys diverge from `structure.json`.
