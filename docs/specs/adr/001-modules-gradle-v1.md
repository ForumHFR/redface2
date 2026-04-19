---
title: ADR-001
parent: ADRs
grand_parent: Spécifications
nav_order: 1
permalink: /specs/adr/001-modules-gradle-v1
---

# ADR-001 — Découpage Gradle v1 : 15 modules avant les extensions

## Statut

Accepté — 2026-04-19

## Contexte

Le projet doit rester lisible pour des contributeurs humains et des agents LLM, tout en évitant qu'une séparation purement conventionnelle dérive avec le temps.

Un monolithe unique `:app` simplifie le bootstrap, mais ne protège pas les frontières. À l'inverse, un découpage trop fin dès le jour 1 crée une complexité de structure avant même que le noyau soit validé.

## Décision

La v1 de Redface 2 adopte un découpage en **15 modules** pour les Phases 0 à 3 :

- **8 modules core**
  - `:core:model`
  - `:core:domain`
  - `:core:data`
  - `:core:network`
  - `:core:parser`
  - `:core:database`
  - `:core:ui`
  - `:core:extension`
- **7 modules feature de base**
  - `:feature:forum`
  - `:feature:topic`
  - `:feature:editor`
  - `:feature:messages`
  - `:feature:auth`
  - `:feature:search`
  - `:feature:settings`

Les **8 modules d'extensions communautaires** arrivent en **Phase 4**, pas avant.

## Conséquences

- les frontières d'architecture sont enforcées à la compilation, pas seulement par convention
- `:feature:*` ne dépendent pas directement de la couche data
- l'onboarding et la documentation doivent refléter ce découpage exact
- la complexité des extensions est repoussée après validation du noyau lecteur/forum

## Alternatives considérées

- **Single-module Gradle** : bootstrap plus simple, mais frontières trop faibles
- **Très peu de modules larges** : compromis possible, mais moins bon pour l'isolation des responsabilités
- **Inclure les extensions dès la v1** : trop tôt ; risque d'over-engineering avant validation du noyau
