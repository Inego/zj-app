# Stínový žebříček

**Stínový žebříček** je název zvláštního hodnoceného formátu, kombinace čtyř věcí:

1. **Formát duplikátu** — každá hra Stínového žebříčku, kterou hraješ, je konkrétní **výzva**, kterou jiní hráči hráli před tebou (a/nebo budou hrát po tobě): počáteční ruky, zeď i místo jsou zaručeně úplně stejné, což stahuje vliv štěstí na minimum. Tvůj výsledek není skóre, kterého dosáhneš oproti třem hráčům, s nimiž hraješ, ale to, jak se tvoje celkové skóre srovnává s celkovými skóre všech ostatních hráčů, kteří hráli stejnou výzvu se stejnými kameny a na stejném místě.
2. **Hra proti třem botům** — v každé hře Stínového žebříčku hraješ proti třem botům (týmiž, kteří v režimu Živě doplňují chybějící hráče v hodnocených hrách). Díky tomu nemusí být ostatní hráči online.
3. **Asynchronní srovnání** — hráči nemusí být online ve stejnou chvíli a každý výsledek se srovnává s ostatními, což posouvá hodnocení obou hráčů.
4. **Dynamický fond výzev** — server znovu použije otevřenou výzvu, když je nějaká k dispozici, a novou vytvoří jen tehdy, když žádná není. Každá se otevře ostatním hráčům a uzavře se, jakmile má **10 lidských hráčů** nebo uplyne **30 dní**.

## Proč existuje

Hodnocené hry v režimu Živě potřebují několik lidí online ve stejnou chvíli, a to se stává zřídka. Srovnávání skóre hráčů u stolu má ještě jeden problém: mahjong je částečně dovednost a částečně štěstí a každé místo dostane jiné kameny, takže srovnání celkových skóre vypovídá stejně tak o rozdání jako o hře. Více rozdání vliv štěstí snižuje, ale nedokáže ho odstranit. Stínový žebříček řeší obojí: hraní stejné výzvy štěstí ze srovnání téměř vymaže a nemusíš čekat na ostatní hráče.

## Jak hra probíhá

1. Otevři **Online → Stínový žebříček** a přihlas se.
2. Vyber délku — **1 kolo** (4 rozdání) nebo **2 kola** (8 rozdání) — a zda povolit **ruky bez vzorce**. Viz [Ruka bez vzorce](scoring-chicken-hand).
3. Stiskni **Začít hru**. Server ti dá výzvu tohoto typu, kterou ještě nemáš odehranou, a novou vytvoří jen tehdy, když žádná není. Upřednostňuje výzvy, které jiní hráči už dokončili — takže máš skóre k porovnání — ale můžeš být i první lidský hráč v některé z nich. Bez srovnání ale nikdy nezůstaneš: v tvém místě už hrál bot, takže i jako první lidský hráč se hned srovnáváš s jeho celkovým skóre.

Výzvu si nevybíráš a předem o ní nic nevidíš. Každá výzva má id jako [hl]7K3H-M9PQ[/], které se ukáže po dohrání.

Tempo je klidné: **20 sekund** na každé rozhodnutí — tvůj vlastní tah nebo reakci na odhoz — plus **90 sekund** rezervy na každé rozdání. Když ti spadne připojení, připoj se znovu a pokračuj ve stejné hře.

## S kým se srovnáváš

Výzva je daná předem: stejná zeď kamenů, stejná počáteční ruka v každém rozdání a stejní boti. Každé místo dobírá z vlastní části zdi. Když se část některého místa vyčerpá, dobírání se přesune do největší části patřící jinému místu, takže který kámen přijde další, závisí na tom, jak se hrálo — kameny jsou pro všechny stejné, ale pořadí dobírání není dané předem. Každý hráč, který tuto výzvu přijme, dostane stejnou počáteční ruku i stejné místo, takže rozdíly v celkovém skóre vznikají jen tím, jak se hrálo.

Když dohraješ, tvoje **celkové skóre** za hru se srovná s:

- **Bot.** Když výzva vznikne, hraje v tvém místě nejdřív bot. Jeho celkové skóre je to, co musíš překonat. Tento bot má hodnocení **1200** a to se nikdy nemění. Jeho skóre uvidíš až po dohrání.
- **Každý hráč, který tuto výzvu dokončil před tebou.**
- **Každý hráč, který ji dokončí po tobě.** Jeho výsledky dorazí později a znovu pohnou tvým hodnocením.

Každé srovnání je výhra, prohra, nebo (při přesně stejném celkovém skóre) remíza. Výzva pojme až **10 lidských hráčů**, takže ti může dát až **devět** dalších ke srovnání, plus bota.

## Hodnocení

Každé srovnání mění tvoje hodnocení stejně, jako by šlo o jednoho soupeře v hodnocené hře stejné délky v režimu Živě. Když porazíš silnějšího hráče, získáš víc; když prohraješ se slabším, ztratíš víc. V každém srovnání se aktualizuje hodnocení obou hráčů, a to z hodnoty platné v době jejich hry.

Například při hodnocení 1200 ti výhra nad botem s hodnocením 1200 ve výzvě na 1 kolo dá asi **+2,67**. Ve výzvě na 2 kola dá stejná výhra asi **+5,33**. Výzva s několika hráči znamená několik srovnání a každé se počítá plně.

Stínový žebříček používá stejné hodnocení jako hodnocené hry v režimu Živě.

## Až dohraješ

Tabulka výsledků ukazuje bota a každého dřívějšího hráče: jeho skóre, jeho hodnocení a o kolik každé srovnání změnilo tvoje hodnocení.

**Minulé pokusy** na kartě Stínový žebříček otevřou seznam všech odehraných výzev: kolik má která zatím hráčů a jaká je tvoje celková změna hodnocení z ní. Dokončená výzva ukazuje i skóre — bota a každého hráče, který ji dosud dokončil, včetně těch, kteří hráli po tobě.

Když někdo později hraje jednu z tvých výzev, výsledek se podrží a počká na tvůj příští **souhrn**: přehledu všech pozdějších výsledků s každým skóre a změnou tvého hodnocení. Jak často ti má souhrn chodit, si vybereš v **Nastavení**, od **každé hodiny** až po **jednou denně** (výchozí).

Souhrn se doručuje jedním kanálem. Dokud je aplikace otevřená, objeví se v ní; když je aplikace zavřená a máš zapnutý e-mail, pošle se místo toho e-mailem. Když aplikaci otevřeš dřív, než čekající e-mail odejde, souhrn se doručí v aplikaci a e-mail se zahodí. E-mail si můžeš zapnout v **Nastavení**.

## Férová hra

- **Jeden pokus na výzvu.** Jakmile výzvu začneš, počítá se jako odehraná. Znovu ji nedostaneš, ani když ji nedokončíš.
- **Kameny rozdává server.** Tvoje zařízení dostane jen to, co smíš vidět, takže skryté kameny nejde z aplikace vyčíst.
- **Výzvy se přidělují nepředvídatelně.** Když někdo výzvu dostane, začne nebo dokončí, dva hodiny ji nikdo jiný nedostane a výběr mezi otevřenými výzvami je částečně náhodný. Díky tomu je těžké poslat napřed druhý účet, aby výzvu poznal.
