---
title: ADR-008
parent: ADRs
grand_parent: Spécifications
nav_order: 8
permalink: /adr/008-compose-navigation-3
---

# ADR-008 — Compose Navigation 3 retenu pour la navigation

## Statut

Accepté — 2026-04-19

## Contexte

Redface 2 est une application 100% Compose avec un besoin explicite de :
- back stack typé
- intégration propre avec Material 3 Adaptive
- gestion explicite des scènes et du state
- deep linking HFR, y compris avec des fragments URI non gérés nativement

Les options étudiées étaient principalement :
- Navigation 2.x
- Compose Navigation 3
- Circuit
- Decompose

## Décision

Le projet retient **Compose Navigation 3** comme stack de navigation.

Les choix d'API associés sont :
- routes typées implémentant `NavKey`
- back stack explicite via `NavBackStack<NavKey>`
- scènes calculées via `rememberSceneState`
- gestion du back via `NavigationBackHandler`

Les fragments HFR de type `#t{numreponse}` sont parsés manuellement à l'entrée de l'app, puis transformés en routes typées.

## Conséquences

- la navigation est alignée avec une app Compose-first moderne
- les exemples et snippets doivent utiliser l'API stable actuelle de Navigation 3
- le deep linking HFR reste une responsabilité applicative explicite, pas un mapping "automatique"
- les écrans tablette et list-detail se branchent naturellement sur cette stack

## Alternatives considérées

- **Navigation 2.x** : mature, mais moins propre pour le modèle de scenes/state retenu
- **Circuit** : solide, mais plus structurant et moins naturel pour ce repo
- **Decompose** : puissant, mais apporte un modèle plus large que le besoin immédiat
