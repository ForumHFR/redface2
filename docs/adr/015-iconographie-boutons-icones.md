---
title: ADR-015
parent: ADRs
grand_parent: Spécifications
nav_order: 15
permalink: /adr/015-iconographie-boutons-icones
---

# ADR-015 — Iconographie des boutons-icônes : vector drawables stroke locaux, pas de Material icons

## Statut

Accepté — 2026-06-16

## Contexte

Les boutons-icônes de l'app (flèche retour des top bars, chevrons de page « ‹ »/« › »,
crayon « répondre ») étaient initialement des **glyphes texte** (`Text("←")`, `Text("✎")`…).
Leur taille dépendait de la **police système, de la baseline et du font-scale** : rendu
incohérent selon l'appareil, jamais aligné optiquement sur le titre voisin (cf. #355/#356/#357).

La flèche retour a été corrigée la première (PR #357) en **vector drawable local
(`ic_arrow_back.xml`) dimensionné en dp** et rendu via `material3 Icon`. Les autres
boutons-icônes (chevrons `PageFab`, crayon `ReplyFab` topic + thread MP) souffraient encore
du même défaut (#360).

Contrainte structurante : `config/detekt/detekt.yml` interdit `androidx.compose.material.*`
(règle `ForbiddenImport`) → **pas d'`Icons.*` Material** (ni widgets material1, ni
material-icons). C'est précisément pourquoi la flèche est un vector dessiné à la main et non
un `Icons.AutoMirrored.Filled.ArrowBack`.

Retour communautaire (nicko) : la flèche vectorielle était jugée **trop fine** (« plus
épaisse »). La passe d'uniformisation doit donc aussi corriger le **poids optique**, pas
seulement le pattern.

## Décision

**Tous les boutons-icônes utilisent des vector drawables locaux (`:core:ui/res/drawable`),
tracés en _stroke_, dimensionnés en dp, rendus via un primitive partagé.** Pas de Material
icons.

Convention de tracé (famille homogène) :
- viewport `24×24` ;
- tracé en **`strokeColor` / `strokeWidth="2.5"`** (pas de `fillColor`), `strokeLineCap` et
  `strokeLineJoin` **`round`** ;
- `android:autoMirrored="true"` pour les icônes directionnelles (flèche, chevrons) ;
- `fillColor`/`strokeColor` neutre `#FF000000`, **tinté à l'usage** (`LocalContentColor`).

Le `strokeWidth` 2.5 donne un trait **plus épais** que l'ancien `ic_arrow_back` en `fillColor`
(retour nicko) et un poids identique d'une icône à l'autre.

Primitive partagé : **`RedfaceVectorIcon`** (`:core:ui`, package `core.ui.icon`) encapsule
`material3 Icon` + `painterResource` + la taille canonique (`RedfaceIconDefaults.Size = 24.dp`)
+ le contrat a11y (icône **décorative**, `contentDescription` porté par le conteneur cliquable
`IconButton`/`*FloatingActionButton`). Tout nouveau bouton-icône passe par ce primitive.

Drawables de la famille : `ic_arrow_back`, `ic_chevron_left`, `ic_chevron_right`, `ic_edit`
(les `ic_ms_*` Material Symbols préexistants restent du `fillColor`, voir Conséquences).

## Conséquences

- un seul endroit définit taille en dp + a11y des boutons-icônes (`RedfaceVectorIcon`) ;
- les écrans `topic` (flèche retour, `PageFab`, `ReplyFab`) et `messages` (flèche retour,
  FAB répondre) consomment le primitive ; les chevrons `‹/›` et crayons `✎` ne sont plus des
  glyphes texte ;
- `ic_arrow_back` et `ic_chevron_right` passent de `fillColor` à `stroke` : leur rendu change
  partout où ils servent (settings, recherche, profil, éditeur MP), mais reste un chevron /
  une flèche, plus épais et cohérent — c'est l'effet recherché ;
- coût : maintenir les drawables à la main (pas de dépendance `material-icons`, dont c'était
  l'objectif de la règle detekt) ;
- les icônes denses préexistantes (`ic_ms_*`, Material Symbols recadrés grille 960, p.ex.
  `ic_ms_edit_square` du shell réglages #494) ne sont **pas** retracées en stroke : ce sont des
  pictogrammes pleins, pas des boutons-icônes simples ; elles peuvent toujours passer par
  `RedfaceVectorIcon` pour la taille/a11y.

## Alternatives considérées

- **Whitelister `androidx.compose.material.icons.*` dans `ForbiddenImport`** : donnerait
  `Icons.*` standard, zéro drawable à maintenir, mais assouplit une règle posée volontairement
  (éviter la dépendance `material-icons` et son poids) — rejeté, on garde la règle.
- **Garder les glyphes texte mais forcer la taille en dp** (`Box` fixe / `fontSize` en sp) :
  le tracé reste police-dépendant et le poids n'est pas maîtrisable — rejeté.
- **`fillColor` (Material filled) plutôt que `stroke`** : c'était l'état initial de
  `ic_arrow_back`, jugé trop fin par la communauté — le stroke à largeur explicite résout le
  retour nicko et homogénéise le poids.
