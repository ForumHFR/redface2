---
title: ADR-016
parent: ADRs
grand_parent: Spécifications
nav_order: 16
permalink: /adr/016-roborazzi-screenshot-testing
---

# ADR-016 — Roborazzi pour le rendu visuel (screenshot testing JVM), mode record en Phase 4

## Statut

Accepté — 2026-06-21

## Contexte

La méthodologie ([ADR-000]({{ site.baseurl }}/adr/000-methodologie-triple-hybride)) prototype l'UI Compose
et exclut le TDD red-green dessus ; en MVP, Roborazzi avait été **écarté** (Compose Preview + review
visuelle, cf. `contributing.md` « à reconsidérer Phase 4+ »).

La situation a changé en Phase 4 :
- des **régressions visuelles** réelles ont été vécues sur le rendu (smileys intrinsèques #175, citations,
  blocs de code, AMOLED) — détectables seulement à l'œil, tard ;
- les **refontes UI multi-features** arrivent (Drapeaux #603, Topic #604, refonte réglages v2) — surface
  visuelle large, plusieurs modules ;
- on veut une boucle de vérification **rapide et sans device** (l'émulateur/adb est lent et non
  reproductible en CI).

Roborazzi 1.63 (sur Robolectric, rendu JVM) a été introduit de fait sur `:core:ui` (PostRenderer :
code, smiley, citation) et la coquille réglages v2. Il est consommé comme **artefact de test simple**
(sans le plugin Gradle), avec `roborazzi.test.record=true` forcé.

## Décision

Adopter **Roborazzi comme test de rendu visuel JVM** en Phase 4, en **mode `record`** :
- les tests `captureRoboImage` génèrent des PNG dans `<module>/build/outputs/roborazzi/` (non versionnés)
  pour **inspection visuelle** rapide, sans device ;
- **recommandé** pour tout changement de rendu UI structurant (PostRenderer, écrans refondus) ;
- **pas encore un gate CI dur** : le mode `verify`/baselines committées est différé tant que les
  refontes #603/#604 font bouger le rendu (baselines instables = bruit) ;
- `docs/guides/contributing.md` porte le **mode opératoire** (commandes, quand record vs compare) ; cet
  ADR porte la décision et le pourquoi.

Couverture initiale : `:core:ui` (PostRenderer) + `:feature:topic` + coquille réglages. La couverture
s'étend au cas par cas — **pas** d'obligation « tout écran doit avoir un snapshot ».

## Conséquences

- **+** Filet visuel reproductible (~40 s, JVM, sans device) qui complète Compose Preview ; capture
  inspectable hors IDE et en revue.
- **+** Détecte tôt les régressions de rendu sur les modules couverts.
- **−** En mode `record`, pas de diff automatique contre une baseline : l'inspection reste manuelle ; les
  images ne sont pas versionnées.
- **−** Coût de maintenance : régénérer (`record`) lors d'un changement visuel intentionnel.
- **Étape ultérieure** (hors de cet ADR) : passer en `verify` + baselines committées + gate CI une fois
  les refontes UI stabilisées ; cette bascule fera l'objet d'une décision dédiée.

## Alternatives considérées

- **Compose Preview seul** (statu quo MVP) : pratique en design, mais pas de capture reproductible ni
  inspectable en CI/revue → insuffisant face aux régressions multi-features.
- **Tests instrumentés sur device/émulateur** : lents, flaky, exigent un appareil → inadaptés à une
  boucle rapide et à la CI.
- **`verify` + baselines committées dès maintenant** : rejeté en V1 — baselines instables tant que
  #603/#604 retravaillent le rendu (génère du bruit de diff sans valeur).
- **Paparazzi** : alternative JVM, mais moins alignée sur la stack Robolectric/Compose déjà en place.
