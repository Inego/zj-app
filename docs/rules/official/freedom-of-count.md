# Freedom of Count (自由數和)

Source: https://www.zj-mahjong.info/zj_freecount_eng.html  
Also restated in misc-rules / book Ch.1 §2.

## Rule

If multiple ways exist to arrange **concealed** tiles into a winning hand:

1. Winner may freely choose the arrangement best for them.
2. Score **only that one** arrangement.
3. Patterns from different arrangements cannot both be counted.

Forbidden combos (examples):

- Three Identical Sequences (5.1.3) **+** Three Consecutive Triplets (7.2.1)
- Two Identical Sequences Twice (5.1.2) **+** Seven Pairs (10.2)

## Worked example (classic 3×3 block)

Hand concept: dragon pair + 1-sou pair + 777,888,999 pin (or as three 789 sequences), win on 9-pin.

### Arrangement A — Three Identical Sequences

```
patterns:
  5.1.3 Three Identical Sequences  120
  8.1.1 Mixed Lesser Terminals      40
  3.1   Red (value honor)           10
total: 170
```

### Arrangement B — Three Consecutive Triplets (win on discard 9p)

Site page (older v3.0 numbers) listed 155; **v3.3 recompute:**

```
patterns (v3.3):
  7.2.1 Three Consecutive Triplets 100
  4.1   All Triplets                30
  3.1   Red                         10
  4.2.1 Two Concealed Triplets       5
total: 145
```

### Arrangement B with self-draw 9p

```
patterns (v3.3):
  7.2.1 Three Consecutive Triplets 100
  4.1   All Triplets                30
  3.1   Red                         10
  4.2.2 Three Concealed Triplets    30
total: 170
```

Self-draw can flip best arrangement because concealed-triplet series upgrades (win on discard: identical seq 170 wins; self-draw: consecutive path ties/competes at 170 with different pattern mix — site claimed consecutive wins under old pts).

## Heuristic notes (from author)

- Concealed 3×3 blocks rare; with Lesser Terminals rarer.
- If 4th set is a triplet → consecutive triplets often better (All Triplets).
- Else identical sequences often better (higher base + possible terminals).
- Two Identical Sequences Twice always beats Seven Pairs for pure score comparison of those two shapes → prefer 5.1.2 when both possible.

## Engine implication

When enumerating parsings of a winning hand:

```
best = max over arrangements A of score(A)
  where score(A) = series_sum(patterns(A)) with limit rules
```

Do not union pattern sets across arrangements.
