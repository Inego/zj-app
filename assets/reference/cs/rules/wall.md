# Zeď a poslední kameny

Kameny se dobírají ze **zdi**. Standardní Zung Jung nechává
**mrtvou zeď** nevyužitou, aby hra skončila čistě.

## Mrtvá zeď

Posledních **14** kamenů zdi je **mrtvá zeď** — nedobírají se
v běžných tazích. Náhrady po kongu pořád přicházejí z uspořádání
zdi vyhrazeného pro ten účel; pro začátečníka je důležité tohle:
běžná dobrání se zastaví před vyhrazenými kameny.

V téhle aplikaci se mrtvá zeď **nezobrazuje**; kreslí se jen živá zeď.

## Mořské dno

**Mořské dno** je **poslední živý** kámen zdi — poslední kámen, který
lze dobrat jako běžné (nebo náhradní) dobrání, než se zeď pro hru
vyčerpá. Dobrání na tento kámen je
[Poslední dobrání (9.1.1)](pattern-9.1.1).

Pokud je tím dobráním náhrada po kongu na mořském dně,
[Výhra na kong (9.2)](pattern-9.2) se může s Posledním dobráním
sčítat.

## Dno řeky

Když je mořské dno dobráno, **odhoz** toho hráče (pokud ještě nikdo
nevyhrál) je **dno řeky**. Výhra na tento odhoz je
[Poslední odhoz (9.1.2)](pattern-9.1.2).

## Remíza (bez výhry)

Když se živá zeď vyčerpá bez výhry — typicky poté, co hráč na mořském
dně zahodí a nikdo si dno řeky nenárokuje — je rozdání **remíza**
(bez výhry). Nikdo nedostane body za vítěznou ruku.
