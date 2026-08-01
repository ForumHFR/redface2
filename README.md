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

Phase courante : **Phase 4 — Extensions + refonte UI** ([roadmap](https://forumhfr.github.io/redface2/specs/roadmap)). Phases 0 à 3 livrées (bootstrap ; lecture du forum ; écriture poster/citer/upload/smileys ; messages MP + DT/MultiMP + recherche), bêta **0.37.0** publiée (Play open testing + F-Droid ; refontes des vues Drapeaux [#603](https://github.com/ForumHFR/redface2/issues/603) et Topic [#604](https://github.com/ForumHFR/redface2/issues/604) livrées, passe images [#876](https://github.com/ForumHFR/redface2/issues/876) soldée). En cours : itération 2 des vues Topic et Éditeur, architecture d'extensions ([#6](https://github.com/ForumHFR/redface2/issues/6)/[#7](https://github.com/ForumHFR/redface2/issues/7)).

## Pourquoi réécrire ?

Redface v1 tourne sur une stack de 2015 : Java 11, Retrofit 1.9, RxJava 1, ButterKnife, Otto, minSdk 16. Chaque brique est obsolète ou dépréciée. Un refactoring incrémental serait plus coûteux qu'une réécriture.

## Méthodologie

La méthode canonique du projet est documentée dans [Méthodologie](https://forumhfr.github.io/redface2/specs/methodology) et formalisée dans [ADR-000](https://forumhfr.github.io/redface2/adr/000-methodologie-triple-hybride). `AGENTS.md` garde les règles opérationnelles pour les agents, pas la méthode complète.

## Participer

Les contributions aux specs sont ouvertes. Voir les [issues](https://github.com/ForumHFR/redface2/issues) ou la page [Contribuer](https://forumhfr.github.io/redface2/guides/contributing).

## Licence

`GPL-3.0-only`

Le choix vise à garder le client Android communautaire et à éviter les forks applicatifs fermés. La question d'un éventuel service réseau futur est traitée séparément.
