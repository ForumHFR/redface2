# Redface 2

> Le futur client Android pour [Hardware.fr](https://forum.hardware.fr)

Réécriture complète de [Redface](https://github.com/ForumHFR/Redface) avec une stack moderne : **Kotlin**, **Jetpack Compose**, **MVI**, **Hilt**, **Room**.

## Documentation

Les spécifications complètes sont disponibles sur le site du projet :

**[forumhfr.github.io/redface2](https://forumhfr.github.io/redface2)**

- [Spécifications](https://forumhfr.github.io/redface2/specs) — Vue d'ensemble des pages canoniques
- [Guides](https://forumhfr.github.io/redface2/guides) — Contribution, contexte et nommage
- [Stack technique](https://forumhfr.github.io/redface2/specs/stack) — Pourquoi chaque techno
- [Architecture](https://forumhfr.github.io/redface2/specs/architecture) — Modules, couches, data flow
- [Scope fonctionnel](https://forumhfr.github.io/redface2/specs/scope) — Ce que l'app doit permettre de faire
- [Protocole HFR](https://forumhfr.github.io/redface2/specs/protocol-hfr) — Contrats externes et edge cases
- [Navigation](https://forumhfr.github.io/redface2/specs/navigation) — Écrans et flows
- [Pattern MVI](https://forumhfr.github.io/redface2/specs/mvi) — Architecture UI
- [Modèles](https://forumhfr.github.io/redface2/specs/models) — Structures de données
- [Extensions](https://forumhfr.github.io/redface2/specs/extensions) — Extensions communautaires
- [Méthodologie](https://forumhfr.github.io/redface2/specs/methodology) — Comment le projet spécifie, prototype et teste
- [Roadmap](https://forumhfr.github.io/redface2/specs/roadmap) — Phases de dev
- [Contribuer](https://forumhfr.github.io/redface2/guides/contributing) — Comment participer

## État

Le projet est en **phase de spécification**. Le code viendra après validation des specs par la communauté.

## Pourquoi réécrire ?

Redface v1 tourne sur une stack de 2015 : Java 11, Retrofit 1.9, RxJava 1, ButterKnife, Otto, minSdk 16. Chaque brique est obsolète ou dépréciée. Un refactoring incrémental serait plus coûteux qu'une réécriture.

## Méthodologie

La méthode canonique du projet est documentée dans [Méthodologie](https://forumhfr.github.io/redface2/specs/methodology) et formalisée dans [ADR-000](https://forumhfr.github.io/redface2/adr/000-methodologie-triple-hybride). `AGENTS.md` garde les règles opérationnelles pour les agents, pas la méthode complète.

## Participer

Les contributions aux specs sont ouvertes. Voir les [issues](https://github.com/ForumHFR/redface2/issues) ou la page [Contribuer](https://forumhfr.github.io/redface2/guides/contributing).

## Licence

Apache 2.0 (comme Redface v1)
