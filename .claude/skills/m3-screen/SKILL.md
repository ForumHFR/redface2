---
name: m3-screen
description: Génère un écran Compose Redface 2 complet (State/Intent/Effect/ViewModel/Screen/Content/Previews) selon les conventions Material 3 + MVI documentées dans docs/mvi.md et drafts/material3-ui-ux.md. Use when user asks to bootstrap a new screen in a :feature:* module.
argument-hint: description fonctionnelle de l'écran (ex "écran de résultats de recherche, filtres cat/auteur/date")
disable-model-invocation: true
---

# /m3-screen — générer un écran Compose complet

**Skill stub — implémentation complète en Phase 1.**

## Objectif

Bootstrap un écran Compose dans un module `:feature:*` existant, avec tous les fichiers conventionnels.

## Argument

`$ARGUMENTS` = description fonctionnelle de l'écran.

## Étapes

1. Lire `docs/mvi.md` pour la structure State/Intent/Effect + `docs/architecture.md` pour les dépendances autorisées du module.
2. Lire `drafts/material3-ui-ux.md` (ou `docs/material3.md` après promotion) pour le choix de composants M3.
3. Demander à l'utilisateur le nom du module cible si non fourni (`:feature:<name>`).
4. Générer :
   - `<Name>State.kt` : State + sealed Intent + sealed Effect
   - `<Name>ViewModel.kt` : @HiltViewModel, StateFlow, Channel<Effect>, send(intent)
   - `<Name>Screen.kt` : @Composable collecte state + effects, délègue au Content
   - `<Name>Content.kt` : @Composable stateless, previewable (3 previews : light/dark/fontScale 2.0)
5. Ajouter la route typée (`@Serializable data class <Name>Route`) au `:app:NavGraph`.
6. Générer un test unitaire minimal pour le ViewModel (`intent → state`).
7. Générer un screenshot test Roborazzi pour le Content.

## Format de sortie

Liste des fichiers créés avec chemin complet + résumé des entités générées.

## TODO Phase 1

- [ ] Template complet avec conventions de nommage final
- [ ] Intégration avec le skill `/new-feature` qui crée aussi le module Gradle
- [ ] Vérifier que `/m3-check` passe sur la génération
