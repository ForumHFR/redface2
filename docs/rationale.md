---
title: Pourquoi Redface 2 ?
nav_order: 11
---

# Pourquoi Redface 2 ?
{: .fs-8 }

Justification honnête du projet, avec les doutes assumés.
{: .fs-5 .fw-300 }

---

## Contexte

Réécrire une app depuis zéro est une décision lourde. C'est le **syndrome du rewrite** — classique, documenté ([Joel Spolsky 2000](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/)), souvent une erreur. Cette page documente les raisons de cette décision et les risques assumés, suite aux questions légitimes soulevées par la communauté (notamment [gig-gic](https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=29332)).

Ce document doit exister **avant** le code, pas après.

---

## 1. Problèmes concrets de Redface v1

La justification du rewrite ne tient pas à "la stack est ancienne" — une lib dépréciée n'est pas un problème en soi. Elle tient aux symptômes observables.

### Bugs critiques ouverts (avril 2026)

Source : [issues ouvertes de ForumHFR/Redface](https://github.com/ForumHFR/Redface/issues), 74 issues, dont 21 bugs.

| Issue | Description | Ouvert depuis |
|-------|-------------|---------------|
| [#239](https://github.com/ForumHFR/Redface/issues/239) | Ouverture d'images → HTTP 403 (URL reho.st mal construite) | 2026 |
| [#237](https://github.com/ForumHFR/Redface/issues/237) | Téléchargement d'images → crash | 2026 |
| [#236](https://github.com/ForumHFR/Redface/issues/236) | Scroll de topic qui saute (régression v5.1.0) | 2026 |
| [#225](https://github.com/ForumHFR/Redface/issues/225) | App "quasiment inutilisable" sous Hyper OS 2 (Xiaomi) | 2025 |
| [#228](https://github.com/ForumHFR/Redface/issues/228) | Fenêtre de rédaction qui disparaît | 2024 |
| [#171](https://github.com/ForumHFR/Redface/issues/171) | EndlessScroll cassé | **2016** |
| [#152](https://github.com/ForumHFR/Redface/issues/152) | Notifications mail désactivées après post depuis l'app | **2016** |

Certains bugs traînent depuis 9 ans. Ce n'est pas faute de les signaler.

### Stack en fin de vie (faits techniques)

| Composant | Version v1 | État | Impact concret |
|-----------|-----------|------|----------------|
| minSdk | 16 (Android 4.1, 2012) | Android 4.1 = 0.1% des appareils | APIs modernes inaccessibles (biometric, scoped storage, etc.) |
| Retrofit | 1.9 | Déprécié depuis 2016 | Pas de support coroutines, sécurité réseau obsolète |
| RxJava | 1.x | Déprécié depuis 2018 | Incompatible avec le reste de l'écosystème Kotlin/Compose |
| OkHttp | 3.14.9 | Fin de vie | TLS 1.3 non garanti, pas de support HTTP/3 |
| ButterKnife | — | Déprécié officiellement | Remplacé par ViewBinding/Compose, pas de maintenance |
| Otto (event bus) | — | Déprécié | Patterns modernes = StateFlow/SharedFlow |
| Glide | 4.9 | 3 versions majeures de retard | Bugs connus corrigés dans les versions récentes |
| Dagger Android | mode classique | Remplacé par Hilt | Nécessite un refactor de toute la DI |
| Langage | 100% Java | — | Recrutement contributeurs de plus en plus difficile |

### Pourquoi un refactoring incrémental est coûteux

Chaque brique dépend des autres :
- Migrer Retrofit → Retrofit 2 ou OkHttp direct implique de réécrire toute la couche réseau
- Migrer RxJava → Coroutines touche chaque appel asynchrone de l'app
- Passer de ButterKnife/XML → Compose est une réécriture UI complète
- Passer Dagger Android → Hilt nécessite de refactorer la DI de A à Z
- Passer Java → Kotlin nécessite de tout re-typer

Ces migrations ne peuvent pas être faites indépendamment les unes des autres. En pratique, un refactoring incrémental sur Redface v1 serait une réécriture progressive où l'app resterait partiellement cassée pendant des mois.

**Alternative considérée et écartée** : ne rien faire et laisser v1 vivre en mode maintenance. Écarté car les bugs critiques actuels rendent l'app inutilisable sur certains appareils (Hyper OS 2 #225), et aucun nouveau contributeur ne vient réparer ça.

---

## 2. "Qui te dit que tu vas faire les bons choix ?"

**Rien.** Personne ne peut garantir qu'on fait les bons choix. Le pari est de maximiser les chances de limiter les mauvais choix via un process explicite.

### Ce qu'on met en place

| Mécanisme | Objectif |
|-----------|----------|
| **Specs publiques avant le code** | Les décisions sont débattues et documentées avant l'implémentation |
| **Review communautaire** | Des devs expérimentés (Ayuget, Corran Horn, ezzz, gig-gic) challengent les choix |
| **Audits réguliers** | Audits des specs eux-mêmes ([#14](https://github.com/ForumHFR/redface2/issues/14), [#17](https://github.com/ForumHFR/redface2/issues/17)) pour détecter les erreurs avant qu'elles deviennent du code |
| **Reprise des fixtures v1** | 17 fixtures HTML de Redface v1 servent de base de tests, garantissant que le comportement éprouvé est préservé |
| **Stack enforcement au build** | [#20](https://github.com/ForumHFR/redface2/issues/20) — règles d'architecture en tests (Konsist/ArchUnit), pas en convention Markdown |
| **Attribution IA obligatoire** | [#16](https://github.com/ForumHFR/redface2/issues/16) — chaque commit/PR généré par IA mentionne le modèle, permettant de tracer les erreurs |

### Ce qu'on ne peut pas garantir

- Qu'une stack considérée moderne en 2026 le sera en 2030
- Que l'architecture scale à des features qu'on n'a pas encore anticipées
- Qu'on n'oublie pas des edge cases que v1 gérait silencieusement

**Plan de mitigation** : documenter les décisions (ADR ou équivalent), garder les specs versionnées, permettre le retour en arrière rapide via le process de review.

---

## 3. "Archi modulaire, c'est quoi ? Qu'est-ce qui est monolithique ?"

### Redface v1 : monolithique (fait vérifié)

Source : [ForumHFR/Redface/settings.gradle](https://github.com/ForumHFR/Redface/blob/master/settings.gradle).

- **1 seul module Gradle** : `:app`
- Tout le code dans `app/src/main/java/com/ayuget/redface/`
- Organisé en packages Java (`account`, `cache`, `data`, `network`, `ui`, etc.) mais sans séparation Gradle
- Impossible d'isoler la couche réseau ou le parser pour les tester/réutiliser indépendamment

### Conséquences pratiques

- **Tests unitaires lents** : tout recompile, tout s'exécute dans le même module
- **Cycles de dépendances possibles** (classes UI qui importent du réseau directement)
- **Pas de réutilisation** : le parser v1 ne peut pas être utilisé hors de Redface (une autre app devrait copier-coller le code)
- **Onboarding difficile** : un nouveau contributeur doit comprendre tout le code pour toucher un bout

### Redface v2 : 23 modules Gradle

Détaillé dans [architecture.md](architecture.md). En résumé :

- 8 modules `:core:*` (model, domain, data, network, parser, database, ui, extension)
- 7 modules `:feature:*` de base (forum, topic, editor, messages, auth, search, settings)
- 8 modules `:feature:*` d'extensions (bookmarks, blacklist, qualitay, redflag, etc.)

Chaque feature ne dépend que de `:core:domain` + `:core:ui`. Impossible d'importer du réseau dans un écran par erreur — Gradle l'interdit à la compilation.

### Contre-argument honnête

23 modules, c'est beaucoup. Dans la review audit [#17](https://github.com/ForumHFR/redface2/issues/17), ce point a été soulevé. On a phasé : 15 modules en Phases 0-3, les 8 extensions n'arrivent qu'en Phase 4. [Now in Android](https://github.com/android/nowinandroid) (60+ modules) et [Pocket Casts](https://github.com/Automattic/pocket-casts-android) (37) montrent que ce nombre est dans la norme pour une app Android moderne.

---

## 4. "Pourquoi aujourd'hui aucun dev ne vient ?"

**La question la plus dure.** Sans contributeurs, le projet meurt de la même façon que v1.

### Faits : contributeurs de Redface v1

Source : [ForumHFR/Redface contributors](https://github.com/ForumHFR/Redface/graphs/contributors).

| Contributeur | Commits | % total |
|--------------|---------|---------|
| Ayuget | 502 | **92%** |
| KaiserZip | 19 | 3.5% |
| nbonnec | 12 | 2.2% |
| cdongieux | 8 | 1.5% |
| Autres (3) | 4 | 0.8% |

- **3 contributeurs** avec 5+ commits sur 11 ans
- **Aucun nouveau contributeur depuis 2022** (sauf nbonnec qui revient après 6 ans d'absence)
- **PRs externes ouvertes depuis 2019-2021 jamais mergées** ([#209](https://github.com/ForumHFR/Redface/pull/209), [#211](https://github.com/ForumHFR/Redface/pull/211), [#217](https://github.com/ForumHFR/Redface/pull/217))

### Diagnostic honnête

Pourquoi si peu de contributeurs :

1. **Stack pas attractive** : un dev Android en 2026 ne va pas commencer un projet en Java + RxJava 1 + ButterKnife. Il préfère un projet Kotlin + Compose.
2. **Mainteneur unique surchargé** : les PRs externes restent ouvertes des années sans review, ce qui décourage les nouveaux.
3. **Pas d'onboarding** : pas de CONTRIBUTING.md détaillé, pas de roadmap, pas d'architecture documentée.
4. **Projet perçu comme "presque mort"** : peu d'activité visible, 0 commit en 2020 et 2024.

### Plan pour Redface 2

| Levier | Mise en œuvre |
|--------|---------------|
| **Stack moderne attractive** | Kotlin + Compose + Coroutines. Standard 2026, attire les devs Android actuels |
| **Specs publiques et détaillées** | 11 pages de documentation avant le premier commit de code |
| **Architecture modulaire** | Un contributeur peut toucher `:feature:bookmarks` sans comprendre tout le projet |
| **Onboarding via skills Claude Code** | `/parse-fixture`, `/spec-check`, `/spec-audit` pour baisser la barrière |
| **LLM-assisted dev documenté** | [#16](https://github.com/ForumHFR/redface2/issues/16) — règles claires pour contribuer avec un LLM, attribution obligatoire |
| **Process de review clair** | Pas de PR ouverte pendant 4 ans — engagement à review sous 7 jours |

### Reconnaissance honnête du risque

Tous ces leviers sont **spéculatifs**. Ils peuvent ne pas suffire.

**Signaux à surveiller dans les 6 mois suivant le début du dev :**
- Nombre de contributeurs externes qui ont ouvert une PR
- Temps moyen de review d'une PR (cible < 7 jours)
- Nombre d'issues "good first issue" traitées par d'autres qu'Ayuget/XaTriX
- Activité sur le topic HFR

Si ces signaux sont rouges à 6 mois, il faudra se poser la question du maintien du projet. C'est un pari, pas une certitude.

**Le LLM ne peut pas porter le projet seul sur 2 ans.** Il aide à démarrer, à cadrer, à générer du boilerplate. Mais sans 2-3 devs humains engagés, le projet meurt comme v1.

---

## Décision

Sur la base des 4 points ci-dessus :

- **Problèmes de v1** : réels, documentés, pas résolus malgré les années (✅ justifie une action)
- **Risque de mauvais choix** : mitigé par un process explicite, mais pas éliminé
- **Monolithique** : fait technique vérifié — v1 est monolithique, v2 est modulaire
- **Contributeurs** : risque assumé, plan de mitigation posé, jalon de réévaluation à 6 mois

**Décision** : poursuivre le rewrite en mode ouvert et prudent. Les specs avant le code, la communauté avant les décisions, les signaux de contribution comme check de santé.

Cette page sera mise à jour si les faits évoluent.
