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
- **Repère « Dernier message lu » et liseré d'ancre rejoués au retour sur la page d'atterrissage** (#953/F4, bêta 0.37.0). Depuis le moteur de pagination in-ViewModel (#895), un changement de page préserve `scrollTo` et `forceRefresh` dans la requête ; or les deux surfaces visuelles les lisent en direct. Revenir sur la page d'arrivée dans la même session ré-affiche donc le bandeau et le liseré, déjà vus. **Strictement visuel** : le canal d'effet est latché (aucun scroll rejoué), le bypass de TTL a son propre latch consommable (aucun rechargement réseau), et les ancres persistées ne sont pas touchées. Correctif cadré pour l'après-bêta — dériver les deux surfaces du latch de consommation existant.

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

## Messages privés &amp; DT (Phase 3 — éléments reportés)

- **Écriture MPStorage v1 non activée — POST non observé.** La mécanique de write-back (read-modify-write préservant tous les namespaces tiers du document JSON, cap 256 KiB, sélection déterministe du document cible) est implémentée et unit-testée, mais **aucun POST réel n'est déclenché** : le contrat `bdd.php cat=prive` n'a jamais été capturé en conditions réelles (pas de device cette nuit-là). L'API publique reste en lecture/préparation ; le POST live n'est accessible que par un chemin module-interne test-only. Activation réelle reportée après observation du contrat (#6, ADR-014 §4).
- **Onglet « DT » — première page de l'inbox seulement.** La liste des MultiMP se base sur la 1ʳᵉ page de la boîte (conversations récentes) ; le balayage multi-pages est différé (coût du scan MPStorage). Le badge « reprise p.N » est une **position de reprise de lecture** (MPStorage), **pas** un état lu/non-lu — le non-lu vient du dot inbox (`hasUnread`). Pas de « vrais » `Flag` MP (HFR n'expose pas de drapeaux côté MP).
- **Recherche intra-topic — navigation résultat suivant/précédent (#546, chantier B).** En mode **non filtré** (case « Filtrer » décochée), des flèches « précédent / suivant » sautent entre les correspondances via le curseur `currentnum`. Détails du contrat HFR (vérifié live) : un pas vers l'avant POST `transsearch.php` **sans** `firstnum`/`dep` (renvoyer `firstnum` ré-ancre HFR sur la 1ʳᵉ correspondance et bloque la progression) ; HFR est **forward-only** (pas d'endpoint « précédent ») → l'historique des curseurs visités est tenu côté client dans le ViewModel et rejoué pour reculer. Fin des résultats détectée quand le curseur renvoyé est absent des posts de la page (sentinel) → message sobre, pas de navigation. Le cas « **aucun résultat** » (mot trop fréquent déclenchant la règle des 50 % du fulltext MyISAM de HFR, ou terme absent) rend une page « aucune réponse n'a été trouvée » → état `NoResults` distinct d'une erreur réseau (comportement HFR, pas un bug app). En mode **filtré**, la page renvoyée EST la liste des correspondances → pas de navigation par résultat.

---

*Page maintenue au fil de l'eau : ajouter ici tout compromis délibéré ou limite plateforme nouvellement constaté, avec un lien vers l'issue de suivi.*
