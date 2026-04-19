---
title: ADR-000
parent: ADRs
grand_parent: Spécifications
nav_order: 0
permalink: /adr/000-methodologie-triple-hybride
---

# ADR-000 — Méthodologie triple-hybride SDD + Prototype + TDD

## Statut

Accepté — 2026-04-19

## Contexte

Le projet a démarré dans une logique très spec-first. Après plusieurs cycles d'audit et plusieurs milliers de lignes de documentation sans code, il est apparu qu'une partie importante du projet ne pouvait pas être correctement conçue uniquement en prose.

Dans le même temps, certaines zones restent trop risquées pour être laissées à l'improvisation :
- protocole HFR
- sécurité des credentials
- frontières d'architecture
- contrats externes comme MPStorage

Le projet est aussi multi-LLM. Sans méthode explicite, le risque principal devient la production de texte plausible, mais mal calibré par rapport au réel.

## Décision

Redface 2 adopte une méthodologie **triple-hybride** :

- **Spec-first sélectif** pour les invariants difficiles à inverser
- **Prototype-first** pour les sujets d'exploration et de découverte
- **TDD sélectif** pour les fonctions pures et déterministes
- **Test-after** pour les intégrations réelles
- **Coverage guidée par risque**, pas par quota global
- **ADRs a posteriori**, jamais comme RFC pré-code

La page canonique est [`docs/specs/methodology.md`]({{ site.baseurl }}/specs/methodology).

## Conséquences

- la méthode n'est plus dupliquée dans `README.md`, `AGENTS.md`, `docs/guides/contributing.md` et `docs/guides/rationale.md`
- les écrans Compose, le schéma Room et le rendu BBCode doivent être validés par prototype avant d'être figés
- les parsers, helpers purs, mappers et reducers MVI sont de bons candidats au TDD strict
- un audit de specs n'est plus un réflexe ; il doit avoir un déclencheur concret
- les changements structurants demandent une validation séparée du producteur du changement

## Alternatives considérées

- **SDD partout** : trop rigide, produit de la prose avant l'apprentissage réel
- **Prototype partout** : insuffisant pour les sujets de sécurité, de protocole et d'architecture
- **TDD partout** : trop coûteux et peu pertinent sur UI Compose, wiring DI ou navigation
