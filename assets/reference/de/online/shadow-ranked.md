# Schatten-Ranked

**Schatten-Ranked** ist der Name eines besonderen gewerteten Formats, einer Kombination aus vier Dingen:

1. **Das Duplikatformat** — jedes Spiel im Schatten-Ranked, das du spielst, ist ein bestimmtes **Duplikat**, das andere Spieler vor dir gespielt haben (und/oder nach dir spielen werden): die anfänglichen Hände, die Mauer und der Sitz sind garantiert genau gleich, was den Glücksfaktor auf ein Minimum reduziert. Dein Ergebnis ist nicht die Punktzahl, die du im Verhältnis zu den drei anderen Spielern erzielst, mit denen du spielst, sondern wie deine Gesamtpunktzahl im Vergleich zu den Gesamtpunktzahlen aller anderen Spieler abschneidet, die dasselbe Duplikat mit denselben Steinen und auf demselben Sitz gespielt haben.
2. **Ein Spiel gegen drei Bots** — in jedem Spiel im Schatten-Ranked spielst du gegen drei Bots (dieselben, die in gewerteten Live-Spielen als Lückenfüller verwendet werden). Dadurch müssen andere Spieler nicht online sein.
3. **Asynchroner Vergleich** — Spieler müssen nicht gleichzeitig online sein, und jedes Ergebnis wird mit den anderen verglichen, wodurch sich die Wertung beider Spieler ändert.
4. **Ein dynamischer Pool von Duplikaten** — der Server verwendet ein offenes Duplikat wieder, wenn eines verfügbar ist, und erstellt nur dann ein neues, wenn keines da ist. Jedes wird für andere Spieler geöffnet und geschlossen, sobald es **10 menschliche Spieler** enthält oder nach **30 Tagen**.

## Warum es das gibt

Für gewertete Live-Spiele müssen mehrere Personen gleichzeitig online sein, was selten vorkommt. Der Vergleich der Punktzahlen der Spieler an einem Tisch hat ein weiteres Problem: Mahjong ist teils Können, teils Glück, und jeder Sitz bekommt andere Steine ausgeteilt, daher sagt der Vergleich der Gesamtpunktzahlen der Spieler genauso viel über die Austeilung wie über das Spiel aus. Mehr Hände verringern den Einfluss des Glücks, können ihn aber nicht ausschalten. Schatten-Ranked löst beides: dasselbe Duplikat zu spielen nimmt das Glück fast vollständig aus dem Vergleich, und du musst nicht auf andere Spieler warten.

## Wie ein Spiel abläuft

1. Öffne **Online → Schatten-Ranked** und melde dich an.
2. Wähle die Länge — **1 Runde** (4 Hände) oder **2 Runden** (8 Hände) — und ob **musterlose Hände** erlaubt sind. Siehe [Musterlose Hand](scoring-chicken-hand).
3. Drücke **Spiel starten**. Der Server gibt dir ein Duplikat dieses Typs, das du noch nicht gespielt hast, und erstellt nur dann ein neues, wenn keines da ist. Er bevorzugt Duplikate, die andere Spieler bereits beendet haben — damit du Punktzahlen zum Vergleichen hast —, aber du kannst in einem auch der erste menschliche Spieler sein. Einen Vergleich hast du aber trotzdem immer: Ein Bot hat bereits auf deinem Sitz gespielt, also wird deine Gesamtpunktzahl auch als erster menschlicher Spieler sofort mit seiner verglichen.

Du wählst das Duplikat nicht und kannst vorab nichts darüber erfahren. Jedes Duplikat hat eine ID wie [hl]7K3H-M9PQ[/], die nach deinem Beenden angezeigt wird.

Das Tempo ist entspannt: **20 Sekunden** pro Entscheidung — dein eigener Zug oder die Reaktion auf einen Abwurf — plus eine **90-Sekunden-Reserve** pro Hand. Wenn deine Verbindung abbricht, verbinde dich erneut, um dasselbe Spiel fortzusetzen.

## Mit wem du verglichen wirst

Ein Duplikat steht im Voraus fest: dieselbe Mauer aus Steinen, dieselbe anfängliche Hand in jeder Hand und dieselben Bots. Jeder Sitz zieht aus seinem eigenen Teil der Mauer. Wenn der Teil eines Sitzes aufgebraucht ist, geht die Ziehung aus dem längsten Teil eines anderen Sitzes hervor, sodass der nächste Stein davon abhängt, wie die Hand gespielt wurde — die Steine sind für alle gleich, aber die Ziehungsreihenfolge steht nicht im Voraus fest. Jeder Spieler, der dieses Duplikat annimmt, bekommt dieselbe anfängliche Hand und denselben Sitz, sodass Unterschiede in der Gesamtpunktzahl nur daraus entstehen, wie das Spiel gespielt wurde.

