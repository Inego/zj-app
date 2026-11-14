# Russian terminology (Чжун-юн / Zung Jung)

Glossary for UI chrome (`assets/i18n/ui_ru.properties`) and the reference pack
(`assets/reference/ru/`). Graduated from working notes during localization Stages 2–3.

**Name in Russian UI and reference text: Чжун-юн** (transliteration of 中庸 / Zung Jung). Keep Latin “Zung Jung” only in English sources, code comments, and bilingual notes.

**Zung Jung / Чжун-юн (中庸麻雀 / “Middle Way”) is a pattern-based additive scoring system by Alan Kwan (v3.3 is the common reference). It has 44 patterns in 10 categories. There is no established Russian translation specifically for Zung Jung beyond this name form, so pattern and table terms draw primarily from established Russian MCR (“спортивный маджонг” / “Зелёная книга”) terminology used by the Russian Mahjong Federation and sites like mahjong.ru.**

Russian MCR prefers native or calqued terms over pure transliterations where possible (e.g., “чоу”, “панг/понг”, “конг”, “пара”, “закрытая рука”, “масть”, “терминальные/благородные кости”, “фан” for scoring elements). Basic gameplay terms align closely across variants.

**This project is not Riichi, in any language.** The English vocabulary is the one the bundles already use — chow, pung, kong, hu, self-draw, discard, river — and Japanese Riichi loanwords (*tsumo*, *ron*, *riichi*, *tenpai*, *yaku*) appear nowhere: not in the UI, not in the reference pack, not in code, comments, commit messages or docs. Riichi is the ruleset most readers arrive from, which is exactly why the reference pack's [overview](../assets/reference/en-US/rules/overview.md) introduces Zung Jung as a *different* family; the wording has to hold that line too.

### Core basic terms (shared with MCR)
Canonical UI wording lives in `assets/i18n/ui_ru.properties` (native-speaker edits). Keep the reference pack aligned with it.

- Sequence / Chow / Chi → **чоу** (ряд/последовательность)
- Triplet / Pung → **панг** (or **понг**)
- Kong / Quad → **конг**
- Pair / Eyes → **пара** (глаза)
- Concealed (hand/set) → **закрытая** / **закрытый**
- Exposed / Melded → **открытая** / **открытый**
- Self-draw → **взятие со стены** / **самостоятельный выигрыш**
- Win on discard → **выигрыш на сбросе**
- Discard (tile/action) → **сброс** (not “снос”)
- Seat Wind → **ветер места** (собственный ветер)
- Dragons → **драконы** (Белый, Зелёный, Красный)
- Winds → **ветры** (Восток, Юг, Запад, Север)
- Terminals (1s & 9s) → **терминальные кости** (or “терминалы”)
- Simples / Middles (2–8) → **простые** / **средние кости**
- Honors → **благородные кости**
- Suits → **масти** (точки/круги, бамбуки, иероглифы/символы/вана)
- Regular hand → **стандартная рука** (4 сета + пара)
- Irregular hand → **нестандартная рука**
- Scoring pattern → **комбинация** (not loanword “паттерн”)
- Patternless hand (no scoring combinations) → **рука без комбинаций** (token 1 point; setup: **разрешить руки без комбинаций**)
- Bot seat (synthetic online opponent) → **Бот** / **Бот 3** — composed by the client from the `is_bot` flag (`seat.bot`, `seat.bot_numbered`), never taken from the server's English wire placeholder
- Dead wall / King’s tiles → **мёртвая стена**
- Seabed tile → **последняя кость стены** (or “морское дно”)
- Riverbed tile → **последний сброс**

### Recommended translations for Zung Jung patterns (v3.3)
Aligned with Russian MCR fan names where patterns overlap or are analogous (e.g., from the Green Book translations by Petr Manyakhin and Federation usage). Point values are from common English sources (minor version differences exist, e.g., Pure One-Suit sometimes listed as 80 or 100).

**1.0 Trivial patterns**
- 1.1 All Sequences (平和) → **Все последовательности** (or “Все чоу”)
- 1.2 Concealed Hand (門前清) → **Закрытая рука**
- 1.3 No Terminals (斷么九) → **Все простые** (or “Без терминалов и благородных”)

**2.0 One-Suit patterns**
- 2.1.1 Mixed One-Suit / Half Flush (混一色) → **Полумасть** / **Смешанная одна масть**
- 2.1.2 Pure One-Suit / Full Flush (清一色) → **Чистая масть** / **Полная одна масть**
- 2.2 Nine Gates (九蓮寶燈) → **Девять врат**

