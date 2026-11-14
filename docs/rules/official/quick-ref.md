# Zung Jung v3.3 — Quick Pattern Table

Source: book_en/App G - zj33_patterns_en_brief.html

```
id      name_en                          pts
1.1     All Sequences                      5
1.2     Concealed Hand                     5
1.3     No Terminals                       5
2.1.1   Mixed One-Suit                    40
2.1.2   Pure One-Suit                     80
2.2     Nine Gates                       480
3.1     Value Honor                   10/set
3.2.1   Small Three Dragons               40
3.2.2   Big Three Dragons                130
3.3.1   Small Three Winds                 30
3.3.2   Big Three Winds                  120
3.3.3   Small Four Winds                 320
3.3.4   Big Four Winds                   400
3.4     All Honors                       320
4.1     All Triplets                      30
4.2.1   Two Concealed Triplets             5
4.2.2   Three Concealed Triplets          30
4.2.3   Four Concealed Triplets          125
4.3.1   One Kong                           5
4.3.2   Two Kong                          20
4.3.3   Three Kong                       120
4.3.4   Four Kong                        480
5.1.1   Two Identical Sequences           10
5.1.2   Two Identical Sequences Twice     60
5.1.3   Three Identical Sequences        120
5.1.4   Four Identical Sequences         480
6.1     Three Similar Sequences           35
6.2.1   Small Three Similar Triplets      30
6.2.2   Three Similar Triplets           120
7.1     Nine-Tile Straight                40
7.2.1   Three Consecutive Triplets       100
7.2.2   Four Consecutive Triplets        200
8.1.1   Mixed Lesser Terminals            40
8.1.2   Pure Lesser Terminals             50
8.1.3   Mixed Greater Terminals          100
8.1.4   Pure Greater Terminals           400
9.1.1   Final Draw                        10
9.1.2   Final Discard                     10
9.2     Win on Kong                       10
9.3     Robbing a Kong                    10
9.4.1   Blessing of Heaven               155
9.4.2   Blessing of Earth                155
10.1    Thirteen Terminals               160
10.2    Seven Pairs                       30
CHICKEN chicken hand (no patterns)         1
LIMIT   compound sum cap                 320
```

## Payoff cheat sheet (formal)

```
winner_income = 3 * V
self_draw: each pays V
win on discard V<=25: each pays V
win on discard V>25:
  others each pay 25
  discarder pays 3*V - 50
```
