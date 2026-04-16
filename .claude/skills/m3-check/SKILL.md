---
name: m3-check
description: Audit Material 3 sur un écran ou composant Compose Redface 2 (30 color roles, 15 typo styles, shapes/motion tokens, composants dépréciés, ExperimentalMaterial3Api). Génère un rapport markdown par sévérité. Use after modifying any :core:ui or :feature:* Compose code.
argument-hint: chemin fichier ou dossier Compose
disable-model-invocation: true
---

# /m3-check — audit Material 3 sur Compose

**Skill stub — implémentation complète en Phase 0 du draft `drafts/material3-ui-ux.md`.**

## Objectif

Vérifier qu'un fichier ou dossier Compose respecte les règles Material 3 définies dans `drafts/material3-ui-ux.md` (à promouvoir en `docs/material3.md` en Phase 0).

## Argument

`$ARGUMENTS` = chemin vers un fichier `.kt` Compose ou un dossier `:feature:*` / `:core:ui/*`.

## Étapes

1. Lire le fichier / les fichiers du dossier.
2. Vérifier les règles critiques :
   - Pas d'import `androidx.compose.material.*` (Material 2) — uniquement `androidx.compose.material3.*`
   - `ColorScheme`, `Typography`, `Shapes` instantiés uniquement dans `:core:ui` (pas dans les features)
   - Pas de `RoundedCornerShape(Xdp)` hardcoded hors `:core:ui` — utiliser `MaterialTheme.shapes.*`
   - `@OptIn(ExperimentalMaterial3Api::class)` présent pour `ListDetailPaneScaffold`, `PullToRefreshBox`, `NavigationSuiteScaffold`
   - Pas de `Color(0xFFxxxxxx)` hardcoded hors `:core:ui/theme/Color.kt`
   - Pas de `TextStyle(fontSize = Xsp)` hardcoded — utiliser `MaterialTheme.typography.*`
   - Pas de `Accompanist*` déprécié (SwipeRefresh, SystemUiController)
   - Composants avec variante (Chip → AssistChip/FilterChip/InputChip/SuggestionChip) : variante explicite
3. Classer les findings par sévérité : critique (bloque merge), important (à corriger avant review), moyen (polish).
4. Générer un rapport markdown.

## Format de sortie

```
## m3-check — <fichier/dossier>

### Critique
- [file:line] description → fix

### Important
- ...

### Moyen
- ...

### Clean
- (aucun problème détecté)
```

## TODO Phase 0

- [ ] Étoffer la liste des 19 règles (référence : section 14 couche 4 de `drafts/material3-ui-ux.md`)
- [ ] Lier avec Konsist : les règles Konsist sont la source de vérité, ce skill les invoque
- [ ] Tester sur `:feature:topic` (écran le plus complexe) une fois Phase 1 démarrée
