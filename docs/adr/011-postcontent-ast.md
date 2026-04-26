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

Périmètre accepté : le contrat cible `PostContent`. L'implémentation initiale est livrée par la PR [#78](https://github.com/ForumHFR/redface2/pull/78) (parser HTML topic + AST `PostContent`) et la PR [#80](https://github.com/ForumHFR/redface2/pull/80) (`PostRenderer` Compose, suppression de `Post.content: String`, retrait de Jsoup hors `:core:parser`). La dette [#65](https://github.com/ForumHFR/redface2/issues/65) qui demandait le retrait du fragment HTML brut de `Post.content` est résorbée par #80.

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
- le parser BBCode de l'éditeur transforme le BBCode HFR brut vers la même AST `PostContent` quand l'éditeur arrive en Phase 2 ;
- `:core:ui` expose `PostRenderer` et ne dépend ni de Jsoup ni d'un parser BBCode ;
- le renderer Compose consomme uniquement `PostContent` ;
- le chemin `HTML -> BBCode -> AST` est exclu ;
- le renderer WebView est exclu pour le flux principal.

L'AST est sémantique, pas une copie de DOM HTML. Elle représente des blocs et inlines utiles à Redface :

- texte, gras, italique, souligné, barré, couleur ;
- liens ;
- smileys HFR builtin (`:code:`) et perso (`[:name]`) sans heuristique côté renderer ;
- citations récursives avec auteur, `numreponse` et page quand connus ;
- spoilers ;
- blocs monospace `[fixed]` ;
- images et médias, en bloc ou inline selon le contexte HTML ou BBCode source ;
- paragraphes et sauts de ligne.

La liste exacte des nœuds peut évoluer avec les fixtures réelles, mais la frontière reste stable : les sources HTML et BBCode sont normalisées avant d'atteindre l'UI. Les types Kotlin canoniques vivent dans [models.md]({{ site.baseurl }}/specs/models#topics-et-posts).

Le slice initial gardait un fragment HTML brut dans `Post.content` comme prototype. Cette dette ([#65](https://github.com/ForumHFR/redface2/issues/65)) est résorbée par la PR [#80](https://github.com/ForumHFR/redface2/pull/80) : `Post.content` porte désormais directement un `PostContent` rendu par `PostRenderer` Compose dans `:core:ui`, sans HTML brut côté UI.

## Conséquences

- `:core:model` contient les types purs `PostContent`, `PostBlock` et `PostInline`.
- `:core:parser` contient les parseurs déterministes vers `PostContent`, couverts par fixtures réelles et tests unitaires. Le parser HTML topic arrive en Phase 1 ; le parser BBCode éditeur peut attendre la Phase 2.
- `PostRenderer` devient réutilisable pour la lecture, la preview éditeur et les éventuels rendus texte simplifiés.
- La preview éditeur Phase 2 ne duplique pas un renderer BBCode séparé.
- Le cache Room peut décider plus tard de stocker aussi la source brute ou une forme sérialisée de l'AST, mais le modèle domaine exposé aux features reste `PostContent`. Le grain exact de stockage reste couvert par [#26](https://github.com/ForumHFR/redface2/issues/26).
- Les extensions de type `PostDecorator` travaillent sur le modèle domaine et peuvent inspecter l'AST sans parser du HTML.

## Alternatives considérées

- **HTML HFR subset -> renderer Compose** : très simple pour la lecture initiale, mais fuite de détails HTML dans l'UI et mauvaise réutilisation pour l'éditeur.
- **AST HTML-only en Phase 1, BBCode différé Phase 2** : pragmatique comme staging d'implémentation, mais insuffisant comme contrat d'architecture. On garde `PostContent` comme cible stable tout en autorisant l'arrivée du parser BBCode seulement avec l'éditeur.
- **HTML -> BBCode -> AST BBCode** : séduisant pour avoir un seul parser BBCode, mais fragile. Le HTML HFR rendu ne préserve pas toujours l'intention BBCode originale.
- **BBCode brut dans `Post.content` partout** : incompatible avec le flux lecture réel, qui reçoit du HTML de topic.
- **WebView** : plus rapide à court terme, mais reproduit les problèmes de scroll, recyclage et mémoire de Redface v1.
