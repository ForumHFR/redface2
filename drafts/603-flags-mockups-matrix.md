# #603 — Refonte vue Drapeaux : matrice de mockups Roborazzi

> Objectif (vision XaTriX) : offrir un **grand nombre d'options** pour personnaliser la vue Drapeaux,
> du **ultra-compact** au **confortable** (ultra-compact → compact → équilibré → confort → confort+).
> Ce doc = la matrice de variantes à rendre en PNG (Roborazzi record-only, module `:core:ui` test).
> Pipeline : mockups Compose auto-portés (fake data) → `captureRoboImage` → galerie pour revue.
>
> ⚠️ Coil/AsyncImage reste sur placeholder sous Robolectric → **avatars = initiales/vecteur**, jamais réseau.

## Tokens (à confirmer depuis le design system réel — agent design)
- Thème : `RedfaceTheme` (light / dark / AMOLED). Rendre chaque persona en **dark** (défaut HFR) + 1-2 en light.
- Couleurs drapeaux HFR : rouge / orange / vert / cyan (+ ?). [À remplir : valeurs exactes]
- Typo : [échelle M3 réelle]. Densité = on joue sur `bodySmall`/`labelSmall` + paddings.
- Spacing tokens : [Dimens réels]

## Modèle de ligne topic (champs à exposer — agent code/design)
Champs candidats (à mapper sur le modèle réel `FlaggedTopic`/`Topic`) :
`title`, `category`, `flagColor`, `pageCount`, `lastReadPage`, `unreadCount` (= pages/posts à lire),
`lastPoster`, `lastPostDate`, `quotedMe` (a-t-on été cité ?), `isPinned`, `isMuted`, `hasNewAnswerToMe`.

---

## AXE A — Spectre de densité (colonne vertébrale)
Même contenu (≈8 topics, 3 catégories), on fait varier hauteur de ligne / paddings / nb de lignes.
- **A1 Ultra-compact** — 1 ligne/topic, ~36dp, pas de meta secondaire, flag = liseré gauche fin, compteur en pill trailing.
- **A2 Compact** — 1 ligne + meta inline discrète (pages·dernier posteur), ~44dp.
- **A3 Équilibré (défaut proposé)** — titre + 1 ligne meta, ~56dp.
- **A4 Confort** — 2 lignes + avatar initiales + timestamp, ~72dp.
- **A5 Confort+** — carte spacieuse, avatar, meta riche, séparateurs aérés, ~88dp.

## AXE B — Style des onglets / sélecteur de catégorie
- **B1** Onglets texte (noms de catégories).
- **B2** Puces couleur (drapeau) — pastilles colorées par catégorie.
- **B3** Icônes de catégorie seules.
- **B4** Icône + label court.
- **B5** Pills scrollables horizontales vs **SegmentedButton** fixe.
- **B6** Pas d'onglets : liste plate groupée par catégorie avec **sticky headers**.

## AXE C — Chrome / barres (combinaisons)
- **C1** Chrome complet : top bar + bottom bar avec labels + barre système.
- **C2** Sans top bar (les onglets deviennent l'en-tête / header slim).
- **C3** Bottom bar **sans labels** (icônes seules).
- **C4** Edge-to-edge, **barre système masquée** (immersif).
- **C5** Ultra-compact total : pas de top bar + bottom iconly + immersif.
- **C6** Top bar **collapsible** au scroll (large→slim).

## AXE D — Anatomie de la ligne (le cœur : libérer de la place pour le titre/infos)
- **D1** Pastille drapeau à gauche (classique).
- **D2** Drapeau = **liseré vertical au bord gauche** (libère la largeur du titre). ← piste "décaler la pastille"
- **D3** Compteur non-lus : **pill trailing** vs dot leading.
- **D4** Indicateur **pages à lire** ("+3 pages", barre de progression lecture).
- **D5** **Icône "vous avez été cité"** (quote alert) sur la ligne.
- **D6** Dernier posteur (username + avatar initiales) + timestamp, alignement propre.
- **D7** 2 lignes : titre / meta (posteur · heure · pages).
- **D8** 1 ligne titre tronqué + meta trailing alignée à droite.
- **D9** Colonne d'infos alignée à droite (pages/non-lus/heure).
- **D10** Encodage couleur drapeau : dot / liseré / fond teinté / chip trailing / souligné titre (mini-showcase).

## AXE E — Recherche
- **E1** M3 `SearchBar` dockée en haut.
- **E2** Icône recherche (top action) → champ qui s'étend.
- **E3** Chips de filtre inline (filtrer par couleur de drapeau / catégorie).

## AXE F — Menu contextuel (appui long) — en faire un excellent
- **F1** `DropdownMenu` M3 ancré sur la ligne.
- **F2** **Bottom sheet** d'actions riches (recommandé pour le nb d'actions).
- **F3** Barre d'actions rapides (quick actions row) façon icônes.
Actions proposées (riches) : Ouvrir · **Aller au 1er non-lu** · Marquer lu · Retirer le drapeau ·
**Changer la couleur du drapeau** · Épingler · Mettre en sourdine (mute) · S'abonner/notif ·
Copier le lien · Ouvrir dans le navigateur · **Basculer alerte "cité"** · Partager.

