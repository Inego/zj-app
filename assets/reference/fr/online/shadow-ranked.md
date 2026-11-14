# Classé à l'ombre

**Classé à l'ombre** est le nom d'un format classé particulier, une combinaison de quatre éléments :

1. **Le format duplicate** — chaque partie de Classé à l'ombre que vous jouez est un **duplicate** précis que d'autres joueurs ont joué avant vous (et/ou joueront après) : les mains de départ, le mur et la place sont garantis exactement identiques, ce qui réduit le facteur chance au minimum. Votre résultat n'est pas le score obtenu par rapport aux trois autres joueurs avec qui vous jouez, mais la façon dont votre total se compare aux totaux de tous les autres joueurs ayant joué le même duplicate avec les mêmes tuiles et à la même place.
2. **Une partie contre trois bots** — dans chaque partie de Classé à l'ombre, vous jouez contre trois bots (les mêmes que ceux utilisés dans les parties classées en direct pour combler les places vides). Plus besoin que d'autres joueurs soient en ligne.
3. **Comparaison asynchrone** — les joueurs n'ont pas besoin d'être en ligne en même temps, et chaque résultat est comparé aux autres, ce qui fait évoluer le classement des deux joueurs.
4. **Un pool dynamique de duplicates** — le serveur réutilise un duplicate ouvert lorsqu'il en existe un et n'en crée un nouveau que lorsqu'il n'y en a aucun. Chacun est ouvert aux autres joueurs et retiré dès qu'il réunit **10 joueurs humains** ou au bout de **30 jours**.

## Pourquoi ce mode existe

Les parties classées en direct ont besoin de plusieurs personnes en ligne au même moment, et cela arrive rarement. Comparer les scores des joueurs à une table pose un autre problème : le mahjong tient en partie de l'adresse et en partie de la chance, et chaque place reçoit des tuiles différentes ; comparer les totaux des joueurs en dit donc autant sur la distribution que sur le jeu. Plus de mains réduisent l'influence de la chance, mais ne peuvent pas l'éliminer. Le Classé à l'ombre résout les deux : jouer le même duplicate retire presque toute la chance de la comparaison, et vous n'avez pas à attendre d'autres joueurs.

## Comment se déroule une partie

1. Ouvrez **Jouer en ligne → Classé à l'ombre** et connectez-vous.
2. Choisissez la longueur — **1 manche** (4 mains) ou **2 manches** (8 mains) — et si les **mains sans figure** sont autorisées. Voir [Main sans figure](scoring-chicken-hand).
3. Appuyez sur **Démarrer une partie**. Le serveur vous donne un duplicate de ce type que vous n'avez pas encore joué, et n'en crée un nouveau que lorsqu'il n'y en a aucun. Il préfère les duplicates que d'autres joueurs ont déjà terminés — vous avez ainsi des scores à comparer — mais vous pouvez aussi être le premier joueur humain à en jouer un. Vous n'êtes jamais sans comparaison : un bot a déjà joué à votre place, donc même en tant que premier joueur humain, votre total est immédiatement comparé au sien.

Vous ne choisissez pas le duplicate et vous ne pouvez rien en savoir à l'avance. Chaque duplicate a un identifiant comme [hl]7K3H-M9PQ[/], affiché une fois que vous avez terminé.

Le rythme est tranquille : **20 secondes** par décision — votre propre tour, ou une réponse à une défausse — plus une **réserve de 90 secondes** par main. Si votre connexion est perdue, reconnectez-vous pour continuer la même partie.

## Avec qui vous êtes comparé

Un duplicate est fixé à l'avance : le même mur de tuiles, la même main de départ pour chaque main, et les mêmes bots. Chaque place pioche dans sa propre portion du mur. Lorsqu'une portion de place est épuisée, la pioche se poursuit dans la plus grande portion appartenant à une autre place, si bien que la tuile suivante dépend de la façon dont la main a été jouée : les tuiles sont les mêmes pour tout le monde, mais l'ordre des pioches n'est pas fixé à l'avance. Chaque joueur qui affronte ce duplicate reçoit la même main de départ et la même place, donc les différences de total ne proviennent que de la façon dont la partie a été jouée.

Lorsque vous terminez, votre **total de la partie** est comparé avec :

- **Le bot.** À la création d'un duplicate, un bot joue en premier à votre place. Son total est le score à battre. Ce bot a un classement de **1200** et son classement ne change jamais. Vous ne pouvez pas voir son score avant d'avoir terminé.
- **Chaque joueur qui a terminé ce duplicate avant vous.**
- **Chaque joueur qui le terminera après vous.** Leurs résultats arrivent plus tard et font à nouveau évoluer votre classement.

Chaque comparaison est une victoire, une défaite ou (sur un total exactement égal) un match nul. Un duplicate accueille jusqu'à **10 joueurs humains**, il peut donc vous donner jusqu'à **neuf** autres joueurs avec qui vous comparer, plus le bot.

## Classement

Chaque comparaison fait évoluer votre classement comme contre un adversaire dans une partie en direct de même longueur. Battre un joueur plus fort vous rapporte davantage ; perdre contre un joueur plus faible vous coûte davantage. Dans une comparaison, le classement des deux joueurs est actualisé, d'après le classement de chaque joueur au moment où il a joué.

Par exemple, à 1200, battre le bot à 1200 sur un duplicate de 1 manche rapporte environ **+2.67**. Sur un duplicate de 2 manches, la même victoire rapporte environ **+5.33**. Un duplicate avec plusieurs joueurs implique plusieurs comparaisons, et chacune compte pleinement.

Le Classé à l'ombre utilise le même classement que les parties classées en direct.

## Après avoir terminé

Le tableau des résultats montre le bot et chaque joueur précédent, avec leur score, leur classement, et l'effet de chaque comparaison sur le vôtre.

**Mes tentatives** dans l'onglet Classé à l'ombre montre chaque duplicate que vous avez joué : combien de joueurs il compte pour l'instant et la variation totale de votre classement pour celui-ci. Un duplicate terminé montre aussi les scores — celui du bot et de chaque joueur qui l'a terminé jusqu'ici, y compris ceux qui ont joué après vous.

Lorsque quelqu'un joue plus tard l'un de vos duplicates, le résultat est mis de côté et rassemblé dans votre prochain **résumé** : un récapitulatif de chaque résultat ultérieur, avec chaque score et la variation de votre classement. Vous choisissez la fréquence du résumé dans les **Réglages**, de **chaque heure** à **une fois par jour** (par défaut).

Le résumé est livré sur un seul canal. Tant que l'application est ouverte, il y apparaît ; si l'application est fermée et que vous avez activé l'e-mail, il vous est envoyé par e-mail à la place. Ouvrir l'application avant l'envoi d'un e-mail en attente livre le résumé dans l'application et annule l'e-mail. Vous pouvez activer l'e-mail dans les **Réglages**.

## Jeu équitable

- **Une tentative par duplicate.** Dès que vous commencez un duplicate, il compte comme joué. Vous ne pouvez plus l'obtenir à nouveau, même si vous ne le terminez pas.
- **C'est le serveur qui distribue les tuiles.** Votre appareil ne reçoit que ce que vous êtes autorisé à voir, les tuiles cachées ne peuvent donc pas être lues depuis l'application.
- **Les duplicates sont attribués de façon imprévisible.** Après qu'un duplicate a été donné à quelqu'un, que cette personne l'ait commencé ou terminé, personne d'autre ne l'obtient pendant deux heures, et le choix parmi les duplicates ouverts est en partie aléatoire. Il est ainsi difficile d'envoyer un second compte en avance pour reconnaître un duplicate.
