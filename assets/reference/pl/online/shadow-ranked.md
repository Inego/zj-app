# Cieniowa rankingowa

**Cieniowa rankingowa** to nazwa specjalnego formatu rankingowego, połączenia czterech elementów:

1. **Format na duplikatach** — każda gra w Cieniowej rankingowej to konkretny **duplikat**, który inni gracze rozegrali przed Tobą (i/lub rozegrają po Tobie): ręce początkowe w każdym rozdaniu, mur i miejsce są zagwarantowane jako dokładnie takie same, co sprowadza czynnik szczęścia do minimum. Twój rezultat to nie punkty, jakie zdobywasz względem trzech pozostałych graczy, z którymi grasz, lecz to, jak Twój wynik wypada na tle wyników wszystkich innych graczy, którzy rozegrali ten sam duplikat z tymi samymi kamieniami i na tym samym miejscu.
2. **Gra przeciwko trzem botom** — w każdej grze w Cieniowej rankingowej grasz przeciwko trzem botom (tym samym, które w rankingowych grach na żywo pełnią rolę uzupełnienia). Dzięki temu inni gracze nie muszą być online.
3. **Porównanie asynchroniczne** — gracze nie muszą być online w tym samym czasie, a każdy wynik jest porównywany z pozostałymi, co zmienia ranking obu graczy.
4. **Dynamiczna pula duplikatów** — serwer używa otwartego duplikatu, gdy jakiś jest dostępny, a nowy tworzy tylko wtedy, gdy go nie ma. Każdy jest otwierany dla innych graczy i wycofywany, gdy zgromadzi **10 ludzkich graczy** lub po **30 dniach**.

## Dlaczego istnieje

Rankingowe gry na żywo wymagają, by kilku graczy było online w tym samym momencie, a to zdarza się rzadko. Porównywanie punktów graczy przy jednym stole ma jeszcze inny problem: mahjong to częściowo umiejętności, a częściowo szczęście, a każdemu miejscu rozdaje się inne kamienie, więc porównywanie wyników graczy mówi tyle samo o rozdaniu, co o grze. Więcej rozdań zmniejsza wpływ szczęścia, ale nie potrafi go wyeliminować. Cieniowa rankingowa rozwiązuje oba problemy: gra w tym samym duplikacie niemal usuwa szczęście z porównania, a nie musisz czekać na innych graczy.

## Jak przebiega gra

1. Otwórz **Online → Cieniowa rankingowa** i zaloguj się.
2. Wybierz długość — **1 runda** (4 rozdania) lub **2 rundy** (8 rozdań) — oraz to, czy **ręce bez układu** są dozwolone. Zobacz [Ręka bez układu](scoring-chicken-hand).
3. Naciśnij **Rozpocznij grę**. Serwer przydziela Ci duplikat tego typu, którego jeszcze nie rozegrałeś, i tworzy nowy tylko wtedy, gdy takiego nie ma. Preferuje duplikaty, które inni gracze już ukończyli — dzięki temu masz wyniki do porównania — ale możesz też być pierwszym ludzkim graczem w danym duplikacie. Nigdy nie pozostajesz bez porównania: bot już rozegrał partię na Twoim miejscu, więc nawet jako pierwszy ludzki gracz od razu jesteś porównywany z jego wynikiem.

Nie wybierasz duplikatu i nie możesz z wyprzedzeniem dowiedzieć się o nim niczego. Każdy duplikat ma identyfikator w postaci [hl]7K3H-M9PQ[/], pokazywany po zakończeniu gry.

Tempo jest spokojne: **20 sekund** na każdą decyzję — Twój własny ruch lub odpowiedź na zrzutkę — plus **90-sekundowy** zapas w każdym rozdaniu. Jeśli połączenie się zerwie, połącz się ponownie, aby kontynuować tę samą grę.

## Z kim jesteś porównywany

