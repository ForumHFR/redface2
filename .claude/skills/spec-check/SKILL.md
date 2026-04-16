---
name: spec-check
description: Vérifie la cohérence cross-file des specs Redface 2 (noms modules, modèles, types, diagrammes, versions). Use after each specs modification to catch inconsistencies before commit.
disable-model-invocation: true
---

# Vérification de cohérence des specs

Vérifie la cohérence entre les pages de specs de Redface 2.

## Étapes

1. Lire tous les fichiers `docs/*.md`.
2. Pour chaque modèle défini dans `models.md`, vérifier qu'il est utilisé correctement dans `mvi.md`, `architecture.md` et `navigation.md` (noms de champs, types).
3. Pour chaque module Gradle dans `architecture.md`, vérifier qu'il apparaît dans le diagramme mermaid **ET** dans le tableau texte **ET** dans `contributing.md`.
4. Pour chaque interface dans `architecture.md` (Repository, etc.), vérifier que le nommage est identique dans `mvi.md`.
5. Pour chaque diagramme mermaid, vérifier qu'il reflète le texte qui l'entoure.
6. Vérifier que les exemples Kotlin sont conceptuellement compilables :
   - Propriétés utilisées existent dans les data classes définies
   - Types correspondent (Instant partout pour les dates, pas de mix String/Instant)
   - Paramètres de navigation correspondent
   - Pas d'imports implicites incohérents (pas de SwipeRefresh si Material 3)
7. Vérifier que `index.md` reflète l'architecture actuelle.
8. Vérifier que la version dans `_config.yml` est cohérente avec les changements.
9. Vérifier qu'aucun composant déprécié n'est utilisé dans les exemples (`EncryptedSharedPreferences`, Accompanist SwipeRefresh, Compose Navigation string-based, etc.).
10. Vérifier que les versions de libs dans `contributing.md` sont à jour vs doc officielle.

## Format de sortie

Pour chaque incohérence trouvée :
```
[FICHIER:LIGNE] Description du problème
  → Fix proposé
```

Si tout est cohérent : `Specs cohérentes, aucune incohérence trouvée.`
