# Suivi — pinch-to-zoom vue topic (#182) & contrat images (viewer)

> **Draft non normatif** (cf. AGENTS.md § « Les drafts ne gouvernent rien »). Il ne gouverne rien
> par lui-même : **à la décision, ses contrats et gates seront recopiés dans les issues
> d'exécution (critères d'acceptation)** et le draft promu vers `docs/` ou lié depuis elles.
> Objectif : que le chantier soit **reprenable de zéro** (faits, contrats, options, plan,
> garde-fous, relevés de POC) si la session qui l'a produit disparaît, et **remis sur la route**
> si l'implémentation dérive.
>
> Décideur : @XaTriX · Instruit le 13/07/2026 par Claude (Fable 5) + GPT 5.6 Sol (xhigh, accès
> repo, 2 rounds contradictoires + relecture par un agent tiers côté Sol ; gate de complétude de
> CE draft par Sol le 13/07 — 15 points intégrés).

## Références immuables (sources pérennes)

- **Snapshot RF2 instruit** : `ForumHFR/redface2@5820a6c430aa1d963376505d41b73ffc50e0315a`
  (dev, 13/07/2026 — post-0.29.2). Les fichier:ligne cités ci-dessous valent pour CE commit ;
  re-vérifier après toute évolution des fichiers touchés.
- **Snapshot RF1 examiné** : `ForumHFR/Redface@d53551bee33c4e2d79de708d4d0d7c7737bed1ec`
  (dernier commit legacy, 30/03/2026).
