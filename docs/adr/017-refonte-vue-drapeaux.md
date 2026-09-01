---
title: ADR-017
parent: ADRs
grand_parent: Spécifications
nav_order: 17
permalink: /adr/017-refonte-vue-drapeaux
---

# ADR-017 — Refonte de la vue Drapeaux (#603) : modèle de présentation, marqueur « barre de couleur », super-favori local, `quotedMe` différé

## Statut

Accepté — 2026-06-24

## Contexte

La vue Drapeaux ([#603](https://github.com/ForumHFR/redface2/issues/603)) entre en refonte UI
(Phase 4). La vision produit (XaTriX) et sa déclinaison visuelle sont fixées :

- vision : `drafts/603-flags-vision-xatrix.md` ;
- feuille de route d'implémentation : `drafts/603-flags-implementation-roadmap.md` ;
- matrice de mockups (galerie Roborazzi publiée) : `drafts/603-flags-mockups-matrix.md`.

La refonte touche l'app bar (search bar « façon Réglages »), la liste (ligne standardisée +
en-tête de catégorie), un bottom sheet d'appui-long enrichi, un menu de config rapide et une barre
de progression. Conformément à la [méthodologie triple-hybride]({{ site.baseurl }}/adr/000-methodologie-triple-hybride),
le curseur est : **SDD léger** pour le modèle/états/persistance (cet ADR), **prototype-driven** pour
l'UI (vision + mockups font foi), **TDD** pour les fonctions pures.

L'état de prod actuel a été cartographié avant d'écrire cet ADR :

- `core/model/.../Flag.kt` expose déjà tout le métier nécessaire : `cat`, `subcat`, `topicId`,
  `title`, `totalPages`, `replyCount`, `type` (`FlagType` CYAN/RED/FAVORITE), `isFavorite`,
  `hasUnread`, `lastReadPage`, `lastPostReadId`, `firstPostAuthor`, `lastReplyAuthor`, `lastReplyAt`.
- `core/domain/.../preferences/FlagsViewSettings.kt` : `groupByCategory`, `hideReadCategories`,
  `unreadOnly`, persistés via `UserPreferencesRepository` (DataStore), avec override optionnel
  par `FlagType`.
- Le regroupement par catégorie est **déjà** une fonction pure (`groupFlagsByCategory`,
  `FlagCategorySection`).
- Le chargement est signalé par `FlagsListUiState.Loading` (chargement initial) et un unique
  `isRefreshing: Boolean` levé pour le refresh **manuel ET automatique** (`refresh()` /
  `maybeAutoRefresh()`).
- Aucun champ « cité / quoté / mention » n'est exposé par les modèles/API **actuels** : ni `Flag`,
  ni le DTO REST `RestTopic`, ni un endpoint REST HFR connu
  (cf. [ADR-003]({{ site.baseurl }}/adr/003-api-rest-hfr-hybride)).

Quatre spikes bloquants ont été levés avant de s'engager (résultats détaillés :
`drafts/603-flags-spikes-report.md`).

## Décision

### 1. Indicateur « cité » (`quotedMe`) — **différé**, jamais simulé

Le spike confirme que l'indicateur n'est **exposé par aucun modèle/API actuels** (ni `Flag`, ni
`RestTopic`, ni endpoint REST HFR connu).
Conformément à la charte anti-dérive (« pas de simulation de données absentes »), l'indicateur
« cité » est **retiré du MVP**. Les composants UI peuvent porter le *paramètre* (`quotedMe: Boolean`)
mais il reste **toujours `false` en production** tant qu'aucune source réelle n'existe — aucun badge
« cité » ne doit être rendu à partir de données fabriquées. Réintroduction conditionnée à une source
serveur réelle (ou une feature explicitement locale ultérieure).

### 2. Marqueur de ligne par défaut = **barre de couleur** (liseré vertical)

Le marqueur gauche est **configurable** (barre de couleur · pastille tonale + icône · dot). Le
**défaut** est la **barre de couleur** (liseré vertical ~3 dp, coins légèrement arrondis), choix le
plus sobre qui libère la largeur du titre. Couleur résolue depuis `FlagPalette` (+ jaune favori),
désaturée (`alpha`) si lu.

### 3. Bouton d'en-tête de catégorie = **« ouvrir la catégorie »**

En vue groupée, le bouton à droite de l'en-tête de catégorie **ouvre la catégorie** (chevron ›).
Les alternatives « badge cité » (dépend de `quotedMe`, non confirmé) et « nb de non-lus » sont
écartées du défaut.

### 4. Modèle de présentation + fonctions pures (hors composition)

La transformation `List<Flag>` → modèle d'affichage est **calculée hors composition** (ViewModel /
mappeur), via des **fonctions pures unit-testées** :

- `pagesToRead = max(totalPages - lastReadPage, 0)` ;
- regroupement / tri / en-têtes de catégorie (réutilise et étend `groupFlagsByCategory`) ;
- résolution du marqueur (couleur + état lu/non-lu) ;
- filtres existants (`unreadOnly`, `hideReadCategories`).

`hasUnread` reste la **source de vérité** de l'état lu/non-lu ; `pagesToRead` n'est qu'un compteur de
pages restantes d'affichage — il peut valoir `0` alors que `hasUnread` est vrai (non-lus sur la
dernière page lue). Ne jamais déduire « tout lu » de `pagesToRead == 0`.

- **Note (#814, 2026-09-02)** : `lagTone(pagesToRead)` (1-2 → `LOW`, 3-9 → `MEDIUM`, ≥ 10 → `HIGH`,
  fonction pure `:core:model`) pilote la couleur de la pastille « pages à lire », désormais
  **indépendante de la couleur du drapeau** (rôles M3 `surfaceVariant` / `tertiaryContainer` / `error`).

