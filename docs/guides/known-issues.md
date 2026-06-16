---
layout: default
title: Limitations connues et compromis assumés
parent: Guides
nav_order: 9
permalink: /guides/known-issues
---

# Limitations connues et compromis assumés

Liste **vivante** des limitations connues de Redface 2 et des compromis **assumés** (choix délibérés, pas des bugs). Elle évite de re-diagnostiquer plusieurs fois les mêmes points et sert de référence pour les retours testeurs. Chaque entrée pointe vers l'issue de suivi quand il y en a une.

> Convention : un point listé ici est un **comportement attendu** (compromis documenté) ou une **limite plateforme**, pas une régression. Les vrais bugs vivent dans les issues, pas ici.

## Lecture et rendu

- **Citations en mode connecté — pas de saut vers le post cité.** En authentifié, HFR sert l'en-tête de citation avec un lien dynamique `forum2.php?…&numreponse=M` au lieu du permalien statique `sujet_<post>_<page>.htm#tN`. Le parser extrait l'auteur de la citation mais laisse `page`/`numreponse` à `null` → le tap « aller au message cité » est inactif en mode connecté (il fonctionne en anonyme). Compromis Phase 2.
- **Modèle de contenu de post gelé.** L'AST `PostContent` est sérialisé en base (Room) : ajouter un nouveau type de bloc/inline impose une migration. Les évolutions de rendu réutilisent les primitives existantes (ex. `LineBreak`) tant que possible.

## Drapeaux et listes

- **Fraîcheur du cache de drapeaux.** Le compteur de réponses et le dernier posteur affichés peuvent être périmés jusqu'au prochain rafraîchissement (cache REST + Room). Le rafraîchissement automatique (#378/#421) couvre le cas ; un écart résiduel relève de la fraîcheur, pas d'un mauvais mapping (cf. #331, #501).
- **Drapeaux globaux tronqués par récence.** L'endpoint REST global tronque par récence ; le détail par catégorie est complet (cf. #251).

## Messages privés

- **Pas de drapeau lu/non-lu serveur.** HFR n'expose aucun état lu/non-lu serveur pour les MP : l'app s'appuie sur une heuristique (dot binaire par conversation). Le compteur de badge est donc une estimation côté client, recalculée à la lecture (cf. #313, #361).

## Réglages

- **Options « À venir » des sous-pages dédiées.** Les options planifiées (grisées) des catégories adossées à une sous-page propre — Affichage, Images, Compte — restent **cherchables** mais ne s'affichent pas en naviguant ces sous-pages (UI dédiée, hors catalogue générique). Les autres catégories les listent (cf. #494).
- **Rôle du hamburger non défini.** L'icône hamburger de la barre de recherche des réglages est présente mais désactivée : son rôle (tiroir de navigation ?) est à définir (cf. #494).

## Navigation et chrome

- **Barre du bas ≈ 64 dp minimum.** La barre de navigation basse ne descend pas sous ~64 dp (`ShortNavigationBarCompact`) : aller plus bas violerait le plancher de cible tactile d'accessibilité (48 dp). Une réduction « de moitié » n'est pas possible proprement (cf. #515).
- **Effet « contenu sous la barre » = tonal, pas de flou.** L'effet de profondeur sous la search app bar est une translucidité tonale + scrim (idiome Material 3), pas un vrai flou « frosted glass » (techniquement fragile en Compose, non standard M3). Une barre extensible/collapsante reste à explorer (cf. #516).
- **Plein écran / barre système Android.** Masquer la barre de navigation système du bas (3 boutons) est un mode immersif : Android impose son **réaffichage transitoire** au swipe depuis le bas (on ne peut pas la supprimer définitivement). Le rendu exact varie en gestuel vs 3-boutons (cf. #518).

## Réseau

- **Prefetch non authentifié — volontaire.** Le préchargement utilise des requêtes non authentifiées, délibérément, pour **ne pas marquer les drapeaux comme lus** côté serveur.
- **Deep links HFR — domaine non vérifiable.** Le domaine HFR n'est pas vérifiable côté Play Console ; les fragments d'URI (`#t<id>`) sont parsés dans `RedfaceApp` (Compose Navigation 3 ne gère pas les fragments nativement) (cf. #127).

---

*Page maintenue au fil de l'eau : ajouter ici tout compromis délibéré ou limite plateforme nouvellement constaté, avec un lien vers l'issue de suivi.*