Wenn du fertig bist, wird deine **Gesamtpunktzahl** für das Spiel verglichen mit:

- **Dem Bot.** Wenn ein Duplikat erstellt wird, spielt zuerst ein Bot auf deinem Sitz. Seine Gesamtpunktzahl ist die Punktzahl, die es zu schlagen gilt. Dieser Bot hat eine Wertung von **1200**, und seine Wertung ändert sich nie. Seine Punktzahl kannst du erst nach deinem Beenden sehen.
- **Jedem Spieler, der dieses Duplikat vor dir beendet hat.**
- **Jedem Spieler, der es nach dir beendet.** Seine Ergebnisse kommen später an und verändern deine Wertung erneut.

Jeder Vergleich ist ein Sieg, eine Niederlage oder (bei genau gleicher Gesamtpunktzahl) ein Unentschieden. Ein Duplikat fasst bis zu **10 menschliche Spieler**, also kannst du bis zu **neun** andere zum Vergleichen haben, plus den Bot.

## Wertung

Jeder Vergleich ändert deine Wertung so, wie sie sich gegen einen Gegner in einem gewerteten Live-Spiel derselben Länge ändern würde. Besiegst du einen stärkeren Spieler, gewinnst du mehr; verlierst du gegen einen schwächeren, kostet es dich mehr. In einem Vergleich werden die Wertungen beider Spieler aktualisiert, wobei die Wertung jedes Spielers zum Zeitpunkt seines Spiels zugrunde gelegt wird.

Zum Beispiel bringt dir bei einer Wertung von 1200 ein Sieg gegen den 1200er Bot in einem Duplikat über 1 Runde etwa **+2.67**. In einem Duplikat über 2 Runden bringt derselbe Sieg etwa **+5.33**. Ein Duplikat mit mehreren Spielern bedeutet mehrere Vergleiche, und jeder zählt voll.

Schatten-Ranked verwendet dieselbe Wertung wie die gewerteten Live-Spiele.

## Nachdem du fertig bist

Die Ergebnistabelle zeigt den Bot und jeden früheren Spieler: seine Punktzahl, seine Wertung und um wie viel jeder Vergleich deine Wertung verändert hat.

**Meine Versuche** im Tab Schatten-Ranked zeigen jedes Duplikat, das du gespielt hast: wie viele Spieler es bisher hat und deine gesamte Wertungsänderung daraus. Ein abgeschlossenes Duplikat zeigt außerdem die Punktzahlen — den Bot und jeden Spieler, der es bisher beendet hat, auch die, die nach dir gespielt haben.

Wenn jemand später eines deiner Duplikate spielt, wird das Ergebnis zurückgehalten und in deinem nächsten **Digest** gesammelt: eine Zusammenfassung jedes späteren Ergebnisses mit jeder Punktzahl und deiner Wertungsänderung. Wie oft ein Digest fällig ist, wählst du in den **Einstellungen**, von **jede Stunde** bis **einmal täglich** (Standard).

Der Digest wird über einen einzigen Kanal zugestellt. Solange die App geöffnet ist, erscheint er dort; ist die App geschlossen und hast du E-Mail aktiviert, wird er stattdessen per E-Mail gesendet. Öffnest du die App, bevor eine wartende E-Mail versendet wird, wird der Digest in der App zugestellt und die E-Mail verworfen. E-Mail kannst du in den **Einstellungen** aktivieren.

## Faires Spiel

- **Ein Versuch pro Duplikat.** Sobald du ein Duplikat beginnst, zählt es als gespielt. Du kannst es nicht erneut bekommen, auch wenn du es nicht beendest.
- **Der Server teilt die Steine aus.** Dein Gerät empfängt nur, was du sehen darfst, daher können die verdeckten Steine nicht aus der App ausgelesen werden.
- **Duplikate werden unvorhersehbar ausgegeben.** Nachdem jemand ein Duplikat erhalten, begonnen oder beendet hat, bekommt es zwei Stunden lang niemand sonst, und die Auswahl unter den offenen Duplikaten ist teils zufällig. Dadurch ist es schwierig, ein zweites Konto vorauszuschicken, um ein Duplikat vorab zu erkunden.
