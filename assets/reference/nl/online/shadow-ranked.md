# Schaduw-gerangschikt

**Schaduw-gerangschikt** is de naam van een speciaal gerangschikt formaat, een combinatie van vier dingen:

1. **Het duplicaatformaat** — elk schaduw-gerangschikt spel dat je speelt is een specifiek **duplicaat** dat andere spelers vóór jou hebben gespeeld (en/of na jou zullen spelen): de beginhanden, de muur en de zitplaats zijn gegarandeerd precies hetzelfde, wat de geluksfactor tot een minimum beperkt. Je resultaat is niet de score die je behaalt ten opzichte van de andere drie spelers met wie je speelt, maar hoe je totaal zich verhoudt tot de totalen van alle andere spelers die hetzelfde duplicaat met dezelfde stenen en op dezelfde zitplaats hebben gespeeld.
2. **Een spel tegen drie bots** — in elk schaduw-gerangschikt spel speel je tegen drie bots (dezelfde die in live-gerangschikte spelen als invallers worden gebruikt). Zo hoeven andere spelers niet online te zijn.
3. **Asynchrone vergelijking** — spelers hoeven niet op hetzelfde moment online te zijn, en elk resultaat wordt met de andere vergeleken, waardoor de ratings van beide spelers verschuiven.
4. **Een dynamische pool van duplicaten** — de server hergebruikt een open duplicaat wanneer er een beschikbaar is en maakt alleen een nieuw aan wanneer er geen is. Elk duplicaat wordt opengesteld voor andere spelers en buiten gebruik gesteld zodra het **10 menselijke spelers** bevat of na **30 dagen**.

## Waarom het bestaat

Live-gerangschikte spelen vereisen dat meerdere mensen op hetzelfde moment online zijn, en dat gebeurt zelden. Het vergelijken van de scores van de spelers aan een tafel heeft nog een probleem: mahjong is deels vaardigheid en deels geluk, en elke zitplaats krijgt andere stenen gedeeld, dus het vergelijken van de totalen van spelers zegt evenveel over de deling als over het spel. Meer handen verminderen de invloed van geluk, maar kunnen die niet wegnemen. Schaduw-gerangschikt lost beide op: hetzelfde duplicaat spelen haalt geluk bijna uit de vergelijking, en je hoeft niet op andere spelers te wachten.

## Hoe een spel verloopt

1. Open **Online → Schaduw-gerangschikt** en meld je aan.
2. Kies de lengte — **1 ronde** (4 handen) of **2 rondes** (8 handen) — en of **patroonloze handen** zijn toegestaan. Zie [Patroonloze hand](scoring-chicken-hand).
3. Druk op **Een spel starten**. De server geeft je een duplicaat van dat type dat je nog niet hebt gespeeld, en maakt alleen een nieuw aan wanneer er geen is. Hij geeft de voorkeur aan duplicaten die andere spelers al hebben afgerond — zodat je scores hebt om mee te vergelijken — maar je kunt er ook de eerste menselijke speler in zijn. Je zit echter nooit zonder vergelijking: een bot heeft al op je zitplaats gespeeld, dus zelfs als eerste menselijke speler wordt je totaal direct met het zijne vergeleken.

Je kiest het duplicaat niet en je kunt er vooraf niets over zien. Elk duplicaat heeft een id zoals [hl]7K3H-M9PQ[/], die wordt getoond nadat je klaar bent.

Het tempo is rustig: **20 seconden** per beslissing — je eigen beurt of het reageren op een afleg — plus een **reserve van 90 seconden** per hand. Valt je verbinding weg, verbind dan opnieuw om hetzelfde spel voort te zetten.

## Met wie je wordt vergeleken