La composition ne recalcule **jamais** le regroupement ; les en-têtes collants gardent un **état
stable par catégorie** (clé = identité catégorie, pas un index fragile).

### 5. Persistance : extension de `FlagsViewSettings` + super-favori local

- `FlagsViewSettings` est **étendu** (DataStore, champs versionnés, **defaults conservateurs**). Champs
  GLOBAUX réellement livrés (#603, non soumis à l'override per-onglet) : `markerStyle` (défaut
  **STRIPE**), `singleLineTitle`, `categoryBandStyle` (défaut **MINIMAL**), `markerBorder`,
  `plusLusIndicatorStyle` (défaut **Ring**, cf. #661/#721), `flagGlyphStyle` (défaut **Flag**) et
  `showLoadingBar` (défaut **on**, cf. #728). Les champs existants (`groupByCategory`,
  `hideReadCategories`, `unreadOnly`) sont conservés.
- **Note (audit 2026-06-29)** : la **densité** d'affichage envisagée initialement ici n'a finalement
  **pas** été intégrée à `FlagsViewSettings` ; elle vit comme préférence **globale séparée**
  (`DisplayDensity`) et n'est pas (encore) consommée par la vue Drapeaux.
- **Super-favori** : nouvelle notion **purement locale** (ensemble de `topicId` persisté en
  DataStore), **distincte** de `isFavorite` (qui reflète `flag_owntopic == 3` côté serveur). Pas de
  mutation serveur. L'action vit dans le bottom sheet d'appui-long (cf. décision 6). L'onglet
  « Super » (déjà présent comme placeholder) pourra l'afficher ultérieurement (hors run MVP).

### 6. Bottom sheet d'appui-long (F2) — métadonnées API, **sans choix de couleur**

L'appui-long ouvre un bottom sheet riche qui **remplace** le retrait direct (dialog de confirmation).
Il affiche les **métadonnées déjà disponibles dans `Flag`** (créateur `firstPostAuthor`, dernier
répondant `lastReplyAuthor`, date `lastReplyAt`, position `lastReadPage`/`totalPages`, `replyCount`,
catégorie), des quick-actions et des actions secondaires, **plus** l'option « Super favori » (local).
Le **sélecteur de couleur de drapeau est explicitement exclu** (la couleur reflète le bucket serveur,
non éditable).

### 7. Barre de progression pilotée par un signal existant

La barre de progression M3 (linéaire, fine, **non ondulée**) est visible pendant **tout** chargement
de l'onglet — manuel ET automatique — pilotée par `state is Loading || isRefreshing`. Aucun nouveau
booléen fragile : `isRefreshing` couvre déjà les deux chemins. (Livrée en PR4, en queue de run.)

### 8. App bar translucide au scroll = idiome existant ; hide-on-scroll de la bottom bar **différé**

La search app bar réutilise l'idiome `elevated` de `RedfaceSearchAppBar` (shell Réglages) :
au repos `surface` ; au scroll `surfaceContainer` translucide + ombre fine, contenu glissant dessous
(Box + `contentPadding`). Le **swipe inter-onglets** (horizontal) ne conflicte pas avec le scroll
(vertical) ni le pull-to-refresh. Le **hide-on-scroll de la bottom bar** est **différé** (sensible
insets/scroll, hors run) ; il fera l'objet d'un travail dédié.

## Conséquences

- **+** Décisions tranchées et tracées : le run d'implémentation (PR1→PR6, PR4) part de défauts
  stables, sans re-arbitrage.
- **+** Le métier nécessaire est déjà dans `Flag` : pas de nouvel appel réseau pour la ligne ni le
  sheet (sauf les actions). `pagesToRead` est purement dérivé.
- **+** Fonctions pures testables (TDD) découplées du Compose (snapshot Roborazzi côté rendu).
- **−** L'indicateur « cité », visible dans les mockups, **n'apparaît pas** en prod : écart assumé
  vision↔prod tant que la source serveur manque.
- **−** Le super-favori local introduit un store DataStore supplémentaire à versionner et migrer.
- **−** La couleur de drapeau n'est pas personnalisable (reflète le bucket serveur) : écart avec
  certains mockups avancés (color picker), assumé.
- **Étapes ultérieures** (hors de cet ADR / différées) : hide-on-scroll bottom bar, onglets
  configurables/masquables, catégories repliables, paliers de densité avancés, migration DataStore
  élargie (PR7), réintroduction `quotedMe` si source réelle.

## Alternatives considérées

- **Marqueur défaut = pastille tonale + icône** : plus expressif mais plus chargé et mange la largeur
  du titre ; gardé en **option**, pas en défaut.
- **Bouton d'en-tête = badge « cité » ou nb non-lus** : le badge « cité » dépend de `quotedMe`
  (non confirmé) ; rejeté pour le défaut.
- **Simuler `quotedMe`** depuis une heuristique (ex. présence d'un drapeau cyan) : rejeté — viole la
  charte « pas de simulation de données absentes » et tromperait l'utilisateur.
- **Nouveau booléen `isAutoRefreshing` dédié** pour la barre de progression : inutile, `isRefreshing`
  couvre déjà manuel + auto ; rejeté (booléen fragile redondant).
- **Super-favori côté serveur** (réutiliser `flag_owntopic`/favoris HFR) : rejeté — la mutation REST
  des drapeaux est trop fragile (cf. ADR-003) et « super-favori » est un concept produit local sans
  équivalent serveur.
- **Recalcul du regroupement en composition** : rejeté — coût de recomposition + risque d'état
  d'en-tête collant instable (piège identifié au cadrage).
