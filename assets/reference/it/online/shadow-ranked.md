# Classificata all'ombra

**Classificata all'ombra** è il nome di un formato classificato speciale, una combinazione di quattro cose:

1. **Il formato duplicato** — ogni partita di Classificata all'ombra che giochi è un **duplicato** specifico che altri giocatori hanno giocato prima di te (e/o giocheranno dopo): le mani iniziali, il muro e il posto sono garantiti esattamente identici, riducendo al minimo il fattore fortuna. Il tuo risultato non è il punteggio che ottieni rispetto agli altri tre giocatori con cui giochi, ma come il tuo totale si confronta con i totali di tutti gli altri giocatori che hanno giocato lo stesso duplicato con le stesse tessere e nello stesso posto.
2. **Una partita contro tre bot** — in ogni partita di Classificata all'ombra giochi contro tre bot (gli stessi che vengono usati nelle partite classificate Live come riempitivi). Questo elimina la necessità che altri giocatori siano online.
3. **Confronto asincrono** — i giocatori non devono essere online nello stesso momento, e ogni risultato viene confrontato con gli altri, aggiornando il punteggio di entrambi i giocatori.
4. **Un insieme dinamico di duplicati** — il server riutilizza un duplicato aperto quando ce n'è uno disponibile e ne crea uno nuovo solo quando non ce n'è nessuno. Ciascuno viene aperto ad altri giocatori e ritirato quando contiene **10 giocatori umani** o dopo **30 giorni**.

## Perché esiste

Le partite classificate Live richiedono che più persone siano online nello stesso momento, e questo accade raramente. Confrontare i punteggi dei giocatori allo stesso tavolo ha un ulteriore problema: il Mahjong è in parte abilità e in parte fortuna, e a ogni posto vengono distribuite tessere diverse, quindi confrontare i totali dei giocatori dice tanto sulla distribuzione quanto sul gioco. Più mani riducono l'influenza della fortuna, ma non possono eliminarla. Classificata all'ombra risolve entrambe le cose: giocare lo stesso duplicato elimina quasi del tutto la fortuna dal confronto, e non devi aspettare altri giocatori.

## Come funziona una partita

1. Apri **Online → Classificata all'ombra** e accedi.
2. Scegli la durata — **1 giro** (4 mani) o **2 giri** (8 mani) — e se sono consentite le **mani senza figure**. Vedi [Mano senza figure](scoring-chicken-hand).
3. Premi **Avvia una partita**. Il server ti assegna un duplicato di quel tipo che non hai ancora giocato, e ne crea uno nuovo solo quando non ce n'è nessuno. Preferisce i duplicati che altri giocatori hanno già finito — così hai dei punteggi con cui confrontarti — ma puoi anche essere il primo giocatore umano in uno. Non sei mai senza confronto, però: un bot ha già giocato nel tuo posto, quindi anche come primo giocatore umano vieni confrontato subito con il suo totale.

Il duplicato non lo scegli e non puoi saperne nulla in anticipo. Ogni duplicato ha un identificatore come [hl]7K3H-M9PQ[/], mostrato dopo che hai finito.

Il ritmo è rilassato: **20 secondi** per ogni decisione — il tuo turno, o la risposta a uno scarto — più una **riserva di 90 secondi** a ogni mano. Se la connessione cade, riconnettiti per continuare la stessa partita.

## Con chi vieni confrontato

Un duplicato è fissato in anticipo: lo stesso muro di tessere, la stessa mano iniziale a ogni mano e gli stessi bot. Ogni posto pesca dalla propria porzione del muro. Quando la porzione di un posto si esaurisce, la pesca passa alla porzione più lunga appartenente a un altro posto, quindi quale tessera arriva dopo dipende da come è stata giocata la mano — le tessere sono le stesse per tutti, ma l'ordine di pesca non è fissato in anticipo. Ogni giocatore che affronta questo duplicato riceve la stessa mano iniziale e lo stesso posto, quindi le differenze nel totale derivano solo da come è stata giocata la partita.

Quando finisci, il tuo **totale della partita** viene confrontato con:

- **Il bot.** Quando un duplicato viene creato, un bot gioca nel tuo posto per primo. Il suo totale è il punteggio da battere. Questo bot ha un punteggio di **1200** e il suo punteggio non cambia mai. Non puoi vedere il suo punteggio finché non finisci.
- **Ogni giocatore che ha finito questo duplicato prima di te.**
- **Ogni giocatore che lo finisce dopo di te.** I loro risultati arrivano più tardi e modificano di nuovo il tuo punteggio.

Ogni confronto è una vittoria, una sconfitta o (in caso di totale esattamente uguale) un pareggio. Un duplicato può contenere fino a **10 giocatori umani**, quindi può darti fino a **nove** altri con cui confrontarti, più il bot.

## Punteggio

Ogni confronto cambia il tuo punteggio come farebbe contro un solo avversario in una partita Live della stessa durata. Battere un giocatore più forte ti fa guadagnare di più; perdere contro uno più debole ti costa di più. In ogni confronto il punteggio di entrambi i giocatori viene aggiornato, usando il punteggio di ciascun giocatore al momento in cui ha giocato.

Ad esempio, con un punteggio di 1200, battere il bot da 1200 in un duplicato da 1 giro dà circa **+2.67**. In un duplicato da 2 giri la stessa vittoria dà circa **+5.33**. Un duplicato con più giocatori comporta più confronti, e ciascuno conta per intero.

Classificata all'ombra usa lo stesso punteggio delle partite classificate Live.

## Dopo che hai finito

La tabella dei risultati mostra il bot e ogni giocatore precedente: il loro punteggio partita, il loro punteggio e quanto ogni confronto ha cambiato il tuo.

**Le mie partite** nella scheda Classificata all'ombra mostra ogni duplicato che hai giocato: quanti giocatori ha finora e la variazione totale del tuo punteggio che ne deriva. Un duplicato completato mostra anche i punteggi — quelli del bot e di ogni giocatore che l'ha finito finora, compresi quelli che hanno giocato dopo di te.

Quando qualcuno gioca uno dei tuoi duplicati più tardi, il risultato viene conservato e raccolto nel tuo prossimo **riepilogo**: una sintesi di ogni risultato successivo, con ogni punteggio e la variazione del tuo punteggio. Scegli con quale frequenza è previsto un riepilogo in **Impostazioni**, da **ogni ora** a **una volta al giorno** (l'impostazione predefinita).

Il riepilogo viene consegnato su un solo canale. Mentre l'app è aperta appare lì; se l'app è chiusa e hai attivato le email, viene inviato per email. Aprire l'app prima dell'invio di un'email in attesa consegna il riepilogo nell'app e annulla l'email. Puoi attivare le email in **Impostazioni**.

## Gioco leale

- **Una sola partita per duplicato.** Una volta che inizi un duplicato, conta come giocato. Non puoi ottenerlo di nuovo, anche se non lo finisci.
- **Le tessere le distribuisce il server.** Il tuo dispositivo riceve solo ciò che ti è consentito vedere, quindi le tessere nascoste non possono essere lette dall'app.
- **I duplicati vengono assegnati in modo imprevedibile.** Dopo che a qualcuno viene assegnato un duplicato, lo inizia o lo finisce, nessun altro lo riceve per due ore, e la scelta tra i duplicati aperti è in parte casuale. Questo rende difficile mandare avanti un secondo account per conoscere un duplicato.
