---
name: roborazzi
description: Capture (record) et inspecte le rendu Compose via Roborazzi en JVM (sans device) pour un changement de rendu UI. Use when changing PostRenderer or refactoring a screen's rendering and you need to eyeball the result.
argument-hint: "[module] (défaut :core:ui ; ex :feature:topic)"
disable-model-invocation: true
---

# /roborazzi — capture & inspection du rendu (record-only)

Screenshot testing JVM (Robolectric, sans device). **Record-only** : le plugin Gradle Roborazzi n'est pas applicable sous AGP 9 → il n'existe **pas** de tâche `recordRoborazzi` / `verifyRoborazzi` ; le record est forcé via `roborazzi.test.record=true` (cf. `core/ui/build.gradle.kts`, [ADR-016]).

## Argument

`$ARGUMENTS` = module (défaut `:core:ui`).

## Étapes

1. Lancer les tests du module dans l'env Docker :

   ```bash
   # remplacer :core:ui par le module ciblé (ex. :feature:topic)
   ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest --rerun-tasks
   ```

2. Les PNG sont générés dans `<module>/build/outputs/roborazzi/` (**non versionnés**).
3. **Inspecter visuellement** les PNG (le but est l'œil humain/agent, pas un diff automatique).

## Quand l'utiliser

- Changement intentionnel de rendu (`PostRenderer`, écran refondu #603/#604).
- Capturer une **baseline avant** une refonte, ré-inspecter **après** (le pipeline méthodo le prévoit pour une refonte d'écran existant).

## Limites (assumées)

- **Pas de `verify`** (comparaison auto) ni de gate CI tant que le plugin reste inapplicable sous AGP 9. La bascule record→verify + baselines versionnées fera l'objet d'une décision après stabilisation #603/#604 (ADR-016).
- Les nodes async (Coil `AsyncImage`) restent sur leur placeholder sous Robolectric → asserter la structure du layout, pas le bitmap rendu.
