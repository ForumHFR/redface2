# #603 — Vision XaTriX de la vue Drapeaux (mode « classique », ni trop compact ni trop large)

> Spec donnée par XaTriX le 2026-06-24. Mode par défaut visé. Sert de base aux rendus « vision ».

## Haut — Search app bar (comme la vue Réglages), fond transparent en dessous
- **Gauche** : au lieu du burger, une **icône de drapeau colorée** indiquant l'onglet courant (cyan / rouge / favori…).
- **Centre** : la **search bar** (style identique à la search bar des Réglages).
- **Droite** : **photo de profil** — coins arrondis, mais **respecter l'aspect source** (carré ou rectangle), ne pas forcer le cercle.

## Sous l'app bar — barre de progression Material 3
- **Linéaire, fine, sobre, NON ondulée**. Visible **seulement pendant un chargement** des drapeaux de l'onglet — **manuel ET automatique** (donne enfin un visuel au refresh auto, qui n'en avait pas).

## Liste des sujets
- Mélangée **ou** par catégorie.
- **Marqueur à gauche de la ligne** : **pastille** ou **barre de couleur** (cf mockup A1) → indique cyan / lu / non-lu selon les réglages.
- Si **par catégorie** : dans la barre de catégorie, une **icône Material 3 / Google Icons** + un **bouton à droite** dont l'utilité reste à définir. Propositions : ouvrir la catégorie (›) · badge « nb de posts où on est cité » · nb de sujets/non-lus · repli/expand.

### Chaque ligne de topic
- **Titre** (tronqué si nécessaire).
- **Pseudo du dernier posteur** + **date du dernier post**.
- **Nombre de pages à lire**.
- **Indicateur « cité »** (on a été quoté → demande de l'attention).
- Toutes les infos **correctement alignées/accordées dans le cadre** → sensation propre et standard.

## Bas — bottom bar classique
- Avec **ou sans labels**.
- **S'efface au scroll** pour allonger la liste.
- Au scroll : faire apparaître en **fond le haut de la liste défilante** (contenu sous l'app bar translucide) → impression de grandeur.
- **Tap sur l'icône (drapeau) de la bottom bar** → petit **menu de configuration rapide** de la vue Drapeaux.

## Appui long sur un topic → menu (base = F2, le bottom sheet riche)
- **Plus d'infos sur le topic** : créateur, dernier répondant, dates, etc. — ce que renvoie l'API (`firstPostAuthor`, `lastReplyAuthor`, `lastReplyAt`, `lastReadPage`/`totalPages`, `replyCount`, catégorie).
- **Enlever le choix des couleurs.**
- **Ajouter** une option **« Super favori »**.
- Le reste du F2 est bon (quick-actions + actions secondaires).

## Variantes ouvertes à rendre
- marqueur : pastille vs barre couleur vs dot ; liste mixte vs par catégorie.
- bouton d'action d'en-tête de catégorie : 3 propositions de fonction.
- bottom bar avec/sans labels ; état « scrollé » (app bar translucide, bottom cachée).
- état chargement (barre de progression visible) vs idle.
- photo de profil carrée vs rectangle.
- thèmes dark / light / amoled.
- menu config rapide (depuis la bottom bar) ; sheet appui-long enrichi (F2 v2).
