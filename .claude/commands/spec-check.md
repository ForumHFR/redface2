# Vérification de cohérence des specs

Vérifie la cohérence entre les 9 pages de specs de Redface 2.

## Étapes

1. Lire tous les fichiers `docs/*.md`
2. Pour chaque modèle défini dans `models.md`, vérifier qu'il est utilisé correctement dans `mvi.md`, `architecture.md` et `navigation.md` (noms de champs, types)
3. Pour chaque module Gradle dans `architecture.md`, vérifier qu'il apparaît dans le diagramme mermaid ET dans le tableau texte ET dans `contributing.md`
4. Pour chaque interface dans `architecture.md` (Repository, etc.), vérifier que le nommage est identique dans `mvi.md`
5. Pour chaque diagramme mermaid, vérifier qu'il reflète le texte qui l'entoure
6. Vérifier que les exemples Kotlin sont conceptuellement compilables (types existants, propriétés définies)
7. Vérifier que `index.md` reflète l'architecture actuelle
8. Vérifier que la version dans `_config.yml` est cohérente avec les changements

## Format de sortie

Pour chaque incohérence trouvée :
```
[FICHIER:LIGNE] Description du problème
  → Fix proposé
```

Si tout est cohérent : "Specs cohérentes, aucune incohérence trouvée."
