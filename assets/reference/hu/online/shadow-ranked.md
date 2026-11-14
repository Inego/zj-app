# Árnyékos rangsorolt

Az **Árnyékos rangsorolt** egy különleges rangsorolt formátum neve, amely négy dolog kombinációja:

1. **A duplikát formátum** — minden Árnyékos rangsorolt játék, amelyet játszik, egy konkrét **duplikát**, amelyet más játékosok már játszottak Ön előtt (és/vagy fognak játszani Ön után): a kezdő kezek, a fal és a hely garantáltan pontosan ugyanazok, ami a minimumra csökkenti a szerencse szerepét. Az eredménye nem az a pontszám, amelyet a másik három játékoshoz képest ér el, hanem az, hogy a végösszege hogyan viszonyul mindazoknak a végösszegéhez, akik ugyanezt a duplikátot játszották ugyanazokkal a kövekkel és ugyanazon a helyen.
2. **Játék három bot ellen** — minden Árnyékos rangsorolt játékban három bot ellen játszik (ugyanazok ellen, amelyeket az élő rangsorolt játékokban is használnak kiegészítésként). Így nincs szükség arra, hogy más játékosok online legyenek.
3. **Aszinkron összehasonlítás** — a játékosoknak nem kell egyszerre online lenniük, és minden eredményt összehasonlítanak a többivel, ami mindkét játékos értékelését elmozdítja.
4. **A duplikátok dinamikus készlete** — a szerver újrahasznál egy nyitott duplikátot, ha van elérhető, és csak akkor hoz létre újat, ha nincs. Mindegyiket megnyitják más játékosok előtt, és visszavonják, amint **10 emberi játékos** van benne, vagy **30 nap** után.

## Miért van rá szükség

Az élő rangsorolt játékokhoz arra van szükség, hogy több ember egyszerre online legyen, és ez ritkán fordul elő. Az asztal játékosai pontszámainak összehasonlításának van egy további problémája is: a mahjong részben tudás, részben szerencse, és minden hely más köveket kap, így a játékosok végösszegeinek összehasonlítása legalább annyit mond az osztásról, mint a játékmenetről. A több leosztás csökkenti a szerencse hatását, de nem tudja megszüntetni. Az Árnyékos rangsorolt mindkét problémát megoldja: ugyanannak a duplikátnak a játszása szinte teljesen eltünteti a szerencsét az összehasonlításból, és nem kell más játékosokra várni.

## Hogyan zajlik egy játék

1. Nyissa meg az **Online → Árnyékos rangsorolt** menüpontot, és jelentkezzen be.
2. Válassza ki a hosszúságot — **1 forduló** (4 leosztás) vagy **2 forduló** (8 leosztás) —, valamint azt, hogy engedélyezettek-e a **minta nélküli kezek**. Lásd: [Minta nélküli kéz](scoring-chicken-hand).
3. Nyomja meg a **Játék indítása** gombot. A szerver ad Önnek egy ilyen típusú duplikátot, amelyet még nem játszott, és csak akkor hoz létre újat, ha nincs ilyen. Előnyben részesíti azokat a duplikátokat, amelyeket más játékosok már befejeztek — hogy legyen mihez hasonlítani a pontszámait —, de Ön lehet az első emberi játékos is egyben. Összehasonlítás nélkül azonban soha nem marad: egy bot már játszott az Ön helyén, így már első emberi játékosként is azonnal az ő végösszegével hasonlítják össze.

A duplikátot Ön nem választja ki, és előre semmit sem láthat belőle. Minden duplikátnak van egy azonosítója, például [hl]7K3H-M9PQ[/], amely a befejezés után jelenik meg.

A tempó nyugodt: döntésenként **20 másodperc** — a saját körében vagy egy eldobásra válaszolva —, plusz leosztásonként **90 másodperc** tartalék. Ha a kapcsolat megszakad, csatlakozzon újra ugyanannak a játéknak a folytatásához.

## Kikkel hasonlítják össze

A duplikát előre rögzített: ugyanaz a fal, ugyanaz a kezdő kéz minden leosztásban, és ugyanazok a botok. Minden hely a fal saját kötegéből húz. Amikor egy hely kötege kimerül, a húzás egy másik hely leghosszabb kötegéből folytatódik, így az, hogy melyik kő következik, attól függ, hogyan játszották a leosztást — a kövek mindenki számára ugyanazok, de a húzási sorrend előre nincs rögzítve. Minden játékos, aki megjátssza ezt a duplikátot, ugyanazt a kezdő kezet és ugyanazt a helyet kapja, így a végösszeg különbségei csak abból származnak, hogyan játszották a játékot.

