# Variants (casual / optional)

## 1. Uniform Payoff Scheme (中庸皆一制)

Source: https://www.zj-mahjong.info/zj_uniform_eng.html

For novices / casual aggression. **Not** recommended for skill-focused play.

### Rules

- On any win (self-draw or discard): **every** non-winner pays **1 × V**.
- Winner still gets **3 × V**.
- No discarder-extra on big hands.
- Scorekeeping can record only winners' hand values; session end:  
  `score_i = 3 * sum(V of i's wins) - sum(V of all others' wins)`.

Tradeoff: simpler, more aggressive building; weakens defensive skill and fairness of punishing bad discards.

---

## 2. Bonus / Flower tiles (花牌) — category 11

Source: https://www.zj-mahjong.info/zj_flowers_eng.html

- Not used in formal competition (luck + complexity).
- Optional for family play with standard 8 bonus tiles.

### Setup

- 4 Flowers + 4 Seasons.
- Each seat has 1 proper Flower + 1 proper Season; 6 improper.
- Declare + replace bonus tiles per common mahjong practice.

### Patterns (all cumulative; no series exclusion)

| id | en | zh | pts | def |
|---|---|---|---:|---|
| 11.1.1 | Improper Flower/Season | 不正花 | 2/tile | Not proper to seat |
| 11.1.2 | Proper Flower/Season | 正花 | 4/tile | Proper to seat |
| 11.2.1 | Four Flowers | 四花 | 10 | Complete flower set |
| 11.2.2 | Four Seasons | 四季 | 10 | Complete season set |

### Examples

```
4-flower bouquet:
  11.2.1 10 + proper 4 + 3×improper 2 = 20
all 8 bonus:
  40 total for flowers
```

- Hand with any bonus tiles is **not** chicken; no +1 chicken.
- Optional house rule: halve all flower values.

---

## 3. Five-point minimum

From basic rules:

- Chicken illegal; treated as false win.
- Official variant; WSoM may adopt — check event site.
- Standard ZJ: **no** min.

Author essay (chicken-hand): https://www.zj-mahjong.info/zj_chicken_hk.html  
Summary in `design-notes.md`.

---

## 4. Siu Hong / 小康 (simplified “old-style” subset) v1.1

Source: book_hk/16.9 Simple.html

Uses ZJ additive rules + formal (or other fixed-income) payoff, but **subset of patterns** (old-style only). Author notes it is weak for pattern-building play.

### Extra

- Suggested **basic 5 pts** per win if no flowers (or use flowers as soft basic).
- Optional single-form limit at 160 instead of compound 320.

### Included patterns (defs same as ZJ)

```
0.0  Basic points (if no flowers)     5
1.1  All Sequences                    5
2.1.1 Mixed One-Suit                 40
2.1.2 Pure One-Suit                  80
2.2  Nine Gates                     480
3.1  Value Honor                  10/set
3.2.1 Small Three Dragons            40
3.2.2 Big Three Dragons             130
3.3.3 Small Four Winds              320
3.3.4 Big Four Winds                400
3.4  All Honors                     320
4.1  All Triplets                    30
4.2.3 Four Concealed Triplets       130  # note: 130 here vs ZJ 125
4.3.4 Four Kong                     480
8.1.3 Mixed Greater Terminals       100
8.1.4 Pure Greater Terminals        400
9.1.1 Final Draw                     10
9.2  Win on Kong                     10
9.3  Robbing a Kong                  10
9.4.1 Heaven                        160  # vs ZJ 155
9.4.2 Earth                         160
10.1 Thirteen Terminals             160
```

Omitted vs full ZJ: most “new-style” identity/similar/consecutive/lesser-terminal/incidental mid patterns, Small/Big Three Winds mid-series, etc.

---

## 5. Teaching / family progression

Source: https://www.zj-mahjong.info/zj_child_eng.html

Recommended category intro order:

```
3 honors → 2 one-suit → 4 triplets/kong → 8 terminals
→ 1 trivial → 5 identical → 6 similar → 7 consecutive
→ 9 incidental → 10 irregular
```

Until cat 1 introduced: many chickens → may add temporary basic 5.  
Use Uniform Payoff until all cats learned; family may use win-only point accumulation (no losses).