Een duplicaat ligt vooraf vast: dezelfde muur van stenen, dezelfde beginhand in elke hand en dezelfde bots. Elke zitplaats trekt uit zijn eigen stapel van de muur. Raakt de stapel van een zitplaats leeg, dan gaat de trekking verder uit de langste stapel van een andere zitplaats, dus welke steen hierna komt, hangt af van hoe de hand is gespeeld — de stenen zijn voor iedereen hetzelfde, maar de trekorde ligt niet vooraf vast. Elke speler die dit duplicaat aangaat, krijgt dezelfde beginhand en dezelfde zitplaats, dus verschillen in het totaal komen alleen voort uit hoe het spel is gespeeld.

Wanneer je klaar bent, wordt je **speeltotaal** voor het spel vergeleken met:

- **De bot.** Wanneer een duplicaat wordt aangemaakt, speelt een bot eerst op je zitplaats. Het totaal daarvan is de score die je moet verslaan. Deze bot heeft een rating van **1200** en zijn rating verandert nooit. Je kunt zijn score pas zien als je klaar bent.
- **Elke speler die dit duplicaat vóór jou heeft afgerond.**
- **Elke speler die het na jou afrondt.** Hun resultaten komen later binnen en verschuiven je rating opnieuw.

Elke vergelijking is een winst, een verlies of (bij een exact gelijk totaal) een gelijkspel. Een duplicaat bevat maximaal **10 menselijke spelers**, dus het kan je tot **negen** anderen opleveren om mee te vergelijken, plus de bot.

## Rating

Elke vergelijking verandert je rating zoals tegen één tegenstander in een live-spel van dezelfde lengte. Een sterkere speler verslaan levert je meer op; van een zwakkere verliezen kost je meer. Bij een vergelijking worden de ratings van beide spelers bijgewerkt, op basis van de rating van elke speler op het moment dat die speelde.

Bijvoorbeeld: met een rating van 1200 levert het verslaan van de bot van 1200 in een duplicaat van 1 ronde ongeveer **+2.67** op. In een duplicaat van 2 rondes levert dezelfde winst ongeveer **+5.33** op. Een duplicaat met meerdere spelers betekent meerdere vergelijkingen, en elke vergelijking telt volledig mee.

Schaduw-gerangschikt gebruikt dezelfde rating als live-gerangschikte spelen.

## Nadat je klaar bent

De resultatentabel toont de bot en elke eerdere speler: hun score, hun rating en hoeveel elke vergelijking de jouwe heeft veranderd.

**Mijn pogingen** op het tabblad Schaduw-gerangschikt toont elk duplicaat dat je hebt gespeeld: hoeveel spelers het tot nu toe heeft en je totale ratingverandering ervan. Een afgerond duplicaat toont ook de scores — de bot en elke speler die het tot nu toe heeft afgerond, inclusief degenen die na jou hebben gespeeld.

Wanneer iemand later een van je duplicaten speelt, wordt het resultaat vastgehouden en verzameld in je volgende **samenvatting**: een overzicht van elk later resultaat, met elke score en je ratingverandering. Hoe vaak een samenvatting verschijnt, kies je in **Instellingen**, van **elk uur** tot **één keer per dag** (de standaard).

De samenvatting wordt via één kanaal bezorgd. Zolang de app open is, verschijnt hij daar; is de app gesloten en heb je je voor e-mail aangemeld, dan wordt hij in plaats daarvan gemaild. Open je de app voordat een wachtende e-mail wordt verzonden, dan wordt de samenvatting in de app bezorgd en vervalt de e-mail. Je kunt je in **Instellingen** voor e-mail aanmelden.

## Eerlijk spel

- **Één poging per duplicaat.** Zodra je een duplicaat begint, geldt het als gespeeld. Je kunt het niet opnieuw krijgen, zelfs niet als je het niet afrondt.
- **De server deelt de stenen.** Je apparaat ontvangt alleen wat je mag zien, dus de verborgen stenen kunnen niet uit de app worden gelezen.
- **Duplicaten worden onvoorspelbaar toegewezen.** Nadat iemand een duplicaat heeft gekregen, is begonnen of afgerond, krijgt niemand anders het twee uur lang, en de keuze uit open duplicaten is deels willekeurig. Zo is het moeilijk om een tweede account vooruit te sturen om een duplicaat te verkennen.
