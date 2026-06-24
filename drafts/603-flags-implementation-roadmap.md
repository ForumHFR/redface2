# #603 — Refonte vue Drapeaux : feuille de route d'implémentation

> Backée par un cadrage Codex (gpt-5.5/xhigh, 2026-06-24). Vision = `drafts/603-flags-vision-xatrix.md`.
> Mockups (référence visuelle) : https://forumhfr.github.io/artifacts/flags-mockups-603-vision/
> Méthodo = triple-hybride. **Curseur** : SDD pour données/états/persistance, prototype-driven pour l'UI,
> TDD pour les fonctions pures.

## Décision méthodo (où placer le curseur)
- **SDD (spec d'abord, légère)** : extension du modèle UI, états de chargement, persistance (super favori,
  onglets, densité), `quotedMe`, settings. → **ADR courte**, pas un dossier design.
- **Prototype-driven** : app bar, liste/ligne, bottom sheet, bottom bar (la vision + les mockups font foi).
- **TDD** : fonctions pures (pages-à-lire, regroupement, tri, filtres, mapping marqueur/onglets).

## Spikes bloquants (à lever AVANT de s'engager)
1. **`quotedMe`** : dispo proprement côté API/REST HFR ? **Go/No-Go**. Si non → le sortir du MVP, ou feature
   **explicitement locale** plus tard. **Ne pas simuler** un indicateur « cité » sans source réelle.
2. **Chargement auto** : existe-t-il un signal distinct/fiable du refresh automatique pour piloter la barre de
   progression ? (sinon on bricole un booléen fragile).
3. **Scroll → hide bottom bar / app bar translucide** en Nav3 + M3 **sans casser le swipe d'onglets déjà acté**.
4. **Icônes de catégorie** : mapping cat → drawable Material Symbols (sans import Material Icons, interdit detekt).
   Déjà ajoutés : `ic_ms_memory`, `ic_ms_sports_esports` (+ `ic_ms_forum` existant) — compléter le set (~20 cats).

## Séquence de PRs (MVP = PR1→PR5 ; PR6/PR7 ensuite)
- **PR0 — Spec + spikes** : ADR courte (modèle UI, états chargement, persistance, `quotedMe`, `superFavorite`,
  settings) + les 4 spikes ci-dessus.
- **PR1 — Modèle UI + fonctions pures (TDD)** : `FlagUiModel`, `pagesToRead = max(totalPages - lastReadPage, 0)`,
  regroupement, tri, headers catégorie, mapping marqueurs. Pas de grosse UI encore.
- **PR2 — Search app bar « classique »** : remplace `FlagsHeader` + `PrimaryTabRow` texte par l'app bar façon
  Réglages (icône drapeau = onglet courant, recherche, profil). **Conserver** onglets/swipe/pull-to-refresh.
- **PR3 — Liste refondue** (Roborazzi avant/après) : ligne standardisée (titre/auteur/date/pages-à-lire/cité si
  dispo), en-tête catégorie (vraie icône + bouton d'action), **marqueur gauche configurable** (pastille tonale+icône
  OU barre de couleur).
- **PR4 — Barre de progression** : M3 fine non-ondulée, **seulement** pendant chargement manuel OU auto. **Après**
  clarification des états (PR0/PR1).
- **PR5 — Bottom sheet appui-long (F2)** : remplace le retrait direct ; actions + **métadonnées API** + **super
  favori local** ; **pas de choix de couleur**.
- **PR6 — Bottom bar + config rapide** : labels optionnels, **hide-on-scroll** ; tap icône Drapeaux = menu config
  rapide. Après stabilisation liste/app bar (sensible aux insets + scroll).
- **PR7 — Options avancées** : onglets configurables/masquables, catégories repliables, ≥3 paliers de densité,
  migration DataStore.

## Stratégie de test
- **Roborazzi** : baseline de `FlagsRoute` **actuel** d'abord, puis snapshots après **PR3 / PR6 / PR7**.
- **Unit/TDD** : `pagesToRead` · regroupement catégorie · ordre des flags · hide-read-categories · unread-only ·
  mapping état marqueur · onglets visibles/configurés · **migration `FlagsViewSettings`**. Pas de TDD sur du Compose
  pur (snapshot + tests de mappers suffisent).

