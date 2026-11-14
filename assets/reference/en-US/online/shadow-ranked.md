# Shadow Ranked

**Shadow Ranked** is the name of a special ranked format, a combination of four things:

1. **The duplicate format** — each Shadow Ranked game you play is a specific **challenge** that other players played before (and/or will play after) you: the starting hands, the wall, and the seat are guaranteed to be exactly the same, bringing down the luck factor to a minimum. Your result is not the score you get relative to the other three players you play with, but how your total compares with the totals of all other players who played the same challenge with the same tiles and in the same seat.
2. **A game against three bots** — in each Shadow Ranked game you play against three bots (the same ones that are used in Live ranked games as fillers). This removes the need for other players to be online.
3. **Asynchronous comparison** — players don't have to be online at the same time, and each result is compared with the others, moving ratings of both players.
4. **A dynamic pool of challenges** — the server reuses an open challenge when one is available and creates a new one only when there is none. Each is opened to other players and retired once it holds **10 human players** or after **30 days**.

## Why it exists

Live ranked games need several people online at the same moment, and that rarely happens. Comparing the scores of the players at a table has a further problem: Mahjong is part skill and part luck, and each seat is dealt different tiles, so comparing players' totals says as much about the deal as about the play. More hands reduce luck's influence, but they can't eliminate it. Shadow Ranked solves both: playing the same challenge nearly removes luck from the comparison, and you don't have to wait for other players.

## How a game works

1. Open **Online → Shadow Ranked** and sign in.
2. Choose the length — **1 round** (4 hands) or **2 rounds** (8 hands) — and whether **patternless hands** are allowed. See [Patternless hand](scoring-chicken-hand).
3. Press **Start a game**. The server gives you a challenge of that type that you haven't played yet, and creates a new one only when there is none. It prefers challenges other players have already finished — so you have scores to compare against — but you can also be the first human player in one. You're never without a comparison, though: a bot has already played in your seat, so even as the first human player you're compared with its total right away.

You don't choose the challenge and you can't see anything about it in advance. Each challenge has an id like [hl]7K3H-M9PQ[/], shown after you finish.

The pace is relaxed: **20 seconds** per decision — your own turn, or responding to a discard — plus a **90-second** reserve each hand. If your connection drops, reconnect to continue the same game.

## Who you are compared with

A challenge is fixed in advance: the same wall of tiles, the same starting hand in every hand, and the same bots. Each seat draws from its own pile of the wall. When a seat's pile runs out, the draw spills from the longest pile belonging to another seat, so which tile comes next depends on how the hand has been played — the tiles are the same for everyone, but the draw order is not fixed in advance. Every player who takes on this challenge gets the same starting hand and the same seat, so differences in the total come only from how the game was played.

When you finish, your **match total** for the game is compared with:

- **The bot.** When a challenge is created, a bot plays in your seat first. Its total is the score to beat. This bot is rated **1200** and its rating never changes. You can't see its score until you finish.
- **Every player who finished this challenge before you.**
- **Every player who finishes it after you.** Their results arrive later and move your rating again.

Each comparison is a win, a loss, or (on an exactly equal total) a draw. A challenge holds up to **10 human players**, so it can give you as many as **nine** others to compare with, plus the bot.

## Rating

Each comparison changes your rating as it would against one opponent in a live game of the same length. Beating a stronger player gains you more; losing to a weaker one costs you more. Both players in a comparison have their ratings updated, using each player's rating at the time they played.

For example, at 1200, beating the 1200 bot on a 1-round challenge gives about **+2.67**. On a 2-round challenge the same win gives about **+5.33**. A challenge with several players means several comparisons, and each one counts in full.

Shadow Ranked uses the same rating as live ranked games.

## After you finish

The results table shows the bot and every earlier player: their score, their rating, and how much each comparison changed yours.

**Past attempts** on the Shadow Ranked tab opens the list of every challenge you've played: how many players it has so far and your total rating change from it. A completed challenge also shows the scores — the bot and every player who has finished it so far, including those who played after you.

When someone plays one of your challenges later, the result is held and gathered into your next **digest**: a summary of every later result, with each score and your rating change. You choose how often a digest is due in **Settings**, from **every hour** to **once a day** (the default).

The digest is delivered on one channel. While the app is open it appears there; if the app is closed and you have opted into email, it is emailed instead. Opening the app before a waiting email goes out delivers the digest in the app and drops the email. You can opt into email in **Settings**.

## Fair play

- **One attempt per challenge.** Once you start a challenge, it counts as played. You can't get it again, even if you don't finish it.
- **The server deals the tiles.** Your device only receives what you are allowed to see, so the hidden tiles can't be read from the app.
- **Challenges are handed out unpredictably.** After someone is given a challenge, starts it, or finishes it, nobody else gets it for two hours, and the choice among open challenges is partly random. This makes it hard to send a second account ahead to learn a challenge.
