---
title: Méthodologie
parent: Spécifications
nav_order: 8
permalink: /specs/methodology
---

# Méthodologie
{: .fs-8 }

Comment Redface 2 spécifie, prototype, décide et teste.
{: .fs-5 .fw-300 }

---

## Principe

Redface 2 utilise une méthodologie **triple-hybride : SDD + Prototype + TDD**.

Ce choix vient d'un constat simple : au démarrage, le projet est parti trop loin dans une logique spec-first. Après 3 cycles d'audit et ~6500 lignes de documentation sans une ligne de code, il est devenu clair qu'une partie importante du projet ne pouvait pas être correctement conçue uniquement en prose. Le pivot formel a été acté pendant le cycle [#24](https://github.com/ForumHFR/redface2/issues/24) (simplification post-v0.4.0).

Certaines zones doivent être **spécifiées avant** d'être codées :
- le protocole HFR
- les frontières d'architecture
- la sécurité des credentials
- les contrats externes comme MPStorage

D'autres zones doivent être **découvertes en codant** :
- l'UI Compose
- le schéma Room
- les arbitrages de perf
- le rendu BBCode

Enfin, certaines briques gagnent à être **testées en TDD strict** parce qu'elles sont pures et déterministes :
- parser HTML → domaine
- BBCode → AST
- reducers / helpers MVI
- mappers

La méthode du projet consiste donc à utiliser **le bon niveau de formalisme selon le type de problème**, au lieu d'appliquer une seule école partout.

Cette page est la **source canonique** de la méthode du projet. L'ADR-000 en formalise la décision a posteriori.

## Les 7 règles

| Règle | Application |
|---|---|
| **1. Spec ce qui doit tenir** *(SDD sélectif)* | Protocole HFR, architecture des couches, sécurité credentials, contrats externes, langage métier. Une erreur ici crée de la dette silencieuse. |
| **2. Prototype ce qu'on découvre** | UI/UX Compose, schéma Room, perf, interactions features, rendu BBCode. Le design émerge du 2e use case, pas du 0e. |
| **3. TDD sélectif sur fonctions pures** | Parser (HTML → domain), BBCode → AST, ViewModels MVI, helpers (`matchesFilter`, `comparatorFor`), date parser, mappers. Red → Green → Refactor. |
| **4. Test-after sur intégrations** | Repositories réseau+parser+cache, deep linking, flows authentifiés. Les tests viennent après l'impl, avec des mocks ou fixtures réalistes. |
| **5. Coverage guidée par risque** | Pas d'objectif "100%". On couvre les edge cases réels, les régressions plausibles et les fixtures HFR capturées. |
| **6. ADRs a posteriori** | Les ADRs formalisent les décisions après arbitrage réel. Pas de RFC spéculative avant code. |
| **7. Règle des 30 minutes** | Face à une feature nouvelle, coder 30 minutes d'abord ; spec seulement ce qui débloque les 30 suivantes. |

**Règle méta** : pas de nouveau cycle d'audit de specs sans déclencheur concret. Un audit se justifie par une incohérence réelle, un bug récurrent, ou une friction d'onboarding.

## Matrice de décision

Quand un nouveau sujet arrive, commencer par cette question :

| Si le sujet est surtout... | Approche par défaut |
|---|---|
| Contrat externe ou contrainte difficile à inverser | **Spec-first** |
| Forme UX ou découverte de stockage | **Prototype-first** |
| Logique pure et déterministe | **TDD-first** |
| Orchestration réelle entre composants | **Impl puis test d'intégration** |

## Exemples concrets

