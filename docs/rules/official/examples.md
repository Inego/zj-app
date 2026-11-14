# Scoring Examples (official book Ch.1 §3)

Source: https://www.zj-mahjong.info/book_hk/1.2%20Examples.html  
Version: v3.3. Tile GIFs omitted; abstract forms used.

## Ex1 — Common All Sequences + No Terminals

```
melds: chi 567p, chi 345m, seq 345s, eyes 55m, win-complete 234s
patterns: 1.1 All Sequences 5 + 1.3 No Terminals 5
total: 10
note: two-suit "similar sequences" do NOT score 6.1 (need three suits)
```

## Ex2 — Value honor

```
West seat win: chi 567m, chi 789p, pong West, eyes White×2, pong 111p
patterns: 3.1 Seat Wind 10
total: 10
```

## Ex3 — Chicken hand

```
chi 567s, pong 333m, seq 456m, eyes Green×2, win-complete 234s
patterns: none
total: 1 (chicken)
note: honors present → no 1.3
```

## Ex4 — Multi-pattern medium hand + payoff

```
chi 678s, chi 678m, seq 678m (identical), eyes 55p, win-complete 678p
patterns:
  6.1 Three Similar Sequences  35
  5.1.1 Two Identical Sequences 10
  1.1 All Sequences             5
  1.3 No Terminals              5
total: 55
payoff (win on discard, formal):
  winner income 165
  discarder 115; others 25 each
```

## Ex5 — Mixed one-suit + straight + honor kong

```
chi 123p, open kong Red×4, seq 789p, eyes 22p, win-complete 456p
patterns:
  2.1.1 Mixed One-Suit  40
  7.1 Nine-Tile Straight 40
  3.1 Red               10
  4.3.1 One Kong         5
total: 95
note: discarder of completing 4p pays 235 (3*95-50)
```

## Ex6 — Seven pairs mixed greater terminals

```
pairs: 99s,99s,11p,EE,White×2,Green×2,Red×2
  (four 9s = two pairs; no kong declared)
patterns:
  8.1.3 Mixed Greater Terminals 100
  10.2 Seven Pairs               30
total: 130
notes:
  - no triplet-required patterns (no Big/Small Dragons from pairs alone)
  - counts 8.1.3, not lesser-terminals forms
```

## Ex7 — Mixed lesser terminals core

```
White×3, Green×3, 123s, eyes 99s, win-complete 789p
patterns:
  8.1.1 Mixed Lesser Terminals 40
  3.1 White 10 + Green 10
  4.2.1 Two Concealed Triplets  5
  1.2 Concealed Hand            5
total: 70
```

## Ex8 — Compound limit

```
concealed pure dots triplets: 222p 666p 777p 888p eyes 33p
patterns (site):
  2.1.2 Pure One-Suit           80
  7.2.1 Three Consecutive Triplets 100
  4.2.3 Four Concealed Triplets 125
  4.1 All Triplets               30
  1.2 Concealed Hand              5
raw sum: 340 → compound limit 320
```

## Ex9 — Same-round immunity subtlety

```
East: chi 345s, 777s, 777m, eyes 77p, calling 2p and 7p
- East self-draws 2p but discards it (wants 7p for 6.2.2)
- West later discards 2p; East wins on it
- West claims same-round immunity → three-way split (each pays V)
- V = 40: 6.2.1 Small Three Similar Triplets 30 + 1.3 5 + 4.2.1 5
critical:
  win-on-discard 2p → that triplet is EXPOSED (not concealed)
  if East had self-drawn-won on 2p earlier → Three Concealed Triplets path (65 each)
```

## Engine test seeds (abstract)

| id | expected_V | notes |
|---|---:|---|
| chicken | 1 | no patterns |
| all sequences + no terminals | 10 | |
| seat wind only | 10 | |
| 6.1+5.1.1+1.1+1.3 | 55 | big-hand payoff |
| 2.1.1+7.1+3.1+4.3.1 | 95 | |
| 8.1.3+10.2 | 130 | seven pairs |
| 8.1.1+3.1×2+4.2.1+1.2 | 70 | |
| pure + 7.2.1 + 4.2.3 + 4.1 + 1.2 | 320 | raw 340 capped |
