---
name: validate
description: Exécute la validation locale canonique Redface 2 (reproduit la CI) dans l'environnement Docker de référence et produit un rapport commandes/résultats. Use before opening or updating a PR with code changes that must reproduce CI locally.
argument-hint: "[module] — sans argument : validation complète canonique (= CI)"
disable-model-invocation: true
---

# /validate — validation locale canonique

Reproduit la CI Redface 2 en local pour éviter le « build local vert / CI rouge ».

## Objectif

Lancer la validation dans l'environnement Docker épinglé (même image que la CI) et **lire le résultat** avant d'ouvrir ou de mettre à jour une PR.

## Quand l'invoquer

- Avant d'ouvrir une PR avec des changements de code.
- Après une review qui touche un test ou la source qu'il couvre (ajouter `--rerun-tasks`).
- En gate final avant un déploiement émulateur.

## Commande canonique (= CI, cf. `.github/workflows/ci.yml`)

```bash
./scripts/docker-dev.sh ./gradlew \
  :app:assembleProdDebug test testDebugUnitTest lintDebug :app:lintProdDebug detektAll \
  --console=plain --no-daemon
```

Itératif (modules touchés seulement, plus rapide) :

```bash
./scripts/docker-dev.sh ./gradlew \
  detektAll :feature:<module>:testDebugUnitTest :feature:<module>:lintDebug --rerun-tasks
```

## Règles

- **Jamais `:app:testDevDebugUnitTest` seul** : il COMPILE mais n'EXÉCUTE PAS les tests des modules `feature`/`core` (la CI lance `testDebugUnitTest` global). Vécu sur #588.
- `detektAll` est une tâche **racine** (pas `:core:ui:detektAll`) ; `lintDebug` peut être ciblé par module.
- `--rerun-tasks` dès qu'on touche un test ou sa source : le cache Docker peut renvoyer un faux-vert.
- Ne jamais conclure « vert » sans avoir lu `BUILD SUCCESSFUL` / `BUILD FAILED` dans le log (un `; echo "$?"` en arrière-plan masque l'exit code de Gradle).

## Format de sortie

```
Commande(s) lancée(s) : <…>
Résultat            : BUILD SUCCESSFUL | BUILD FAILED in <durée>
Échecs              : <tâche:test ou "aucun">
Non exécuté (env)   : <… ou "rien">
```