## AXE G — Icônes de catégorie (proposition de mapping)
Mini-showcase : mapping catégories HFR courantes → icône M3 + couleur d'accent
(ex. Hardware→memory, Software→terminal, Jeux Vidéo→sports_esports, OS Alternatifs→penguin, etc.).

## AXE H — Personas combinés (presets nommés, sert directement la vision)
Écrans complets combinant les choix, **nommés comme des presets sélectionnables** :
- **H1 "Ultra-compact"** = A1 + C5 + D2 + B2.
- **H2 "Compact"** = A2 + C3 + D8 + B1.
- **H3 "Équilibré"** = A3 + C1 + D7 + B4.
- **H4 "Confort"** = A4 + C1 + D6/D7 + B4 + avatars.
- **H5 "Confort+ / lecture"** = A5 + C6 + D4(progress) + D5(quote) + B5.

---

## Plan de rendu
- Taille device : téléphone, ~360×800 dp (qualifier Robolectric à confirmer depuis tests existants).
- 1 `@Test` = 1 PNG nommé `flags_<axe><n>_<variant>.png`.
- Personas (H) = écran complet ; axes B/D/F/G = captures composant (barre/ligne/menu) pour itérer vite.
- Cible : **30-40 PNG**. Dark par défaut ; quelques doublons light + AMOLED sur les personas.

## Cadrage Codex (gpt-5.5/xhigh, 2026-06-24) — GO + ajouts à forte valeur intégrés
Ajouts priorisés (nouveaux axes) :
- **AXE I — Tri / groupement** : par catégorie / activité récente / non-lus / favoris / couleur.
- **AXE J — Catégories repliables + mode mono-catégorie** (essentiel car vue groupée).
- **AXE K — Swipe actions** sur la ligne : marquer lu / retirer drapeau / favori.
- **AXE L — Sélection multiple** (nettoyage en masse des lus).
- **AXE M — Progression de lecture / delta nouveaux posts** (mieux que `p.X/Y` brut).
- **AXE N — Tablette / 2 colonnes** (si temps restant).
- **A11y** : 1 seul PNG « debug hit targets » (overlay zones 48dp sur l'ultra-36dp + mention « Expert density »), ne pas polluer les autres.

Garde-fous Codex :
- Séparer visuellement 3 tiers de champs : **implémentable now** (couleur, favori, non-lu, pages, auteurs, date) · **proposé/local** (épinglé, sourdine, preset layout) · **à vérifier serveur** (`quotedMe` — NE PAS présenter comme acquis). `pagesToRead` ≈ `totalPages - lastReadPage` → OK.
- Menu : ajouter « Ouvrir à la dernière page » + « Marquer la catégorie comme lue ». `S'abonner` = doublon possible du drapeau (prudence). `Sourdine`/`Épingler` = local-only assumé OK.
- Risques techniques : Canvas simples, tester ellipsis sur titres HFR longs, éviter mesures fines (fonts Robolectric), surfaces statiques OK, tuiles-initiales = bon compromis.

Sous-ensemble noyau si coupe nécessaire (~23-28 PNG) : 5 densités + 4 anatomies (dot/liseré/pill/progression) + 3 chromes + 3 onglets + 3 personas + 3 menus + 2 thèmes.
