---
title: ADR-011
parent: ADRs
grand_parent: Spécifications
nav_order: 11
permalink: /adr/011-postcontent-ast
---

# ADR-011 — AST sémantique `PostContent` comme contrat de rendu

## Statut

Accepté — 2026-04-24

## Contexte

Redface 2 doit rendre les posts HFR en Compose natif, sans WebView. Le flux réel impose deux sources différentes :

- en lecture de topic, HFR fournit du **HTML déjà rendu** ;
- dans l'éditeur, les formulaires HFR manipulent du **BBCode brut**.

La première tranche de lecture de topic fixe a volontairement rendu un sous-ensemble HTML directement dans `:feature:topic` pour apprendre vite sur des fixtures réelles. Ce chemin reste un prototype : il ne doit pas devenir le contrat stable du renderer.

Le risque architectural est de choisir une représentation trop proche d'une source :

- une AST HTML rend le lecteur simple, mais réutilise mal le renderer pour la preview BBCode ;
- une AST BBCode pure colle à l'éditeur, mais force la lecture de topic à reconstruire du BBCode depuis du HTML HFR, ce qui est fragile et potentiellement lossy ;
- une WebView reproduit les limites de Redface v1.

## Décision

Redface 2 retient une AST sémantique commune, `PostContent`, comme contrat entre parsing et rendu.

Le contrat cible est :

```text
HTML HFR topic  ─┐
                 ├─> PostContent AST ─> PostRenderer Compose
BBCode éditeur  ─┘
```

Les règles associées sont :

- `Post.content` et `PMMessage.content` portent un `PostContent`, pas une chaîne HTML ou BBCode brute ;
- `:core:parser` transforme le HTML HFR réel en modèles domaine et en `PostContent` ;
- le parser BBCode de l'éditeur transforme le BBCode HFR brut vers la même AST `PostContent` ;
- `:core:ui` expose `PostRenderer` et ne dépend ni de Jsoup ni d'un parser BBCode ;
- le renderer Compose consomme uniquement `PostContent` ;
- le chemin `HTML -> BBCode -> AST` est exclu ;
- le renderer WebView est exclu pour le flux principal.

L'AST est sémantique, pas une copie de DOM HTML. Elle représente des blocs et inlines utiles à Redface :

- texte, gras, italique, souligné, barré, couleur ;
- liens ;
- smileys HFR ;
- citations récursives avec auteur quand connu ;
- spoilers ;
- blocs monospace `[fixed]` ;
- images et médias ;
- paragraphes et sauts de ligne.

La liste exacte des nœuds peut évoluer avec les fixtures réelles, mais la frontière reste stable : les sources HTML/BBCode sont normalisées avant d'atteindre l'UI.

Le slice actuel qui garde un fragment HTML dans `Post.content` est une dette de prototype. Il doit être résorbé par [#65](https://github.com/ForumHFR/redface2/issues/65) avant que ce pattern soit reproduit ailleurs.

## Conséquences

- `:core:model` contient les types purs `PostContent`, `PostBlock` et `PostInline`.
- `:core:parser` contient les parseurs déterministes vers `PostContent`, couverts par fixtures réelles et tests unitaires.
- `PostRenderer` devient réutilisable pour la lecture, la preview éditeur et les éventuels rendus texte simplifiés.
- La preview éditeur Phase 2 ne duplique pas un renderer BBCode séparé.
- Le cache Room peut décider plus tard de stocker aussi la source brute ou une forme sérialisée de l'AST, mais le modèle domaine exposé aux features reste `PostContent`.
- Les extensions de type `PostDecorator` travaillent sur le modèle domaine et peuvent inspecter l'AST sans parser du HTML.

## Alternatives considérées

- **HTML HFR subset -> renderer Compose** : très simple pour la lecture initiale, mais fuite de détails HTML dans l'UI et mauvaise réutilisation pour l'éditeur.
- **HTML -> BBCode -> AST BBCode** : séduisant pour avoir un seul parser BBCode, mais fragile. Le HTML HFR rendu ne préserve pas toujours l'intention BBCode originale.
- **BBCode brut dans `Post.content` partout** : incompatible avec le flux lecture réel, qui reçoit du HTML de topic.
- **WebView** : plus rapide à court terme, mais reproduit les problèmes de scroll, recyclage et mémoire de Redface v1.