Duplikat jest ustalony z góry: ten sam mur kamieni, ta sama ręka początkowa w każdym rozdaniu i te same boty. Każde miejsce dobiera z własnej części muru. Gdy część danego miejsca się wyczerpie, dobranie przechodzi z najdłuższej części należącej do innego miejsca, więc to, który kamień będzie następny, zależy od przebiegu rozdania — kamienie są dla wszystkich takie same, ale kolejność dobierania nie jest ustalona z góry. Każdy gracz, który podejmuje ten duplikat, otrzymuje tę samą rękę początkową i to samo miejsce, więc różnice w wyniku biorą się wyłącznie ze sposobu rozegrania gry.

Gdy kończysz, Twój **wynik za grę** jest porównywany z:

- **Botem.** Gdy duplikat jest tworzony, bot jako pierwszy gra na Twoim miejscu. Jego wynik to wynik do pobicia. Ten bot ma ranking **1200**, który nigdy się nie zmienia. Nie zobaczysz jego wyniku, dopóki nie skończysz.
- **Każdym graczem, który ukończył ten duplikat przed Tobą.**
- **Każdym graczem, który ukończy go po Tobie.** Ich wyniki przychodzą później i ponownie zmieniają Twój ranking.

Każde porównanie to zwycięstwo, porażka albo (przy dokładnie równym wyniku) remis. Duplikat mieści do **10 ludzkich graczy**, więc może dać Ci nawet **dziewięciu** innych do porównania, plus bota.

## Ranking

Każde porównanie zmienia Twój ranking tak, jakby chodziło o jednego przeciwnika w grze na żywo o tej samej długości. Wygrana z silniejszym graczem daje Ci więcej; przegrana ze słabszym kosztuje Cię więcej. W przypadku obu graczy w porównaniu ranking jest aktualizowany na podstawie rankingu każdego z nich z chwili, gdy grał.

Na przykład przy rankingu 1200 wygrana z botem o rankingu 1200 w duplikacie na 1 rundę daje około **+2.67**. W duplikacie na 2 rundy ta sama wygrana daje około **+5.33**. Duplikat z kilkoma graczami to kilka porównań, a każde liczy się w pełni.

Cieniowa rankingowa używa tego samego rankingu co rankingowe gry na żywo.

## Po zakończeniu gry

Tabela wyników pokazuje bota oraz każdego wcześniejszego gracza: ich punkty, ich ranking i to, jak bardzo każde porównanie zmieniło Twój ranking.

**Moje próby** na zakładce Cieniowa rankingowa pokazują każdy duplikat, który rozegrałeś: ilu ma dotychczas graczy oraz jak łącznie zmienił Twój ranking. Ukończony duplikat pokazuje też punkty — bota i każdego gracza, który go dotąd ukończył, w tym tych, którzy zagrali po Tobie.

Gdy ktoś później rozegra jeden z Twoich duplikatów, wynik jest wstrzymywany i zbierany do Twojego następnego **podsumowania**: zestawienia wszystkich późniejszych wyników, wraz z każdym wynikiem i zmianą Twojego rankingu. To, jak często należy się podsumowanie, wybierasz w **Ustawieniach** — od **co godzinę** do **raz dziennie** (domyślnie).

Podsumowanie jest dostarczane jednym kanałem. Gdy aplikacja jest otwarta, pojawia się tam; jeśli aplikacja jest zamknięta i włączyłeś pocztę e-mail, zostaje wysłane e-mailem. Otwarcie aplikacji przed wysłaniem oczekującego e-maila dostarcza podsumowanie w aplikacji i anuluje e-mail. Poczta e-mail może być włączona w **Ustawieniach**.

## Uczciwa gra

- **Jedna próba na duplikat.** Gdy raz rozpoczniesz duplikat, liczy się jako rozegrany. Nie możesz go dostać ponownie, nawet jeśli go nie ukończysz.
- **Kamienie rozdaje serwer.** Twoje urządzenie otrzymuje tylko to, co wolno Ci zobaczyć, więc ukrytych kamieni nie można odczytać z aplikacji.
- **Duplikaty są przydzielane nieprzewidywalnie.** Gdy komuś zostanie przydzielony duplikat, rozpocznie go lub ukończy, nikt inny nie dostaje go przez dwie godziny, a wybór spośród otwartych duplikatów jest częściowo losowy. Utrudnia to wysłanie drugiego konta przodem, aby poznać duplikat.
