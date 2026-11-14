---
name: roborazzi
description: Compare les captures Compose aux références Roborazzi versionnées (JVM, sans device), puis enregistre explicitement les changements visuels voulus. Use when changing PostRenderer or refactoring a screen's rendering.
argument-hint: "[module] (défaut :core:ui ; ex :feature:topic)"
disable-model-invocation: true
---

# /roborazzi — comparaison et mise à jour du rendu

Screenshot testing JVM (Robolectric, sans device), Roborazzi **1.77.0** sans plugin Gradle amont.
La convention `redface.android.roborazzi` transmet les propriétés système aux tests :
`-Proborazzi.mode=verify` (défaut) ou `-Proborazzi.mode=record` (explicite).
Tolérance : `changeThreshold = 0f` **et** `SimpleImageComparator(maxDistance = 0f)` dans le helper
partagé `build-logic/src/roborazziTest/kotlin/`. Une référence manquante fait aussi échouer le test.

## Argument

`$ARGUMENTS` = module (défaut `:core:ui`).

## Étapes

1. **Avant** de modifier un rendu existant, vérifier les références du module dans l'env Docker épinglé :

   ```bash
   # remplacer :core:ui par le module ciblé (ex. :feature:topic)
   ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
     -Proborazzi.mode=verify --rerun-tasks
   ```

2. Après modification, relancer en `verify`. Les références PNG sont dans
   `<module>/src/test/screenshots/` (**versionnées**). Les images d'échec `*_actual.png` et
   `*_compare.png` sont sous `<module>/build/outputs/roborazzi/<tâche>/` ; les résultats JSON sous
   `<module>/build/test-results/roborazzi/<tâche>/`. **Inspecter les diffs**, expliquer chaque écart.
3. Seulement pour un changement visuel **intentionnel** (ou l'enregistrement initial), enregistrer
   explicitement sur box dans le même environnement épinglé. Cibler le test modifié si possible :

   ```bash
   ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
     -Proborazzi.mode=record --tests '*PostRendererQuoteRoborazziTest*' --rerun-tasks
   ```

   Cette commande écrit les références ; elle ne les compare pas. Ne jamais la lancer pour masquer
   un échec inexpliqué. **Jamais de régénération automatique en CI.**
4. **Relire les PNG avant/après dans la PR**, puis vérifier les références approuvées :

   ```bash
   ./scripts/docker-dev.sh ./gradlew verifyRoborazziDebug -Proborazzi.mode=verify --rerun-tasks
   ```

   Cette tâche racine agrège les six modules (`core/ui`, `feature/topic`, `feature/messages`,
   `feature/editor`, `feature/settings`, `feature/forum`), sans filtre `*RoborazziTest*` : les autres
   classes appelant `captureRoboImage` sont aussi couvertes. Elle refuse le mode `record`.
   Mode et références sont des entrées Gradle ; record ne peut être ni à jour ni restauré du cache.
5. Pour l'initialisation des six modules et la preuve de garde (référence absente, changement visuel,
   cache, répétabilité box/CI), suivre `docs/guides/contributing.md` § « Rendu visuel (Roborazzi) ».
   Ne conclure « testé » ou « identique » qu'après lecture des résultats réellement exécutés.

## Quand l'utiliser

- Changement intentionnel de rendu (`PostRenderer`, écran refondu #603/#604).
- Vérifier les références **avant/après** une refonte (pipeline de `docs/specs/methodology.md`).

## Limites (assumées)

- La garde ne couvre que les états capturés ; conserver SDK, densité, locale, police, thème, horloge
  et données déterministes. Comparer la répétabilité sur box et en CI avant de modifier la tolérance.
- Les images async nécessitent un moteur Coil déterministe et une attente de stabilisation ; sans
  cela, la capture peut ne montrer que le placeholder.
