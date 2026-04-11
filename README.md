# Redface 2

> Le futur client Android pour [Hardware.fr](https://forum.hardware.fr)

Reecriture complete de [Redface](https://github.com/ForumHFR/Redface) avec une stack moderne : **Kotlin**, **Jetpack Compose**, **MVI**, **Hilt**, **Room**.

## Documentation

Les specifications completes sont disponibles sur le site du projet :

**[forumhfr.github.io/redface2](https://forumhfr.github.io/redface2)**

- [Stack technique](https://forumhfr.github.io/redface2/stack) — Pourquoi chaque techno
- [Architecture](https://forumhfr.github.io/redface2/architecture) — Modules, couches, data flow
- [Navigation](https://forumhfr.github.io/redface2/navigation) — Ecrans et flows
- [Pattern MVI](https://forumhfr.github.io/redface2/mvi) — Architecture UI
- [Modeles](https://forumhfr.github.io/redface2/models) — Structures de donnees
- [Features](https://forumhfr.github.io/redface2/features) — Extensions communautaires
- [Roadmap](https://forumhfr.github.io/redface2/roadmap) — Phases de dev
- [Contribuer](https://forumhfr.github.io/redface2/contributing) — Comment participer

## Etat

Le projet est en **phase de specification**. Le code viendra apres validation des specs par la communaute.

## Pourquoi reecrire ?

Redface v1 tourne sur une stack de 2015 : Java 11, Retrofit 1.9, RxJava 1, ButterKnife, Otto, minSdk 16. Chaque brique est obsolete ou deprecie. Un refactoring incremental serait plus couteux qu'une reecriture.

## Participer

Les contributions aux specs sont ouvertes. Voir les [issues](https://github.com/ForumHFR/redface2/issues) ou la page [Contribuer](https://forumhfr.github.io/redface2/contributing).

## Licence

Apache 2.0 (comme Redface v1)
