# Senka rangirana

**Senka rangirana** je naziv posebnog rangiranog formata, kombinacije četiri stvari:

1. **Format duplikata** — svaka igra u Senci rangiranoj je konkretan **duplikat** koji su drugi igrači odigrali pre vas (i/ili će odigrati posle): početne ruke, zid i mesto su garantovano potpuno isti, što faktor sreće svodi na minimum. Vaš rezultat nije rezultat koji dobijate u odnosu na druga tri igrača sa kojima igrate, već to kako se vaš ukupan rezultat poredi sa ukupnim rezultatima svih drugih igrača koji su odigrali isti duplikat sa istim pločicama i na istom mestu.
2. **Igra protiv tri bota** — u svakoj igri Senke rangirane igrate protiv tri bota (istih onih koji se koriste u rangiranim igrama uživo kao zamena za nedostajuće igrače). To otklanja potrebu da drugi igrači budu na mreži.
3. **Asinhrono poređenje** — igrači ne moraju biti na mreži u isto vreme, a svaki rezultat se poredi sa ostalima, pomerajući rejting oba igrača.
4. **Dinamički pul duplikata** — server ponovo koristi otvoreni duplikat kada je neki dostupan, a novi stvara samo kada ga nema. Svaki se otvara drugim igračima i zatvara kada skupi **10 pravih igrača** ili nakon **30 dana**.

## Zašto postoji

Rangirane igre uživo zahtevaju da nekoliko ljudi bude na mreži u istom trenutku, a to se retko dešava. Poređenje rezultata igrača za istim stolom ima i dodatni problem: mahdžong je delom veština, a delom sreća, a svako mesto dobija drugačije pločice, pa poređenje ukupnih rezultata igrača govori isto toliko o deljenju koliko i o igri. Više ruku smanjuje uticaj sreće, ali ga ne može otkloniti. Senka rangirana rešava oba problema: igranje istog duplikata gotovo uklanja sreću iz poređenja, a ne morate čekati druge igrače.

## Kako se igra odvija

1. Otvorite **Igraj na mreži → Senka rangirana** i prijavite se.
2. Izaberite dužinu — **1 krug** (4 ruke) ili **2 kruga** (8 ruku) — i da li su dozvoljene **ruke bez kombinacije**. Pogledajte [Ruka bez kombinacije](scoring-chicken-hand).
3. Pritisnite **Započni igru**. Server vam daje duplikat tog tipa koji još niste igrali, a novi stvara samo kada ga nema. Prednost daje duplikatima koje su drugi igrači već završili — da imate s čim da se poredite — ali možete biti i prvi pravi igrač u nekom. Ipak, nikada niste bez poređenja: bot je već odigrao na vašem mestu, pa se i kao prvi pravi igrač odmah poredite sa njegovim ukupnim rezultatom.

Duplikat ne birate i ne možete unapred videti ništa o njemu. Svaki duplikat ima identifikator poput [hl]7K3H-M9PQ[/], koji se prikazuje nakon što završite.

Tempo je opušten: **20 sekundi** po odluci — vaš sopstveni potez ili odgovor na odbacivanje — plus **rezerva od 90 sekundi** za svaku ruku. Ako vam veza padne, ponovo se povežite da nastavite istu igru.

## Sa kim se poredite

Duplikat je unapred fiksiran: isti zid pločica, ista početna ruka u svakoj ruci i isti botovi. Svako mesto vuče iz svog dela zida. Kada se deo nekog mesta isprazni, vučenje se preliva iz najdužeg dela koji pripada drugom mestu, pa to koja pločica dolazi sledeća zavisi od toga kako je ruka odigrana — pločice su iste za sve, ali redosled vučenja nije unapred fiksiran. Svaki igrač koji prihvati ovaj duplikat dobija istu početnu ruku i isto mesto, pa razlike u ukupnom rezultatu nastaju samo iz toga kako je igra odigrana.

Kada završite, vaš **ukupan rezultat igre** poredi se sa:

- **Botom.** Kada se duplikat stvori, bot prvi igra na vašem mestu. Njegov ukupan rezultat je rezultat koji treba nadmašiti. Rejting bota je **1200** i nikada se ne menja. Njegov rezultat ne možete videti dok ne završite.
- **Svakim igračem koji je završio ovaj duplikat pre vas.**
- **Svakim igračem koji ga završi posle vas.** Njihovi rezultati stižu kasnije i ponovo pomeraju vaš rejting.

Svako poređenje je pobeda, poraz ili (pri potpuno jednakom ukupnom rezultatu) nerešeno. Duplikat prima do **10 pravih igrača**, pa vam može dati čak **devet** drugih za poređenje, plus bota.

## Rejting

Svako poređenje menja vaš rejting onako kako bi ga promenilo protiv jednog protivnika u igri uživo iste dužine. Pobeda nad jačim igračem donosi vam više; poraz od slabijeg košta vas više. U svakom poređenju ažuriraju se rejtinzi oba igrača, prema rejtingu svakog od njih u trenutku kada je igrao.

Na primer, sa rejtingom 1200, pobeda nad botom rejtinga 1200 u duplikatu od 1 kruga daje oko **+2.67**. U duplikatu od 2 kruga ta ista pobeda daje oko **+5.33**. Duplikat sa više igrača znači više poređenja, a svako se računa u punom iznosu.

Senka rangirana koristi isti rejting kao rangirane igre uživo.

## Nakon što završite

Tabela rezultata prikazuje bota i svakog ranijeg igrača: njihov rezultat, njihov rejting i koliko je svako poređenje promenilo vaš.

**Moji pokušaji** na kartici Senka rangirana prikazuju svaki duplikat koji ste odigrali: koliko igrača za sada ima i vašu ukupnu promenu rejtinga iz njega. Završen duplikat prikazuje i rezultate — bota i svakog igrača koji ga je do sada završio, uključujući one koji su odigrali posle vas.

Kada neko kasnije odigra jedan od vaših duplikata, rezultat se čuva i skuplja u vaš sledeći **sažetak**: pregled svih kasnijih rezultata, sa svakim rezultatom i promenom vašeg rejtinga. Koliko često dospeva sažetak birate u **Podešavanjima**, od **svakog sata** do **jednom dnevno** (podrazumevano).

Sažetak se dostavlja jednim kanalom. Dok je aplikacija otvorena, pojavljuje se u njoj; ako je aplikacija zatvorena i uključili ste e-poštu, umesto toga se šalje e-poštom. Ako otvorite aplikaciju pre nego što se e-pošta koja čeka pošalje, sažetak se dostavlja u aplikaciji, a e-pošta se odbacuje. E-poštu možete uključiti u **Podešavanjima**.

## Čista igra

- **Jedan pokušaj po duplikatu.** Kada započnete duplikat, on se računa kao odigran. Ne možete ga dobiti ponovo, čak i ako ga ne završite.
- **Pločice deli server.** Vaš uređaj prima samo ono što vam je dozvoljeno da vidite, pa se skrivene pločice ne mogu pročitati iz aplikacije.
- **Duplikati se dodeljuju nepredvidivo.** Nakon što neko dobije duplikat, započne ga ili završi, niko drugi ga ne dobija dva sata, a izbor među otvorenim duplikatima je delimično slučajan. To otežava da se unapred pošalje drugi nalog da upozna duplikat.
