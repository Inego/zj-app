# Varjoluokiteltu

**Varjoluokiteltu** on erityisen luokitellun formaatin nimi — yhdistelmä neljää asiaa:

1. **Kahdenkertaistamismuoto** — jokainen pelaamasi Varjoluokiteltu-peli on tietty **duplikaatti**, jonka muut pelaajat pelasivat ennen sinua (ja/tai pelaavat sinun jälkeesi): aloituskäsien, muurin ja istuimen taataan olevan täsmälleen samat, mikä pienentää onnen osuuden minimiin. Tuloksesi ei ole se pistemäärä, jonka saat suhteessa kolmeen muuhun pelaajaan, joiden kanssa pelaat, vaan se, miten kokonaispistemääräsi vertautuu kaikkien muiden samaa duplikaattia samoilla tiilillä ja samalla istuimella pelanneiden kokonaispistemääriin.
2. **Peli kolmea bottia vastaan** — jokaisessa Varjoluokitellussa pelissä pelaat kolmea bottia vastaan (samoja, joita käytetään Live-luokitelluissa peleissä täytebotteina). Tämä poistaa tarpeen siltä, että muut pelaajat olisivat verkossa.
3. **Asynkroninen vertailu** — pelaajien ei tarvitse olla verkossa samaan aikaan, ja jokaista tulosta verrataan muihin, mikä siirtää molempien pelaajien luokitusta.
4. **Dynaaminen duplikaattipooli** — palvelin käyttää uudelleen avointa duplikaattia, kun sellainen on saatavilla, ja luo uuden vain, kun sellaista ei ole. Jokainen avataan muille pelaajille ja poistetaan käytöstä, kun siihen on kertynyt **10 ihmispelaajaa** tai **30 päivän** kuluttua.

## Miksi se on olemassa

Live-luokitellut pelit vaativat, että useampi ihminen on verkossa samalla hetkellä, ja niin tapahtuu harvoin. Saman pöydän pelaajien pistemäärien vertailussa on lisäksi toinen ongelma: mahjong on osaksi taitoa ja osaksi onnea, ja jokaiselle istuimelle jaetaan eri tiilet, joten pelaajien kokonaispistemäärien vertailu kertoo yhtä paljon jaosta kuin pelaamisesta. Useammat jaot vähentävät onnen vaikutusta, mutta eivät voi poistaa sitä. Varjoluokiteltu ratkaisee molemmat: saman duplikaatin pelaaminen poistaa onnen lähes kokonaan vertailusta, eikä sinun tarvitse odottaa muita pelaajia.

## Miten peli etenee

1. Avaa **Pelaa verkossa → Varjoluokiteltu** ja kirjaudu sisään.
2. Valitse pituus — **1 kierros** (4 jakoa) tai **2 kierrosta** (8 jakoa) — ja se, sallitaanko **kuviottomat kädet**. Katso [Kuvioton käsi](scoring-chicken-hand).
3. Paina **Aloita peli**. Palvelin antaa sinulle sen tyyppisen duplikaatin, jota et ole vielä pelannut, ja luo uuden vain, kun sellaista ei ole. Se suosii duplikaatteja, jotka muut pelaajat ovat jo lopettaneet — jotta sinulla olisi pistemääriä vertailtavaksi — mutta voit myös olla ensimmäinen ihmispelaaja sellaisessa. Vertailua sinulta ei kuitenkaan koskaan puutu: botti on jo pelannut istuimellasi, joten jo ensimmäisenä ihmispelaajana sinua verrataan heti sen kokonaispistemäärään.

Et valitse duplikaattia etkä voi nähdä siitä mitään etukäteen. Jokaisella duplikaatilla on tunnus, kuten [hl]7K3H-M9PQ[/], joka näytetään, kun olet lopettanut.

Tahti on rauhallinen: **20 sekuntia** jokaiseen päätökseen — oma vuorosi tai vastaaminen heittoon — sekä **90 sekunnin varanto** jokaista jakoa kohden. Jos yhteytesi katkeaa, yhdistä uudelleen jatkaaksesi samaa peliä.

## Keiden kanssa sinua verrataan

Duplikaatti on lyöty lukkoon etukäteen: sama tiilimuuri, sama aloituskäsi joka jaossa ja samat botit. Kukin istuin nostaa omasta muurin pinostaan. Kun istuimen pino loppuu, nosto jatkuu jonkin toisen istuimen pisimmästä pinosta, joten se, mikä tiili tulee seuraavaksi, riippuu siitä, miten jakoa on pelattu — tiilet ovat kaikille samat, mutta nostojärjestystä ei ole lyöty lukkoon etukäteen. Jokainen pelaaja, joka ottaa tämän duplikaatin pelatakseen, saa saman aloituskäden ja saman istuimen, joten erot kokonaispistemäärässä johtuvat vain siitä, miten peliä pelattiin.

