# Clasificación en la sombra

**Clasificación en la sombra** es el nombre de un formato clasificatorio
especial, una combinación de cuatro cosas:

1. **El formato duplicado** — cada partida de Clasificación en la sombra que
   juegas es un **reto** concreto que otros jugadores jugaron antes que tú
   (y/o jugarán después): las manos iniciales, el muro y el asiento son
   exactamente los mismos, lo que reduce el factor suerte al mínimo. Tu
   resultado no es la puntuación que obtienes frente a los otros tres
   jugadores con los que juegas, sino cómo se compara tu total con los
   totales de todos los demás jugadores que jugaron el mismo reto con las
   mismas fichas y en el mismo asiento.
2. **Una partida contra tres bots** — en cada partida de Clasificación en la
   sombra juegas contra tres bots (los mismos que se usan en las partidas
   clasificatorias en vivo como relleno). Así no hace falta que otros
   jugadores estén en línea.
3. **Comparación asíncrona** — los jugadores no tienen que estar en línea al
   mismo tiempo, y cada resultado se compara con los demás, lo que mueve la
   clasificación de ambos jugadores.
4. **Un grupo dinámico de retos** — el servidor reutiliza un reto abierto
   cuando hay uno disponible y solo crea uno nuevo cuando no hay ninguno.
   Cada uno se abre a otros jugadores y se retira cuando reúne **10 jugadores
   humanos** o tras **30 días**.

## Por qué existe

Las partidas clasificatorias en vivo necesitan que varias personas estén en
línea en el mismo momento, y eso rara vez ocurre. Comparar las puntuaciones
de los jugadores de una mesa tiene además otro problema: el mahjong es en
parte habilidad y en parte suerte, y a cada asiento le tocan fichas
distintas, así que comparar los totales de los jugadores dice tanto del
reparto como del juego. Más manos reducen la influencia de la suerte, pero no
pueden eliminarla. La Clasificación en la sombra resuelve ambas cosas: jugar
el mismo reto casi elimina la suerte de la comparación, y no tienes que
esperar a otros jugadores.

## Cómo funciona una partida

1. Abre **Jugar en línea → Clasificación en la sombra** e inicia sesión.
2. Elige la duración — **1 ronda** (4 manos) o **2 rondas** (8 manos) — y si
   se permiten las **manos sin figura**. Consulta
   [Mano sin figura](scoring-chicken-hand).
3. Pulsa **Empezar una partida**. El servidor te da un reto de ese tipo que
   aún no hayas jugado y solo crea uno nuevo cuando no hay ninguno. Prefiere
   retos que otros jugadores ya hayan terminado —así tienes puntuaciones con
   las que compararte—, pero también puedes ser el primer jugador humano de
   uno. Aun así, nunca te quedas sin comparación: un bot ya ha jugado en tu
   asiento, así que, incluso como primer jugador humano, tu total se compara
   con el suyo de inmediato.

No eliges el reto y no puedes ver nada sobre él de antemano. Cada reto tiene
un id como [hl]7K3H-M9PQ[/], que se muestra cuando terminas.

El ritmo es relajado: **20 segundos** por decisión —tu propio turno o
responder a un descarte—, más una **reserva de 90 segundos** por mano. Si se
cae la conexión, vuelve a conectarte para continuar la misma partida.

## Con quién te comparan

Un reto se fija de antemano: el mismo muro de fichas, la misma mano inicial
en cada mano y los mismos bots. Cada asiento roba de su propio montón del
muro. Cuando se agota el montón de un asiento, el robo pasa al montón más
largo de otro asiento, así que qué ficha viene después depende de cómo se
haya jugado la mano: las fichas son las mismas para todos, pero el orden de
robo no está fijado de antemano. Todos los jugadores que afrontan este reto
reciben la misma mano inicial y el mismo asiento, así que las diferencias en
el total provienen solo de cómo se jugó la partida.

Cuando terminas, tu **total de la partida** se compara con:

- **El bot.** Cuando se crea un reto, un bot juega primero en tu asiento. Su
  total es la puntuación que hay que superar. Este bot tiene una
  clasificación de **1200** y su clasificación nunca cambia. No puedes ver su
  puntuación hasta que terminas.
- **Todos los jugadores que terminaron este reto antes que tú.**
- **Todos los jugadores que lo terminen después que tú.** Sus resultados
  llegan más tarde y vuelven a mover tu clasificación.

Cada comparación es una victoria, una derrota o (si el total es exactamente
igual) un empate. Un reto admite hasta **10 jugadores humanos**, así que
puede darte hasta **nueve** jugadores más con los que compararte, además del
bot.

## Clasificación

Cada comparación cambia tu clasificación igual que lo haría contra un solo
rival en una partida en vivo de la misma duración. Ganar a un jugador más
fuerte te da más; perder contra uno más débil te cuesta más. En cada
comparación se actualiza la clasificación de ambos jugadores, usando la
clasificación de cada uno en el momento en que jugó.

Por ejemplo, con 1200, ganar al bot de 1200 en un reto de 1 ronda da
aproximadamente **+2.67**. En un reto de 2 rondas, esa misma victoria da
aproximadamente **+5.33**. Un reto con varios jugadores significa varias
comparaciones, y cada una cuenta por completo.

La Clasificación en la sombra usa la misma clasificación que las partidas
clasificatorias en vivo.

## Después de terminar

La tabla de resultados muestra al bot y a todos los jugadores anteriores: su
puntuación, su clasificación y cuánto cambió cada comparación la tuya.

**Mis intentos**, en la pestaña de Clasificación en la sombra, muestra cada
reto que has jugado: cuántos jugadores tiene hasta ahora y tu cambio total de
clasificación con él. Un reto completado también muestra las puntuaciones: la
del bot y la de todos los jugadores que lo han terminado hasta ahora,
incluidos los que jugaron después que tú.

Cuando alguien juega uno de tus retos más tarde, el resultado se guarda y se
reúne en tu siguiente **resumen**: un compendio de todos los resultados
posteriores, con cada puntuación y tu cambio de clasificación. Tú eliges con
qué frecuencia toca un resumen en **Ajustes**, desde **cada hora** hasta
**una vez al día** (lo predeterminado).

El resumen se entrega por un solo canal. Mientras la aplicación está abierta,
aparece allí; si la aplicación está cerrada y has activado el correo
electrónico, se envía por correo. Si abres la aplicación antes de que salga
un correo pendiente, el resumen se entrega en la aplicación y se descarta el
correo. Puedes activar el correo electrónico en **Ajustes**.

## Juego limpio

- **Un intento por reto.** En cuanto empiezas un reto, cuenta como jugado. No
  puedes volver a obtenerlo, aunque no lo termines.
- **El servidor reparte las fichas.** Tu dispositivo solo recibe lo que te
  está permitido ver, así que las fichas ocultas no se pueden leer desde la
  aplicación.
- **Los retos se reparten de forma impredecible.** Después de que a alguien se
  le dé un reto, lo empiece o lo termine, nadie más lo recibe durante dos
  horas, y la elección entre los retos abiertos es en parte aleatoria. Así es
  difícil enviar por delante una segunda cuenta para conocer un reto.