- **Cadrage viewer C** (contenu intégré §3) :
  [#182 — commentaire du 13/07](https://github.com/ForumHFR/redface2/issues/182#issuecomment-4957269503).
- **Demandes communautaires** : permaliens en §6.
- Versions vérifiées le 13/07 : Coil `3.4.0` · Compose BOM `2026.05.01` · navigation3 `1.1.2`
  (catalogue `gradle/libs.versions.toml` du snapshot) · Telephoto `0.19.0` (release GitHub
  02/04/2026, dernier commit amont vu le 08/07/2026) — **candidate**, cf. §3.
- L'artefact « dossier décisionnel » du 13/07 (claude.ai) est NON pérenne : tout son contenu
  décisionnel est subsumé ici.

## 0. État de la décision

| Date | Événement |
|---|---|
| 2026-07-13 | Dossier remis (options A/B/C/E, matrice, plan). **En attente de la réponse formelle à la Question n°1.** |
| 2026-07-13 | Signal provisoire du décideur : **A d'abord** ; prévision **A + C (+ correctif S/M/L)** ensuite — C et S/M/L pas tout de suite. |
| 2026-07-13 | **Décision formelle (canal direct, soir)** : périmètre immédiat = **A seul**, exécuté en 3 issues aux critères recopiés d'ici : [#935](https://github.com/ForumHFR/redface2/issues/935) POC A bloquant · [#936](https://github.com/ForumHFR/redface2/issues/936) durcissement multi-touch · [#937](https://github.com/ForumHFR/redface2/issues/937) A-loupe production. Différé confirmé : package complet A + C + correctif S/M/L dans un second temps (l'architecture ne doit pas les gêner). |
| 2026-07-13 | Validation de préparation (Sol xhigh) : **PRÊT-AVEC-AJUSTEMENTS**, intégrés aux issues — issues autonomes, règle d'arrêt dans #935, S10e = condition de gate, hypothèses des 8 micro-arbitrages posées avant POC, validateur final distinct de l'auteur, annonce DEV avant toute mise à disposition testeurs. Correction de pré-requis PTR (voir §2.1, nota). |
| 2026-07-13/14 | **POC itérations 1-2 : 3 amendements de contrat actés au terrain (§2.1 mis à jour, §7.2 « le réel prime »)** — rubber-band SATURANT tanh (dérive au zoom profond) · panY borné complément du scroll au bord bas (dernier post inzoomable ; gate Sol GO, maths pures dans #939) · **MODE REPLIÉ taps/long-press inertes à >1×** (le hit-test ne suit pas le layer draw-only — S25 : boutons morts/aléatoires à >1×). Settle ANCRÉ au relâcher (frame par frame). Relevés sur #935 (it.1 fonctionnelle S25, it.2 mesures release/R8 émulateur rooté : P95 24 ms, heap +0,5 %, matrice verte). |
| 2026-09-04 | **PR 1 du viewer C implémentée** : MVP plein écran + politique des liens ; gate POC C à exécuter par l'agent de build. |

### La Question n°1 (celle qui commande tout)

> Veux-tu reproduire le contrat RF1 — **loupe graphique globale éphémère** (plancher 1×, plafond
> initial proposé ~2,5× — choix RF2, voir nota §1.1 —, pan horizontal + scroll vertical, reset à
> chaque page) — en acceptant qu'à >1× soient **suspendus** : swipe de page, pull-to-refresh,
> double-tap refresh, sélection de texte, scroll horizontal des blocs `[fixed]` ?

- **Oui** → POC A bloquant (§4, critères binaires), puis package : A-loupe + correctif S/M/L + viewer C (3 PR indépendantes).
- **Non (pan 2D rédhibitoire)** → repli B (§2.2) + C.
- **Le besoin est surtout l'image** → C d'abord, sans prétendre répondre au pinch global.

## 1. Faits établis

Deux natures de faits, à ne pas confondre :
**[LU]** = établi par lecture de code/doc (non exécuté) · **[EXÉCUTÉ]** = observé sur build/capture réelle.

### 1.1 RF1 — la référence (sources lues, comportement partiellement déduit)

- [LU] Rendu : 1 page de topic = 1 WebView (templates HTML maison, `TopicPageView.java`).
- [LU] Le pinch RF1 = 2 lignes : `setBuiltInZoomControls(true)` + `setDisplayZoomControls(false)`
  (`TopicPageView.java:189-190`). Aucun setTextZoom / useWideViewPort / TEXT_AUTOSIZING /
  initialScale / ScaleGestureDetector custom. Aucune persistance du scale (seule la position de
  scroll est sauvegardée, `PostsFragment.java:285`).
- [LU→déduit] Comportement résultant = magnification Chromium par défaut : zoom **graphique**
  plein-page (texte, avatars, images), **sans reflow**, pan 2D, reset au changement de
  page/topic. **Non ré-exécuté** : les bornes exactes de zoom Chromium et le comportement précis
  au changement de page n'ont pas été mesurés sur un build RF1. Le plafond « ~2,5× » du contrat
  A est un CHOIX RF2 (lisibilité/perf), pas une mesure RF1.
- [LU] À côté : réglage persistant de taille de police (6 crans CSS 10→24 px) avec reflow —
  mécanisme distinct du pinch.
- [LU] Double-tap RF1 = refresh (double-tap-zoom inactif sans wideViewPort) — comme RF2.

### 1.2 RF2 — état du code au snapshot (chemins complets)

- **Vue topic** : `feature/topic/src/main/kotlin/fr/forumhfr/redface2/feature/topic/TopicScreen.kt`
  → Scaffold → PullToRefreshBox → `core/ui/src/main/kotlin/fr/forumhfr/redface2/core/ui/post/PostListScaffold.kt`
  = Box { LazyColumn (items key=`numreponse`, item 0 invariant sondage) ; `LazyListScrollbar`
  HORS LazyColumn }. Gestes empilés sur la liste : scroll ; swipe horizontal de page
  (`feature/topic/.../TopicSwipe.kt` — mono-pointeur, slop horizontal, `graphicsLayer.translationX`,
  ne relit `enabled()` qu'à l'armement) ; double-tap refresh ; pull-to-refresh ; sélection
  (`SelectionContainer` unique dans `core/ui/.../post/PostRenderer.kt`, seam `selectable`) ;
  long-press images. **Aucun detectTransformGestures/transformable dans l'app** (grep 13/07).
- **Typo** : préréglage S/M/L (`core/domain/.../preferences/FontScalePreference.kt` 0.9/1.0/1.15,
  clé DataStore `font_scale`) appliqué à la Typography racine via `scaledForReading`
  (`core/ui/.../theme/Type.kt` — fontSize+lineHeight ×factor, letterSpacing exclu, testé
  `TypeTest.kt`). **Manque connu : les médias inline ne suivent PAS S/M/L** — placeholders en
  `.sp` littéraux (`core/ui/.../post/PostMediaDisplayPolicy.kt` : smiley 18.sp, perso 70×50.sp,
  image inline 240×180.sp, px natifs injectés en sp) qui ne réagissent qu'au fontScale OS.
- **Reflow** : AnnotatedString invariants (#175), relayout borné aux items visibles. Le cache
  intrinsèque média (`IntrinsicMediaSizeMeasurer`/`Cache`) stocke des **px natifs par URL** —
  indépendant de l'échelle : un changement d'échelle texte ne nécessite **aucune invalidation ni
  re-mesure** (seule la conversion sp→px à l'affichage change). Images BLOC cappées en dp
  (`blockImageMaxHeightDp(screenHeightDp)`).
- **Ancre de scroll** : `TopicScrollAnchor(index, offsetPx)` (px bruts, par page, VM +
  SavedStateHandle) → tout reflow invalide l'offset ; ré-ancrage sémantique requis
  (item-sous-centroïde + fraction de hauteur ; `numreponse` seul = bon post, mauvaise ligne).
- **Viewer image : n'existe pas**, anticipé par le code : bouton « Afficher en taille réelle
  (à venir) » désactivé (`core/ui/.../post/PostImageMenuSheet.kt`) et
  `PostImageTarget(url, description, linkUrl)` partagés ; `PostImageActionsViewModel` reste dans
  `:feature:topic` pour la sauvegarde, tandis que le sheet gère copie et navigateur.
- **Sémantique tap actuelle** (délibérée, testée) : bloc LIÉE → navigateur ; bloc non liée et
  inline → tap inerte (protection sélection), long-press → menu.
- **Décodage** : images inline cappées 1024 px (`INLINE_IMAGE_DECODE_CAP_PX`,
  PostMediaDisplayPolicy) — un viewer doit recharger la source SANS ce cap-là (bornes propres §3).
- **Navigation** : Nav3 1.1.2, routes @Serializable `RedfaceNavKey`
  (`app/.../navigation/RedfaceNavigation.kt`), pattern « route plein écran qui masque la nav »
  (éditeur, `hidesNavigationSuite()`), transitions custom (le défaut Nav3 = fade 700 ms, à ne
  jamais garder), pas de shared-element propre en 1.1.2.

## 2. Options instruites

| Option | En une phrase | Verdict [LU — à confirmer par POC] |
|---|---|---|
| **A · Loupe globale éphémère** | Zoom graphique + pan 2D, reset/page — l'esprit RF1 | Jugée faisable SUR LECTURE ; le POC (§4) est BLOQUANT — le coût réel est l'arbitre de gestes, pas le dessin. Netteté : display list transformée (pas de raster global par défaut) mais mollesse possible aux scales fractionnaires et sous-arbres offscreen (alpha des signatures) → critère de POC, pas un acquis |
| B · Pinch → taille de texte | Preview graphique pendant le geste, commit reflow par crans au relâcher | Très faisable [LU] ; contrat de repli complet en §2.2 |
| **C · Viewer image plein écran** | Telephoto (candidate) + route Nav3 dédiée, pinch sur UNE image | Cadré §3 ; excellente brique, ne répond pas seul au « global » |
| Correctif S/M/L inline | Les smileys/images inline suivent enfin le préréglage | À faire quelle que soit la décision ; contrat §2.3 |
| E · Îlot de densité | Tout l'îlot grossit et reflowe (dp compris) | DÉCLASSÉE (round 2 : caps dp incohérents, chrome, rangées débordent) — documentée, non recommandée |
| A limité à un post | Zoom d'une carte | Écartée (chevauchement des voisins) ; fallback si POC A échoue et que B est refusé |

Matrice (Sol r2 ; fidélité RF1 25 % / UX 20 % / perf 15 % / a11y 15 % / simplicité 15 % /
réversibilité 10 %) : **package A+S/M/L+C = 4,43** · A-loupe 3,98 · B+C 3,83 · B 3,75 ·
A-par-post+C 3,70 · C seul 3,45 · E+C 3,30.

### 2.1 Contrat A-loupe

Machine d'états cible :

| État | Comportement contractuel |
|---|---|
| `1×` | Tous les gestes actuels intacts (scroll, swipe page, PTR, double-tap refresh, sélection, `[fixed]`) |
| `PINCHING` | Le 2e pointeur posé ANNULE tout swipe non commité ; scale autour du centroïde ; plancher 1×, plafond 2,5× — **rubber-band SATURANT (amendé POC it.1, terrain S25)** : tanh borné à +0,25 max au-delà de 2,5×, inversible (pattern overpull PageSwipe) ; la forme linéaire non bornée initialement proposée laissait l'échelle croître sous une forte pression et l'ancrage faisait défiler l'écran « tout seul » au zoom profond |
| `ZOOMÉ` | 1 doigt : pan X borné dans la layer (translationX ∈ [width×(1−scale), 0]) ; **pan Y = scroll RÉEL de la liste, complété par un translationY BORNÉ au bord bas (amendé POC it.1, terrain S25)** : panY ∈ [H×(1−scale), 0], engagé SEULEMENT une fois le scroll épuisé, résorbé EN PREMIER au retour — il panne le rendu scalé du viewport déjà composé, la justification virtualisation (ne jamais révéler d'items non composés) reste honorée ; sans lui le DERNIER post de la page était inzoomable (au scroll max, le delta d'ancrage clampe et le post scalé sort de la fenêtre visible H/scale) ; l'ancrage est garanti « tant que les bornes permettent la correction » (saturation simultanée scroll+panY = best-effort assumé) ; swipe/PTR/double-tap/sélection/scroll-`[fixed]` suspendus ; **taps et long-press : MODE REPLIÉ ACTÉ (POC it.2, terrain S25 + matrice)** — le hit-test des enfants ne suit PAS le layer draw-only : un tap à >1× atterrit sur les coordonnées NON transformées (mort au mieux, voisin invisible au pire ; « marche parfois vers 1× » = coïncidence des espaces) → taps/long-press déterministement INERTES à >1× (down consommé), interagir = dézoomer (chip/snap) puis taper ; écart de parité RF1 à annoncer sur le fil DEV avant toute livraison (§7.5) |
| `RESET` | Relâcher à scale ≤ 1,03 → snap animé 1× ; sinon le zoom reste tant qu'on est sur la page ; bouton reset discret toujours visible à >1× ; reset synchrone au changement de page ET de topic |
| Chrome | Top bar, FABs, ascenseur (déjà hors LazyColumn) jamais zoomés ; TransformOrigin fixe haut-gauche |

Clé d'état et cycle de vie : l'état (scale, panX) vit dans la composition, keyé sur l'identité
COMPLÈTE `(cat, post, page)` — pas `page` seule (deux topics à la même page ne doivent pas
partager un zoom). Contrats : back pendant zoom = back normal (quitte la page, zoom perdu) ;
rotation/config change = reset accepté (éphémère assumé, PAS de rememberSaveable — la survie au
process restore serait plus que RF1) ; process restore = 1×.

Décisions de micro-arbitrage à figer dans la SPEC D'IMPLÉMENTATION de l'étape 1 (listées ici pour
n'en perdre aucune ; le POC les tranche avec preuve) :
1. Définition de « swipe commité » (seuil de navigation franchi) et fenêtre d'annulation par le 2e doigt.
2. PTR déjà armé (indicateur tiré) quand le 2e doigt arrive → abandon propre du PTR.
3. 3e pointeur pendant PINCHING → ignoré (les 2 premiers font foi) ou re-centroïde ; à trancher.
4. Fling/inertie : pan Y hérite du fling de liste (souhaité) ; fling X dans la layer = à trancher.
5. Overscroll (glow/stretch) pendant ZOOMÉ : supprimé ou conservé sur l'axe liste.
6. Changement de page déclenché PENDANT un geste (par FAB/pager) → reset avant navigation.
7. Espaces de coordonnées : pinch lu en coordonnées écran du LazyColumn NON transformé (le
   détecteur vit AVANT le graphicsLayer, comme `topicPageSwipe`) ; nœud transformé = le
   LazyColumn (listModifier) ; correction de centroïde : maintenir le point contenu sous les
   doigts ⇒ à chaque frame, `scrollBy((centroidY_screen/scale_old − centroidY_screen/scale_new))`
   coalescé en UNE mutation par frame de geste, signe : contenu descend quand scale augmente
   autour d'un centroïde bas. Les formules exactes (X et Y) sont à poser dans la spec avec tests
   unitaires de la fonction de mapping AVANT branchement UI.
8. Animation du snap/reset (durée ~150-250 ms, interruption par nouveau geste).

Pré-requis techniques déjà identifiés : `topicPageSwipe` doit ABANDONNER sur multi-touch même
après slop (il ne relit pas `enabled()` en cours de drag) — première tranche testée de A ;
`PostListScaffold` doit exposer `userScrollEnabled` ; suspension du PTR via
`Modifier.pullToRefresh(enabled = scale == 1f)` au call-site (`Box` + `Indicator` remplaçant le
wrapper) — **corrigé le 13/07 (§7.2, le réel prime)** : `PullToRefreshBox` en material3 1.4.0
(la version du BOM 2026.05.01, vérifiée dans le sources.jar officiel) n'expose PAS `enabled`,
contrairement à ce qu'affirmait ce draft, et un simple gate dans `onRefresh` serait insuffisant
(geste consommé, indicateur armé) ; `selectable = false` à >1× (seam existant).

### 2.2 Contrat B (repli instruit — exécutable sans nouveau dossier)

- Le pinch pilote l'échelle TEXTE du lecteur : pendant le geste, preview graphique
  (graphicsLayer sur la liste, mêmes précautions §2.1) ; au relâcher, COMMIT en un reflow unique.
- Crans, pas de float libre — **contrat initial du repli (exécutable tel quel ; toute
  modification = entrée §0)** : extension de `FontScalePreference` à S 0.9 / M 1.0 / L 1.15 /
  XL 1.3 / XXL 1.5, plafond 1,5 (ouvrable après POC dédié). La lecture DataStore est défensive :
  l'ajout de crans ne casse pas les valeurs persistées. Hystérésis d'accrochage au cran ±0,05.
- Persistance : UNE écriture DataStore au commit (jamais pendant le geste). Le réglage est le
  MÊME que Réglages > Affichage (S/M/L) — le pinch devient un raccourci du réglage existant.
- Ré-ancrage au commit : conserver l'item sous le centroïde + fraction de sa hauteur ; correction
  de scroll en seconde passe post-mesure.
- Gestes : pas d'état « zoomé » persistant → AUCUNE suspension (swipe/PTR/sélection intacts hors
  geste) ; le pinch lui-même s'arme à 2 pointeurs comme en A (mêmes pré-requis multi-touch).
- Médias : inline suivent via le correctif §2.3 ; images bloc inchangées (dp) — C couvre l'inspection.
- Tests : mapping cran↔facteur unitaires ; reflow commit sur page réelle (Robolectric) ;
  ré-ancrage (item stable) ; a11y = cumul multiplicatif avec fontScale OS vérifié à OS 1.0/1.3/2.0.

### 2.3 Contrat du correctif S/M/L inline (autonome)

- Périmètre : le CORPS des posts (sous-arbre `PostRenderer` body) — smileys builtin, persos,
  images INLINE (placeholders sp). EXCLUS : images bloc (cap dp, décision assumée — C couvre),
  chrome de carte (identité, badges, footer), letterSpacing (comportement actuel testé conservé).
- Mécanisme : CompositionLocal dédiée (facteur lecteur) consommée par les boîtes inline AVANT
  leur cap relatif (`maxMediaWidthSp`) — PAS un override `LocalDensity` racine (letterSpacing et
  tout sp non typographique scaleraient), PAS `scaledForReading` recopié (double-scale).
- Cache intrinsèque : AUCUNE invalidation nécessaire (px natifs par URL ; seule la conversion
  d'affichage change) — l'affirmer par un test (le même URL rend deux tailles selon le facteur
  sans nouvelle mesure).
- Cumul : facteur lecteur × fontScale OS, multiplicatif, comme le S/M/L texte actuel.
- Tests : S/M/L × OS 1.0/1.3/2.0 sur corps avec smileys+image inline (tailles attendues
  paramétriques) ; non-régression `PostRendererInlineLayoutTest` (le test 0,85→2,0 existant
  valide le fontScale EFFECTIF, pas un multiplicateur 2× — ne pas sur-interpréter).

## 3. Contrat images (brique C)

Source intégrée : cadrage du 13/07 sur #182 (permalien en tête). **Telephoto = candidate** tant
que licence (annoncée Apache-2.0 — à confirmer au POC), minSdk, impact R8 et capacités réelles
(subsampling, GIF) ne sont pas validés sur build ; alternative de repli si rejet : zoom maison
borné SANS subsampling + caps de décodage stricts (moins bon, chiffré +1-2 sessions).

### 3.1 Sémantique des URLs (table de vérité)

| Cas au tap | `sourceUrl` (chargée) | `previewUrl` (placeholder) | `externalUrl` (bouton navigateur) |
|---|---|---|---|
| Bloc NON liée | url de l'image | la même (déjà en cache mémoire probable) | url de l'image |
| Bloc liée → IMAGE | `linkUrl` (pleine taille) | url affichée (miniature) | `linkUrl` |
| Bloc liée → NON-image / douteux | (pas de viewer) | — | comportement actuel : navigateur sur `linkUrl` |
| Inline nue (via sheet) | url de l'image | la même | url de l'image |

Actions du viewer : **enregistrer** et **copier l'URL** opèrent sur `sourceUrl` ; **ouvrir dans
le navigateur** sur `externalUrl`. #831 (menu des liées-inline) = les MÊMES actions offertes dans
le viewer via la même sheet/actions VM (acté : le menu rejoint le viewer).

Classification « lien image » TESTABLE : `linkUrl` est image-like ssi (extension du path ∈
{jpg, jpeg, png, gif, webp, avif}, insensible à la casse, query ignorée) OU (host ∈ liste blanche
versionnée des rehosteurs connus : reho.st, rehost.diberie.com, i.imgur.com — extensible par
constante). Tout le reste = navigateur. Tests unitaires sur la fonction, cas douteux inclus.

### 3.2 Politique mémoire/décodage

- Recharger la source SANS le cap inline 1024 px, mais JAMAIS sans borne : décodage par tuiles
  (subsampling Telephoto) quand le format le permet ; sinon cap de sécurité au décode (côté long
  ≤ 4096 px OU limite d'allocation — valeur à figer au POC) avec zoom sur le bitmap borné.
- Échec de décodage → écran d'erreur du viewer + bouton navigateur ; JAMAIS de bascule auto.
- GIF animés : aucun engagement avant POC (point mémoire le plus risqué) ; replis possibles :
  zoomable sans subsampling avec cap dimension, ou statique-première-frame + bouton navigateur.
- Cache : requêtes via le `ImageLoader` singleton (partage disque/mémoire avec la liste).

### 3.3 États du viewer (tous à couvrir par tests/POC)

Chargement cache FROID (preview ABSENTE — après process restore notamment) : placeholder neutre +
indicateur, jamais d'écran noir · chargement cache chaud (preview affichée → swap net vers la
source, pas de flash basse-résolution prolongé) · erreur (message + navigateur) · restauration
Nav3 (la route porte les params → rechargement autonome) · retour : barres système RESTAURÉES
(test explicite) · predictive-back.

### 3.4 Gestes et découpage C

Pinch + double-tap (2×/retour) + pan ; back/predictive-back + bouton fermer ; PAS de
swipe-dismiss au MVP. Découpage : POC jetable → PR1 viewer MVP sans régression (dépendance
encapsulée derrière un composable interne, route + transition ~200 ms, activation du bouton
sheet, tap blocs non liées, tests câblage) → PR2 politique des liens (classification §3.1,
non-régression navigateur). Hors MVP : galerie paginée, dérivation miniature→originale, dismiss vertical.

### Checklist anti-« bouse » (recopiée dans les critères d'acceptation de CHAQUE tranche A ou C)

Ne jamais : zoomer la miniature 1024 px au lieu de recharger la source · décoder sans borne
(jank/OOM) · écran noir entre miniature et pleine taille · fade Nav3 700 ms · gestes qui se
battent (pan vs dismiss vs back vs swipe) · intercepter des liens non-image · casser la sélection
de texte · perdre les barres système au retour · promettre « taille réelle » sans meilleure source.

## 4. Plan de livraison, chiffrage, critères de gate BINAIRES

| Étape | Effort | Risque principal | Gate (critères mesurables) |
|---|---:|---|---|
| 1 · POC A (branche jetable, BLOQUANT) | 1-2 | arbitrage pinch-après-swipe-armé ; netteté API 29 | voir « Gate POC A » ci-dessous |
| 2 · Durcissement multi-touch du swipe | (1re tranche A) | régression swipe | test 2-doigts dédié vert + zéro régression des tests swipe existants |
| 3 · A-loupe production | 3-5 | double-scroll, bornes, reset, interactions enfants | matrice de gestes instrumentée 100 % verte (chaque case = test ou preuve datée) |
| 4 · Correctif S/M/L inline | 1-2 | ratio texte/médias | tests paramétriques §2.3 verts |
| 5 · C viewer (POC → PR1 → PR2) | 2-3 | mémoire (GIF), restauration Nav3, classification | Gate POC C ci-dessous + états §3.3 tous testés + classification §3.1 unitaire |
| 6 · B pinch→commit (OPTIONNELLE) | 2-3 | saut au commit, ré-ancrage | contrat §2.2 testé |

**Gate POC C — adoption de Telephoto (GO ssi TOUS les critères, build release/R8)** :
- Licence Apache-2.0 confirmée dans le POM/repo · minSdk ≤ 29 · build release/R8 sans règle keep exotique.
- Zoom/pan fluides sur JPEG 4000×3000 : P95 frame ≤ 32 ms sur les 2 appareils de la matrice.
- Subsampling DÉMONTRÉ : JPEG ≥ 8000 px de côté ouvert et zoomé sans OOM ni downscale visible.
- preview→source : aucun flash basse résolution > 300 ms perceptible (captures au relevé).
- GIF : si un GIF animé ~5 Mo est zoomable sans OOM → supporté ; sinon repli ACTÉ = première
  frame statique zoomable + bouton navigateur (inscrit à §3.2 et au relevé).
- **Repli si un critère non-GIF échoue** : zoom maison borné sans subsampling (+1-2 sessions,
  caps §3.2 stricts) — bascule re-notifiée au décideur avant exécution (changement de qualité perçue).

**Gate POC A (GO ssi TOUS les critères, en build RELEASE/R8)** :
- Matrice d'appareils : émulateur Pixel 8 (API 37) + S10e physique (API 29 bas de gamme réel).
- Page de test : topic réel complexe (texte long, citations imbriquées, smileys, images
  inline/bloc, signature avec alpha, `[fixed]`, `[code]`, post avec toutes les actions).
- Perf : pendant pinch et pan continus, **P95 frame time ≤ 32 ms** (trace de frames jointe au relevé §8).
- Gestes : 20 essais par ligne de matrice (pinch après swipe armé, pinch pendant scroll, pan aux
  4 directions, tap lien à >1×, long-press image à >1×, PTR à 1× après reset). Deux modes au
  contrat, testés dans cet ordre :
  - **Mode nominal (itération 1)** : taps/long-press CONSERVÉS à >1× → GO ssi 0 déclenchement
    parasite (swipe/PTR/sélection pendant zoom) ET ≥ 19/20 reconnus sur CHAQUE ligne, y compris
    tap/long-press.
  - **Mode replié (itération 2, si le nominal échoue sur les lignes tap/long-press uniquement)** :
    taps/long-press DÉSACTIVÉS à >1× (comme la sélection) → GO ssi tap/long-press strictement
    INERTES à >1× (0/20 déclenchements) et toutes les autres lignes au critère nominal.
  Le mode retenu est inscrit au relevé §8 ET reporté dans §2.1 (mise à jour du contrat).
- Netteté (critère autonome — RF1 n'ayant pas été ré-exécuté) : sur chaque appareil, capturer
  (a) le paragraphe de test à zoom loupe 2× et (b) le MÊME paragraphe rendu net par relayout
  (fontScale effectif 2.0) comme référence. GO ssi (a) ne montre ni pixellisation évidente ni
  glyphes baveux à l'œil sur écran réel — jugement humain TRACÉ au relevé §8 avec les deux
  captures archivées (le critère est perceptuel mais borné : référence imposée + preuve jointe).
- Mémoire : 5 cycles zoom-pan-reset sur la page de test → pas de croissance monotone du heap
  (delta < 10 % entre cycle 1 et 5).
- Restauration : 3 changements de page pendant/après zoom → ancres correctes, zoom reset.
- A11y : TalkBack à 1× inchangé (le zoom loupe est un geste 2 doigts hors parcours TalkBack) ;
  l'alternative d'agrandissement accessible = S/M/L (+correctif §2.3) — documenté, pas de
  régression des annonces existantes.
- **Repli** : si les critères ne sont pas TOUS atteints après **2 itérations de POC datées au
  relevé §8** → arrêt de A, exécution du repli B (§2.2) + C, décision re-notifiée au décideur.

## 5. Dissensus actés (à re-trancher uniquement avec des faits nouveaux)

Sol maintient : l'éphémère purge l'état, PAS le risque gestuel ni le déficit a11y du pan 2D
(Simplicité A = 2,5) · ré-ancrage par `numreponse` seul insuffisant · le durcissement multi-touch
est une tranche testée de A, pas un préalable anodin · correctif S/M/L par override LocalDensity
global dangereux — CompositionLocal ciblée · « texte vectoriel re-rendu » trop fort : display
list transformée sans raster global mais sans relayout typographique (mollesse possible aux
scales fractionnaires). Claude a fait plier Sol sur : A-loupe re-scorée 3,3→3,98 · E déclassée ·
trio découplé en 3 PR. Gate de complétude de ce draft : NO-GO r1 (15 points, tous intégrés le 13/07).

## 6. Journal de la demande (adoption) — permaliens

Base topic TU : `https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35395` (ancres `#t<num>`) ; DEV : `post=35421`.

- Demande la plus insistante depuis le début (styx42, Azgor #t2788546, nicko, Batman-Fr, foul).
- 13/07 : Batman-Fr TU #t2791097 (« la SEULE chose qui m'empêche de switcher ») · Deadlock TU
  #t2791122 (frein n°2/3) · thibw TU #t2791123 (« EgoQuote puis pinch ») · Azgor TU #t2791103
  (« indispensable ») · CharLee DEV #t2791139 (viewer interne = brique C) · XaTriX DEV #t2791143
  (viewer « pas cette phase ») · antiseptiqueIncolore TU #t2791083 (crainte « bouse » → réponse
  = checklist §3) · XaTriX TU #t2791133 (« peut-être lancer le pinch ce soir »).

## 7. Remise sur la route (procédure)

1. À LA DÉCISION : recopier les contrats/gates concernés dans les issues d'exécution comme
   critères d'acceptation (ce draft ne gouverne rien par lui-même) ; lier ce draft depuis #182.
2. Si un POC invalide un verdict [LU] : mettre à jour §1/§2 AVANT de continuer (le réel prime).
3. Si le POC A échoue au sens du gate §4 (2 itérations datées) : repli B+C sans nouveau dossier.
4. Aucune promesse publique (GIF, perf, « taille réelle ») sans relevé §8 daté.
5. La Question n°1 tranchée et les gestes suspendus à >1× sont ANNONCÉS sur le topic DEV avant la
   première livraison A (pas de surprise testeurs).
6. Toute dérive du périmètre (galerie, dismiss, dérivation d'URL…) = hors MVP → nouvelle entrée §0.

## 8. Relevés de POC (registre — à remplir, une entrée par itération)

> Gabarit obligatoire — un POC sans relevé ici n'existe pas (la branche jetable emporte la preuve).

```
### POC <A|C> — itération N — YYYY-MM-DD
- Commit testé : <sha> (branche <nom>, jetable)
- Build : <release/R8 | debug> — appareils : <liste>
- Commandes exécutées : <gradle/adb/trace exactes>
- Mesures : P95 frame = <ms> ; gestes = <x/20 par ligne> ; heap Δ = <%> ; netteté = <verdict + captures>
- Anomalies : <liste>
- Verdict vs gate §4 : GO / NO-GO (critères manquants listés)
```