Kun lopetat, **pelin kokonaispistemäärääsi** verrataan:

- **Bottiin.** Kun duplikaatti luodaan, botti pelaa istuimellasi ensin. Sen kokonaispistemäärä on pistemäärä, joka sinun on ylitettävä. Tämän botin luokitus on **1200**, eikä sen luokitus muutu koskaan. Et näe sen pistemäärää ennen kuin lopetat.
- **Jokaiseen pelaajaan, joka lopetti tämän duplikaatin ennen sinua.**
- **Jokaiseen pelaajaan, joka lopettaa sen sinun jälkeesi.** Heidän tuloksensa saapuvat myöhemmin ja muuttavat luokitustasi uudelleen.

Jokainen vertailu on voitto, häviö tai (täsmälleen yhtä suurella kokonaispistemäärällä) tasapeli. Yhteen duplikaattiin mahtuu enintään **10 ihmispelaajaa**, joten se voi antaa sinulle jopa **yhdeksän** muuta vertailtavaksi botin lisäksi.

## Luokitus

Jokainen vertailu muuttaa luokitustasi samoin kuin se muuttuisi yhtä vastustajaa vastaan samanpituisessa Live-pelissä. Vahvemman pelaajan voittaminen tuottaa sinulle enemmän; heikompaa vastaan häviäminen maksaa sinulle enemmän. Vertailussa päivitetään molempien pelaajien luokitukset käyttäen kunkin pelaajan luokitusta sillä hetkellä, kun hän pelasi.

Esimerkiksi luokituksella 1200 botin (1200) voittaminen 1 kierroksen duplikaatissa antaa noin **+2.67**. 2 kierroksen duplikaatissa sama voitto antaa noin **+5.33**. Duplikaatti, jossa on useita pelaajia, tarkoittaa useita vertailuja, ja jokainen niistä lasketaan täysimääräisesti.

Varjoluokiteltu käyttää samaa luokitusta kuin Live-luokitellut pelit.

## Kun olet lopettanut

Tulostaulukko näyttää botin ja jokaisen aiemman pelaajan: heidän pistemääränsä, heidän luokituksensa ja sen, kuinka paljon kukin vertailu muutti sinun luokitustasi.

**Omat yritykset** Varjoluokiteltu-välilehdellä näyttää jokaisen pelaamasi duplikaatin: kuinka monta pelaajaa siinä on tähän mennessä ja kokonaisluokitusmuutoksesi siitä. Valmis duplikaatti näyttää myös pistemäärät — botin ja jokaisen pelaajan, joka on lopettanut sen tähän mennessä, mukaan lukien ne, jotka pelasivat sinun jälkeesi.

Kun joku pelaa jotakin duplikaateistasi myöhemmin, tulos pidetään odottamassa ja kootaan seuraavaan **yhteenvetoosi**: katsaus jokaisesta myöhemmästä tuloksesta, jossa näkyvät kunkin pistemäärä ja luokitusmuutoksesi. Valitset **Asetuksissa**, kuinka usein yhteenveto on valmis, vaihtoehtoina **joka tunti** ... **kerran päivässä** (oletus).

Yhteenveto toimitetaan yhtä kanavaa pitkin. Kun sovellus on auki, se ilmestyy sinne; jos sovellus on suljettu ja olet ottanut sähköpostilähetyksen käyttöön, se lähetetään sen sijaan sähköpostilla. Jos avaat sovelluksen ennen kuin odottava sähköposti lähtee, yhteenveto toimitetaan sovelluksessa ja sähköposti jätetään lähettämättä. Voit ottaa sähköpostilähetyksen käyttöön **Asetuksissa**.

## Reilu peli

- **Yksi yritys duplikaattia kohden.** Kun aloitat duplikaatin, se lasketaan pelatuksi. Et voi saada sitä uudelleen, vaikka et lopettaisi sitä.
- **Palvelin jakaa tiilet.** Laitteesi vastaanottaa vain sen, mitä sinulle on lupa näyttää, joten piilotettuja tiiliä ei voi lukea sovelluksesta.
- **Duplikaatit jaetaan arvaamattomasti.** Kun joku on saanut duplikaatin, aloittanut sen tai lopettanut sen, kukaan muu ei saa sitä kahteen tuntiin, ja valinta avoimien duplikaattien joukosta on osittain satunnaista. Tämä vaikeuttaa toisen tilin lähettämistä etukäteen duplikaattiin tutustumaan.