## Pièges (Codex)
- App bar transparente : insets, contenu dessous, contraste.
- LazyColumn : ne pas recalculer le regroupement en composition.
- Sticky headers + collapse : **état stable par catégorie**, pas par index fragile.
- Swipe onglets vs scroll : ne pas casser l'existant.
- A11y : tap targets 48dp, actions du sheet lisibles.
- DataStore : versionner les nouveaux champs, defaults conservateurs.
- `RedfaceVectorIcon` : interdiction detekt des imports Material Icons.

## Décisions ouvertes (à trancher par XaTriX en PR0)
- Marqueur **par défaut** : barre de couleur (le plus sobre) vs pastille tonale + icône.
- Fonction du **bouton d'en-tête de catégorie** : ouvrir / badge « cité » / nb non-lus.
- `quotedMe` : dans le MVP (si spike OK) ou repoussé.

## Autonomie / mode nuit (contrat) — session Claude Code FRAÎCHE recommandée
L'exploration étant finie, démarrer dans une **session propre** (évite de mélanger design et code).

**Défauts actés (pré-tranchés, override possible) :**
- Marqueur par défaut = **barre de couleur** (sobre ; pastille tonale en option).
- Bouton d'en-tête de catégorie = **« ouvrir la catégorie »** (le badge « cité » dépend de `quotedMe` non confirmé).
- `quotedMe` = **inclus seulement si le spike confirme la dispo serveur**, sinon repoussé — **ne jamais simuler**.

**Périmètre du run (dans l'ordre, ouvre une PR par lot, ne merge RIEN) :**
1. PR0 — ADR courte + 4 spikes
2. PR1 — modèle UI + fonctions pures (TDD)
3. PR2 — search app bar
4. PR3 — liste refondue (Roborazzi avant/après)
5. **PR5 — bottom sheet appui-long** (métadonnées API + super favori local, sans couleur) ← design-locked, inclus
6. **PR6-menu — menu config rapide** (popup depuis l'icône Drapeaux de la bottom bar) ← design-locked, inclus.
   **SANS le hide-on-scroll** (déféré : sensible insets/scroll).
7. PR4 — barre de progression **uniquement si** le spike « signal refresh auto » est concluant ; sinon différer.

**Différé (review/matin)** : PR6 hide-on-scroll, PR7 (onglets configurables/repli/densités/migration DataStore), `quotedMe` si No-Go.

**Règles dures** : gh = **XaaT** only ; **aucun merge** dans `dev` (PR ouvertes, CI verte, pour review) ; **review Codex sur chaque diff** ; CI verte avant le lot suivant ; **pas de post HFR** ; pas de simulation de données absentes ; branches depuis `origin/dev` frais.

**Conditions d'arrêt** : périmètre terminé · spike No-Go bloquant · décision hors-défauts requise → produire un **résumé** + ce qui attend validation.

**Prompt prêt à coller (session fraîche, mode nuit) :**
> Mode nuit, autonomie, ne pose pas de question. Implémentation #603 refonte vue Drapeaux.
> Lis `drafts/603-flags-vision-xatrix.md` + `drafts/603-flags-implementation-roadmap.md` (suis le §Autonomie/mode nuit).
> Défauts actés : marqueur = barre de couleur ; bouton en-tête = ouvrir la catégorie ; `quotedMe` = spike-gated (jamais simulé).
> Fais dans l'ordre : PR0 (ADR + 4 spikes) → PR1 (modèle + fonctions pures, TDD) → PR2 (app bar) → PR3 (liste, Roborazzi avant/après) → PR5 (sheet appui-long : infos API + super favori local, sans couleur) → PR6-menu (config rapide depuis l'icône bottom, SANS hide-on-scroll) → PR4 (barre progression, seulement si spike refresh-auto concluant).
> Une PR par lot, branche depuis origin/dev, Codex review sur chaque diff, CI verte avant le lot suivant.
> Ne merge AUCUNE PR. gh = XaaT only. Pas de post HFR. Pas de simulation de données absentes.
> Stop = périmètre fini / spike No-Go / décision requise → résumé + ce qui attend ma validation.
