# Design Notes & FAQ Essays

Compact digests of non-rule essays useful for engine/policy design.

---

## Pattern selection criteria

Source: https://www.zj-mahjong.info/zj_select_eng.html

1. **Traditional:** widely adopted classics kept (one-suit, all sequences, all triplets…).
2. **Coherence:** reward consistent hands; reject inconsistency rewards (13 unconnected / knitted, all types, fully exposed, single wait, 2/5/8 eyes, etc.).
3. **Symmetry:** preserve suit/dragon/1–9 symmetry; reject graphic-based (all green, reversible), number-class (all fives/even/upper), **prevailing wind** (asymmetry/unfairness; historically late inflation).
4. **10 categories:** only patterns fitting the 10 cats; reject weaker coherence (mixed straight, shifted chows…).
5. **Series logic:** if low pattern in series adopted, higher must exist; adopt small/big fill-ins; reject easy two-set patterns except strongest (Two Identical Sequences); reject original call complexity.
6. **Incidentals:** keep for color but **lower points** vs frequency; reject common **self-draw bonus**.

---

## Name meaning (Zung Jung / 中庸)

Source: https://www.zj-mahjong.info/zj_name_eng.html

- Jyutping: Zung1 Jung4; "j" ≈ "y".
- Surface: moderate pattern count — skill depth without learning burden.
- Deep: ideal unified historical development (math/philosophy), not patchwork local peculiarities.
- Design pillars called out: fixed-income (no self-draw inflation), additive not faan, no min-for-win as primary filter, principled pattern selection, three-triplet patterns valued near Big Three Dragons rarity.

---

## Chicken hand / no-minimum rationale (digest)

Source: https://www.zj-mahjong.info/zj_chicken_hk.html (Chinese)

- Classical default: complete regular hand is enough; chicken is informal label for patternless win.
- Chicken vs small patterned hands (menzen+tanyao+pinfu) has no sharp aesthetic/difficulty boundary → min rule lacks consistency.
- Neutrality for multi-region competition: shared core is **regular hand shape**, not any one region's min/faan definition.
- Min rules redefine "legal hand" by baking patterns into win condition → large local bias.
- Historical irony: modern pinfu originated as classical "chicken-class" hand; banning chicken while allowing pinfu is inconsistent.
- ZJ's pattern set already prevents "rush-only wins dominate" without a min; author claims multi-year evidence no chicken epidemic.
- Banning chicken just creates new smallest legal hands; does not force bigger builds.
- Match-format problems (last-hand stall-hug) are **not** fixed by min points — redesign scoring pool/match structure instead.
- Stance: scoring balance assumes points ≈ real utility; don't warp hand rules to patch tournament format.

---

## False-win quiz

Source: quiz/falsewin1_hk.html (interactive; not fully scraped)

Related book chapter: 錯和論 (false win theory). Useful for edge-case legality (declared win without valid shape / under min variant).

---

## Teaching order (see variants.md)

Category sequence prioritizes historical/symbolic significance; temporary basic points + uniform payoff for learners.

---

## Zung Jung Average (math side-article)

Source: https://www.zj-mahjong.info/zj_avg_eng.html  
Not mahjong rules. Defines average y solving:

```
sum( sgn(xi - y) * sqrt(|xi - y|) ) = 0
```

Balances mean vs median. Mentioned only for completeness of site scrape.

---

## Site map (English resources)

```
index
├── zj33_rules_eng.html          basic rules + formal payoff
├── zj33_patterns_eng.html       full patterns
├── book_en/App G ... brief      quick list
├── book_en/App C ...            basic mahjong
├── book_en/1.1 Misc Rules.html  standard table rules
├── zj_freecount_eng.html        freedom of count
├── zj_uniform_eng.html          uniform payoff
├── zj_flowers_eng.html          flowers
├── zj_select_eng.html           selection criteria
├── zj_name_eng.html             name essay
├── zj_child_eng.html            teaching
├── zj_avg_eng.html              math average
├── zj_chicken_hk.html           chicken essay (zh)
└── book_hk/...                  full Chinese history book
```

Broken/404 at scrape time: `zj_forward_eng.html`, `zj_copyright_eng.html`.  
Core scoring released under CC BY-NC-SA 3.0 HK on pattern/rules pages.
