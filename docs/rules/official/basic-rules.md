# Zung Jung v3.3 — Basic Scoring Rules

Source: https://www.zj-mahjong.info/zj33_rules_eng.html  
Also: book_hk/1.0 zj33_rules_hk.html

## Principles

- Pattern-based scoring system ("Middle Way" / Doctrine of the Mean).
- Only the **winning hand** is scored.
- **No basic points** (副底). Score = sum of pattern values only.
- Winner collects from other three per **payoff scheme**.

## Pattern organization

- 44 patterns in categories + series.
- ID format: `category.series[.level]` e.g. `3.3` = Honor Tiles / Winds.
- Full list: `patterns.md`.

## Additive Rule (複合加算)

- Hand value = sum of all qualifying patterns from **different series**.
- Within same series: count **only one** (the applicable / highest that fits).
- Same pattern cannot be counted more than once.
- **Exception 3.1 Value Honor:** +10 **per** qualifying set (cumulative).
- Implied patterns still count if different series.
  - Example: Four Concealed Triplets always also Concealed Hand + All Triplets → ≥ 125+5+30 = **160**.
- No multiplicative "faan" system.

## Minimum requirement

| Mode | Rule |
|---|---|
| **Standard ZJ** | No min. Any regular hand or cat-10 irregular may win. Patternless "chicken hand" (雞和) = **1** pt. |
| **Official variant** | 5-pt minimum; chicken hand illegal (false win). May be used by WSoM — check event rules. |

## Maximum / limit (滿貫)

| Case | Score |
|---|---|
| **Compound Limit Hand (數滿貫)** | Multiple patterns sum ≥ 320 → score **320** (all patterns still conceptually present; capped). |
| **Listed Limit Hand (役滿貫 / 例牌)** | Hand contains a pattern with listed value ≥ 320 → score **only the single highest-valued pattern** (no other patterns added). |

## Payoff scheme — Formal Competition (中庸競技制)

### Fixed Payoff Principle (固定收入原則)

- Winner total income determined **only** by hand pattern value.
- Self-draw does **not** inflate income.
- Scheme only splits that fixed total among the other three.

### Totals

- Winner always receives **3 × hand_value**.

### Self-draw

- No responsible player.
- Each of three pays **1 × hand_value**.

### Win on discard — discarder responsibility

- Standard score threshold **S = 25**.
- **Small hands (hand_value ≤ 25):** three-way equal split; each pays **1 × hand_value** (even if there is a discarder).
- **Big hands (hand_value > 25):**
  - Each of three first pays **25**.
  - Responsible player pays remaining balance so winner total = 3×value.
  - Non-responsible each pay 25; responsible pays `3*V - 50`.
  - Example V=70: non-resp 25 each; responsible 160; winner 210 = 3×70.
- Example V=55: winner 165; discarder pays 115; others 25 each.

### Same-Round Immunity / Follow-Discard Immunity (跟張免責)

When win on discard, if in the **same round** (from and including winner's previous discard, until before the win) another player already discarded the **same tile** as the winning tile:

- Current discarder is **not responsible** for following that discard.
- If **winner** themselves just discarded the winning tile earlier in that window → **no one responsible** (three-way split).
- Else: the player who **first** discarded that tile in the same round (after winner's previous discard) is responsible.

**Important:** immunity does **not** convert the win to self-draw. Claimed discard still makes that set **exposed** for concealed-triplet scoring.

## Related

- Uniform casual alternative: `variants.md` (Uniform Payoff).
- Multi-arrangement scoring: `freedom-of-count.md`.
