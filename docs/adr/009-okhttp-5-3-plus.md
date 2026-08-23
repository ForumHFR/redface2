---
title: ADR-009
parent: ADRs
grand_parent: Spécifications
nav_order: 9
permalink: /adr/009-okhttp-5-3-plus
---

# ADR-009 — OkHttp 5.3+ retenu comme client HTTP principal

## Statut

Accepté — 2026-04-19 · **amendé 2026-08-23 (passe de cohérence specs↔code)** : la décision est tenue sur son fond (OkHttp 5.3+ direct, sans Retrofit — `okhttp = "5.3.2"` dans `gradle/libs.versions.toml`), mais **une de ses prémisses n'est pas réalisée** — voir la note sous § Décision. Pas de statut `Superseded` : aucune ADR ne remplace celle-ci.

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

> **Prémisse non réalisée — `mockwebserver3` (relevé 2026-08-23).** Le bénéfice « API moderne » ci-dessus cite `mockwebserver3` ; le dépôt ne le consomme **pas**. `gradle/libs.versions.toml` déclare `com.squareup.okhttp3:mockwebserver` (module legacy, même `version.ref` que OkHttp), consommé par `:core:network` et `:core:data`, et **les 13 fichiers de test qui l'utilisent importent tous `okhttp3.mockwebserver.*`** — **zéro** import `mockwebserver3` dans l'arbre. La décision reste valable sans cette prémisse : elle ne portait aucune contrainte sur le harnais de test. Si la migration est un jour souhaitée, la traiter comme un chantier de harnais de test à part entière et non comme un simple changement de coordonnée Gradle : les paquets, et donc tous les imports et les appels, changent.

## Conséquences

- la couche réseau construit ses requêtes explicitement
- la séparation entre réseau et parsing reste nette
- aucun coût de migration 4.x → 5.x n'est à payer plus tard, puisque le projet démarre neuf
- Retrofit et Ktor ne font pas partie du noyau réseau de v1

## Alternatives considérées

- **Retrofit + OkHttp** : adapté aux APIs structurées, peu pertinent pour du HTML brut
- **Ktor Client** : plus générique, mais moins naturel pour ce besoin Android-first immédiat
- **OkHttp 4.x** : inutile de démarrer sur l'ancienne branche alors que 5.3+ est la baseline retenue