Amikor befejezi, a játékra vonatkozó **meccs végösszegét** összehasonlítják:

- **A bot.** Amikor létrejön egy duplikát, először egy bot játszik az Ön helyén. Az ő végösszege a megdöntendő pontszám. Ennek a botnak **1200** az értékelése, és az értékelése soha nem változik. A pontszámát nem láthatja, amíg be nem fejezi.
- **Minden játékos, aki Ön előtt fejezte be ezt a duplikátot.**
- **Minden játékos, aki Ön után fejezi be.** Az eredményeik később érkeznek meg, és ismét elmozdítják az értékelését.

Minden összehasonlítás győzelem, vereség vagy (pontosan egyenlő végösszeg esetén) döntetlen. Egy duplikát legfeljebb **10 emberi játékost** tartalmaz, így a bot mellett akár **kilenc** másik játékost is kaphat az összehasonlításhoz.

## Értékelés

Minden összehasonlítás ugyanúgy változtatja meg az értékelését, mintha egyetlen ellenfél ellen játszana egy ugyanilyen hosszú élő játékban. Egy erősebb játékos legyőzése többet hoz; egy gyengébb játékostól elszenvedett vereség többet visz el. Egy összehasonlításban mindkét játékos értékelése frissül, mindegyiküknek a játék idején érvényes értékelését használva.

Például 1200-as értékeléssel az 1200-as bot legyőzése egy 1 fordulós duplikáton körülbelül **+2.67**-et ad. Egy 2 fordulós duplikáton ugyanez a győzelem körülbelül **+5.33**-at ad. Egy több játékost tartalmazó duplikát több összehasonlítást jelent, és mindegyik teljes egészében számít.

Az Árnyékos rangsorolt ugyanazt az értékelést használja, mint az élő rangsorolt játékok.

## Miután befejezte

Az eredménytáblázat mutatja a botot és minden korábbi játékost: a pontszámukat, az értékelésüket, és azt, hogy az egyes összehasonlítások mennyit változtattak az Önén.

**Saját próbálkozásaim** az Árnyékos rangsorolt lapon mutatja az összes duplikátot, amelyet játszott: hogy eddig hány játékos van benne, és az abból származó teljes értékelésváltozását. Egy befejezett duplikát a pontszámokat is mutatja — a botét és minden eddig befejező játékosét, beleértve azokat is, akik Ön után játszottak.

Amikor valaki később megjátssza az egyik duplikátját, az eredményt visszatartják, és összegyűjtik a következő **összefoglalójába**: minden későbbi eredmény összegzése, minden pontszámmal és az Ön értékelésváltozásával. Azt, hogy milyen gyakran esedékes egy összefoglaló, a **Beállításokban** választhatja ki: az **óránkéntitől** a **naponta egyszeriig** (ez az alapértelmezés).

Az összefoglaló egyetlen csatornán érkezik. Amíg az alkalmazás nyitva van, ott jelenik meg; ha az alkalmazás zárva van, és bekapcsolta az e-mailt, akkor helyette e-mailben küldik el. Ha az alkalmazást egy várakozó e-mail elküldése előtt megnyitja, az összefoglaló az alkalmazásban jelenik meg, és az e-mailt elvetik. Az e-mailt a **Beállításokban** kapcsolhatja be.

## Tisztességes játék

- **Duplikátonként egy próbálkozás.** Amint elkezd egy duplikátot, az játszottnak számít. Nem kaphatja meg újra, akkor sem, ha nem fejezi be.
- **A szerver osztja a köveket.** Az eszköze csak azt kapja meg, amit látnia szabad, így a rejtett kövek nem olvashatók ki az alkalmazásból.
- **A duplikátokat kiszámíthatatlanul osztják ki.** Miután valaki kap egy duplikátot, elkezdi vagy befejezi, két órán keresztül senki más nem kapja meg, és a nyitott duplikátok közötti választás részben véletlenszerű. Ez megnehezíti, hogy egy második fiókot előre küldjön egy duplikát kifürkészéséhez.
