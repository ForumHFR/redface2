---
title: ADR-009
parent: ADRs
grand_parent: Spécifications
nav_order: 9
permalink: /specs/adr/009-okhttp-5-3-plus
---

# ADR-009 — OkHttp 5.3+ retenu comme client HTTP principal

## Statut

Accepté — 2026-04-19

## Contexte

Redface 2 ne consomme pas une API REST structurée. L'application récupère principalement du **HTML brut** HFR, puis le parse.

Dans ce contexte, les abstractions de type Retrofit apportent peu de valeur. Le besoin principal est :
- construire des requêtes HTTP simples
- gérer la session via `CookieJar`
- récupérer du HTML brut
- garder une couche réseau explicite et légère

## Décision

Le projet retient **OkHttp 5.3+** comme client HTTP principal, utilisé **directement**, sans Retrofit.

Les bénéfices retenus :
- adaptation naturelle au scraping HTML
- gestion native des cookies de session
- API moderne (`kotlin.time.Duration`, `mockwebserver3`)
- démarrage sur une base stable et actuelle

Le report éventuel du KMP post-v1 est un **choix de scope**, pas une contrainte liée à OkHttp 5.

## Conséquences

- la couche réseau construit ses requêtes explicitement
- la séparation entre réseau et parsing reste nette
- aucun coût de migration 4.x → 5.x n'est à payer plus tard, puisque le projet démarre neuf
- Retrofit et Ktor ne font pas partie du noyau réseau de v1

## Alternatives considérées

- **Retrofit + OkHttp** : adapté aux APIs structurées, peu pertinent pour du HTML brut
- **Ktor Client** : plus générique, mais moins naturel pour ce besoin Android-first immédiat
- **OkHttp 4.x** : inutile de démarrer sur l'ancienne branche alors que 5.3+ est la baseline retenue
