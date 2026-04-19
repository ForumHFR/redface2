---
title: ADR-010
parent: ADRs
grand_parent: Spécifications
nav_order: 10
permalink: /adr/010-licence-client-android
---

# ADR-010 — Licence GPL-3.0-only pour le client Android

## Statut

Accepté — 2026-04-19

## Contexte

Le repo mentionnait jusque-là **Apache 2.0** dans `AGENTS.md`, sans décision explicitement formalisée, sans fichier `LICENSE` à la racine et sans ADR dédiée.

Le besoin réel du projet à ce stade concerne le **client Android Redface 2** :
- garder le code applicatif communautaire
- éviter les forks fermés du client
- trancher une licence avant le premier vrai code de la Phase 0

Le projet ne contient pas encore de composant serveur propre (sync, proxy, worker réseau, etc.). La licence d'un éventuel service futur doit donc être décidée séparément.

## Décision

Le client Android Redface 2 est publié sous **`GPL-3.0-only`**.

Ce choix s'applique au repo actuel et à ses contributions acceptées.

Il ne préjuge pas :
- de la licence d'un futur service réseau
- d'une politique spécifique pour les assets non-code
- d'un éventuel dual licensing futur

## Conséquences

- le repo dispose désormais d'un fichier `LICENSE` explicite
- `README.md`, `AGENTS.md` et `docs/guides/contributing.md` sont alignés sur cette licence
- les forks applicatifs distribués doivent rester sous GPLv3
- un portage App Store Apple sous la même licence n'est pas un objectif compatible
- si un vrai composant réseau du projet apparaît, une décision de licence dédiée devra être prise au lieu d'étendre abusivement celle du client

## Alternatives considérées

- **Apache 2.0** : plus simple et plus permissive, mais autorise des forks applicatifs fermés
- **AGPL-3.0-only** : pertinente surtout pour un logiciel lui-même utilisé à travers le réseau ; trop ambitieuse pour le repo actuel centré client Android
- **MPL 2.0** : compromis intéressant, mais moins lisible et moins aligné avec l'objectif principal de protection du client