**3.0 Honor Tiles**
- 3.1 Value Honor (番牌) → **Ценные благородные** / **Панг/конг дракона или ветра места** (10 за сет; prevailing/table wind not scored)
- 3.2.1 Small Three Dragons (小三元) → **Малые три дракона**
- 3.2.2 Big Three Dragons (大三元) → **Большие три дракона**
- 3.3.1 Small Three Winds (小三風) → **Малые три ветра**
- 3.3.2 Big Three Winds (大三風) → **Большие три ветра**
- 3.3.3 Small Four Winds (小四喜) → **Малые четыре ветра**
- 3.3.4 Big Four Winds (大四喜) → **Большие четыре ветра**
- 3.4 All Honors (字一色) → **Все благородные**

**4.0 Triplets and Kong**
- 4.1 All Triplets (對對和) → **Все панги**
- 4.2.1 Two Concealed Triplets (二暗刻) → **Два закрытых панга**
- 4.2.2 Three Concealed Triplets (三暗刻) → **Три закрытых панга**
- 4.2.3 Four Concealed Triplets (四暗刻) → **Четыре закрытых панга**
- 4.3.1 One Kong (一槓) → **Один конг**
- 4.3.2 Two Kong (二槓) → **Два конга**
- 4.3.3 Three Kong (三槓) → **Три конга**
- 4.3.4 Four Kong (四槓) → **Четыре конга**

**5.0 Identical Sets** (same suit + same numbers; sequences only)
- 5.1.1 Two Identical Sequences (一般高) → **Два одинаковых чоу** / **Чистое двойное чоу**
- 5.1.2 Two Identical Sequences Twice (兩般高) → **Дважды два одинаковых чоу**
- 5.1.3 Three Identical Sequences (一色三同順) → **Три одинаковых чоу** (одной масти)
- 5.1.4 Four Identical Sequences (一色四同順) → **Четыре одинаковых чоу** (одной масти)

**6.0 Similar Sets** (same numbers across all 3 suits)
- 6.1 Three Similar Sequences (三色同順) → **Смешанное тройное чоу** / **Три одинаковых чоу в трёх мастях**
- 6.2.1 Small Three Similar Triplets (三色小同刻) → **Малые три смешанных панга**
- 6.2.2 Three Similar Triplets (三色同刻) → **Три смешанных панга**

**7.0 Consecutive Sets** (same suit, consecutive numbers)
- 7.1 Nine-Tile Straight (一氣通貫) → **Чистый ряд** / **Полный ряд** (123+456+789 одной масти)
- 7.2.1 Three Consecutive Triplets (三連刻) → **Три последовательных панга**
- 7.2.2 Four Consecutive Triplets (四連刻) → **Четыре последовательных панга**

**8.0 Terminals**
- 8.1.1 Mixed Lesser Terminals (混全帶么) → **Смешанные малые терминалы** (каждый сет и пара содержат терминал или благородный)
- 8.1.2 Pure Lesser Terminals (純全帶么) → **Чистые малые терминалы**
- 8.1.3 Mixed Greater Terminals (混么九) → **Смешанные большие терминалы** / **Все терминальные и благородные**
- 8.1.4 Pure Greater Terminals (清么九) → **Чистые большие терминалы** / **Все терминальные**

**9.0 Incidental bonuses**
- 9.1.1 Final Draw (海底撈月) → **Последняя кость стены** / **Выигрыш последней кости**
- 9.1.2 Final Discard (河底撈魚) → **Последний сброс**
- 9.2 Win on Kong (嶺上開花) → **Выигрыш на замене конга** / **Цветок на горе**
- 9.3 Robbing a Kong (搶槓) → **Ограбление конга**
- 9.4.1 Blessing of Heaven (天和) → **Благословение Небес**
- 9.4.2 Blessing of Earth (地和) → **Благословение Земли**

**10.0 Irregular Hands** (do not score Concealed Hand)
- 10.1 Thirteen Terminals (十三么九) → **Тринадцать сирот** / **Тринадцать терминалов**
- 10.2 Seven Pairs (七對子) → **Семь пар**

### Notes on usage and consistency
- Prefer MCR-style names for familiarity among Russian players (e.g., “Все последовательности”, “Полумасть”, “Девять врат”, “Большие/Малые три/четыре ветра/дракона”, “Все благородные”, “Все панги”, “Закрытая рука”, “Все простые”, “Тринадцать сирот”, “Семь пар”).
- Zung Jung differences to flag: additive points (no fan multipliers), no prevailing wind scoring, no minimum for most hands (chicken hand = 1 pt), specific payoff scheme (self-draw: all pay full; discard ≤25: all pay full; discard >25: special split), compound limit often 320 (listed high-value patterns score face value alone).
- Chinese characters can be kept in parentheses for precision (as in official Zung Jung English docs).
- Sources for English patterns: official Zung Jung site (zj-mahjong.info), Mahjong Wiki, Mahjong Time WSOM pages, San Diego/McGill club summaries.
- Russian MCR references: mahjong.ru Green Book translations and fan tables.

These mappings prioritize consistency with existing Russian competitive Mahjong discourse. For a full rules document or club use, cross-check exact point values and interaction rules against the latest Zung Jung Movement or Alan Kwan materials, as minor numbering/point variations appear across mirrors.