| Sujet | Approche | Pourquoi |
|---|---|---|
| **Protocole HFR** (`docs/specs/protocol-hfr.md`) | Spec-first | Contrat externe mal documenté ; une erreur casse l'app silencieusement. |
| **Parser HFR** (`HfrParser.parseTopicPage`) | TDD strict | Fonction pure, fixtures réelles, edge cases identifiables. Cf. [#15](https://github.com/ForumHFR/redface2/issues/15). |
| **BBCode → AST** | TDD strict | Transformation pure ; facile à verrouiller par tests ciblés. |
| **Écran Drapeaux** | Prototype-first | L'ergonomie réelle n'émerge pas d'une spec statique. |
| **PostRenderer Compose** | Prototype-first | Le rendu de posts en UI réelle se découvre de manière incrémentale sur de vraies fixtures. Cf. [#3](https://github.com/ForumHFR/redface2/issues/3). |
| **Schéma Room** | Prototype-first | Le bon grain de stockage dépend des premiers use cases réellement codés. Cf. [#26](https://github.com/ForumHFR/redface2/issues/26). |
| **Stockage credentials** | Spec-first | Une décision de sécurité implicite devient vite une faille implicite. Option A tranchée dans [ADR-002]({{ site.baseurl }}/specs/adr/002-credentials-option-a). |
| **ViewModel Flags** | TDD sélectif | Reducers, filtres et tri sont déterministes et faciles à tester. |
| **TopicRepository** | Test-after | Orchestration réseau + parser + cache ; utile de tester une fois le flux réel en place. |
| **Deep linking** | Test-after | Le comportement se vérifie mieux après support de plusieurs URLs réelles. |

## Antipatterns

- **Spec complète d'un écran Compose avant prototype** : produit souvent une UI imaginaire, pas un écran robuste.
- **TDD sur UI Compose, Hilt wiring ou animations** : trop de bruit, pas assez de signal.
- **Gate coverage chiffrée** : pousse à écrire des tests décoratifs.
- **ADR pré-code** : fige des décisions encore spéculatives.
- **Audit de specs en boucle** : forte production de findings cosmétiques, faible apprentissage réel.
- **Même texte méthodo dupliqué dans 4 fichiers** : la divergence arrive vite, surtout avec plusieurs LLMs.

## Ce que cette méthode change concrètement

- Une feature n'est pas automatiquement "à spécifier" : on commence par se demander si c'est un invariant, une exploration, ou une fonction pure.
- Une discussion d'architecture ne se clôt pas par une intuition : il faut soit une spec stable, soit un prototype, soit un test qui réduit réellement l'incertitude.
- Un LLM ne doit pas remplir les zones d'inconnu avec du texte plausible. S'il n'y a pas assez de contexte réel, il faut soit prototyper, soit vérifier, soit laisser la question ouverte.

## Rôle du LLM

Le projet est multi-LLM et LLM-first. La méthodologie sert aussi à **contraindre** cette réalité.

- Le LLM peut accélérer la production, pas remplacer l'arbitrage humain sur les sujets structurants.
- Un sujet doit avoir **une source canonique unique** ; le LLM doit la lire avant de modifier.
- Un claim du type `testé`, `vérifié`, `stable`, `compatible` doit reposer sur une commande exécutée, une fixture réelle, ou une doc officielle consultée.
- Un changement structurant ne devrait pas être produit, validé et approuvé par le même agent seul.
- **Vérification API actuelle** : avant d'utiliser une API dont le statut est incertain (existe-t-elle ? est-elle dépréciée ?), consulter la doc officielle via Context7 ou Docfork avec mot-clé *stable release*. Cf. [#19](https://github.com/ForumHFR/redface2/issues/19).

## Checklist anti-dérive

Si un ou plusieurs de ces signaux apparaissent, la méthodologie est probablement mal appliquée :

- le repo gagne plus de prose que de certitude
- l'architecture se détaille avant le premier spike risqué
- un snippet paraît correct mais ne colle pas à l'API stable actuelle
- le backlog ne reflète plus l'état réel des specs
- un draft commence à être traité comme s'il était canonique
- un seul LLM écrit, relit et valide le même changement structurant

## Quand réévaluer

La méthode ne doit pas être réécrite à chaque discussion. Elle se réévalue seulement si :

- une friction concrète revient régulièrement
- le projet change d'échelle
- un cycle d'implémentation réel montre qu'une règle produit plus de bruit que de signal

Par défaut, la prochaine vraie réévaluation a du sens **après la Phase 1**, ou au premier signal net de friction.

## Références

- [ADR-000]({{ site.baseurl }}/specs/adr/000-methodologie-triple-hybride) — formalisation de cette méthode
- [#29](https://github.com/ForumHFR/redface2/issues/29) — issue de promotion
- [#27](https://github.com/ForumHFR/redface2/issues/27) — bootstrap `docs/specs/adr/`
- [Contribuer]({{ site.baseurl }}/guides/contributing) — workflow et stratégie de tests
