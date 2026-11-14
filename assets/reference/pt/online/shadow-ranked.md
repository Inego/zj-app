# Ranqueada na sombra

**Ranqueada na sombra** é o nome de um formato ranqueado especial, uma combinação de quatro coisas:

1. **O formato de duplicatas** — cada partida de Ranqueada na sombra que você joga é uma **duplicata** específica que outros jogadores jogaram antes (e/ou jogarão depois) de você: as mãos iniciais, a muralha e o assento são garantidamente exatamente os mesmos, reduzindo o fator sorte ao mínimo. Seu resultado não é a pontuação que você obtém em relação aos outros três jogadores com quem joga, mas como seu total se compara com os totais de todos os outros jogadores que jogaram a mesma duplicata com as mesmas peças e no mesmo assento.
2. **Uma partida contra três bots** — em cada partida de Ranqueada na sombra você joga contra três bots (os mesmos usados nas partidas ranqueadas ao vivo como substitutos). Isso elimina a necessidade de outros jogadores estarem online.
3. **Comparação assíncrona** — os jogadores não precisam estar online ao mesmo tempo, e cada resultado é comparado com os outros, alterando a classificação de ambos os jogadores.
4. **Um conjunto dinâmico de duplicatas** — o servidor reutiliza uma duplicata aberta quando há uma disponível e cria uma nova apenas quando não há nenhuma. Cada uma é aberta a outros jogadores e encerrada quando reúne **10 jogadores humanos** ou após **30 dias**.

## Por que existe

As partidas ranqueadas ao vivo precisam de várias pessoas online no mesmo momento, e isso raramente acontece. Comparar as pontuações dos jogadores de uma mesa tem um outro problema: o Mahjong é em parte habilidade e em parte sorte, e cada assento recebe peças diferentes, então comparar os totais dos jogadores diz tanto sobre a distribuição quanto sobre o jogo. Mais mãos reduzem a influência da sorte, mas não conseguem eliminá-la. A Ranqueada na sombra resolve as duas coisas: jogar a mesma duplicata quase elimina a sorte da comparação, e você não precisa esperar por outros jogadores.

## Como uma partida funciona

1. Abra **Online → Ranqueada na sombra** e entre.
2. Escolha a duração — **1 rodada** (4 mãos) ou **2 rodadas** (8 mãos) — e se **mãos sem figura** são permitidas. Veja [Mão sem figura](scoring-chicken-hand).
3. Pressione **Iniciar uma partida**. O servidor lhe dá uma duplicata desse tipo que você ainda não jogou e cria uma nova apenas quando não há nenhuma. Ele prefere duplicatas que outros jogadores já terminaram — para que você tenha pontuações com que comparar — mas você também pode ser o primeiro jogador humano em uma. Você nunca fica sem comparação, porém: um bot já jogou no seu assento, então mesmo como primeiro jogador humano você é comparado com o total dele imediatamente.

Você não escolhe a duplicata e não pode ver nada sobre ela com antecedência. Cada duplicata tem um id como [hl]7K3H-M9PQ[/], mostrado depois que você termina.

O ritmo é tranquilo: **20 segundos** por decisão — o seu próprio turno, ou responder a um descarte — mais uma **reserva de 90 segundos** por mão. Se a sua conexão cair, reconecte-se para continuar a mesma partida.

## Com quem você é comparado

Uma duplicata é fixada com antecedência: a mesma muralha, a mesma mão inicial em cada mão e os mesmos bots. Cada assento compra da sua própria pilha da muralha. Quando a pilha de um assento se esgota, a compra transborda da pilha mais longa de outro assento, então qual peça vem a seguir depende de como a mão foi jogada — as peças são as mesmas para todos, mas a ordem das compras não é fixada com antecedência. Todo jogador que assume essa duplicata recebe a mesma mão inicial e o mesmo assento, então as diferenças no total vêm apenas de como a partida foi jogada.

Quando você termina, seu **total da partida** é comparado com:

- **O bot.** Quando uma duplicata é criada, um bot joga no seu assento primeiro. O total dele é a pontuação a ser superada. Esse bot tem classificação **1200**, que nunca muda. Você não pode ver a pontuação dele até terminar.
- **Todo jogador que terminou esta duplicata antes de você.**
- **Todo jogador que terminar esta duplicata depois de você.** Os resultados deles chegam mais tarde e alteram novamente a sua classificação.

Cada comparação é uma vitória, uma derrota ou (em um total exatamente igual) um empate. Uma duplicata reúne até **10 jogadores humanos**, então pode lhe dar até **nove** outros com quem comparar, além do bot.

## Classificação

Cada comparação altera sua classificação como aconteceria contra um adversário em uma partida ao vivo da mesma duração. Vencer um jogador mais forte rende mais; perder para um mais fraco custa mais. Em cada comparação, a classificação dos dois jogadores é atualizada, usando a classificação de cada um no momento em que jogou.

Por exemplo, com 1200, vencer o bot de 1200 em uma duplicata de 1 rodada dá cerca de **+2.67**. Em uma duplicata de 2 rodadas, a mesma vitória dá cerca de **+5.33**. Uma duplicata com vários jogadores significa várias comparações, e cada uma conta integralmente.

A Ranqueada na sombra usa a mesma classificação que as partidas ranqueadas ao vivo.

## Depois que você termina

A tabela de resultados mostra o bot e cada jogador anterior: a pontuação, a classificação e quanto cada comparação alterou a sua.

**Minhas tentativas** na aba Ranqueada na sombra mostra cada duplicata que você jogou: quantos jogadores ela tem até agora e a mudança total da sua classificação por causa dela. Uma duplicata concluída também mostra as pontuações — a do bot e a de cada jogador que a terminou até agora, incluindo os que jogaram depois de você.

Quando alguém joga uma das suas duplicatas mais tarde, o resultado fica retido e é reunido no seu próximo **resumo**: uma compilação de cada resultado posterior, com cada pontuação e a sua mudança de classificação. Você escolhe com que frequência um resumo é enviado em **Configurações**, de **a cada hora** a **uma vez por dia** (o padrão).

O resumo é entregue em um único canal. Enquanto o aplicativo estiver aberto, ele aparece nele; se o aplicativo estiver fechado e você tiver ativado o e-mail, ele é enviado por e-mail. Abrir o aplicativo antes de um e-mail em espera ser enviado entrega o resumo nele e descarta o e-mail. Você pode ativar o e-mail em **Configurações**.

## Jogo limpo

- **Uma tentativa por duplicata.** Assim que você começa uma duplicata, ela conta como jogada. Você não pode recebê-la de novo, mesmo se não terminá-la.
- **O servidor distribui as peças.** Seu dispositivo só recebe o que você tem permissão para ver, então as peças ocultas não podem ser lidas a partir do aplicativo.
- **As duplicatas são distribuídas de forma imprevisível.** Depois que alguém recebe uma duplicata, começa-a ou termina-a, ninguém mais a recebe por duas horas, e a escolha entre as duplicatas abertas é em parte aleatória. Isso torna difícil enviar uma segunda conta à frente para conhecer uma duplicata.
