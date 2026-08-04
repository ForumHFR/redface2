# Changelog — application

Suivi des AAB générés (`./gradlew :app:bundleRelease`) avec le format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

À ne pas confondre avec [`CHANGELOG.md`](../CHANGELOG.md) à la racine, qui suit les versions des **specs**. Ici on suit les versions binaires (`versionCode` / `versionName`) et leur statut de distribution.

Statuts possibles d'une release :

- `local` — l'AAB existe sur une machine de dev, pas distribué
- `internal` — uploadé sur le canal Play Console *internal testing* (canal `dev` de la CD)
- `closed` — uploadé sur le canal Play Console *closed testing* (ancien canal alpha de l'app unique)
- `open` — uploadé sur le canal Play Console *open testing* (canal `beta` de la CD, depuis #233)
- `production` — disponible publiquement sur Play Store

Workflow (depuis #304, CD rev. 4) : le **`versionCode` n'est plus bumpé à la main** — il est alloué au dispatch par le **registre de tags git** (`max(app-v<N>, plancher build.gradle.kts) + 1`, partagé entre les canaux beta et dev). **On DOIT bumper `versionName`** dans `app/build.gradle.kts` avant chaque ship **beta** (ou prod) — F-Droid affiche les versions par `versionName`, donc deux builds au même `versionName` = doublon « X.Y.Z » (cf. `app-v84`/`app-v85`, tous deux `0.5.0`). Un guard CI dans `release.yml` refuse un ship beta dont le `versionName` n'a pas été bumpé. On ajoute une entrée ici, puis on dispatche `gh workflow run release.yml --ref main -f channel=beta|dev`. Quand l'AAB part vers un canal Play Console, mettre à jour le statut de la version concernée.

---

## `0.39.0` — `internal` (dev) — 2026-08-04

### Modifié

- **Sélecteur de smileys : les perso sont bien plus grands** ([#989](https://github.com/ForumHFR/redface2/issues/989), demande **XaTriX**, retour initial de **nicko**). La grille tombait sur 6 colonnes de 48 dp avec un cap image de 44 dp en dur. Or le corpus perso est massivement **paysage 7:5** — `70×50` pèse **31 %** des 74 perso du top 100 HFR mesurés — donc une cellule *carrée* capait ce format sur la largeur en laissant 17 dp de hauteur morts. Les défauts passent au **preset « E »** : cellule au ratio du corpus, 5 colonnes de 65,33×48 dp, marges 8, écart 4, cap 61,33×44. Le format dominant passe de 44×31 à **61×44, surface doublée**, et sature les deux axes à la fois — plus rien n'est gaspillé. Coût : 3 vignettes visibles en moins, contre 18 pour le même gain en cellule carrée. La **règle** de calcul est inchangée, seuls les défauts bougent : un test rejoue l'ancienne géométrie pour le prouver.

### Ajouté

- **Réglage « Délimiteur du sélecteur de smileys »** (Affichage) : aucun (défaut), contour, ou quadrillage. Persisté en DataStore, sur le modèle du profil d'agrandissement des GIF ([#973](https://github.com/ForumHFR/redface2/issues/973)) — enum de domaine, lecture tolérante, mise à jour optimiste avec rollback, `CompositionLocal` semé par le thème (le sélecteur est ouvert depuis quatre écrans).

### Rejeté, et tracé

- **Un plafond d'upscale ×1,5 des petits smileys (preset « F ») est refusé.** Testé sur device puis écarté sur objection de **XaTriX**, confirmée par arbitrage **GPT-5 Codex** : le facteur est nécessairement **non uniforme** — les smileys déjà au cap ne bougent pas, seuls les petits grandissent. Le sélecteur promettait alors une taille que le message ne respecte pas, au moment précis où l'utilisateur choisit. Et l'upscale uniforme est impossible dans une cellule bornée (débordement, ou moins de colonnes, ou un zoom qui ment quand même). `persoScaleCeiling = 1` est désormais **pinné par un test qui cite ce refus**. La piste retenue à la place — aider la sélection sans toucher la taille — est tracée en [#1022](https://github.com/ForumHFR/redface2/issues/1022) (loupe à l'appui long).

### Interne

- Développement à deux mains : couche domain+data par **GPT-5 Codex**, couche UI et géométrie par **Claude Opus 5**. Gate final **Claude Fable 5**, deux bloquants trouvés : (1) l'option « Quadrillage » était **inopérante en production** — des traits continus n'existent que si les cellules sont accolées, et la neutralisation de l'écart ne vivait que dans le banc de test debug, donc le réglage aurait affiché des tirets disjoints ; (2) trois fakes de `UserPreferencesRepository` manquaient dans `feature:editor` et `feature:flags`, CI rouge à la clé.
- Artefact de décision (7 géométries, captures device réelles) : https://forumhfr.github.io/artifacts/smileys-picker-989/
- 1963 tests verts sur `core:ui`, `core:data`, `feature:settings`, `feature:topic`, `feature:editor`, `feature:flags` et `app`.

---

## `0.38.1` — `internal` (dev) — 2026-08-04

### Ajouté

- **« Mettre un favori ici » dans le menu contextuel d'un message** ([#986](https://github.com/ForumHFR/redface2/issues/986), demande **thibw**) : l'action est au niveau du MESSAGE et pas du sujet, parce que `addflag.php` ancre le favori sur une position — le title du bouton HFR lui-même dit « Mettre un favori sur cette position pour y revenir plus tard ». Le `ref` attendu n'est pas recalculé : `Post.quoteRef` porte déjà la valeur émise par HFR dans la toolbar du message (parsée depuis #146, persistée Room v5). Le dériver d'un index de liste serait faux dès la page 2, où le rappel « Reprise du message précédent » porte `ref=0` et ne consomme pas de rang — contrat désormais documenté dans le KDoc de `quoteRef`, qui le déclarait opaque. Aucun undo côté HFR, les libellés n'en promettent pas.

### Interne

- Gate Codex en trois passes, **cinq défauts trouvés dont quatre bloquants**, aucun détectable par la CI, detekt, lint ou les tests initiaux : (1) `Topic.subcat` peut valoir le sentinel `SUBCAT_UNKNOWN` (-1) que le `require` de `FlagAddContext` rejette — l'IAE partait hors du `runCatching` et crashait l'écran ; (2) la position était ancrée sur `request.page`, qui n'est pas la page affichée pendant un changement de page ni en recherche intra-topic — favori posé **silencieusement** sur une page non regardée ; (3) l'entrée s'affichait sur les rappels de page (`quoteRef = 0`) pour échouer à coup sûr ; (4) le post sélectionné survivant à un changement de page, l'action appariait un post périmé avec la page courante ; (5) une `CancellationException` encapsulée dans un `Result.failure` était rapportée comme échec utilisateur.
- Un test annonçant un changement de page sans appeler `switchToPage()` — donc ne prouvant rien — a été remplacé par le scénario réel.
- 121 tests verts sur `TopicViewModelTest`, dont 6 sur cette action.

---

## `0.38.0` — `internal` (dev) — 2026-08-04

### Corrigé

- **Drapeaux — passage à la page suivante quand le dernier lu est en bas de page** ([#638](https://github.com/ForumHFR/redface2/issues/638), retours **thony94** et **MisterDams**) : le tap sur une ligne ouvrait `flag.lastReadPage`, donc quand le dernier message lu était le dernier de sa page et qu'une page suivante existait, l'app rouvrait la page déjà lue avec le message en bas. `lastReadPage + 1` n'était pas le correctif : un arrêt en milieu de page aurait fait sauter les non-lus restants. Le discriminant est REST `last_position` (index global 1-based du dernier message lu) — **déjà désérialisé, mais dont seule la nullité était lue**, la valeur étant jetée. Nouveau `Flag.pageToOpen()` : n'avance que si `last_position % postsPerPage == 0` ET que la page déduite égale `lastReadPage`. Conservative par construction — toute incertitude (pas de non-lu, dernière page, `last_position` absent/`0`/incohérent, dernier lu supprimé [#394](https://github.com/ForumHFR/redface2/issues/394)) retombe sur le comportement précédent : on peut ne pas avancer, on ne saute jamais un message. Décision prise avant navigation, pour ne pas rejouer le flash de [#477](https://github.com/ForumHFR/redface2/issues/477). Aucune bannière à construire : HFR ouvre la page N+1 sur un rappel « Reprise du message précédent ».
- **Drapeaux — « 1er non-lu » sautait un message** (trouvé en corrigeant #638) : le raccourci d'appui long rendait `lastReadPage + 1` dès qu'il y avait du non-lu. Sur la fixture `rest_cat23_participated.json` (`last_position` 479, page 12, 541 messages) il envoyait en page 13 alors que le message 480 n'était pas lu. Les deux chemins partagent désormais la même règle.

### Ajouté

- **Couche protocole pour poser un favori depuis l'app** ([#986](https://github.com/ForumHFR/redface2/issues/986), demande **thibw**) : `HfrClient.addFlag`, `FlagAddContext`, `FlagAddResult`, `FlagAddResponseParser` et `FlagRepository.addFlag`. Contrat `addflag.php` relevé en live : `ref` est le rang 1-based du message dans sa page, `owntopic` est ignoré par HFR (addflag ne pose que des favoris, cyan/red étant automatiques). **Pas encore d'entrée UI** — le point d'entrée et le choix du message d'ancrage restent à cadrer.

### Interne

- Migration Room 15 → 16 : `flag_topics.lastPosition` (nullable) et `flag_topics.postsPerPage` (`DEFAULT 40`, issu de `results_per_page` par sujet — pas un 40 en dur, le serveur normalise et l'option de profil `topicpp` existe).
- KDoc de `TopicSummary.lastReadPage` corrigé : `last_position` est un index global, pas un offset dans la page.
- Relectures croisées : cadrage et review par **GPT-5 Codex**, gate final par **Claude Fable 5**. Trois bloquants trouvés avant merge — trois fakes non compilables (`addFlag` abstraite), les 14 tests de migration cassés par le bump en v16 (`addMigrations` s'arrêtait à 14→15) et le recoupement `lastPosition`/`lastReadPage` manquant.
- 1495 tests verts sur `core:model`, `core:database`, `core:data`, `core:network`, `core:parser`, `feature:flags`, `feature:topic`, `app`.

---

## `0.37.0` — `open` (beta) — 2026-08-01

**Promotion bêta** — première version proposée aux testeurs du canal ouvert depuis la 0.18.0.
Cette entrée synthétise la série dev 0.19.0 → 0.36.1 ; les entrées détaillées ci-dessous restent
la source des changements et correctifs intermédiaires.

### Ajouté et modifié

- **Vue Topic refondue** : top bar et sélecteur de page intégrés, frontières de page et repère de
  dernière lecture plus lisibles, actions de post stabilisées, citations longues repliables et
  option « Posts en pleine largeur » pour gagner de la place de lecture.
- **Moteur de pagination in-ViewModel** : changement de page sans recréer l'écran ni flasher,
  revisite instantanée des pages récentes avec leur position, retour fidèle après un saut de
  citation et transitions de chargement discrètes.
- **Loupe de lecture** : pincement jusqu'à 3× sur toute la page, déplacement borné et glisse amortie ;
  les gestes incompatibles sont suspendus pendant le zoom et l'état se réinitialise au changement
  de page ou de sujet.
- **Images sous contrat** : séparation fiable entre images inline et blocs, taille mesurée sans
  upscale, décodage adapté à la densité, grandes images mieux dimensionnées, miniatures liées et GIF
  plus lisibles, menu d'actions, erreurs visibles et réessai sans recharger les images saines.
- **Surfaces d'écriture consolidées** : réponse rapide ou éditeur plein écran selon le réglage
  (plein écran par défaut, feuille encore expérimentale), citations multiples, brouillons robustes,
  sélecteur de smileys et upload multiple disponibles sur les composeurs concernés ; clavier,
  curseur et bouton d'envoi restent accessibles sur les écrans courts.

---

## `0.36.1` — `internal` (dev) — 2026-08-01

Correctif **N1**, trouvé pendant la review de promotion bêta (coupe 9/10, review GPT-5.6 Codex →
gate Claude Fable 5). Classé bloquant pré-bêta au même titre que F2/F3/F5 de #953 : fonctionnel et
**silencieux**.

### Corrigé

- **Course d'annulation du painter (#960)** : deux occurrences d'une même URL sur une page se
  disputent l'unique réservation du painter. Quand la gagnante était disposée **en plein
  chargement** (un défilement suffit), `rollbackReservation` rendait l'axe à `Untried` sans avancer
  la génération — conforme au verrou #5, mais aucune clé de la perdante ne bougeait alors : ni
  l'`attempt` mémoïsé, ni `failedFresh`. La perdante recomposait sans jamais rappeler
  `reserveIfUntried()` et **restait figée sur son placeholder pour la durée de l'écran**.
  - Aggravation : le **tirer-pour-rafraîchir ne récupérait pas** ce cas — `retryFailedUrls` ne bump
    que les axes portant un `Failed` (verrou #1), et un axe rendu à `Untried` n'a pas de TTL. Seules
    guérisons : recycler la perdante par défilement, une nouvelle occurrence composée plus loin, ou
    quitter l'écran.
  - **Fix** : `MediaAttemptLedger.isUntried(url, kind)` — bit snapshot-observable — devient une clé
    du `LaunchedEffect` de réservation. Le rollback recompose la perdante **et** relance son effet.
    Ni `rollbackReservation` ni `retryFailedUrls` ne sont touchés : verrous #5 et #1 intacts. Le
    corps de l'effet reste gaté sur `failedFresh` **seul** — une failure expirée est encore
    `Failed`, pas `Untried`, et doit continuer d'atteindre `reserveIfUntried()` pour consulter C1.
  - Écarté : avancer la génération au rollback. Trop large — une rollback survient à **chaque**
    défilement disposant un effet en vol, et le bump recréerait tous les `PainterAttempt` de l'URL
    en relançant les effets de mesure, avec annulation possible d'un probe en vol.
  - Surcoût du fix : un `tryReserve` refusé supplémentaire par occurrence observatrice, sans
    écriture, sans bump, sans requête réseau. Aucune boucle de recomposition possible.
  - Résidu consigné, **auto-guérissant** : la course de même forme existe sur l'axe **probe**
    (`IntrinsicMediaSizeMeasurer`), mais `settlePainterGeometry` settle l'axe probe à **chaque**
    succès painter — l'axe painter étant désormais re-armé, tout painter qui aboutit guérit la probe
    coincée. Résidu réel : « painter réussi avec dimensions inexploitables », dont le symptôme est
    une boîte cold, pas une image absente. Durcissement symétrique possible après la bêta.

### Tests

- `PainterAttemptRearmTest` (neuf) — la course de bout en bout : A gagne, B est refusée, A est
  disposée en vol, **B reprend et settle**. **Vérifié en échec avant le fix** — `AssertionError`
  déterministe en phase 3 (« B must re-arm »), reproduite par un validateur distinct.
- `MediaAttemptLedgerTest` — un test épingle que le refresh ne récupère **pas** une entrée rendue à
  `Untried` (la cause traitée au niveau composition, pas au ledger).
- **Faux vert corrigé** : le test du verrou #5 settlait l'axe en `Succeeded` *avant* la rollback
  tardive — sa dernière assertion tenait pour la mauvaise raison, un axe succeeded refusant toute
  réservation que la rollback soit discardée ou non. Réécrit en laissant l'axe **in-flight**, seul
  état où un mauvais scoping serait observable.

---

## `0.36.0` — `internal` (dev) — 2026-07-30

### Ajouté

- **Agrandissement des miniatures-aperçus liées** (#876, `[AMENDEMENT-v1.5-4]` du contrat de rendu) : une
  image de contenu enveloppée d'un lien vers une ressource DISTINCTE du MÊME hôte, et dont le plus grand
  axe natif ne dépasse pas 400 px, voit son plafond no-upscale porté à `min(densité, 3)`. Une vignette
  d'hébergeur de 150 px passe de 7,9 mm à 23,8 mm de large sur un S10e (480 dpi), soit la taille qu'elle
  occupe déjà sur le rendu web. Signalé par tinc sur le fil DEV.
  - Deux gardes ferment les faux positifs : le lien doit pointer **ailleurs** que l'image affichée (les
    auto-liens sont exclus), et au-delà de 400 px natifs une image n'est plus une vignette.
  - `mEffectif = max(mApercu, mGif)` — les deux multiplicateurs ne se cumulent JAMAIS, le plus grand
    gagne, et ce `max` porte aussi le plancher `1,0` (sans lui, une densité < 1 rétrécirait l'image).
  - Décodage inchangé (§7) : la source reste décodée au natif, l'agrandissement se fait au draw.
  - Résiduels assumés et documentés au contrat : hôte à underscore rejeté (limitation du parseur,
    fail-closed), point final terminal conservé, et un `[img]` pointant une URL de smiley perso reste
    indistinguable d'une vignette (la classification se fait sur le token, jamais sur l'URL — I1).

### Modifié

- **Cap de hauteur des images bloc relevé de 50 % à 70 % de la fenêtre utile** (#993, arbitré XaTriX,
  `[AMENDEMENT-v1.5-5]` du contrat de rendu) : sur S10e en portrait, une grande image passe d'un plafond
  de 400 dp à ~496 dp — la cible des ~500 dp. Le plancher 400 dp est conservé et devient le garde des
  fenêtres courtes : sous 571 dp d'utile c'est lui qui gouverne, ce qui préserve à l'identique le
  comportement « cap = fenêtre entière » acté au gate A3 en split-screen (301 dp) et en paysage (288 dp).
  Le changement ne peut jamais RÉDUIRE une image : il l'agrandit ou la laisse identique. Conséquence
  assumée : une capture d'écran occupe au plus environ les deux tiers de l'écran. Aucun cap fixe en dp
  n'est introduit — le cap reste proportionnel, clampé par la fenêtre. Annexe `matrice-invariants-876.md`
  (I3.2) réalignée.
- Banc de test images (topic 148760) : **POST 16** ajouté — 5 cas dont **2 contrôles négatifs** (vignette
  auto-liée, version 800×800 liée). Le banc passe de 45 à 52 cas ; fixture parser recapturée.
- Annexe `matrice-invariants-876.md` : I3.1/I3.4 mentionnent désormais l'exception `mEffectif`
  (péremption qui datait de `[AMENDEMENT-v1.5-2]`, #973).

### Notes de développement

- 37 tests dédiés (22 purs JVM sur l'éligibilité, 15 Robolectric sur le renderer), dont un test qui
  épingle le validateur d'autorité contre un durcissement en `host != null`, et un cas qui prouve le
  non-cumul des deux multiplicateurs. Mutant `scaleCeiling = previewCeiling` vérifié tué.
- Vérifié sur S10e réel : les 5 cas du POST 16 mesurés au pixel (210×450, 450×210, 450×450 pour les
  vignettes liées ; 150×150 et 800×800 **inchangés** pour les deux contrôles négatifs).
- Gouvernance : amendement rédigé par Sol (GPT-5.6 Codex xhigh) et gaté par Claude Fable 5 ; code par
  Claude Fable 5 et gaté par Sol — 3 tours de gate, 2 NO-GO réels levés.

## `0.35.1` — `internal` (dev) — 2026-07-26

Correctif du mode « Posts en pleine largeur » (#983, rapporté par styx42) : espacements
irréguliers et lignes horizontales parasites autour des marqueurs. Le trait de pied d'un post
n'est plus dessiné que d'un post à un autre — là où le marqueur « Dernier message lu », un
placeholder de post masqué ou un îlot de fin (fin de sujet, frontière de page, footers de
recherche) apporte déjà sa propre bordure, il était empilé quelques dp au-dessus d'elle. Et le
marqueur porte désormais son propre rythme vertical, symétrique (il héritait de 8 dp au-dessus
et de rien en dessous) ; il reste traversant, à la largeur des posts qu'il sépare. Le mode encart
est inchangé à l'identique.

## `0.35.0` — `internal` (dev) — 2026-07-26

Agrandissement des GIF (#973, [AMENDEMENT-v1.5-2] au contrat images, arbitrage XaTriX) :
nouveau réglage « Agrandissement des GIF » (Réglages → Affichage) — S (×1, net) / M (×1,5) /
L (×2,5), **défaut M**. S'applique aux GIF de contenu en bloc uniquement (identifiés par le
MIME réel de la probe, jamais l'extension d'URL) ; les images normales, smileys, cc-images et
GIF inline ne changent pas ; les caps de largeur/hauteur continuent de borner le résultat ;
le décodage reste au natif (agrandissement au dessin, net à ×1). Répond aux retours de
l'appel à tests tailles (GIF trop petits depuis le no-upscale strict).

## `0.34.6` — `internal` (dev) — 2026-07-26

Fixes pré-promotion (#953, bloquants F2/F3/F5/F6 de la review beta) : la réponse rapide
survit à une rotation pendant l'envoi (l'état est restauré, plus de rejet fantôme du
résultat) ; les brouillons de réponse rapide sont strictement isolés entre comptes
(re-capture de l'owner à chaque ouverture, sessions scellées, gardes de lecture/suppression
en base — un compte ne peut plus lire ni effacer le brouillon d'un autre) ; la création de
topic n'est plus soumise pendant un upload d'image en vol ; la spec navigation reflète le
défaut plein écran de l'éditeur.

## `0.34.5` — `internal` (dev) — 2026-07-26

Réglage « Posts en pleine largeur » — Lot 5 (#884) de la passe images (#876), arbitrage
XaTriX : optionnel, 2 états, défaut inchangé. Nouveau réglage dans Réglages → Topic :
en pleine largeur, les posts s'affichent bord à bord (fond plat, bande d'identité sur
toute la largeur, fine ligne de séparation) au lieu des encarts — plus de place pour le
texte ET les images (le dimensionnement de la passe images s'applique à la largeur
gagnée). Les gouttières ne disparaissent qu'entre les posts : sondages, frontières de
page et fin de sujet gardent leur respiration. Accessibilité : chaque post est un groupe
TalkBack avec le pseudo en titre (navigation par titres), dans les deux modes. Le mode
encart par défaut est strictement inchangé (rendu byte-identique).

---

## `0.34.4` — `internal` (dev) — 2026-07-22

Les états d'erreur & retry — Lot 4 (#960) de la passe images (#876, contrat v1.5 §6,
la mort de #813/B5) : registre d'essais par URL (UNE tentative probe + UNE tentative
painter par génération — une URL morte n'est plus re-requêtée à chaque recomposition
ni par chaque occurrence), TTL négatif 60 s qui ouvre une NOUVELLE génération (jamais
la courante), slot d'erreur VISIBLE dans la boîte réservée (bloc ET inline — fini le
vide silencieux), retry manuel par TAP sur le slot (« Réessayer », universel : corps
de post, MP, aperçu, signatures), pull-to-refresh scopé aux SEULES images en échec de
la page (les images saines ne sont jamais re-décodées), protocole G2 (une image dont
la mesure échoue prend sa boîte de son propre décodage — SVG s'affiche ainsi), coil-svg
embarqué, AVIF selon décodeur device avec état d'erreur propre + retry sinon. Gates
Sol P1..P4 + gate final (2 NO-GO fermés en TDD : générations qui gelaient un axe en
vol, éviction du cache de géométrie). 513 tests :core:ui.

## `0.34.3` — `internal` (dev) — 2026-07-21

Le sizing & décodage density-aware — Lot 3 (#959) de la passe images (#876, contrat
v1.5 §3/§7/§9) : équation unique en PIXELS PHYSIQUES (no-upscale réel : 1 px source ≤
1 px écran, hauteur dérivée de la largeur arrondie), fraction de largeur dédiée
`fImage = 0,95` sur les 3 chemins ([AMENDEMENT-v1.5-1], levier A #884), cap hauteur
bloc CLAMPÉ à la fenêtre utile (le legacy écran disparaît), probe intrinsèque
header-only (fini l'écrêtage 1024 des grandes photos — B8 ; EXIF gardé, GIF réels
OK), décodage §7 par buckets 256/facteur commun ≤ 2048 avec la taille DANS les clés
de requêtes (netteté — bug nicko #842), GIF gatés (boîte finale ∧ viewport réel ∧
RESUMED — prefetch et arrière-plan figés), floor 16 sp retiré du chemin mesuré
(slots cold/cc seulement). [AMENDEMENT-Lot3-1] : hitbox au-delà du minimum touch
target plateforme (a11y, approbation a posteriori). Gates Sol : cadrage r2 GO,
mini-gate P2 GO, gate final r2 GO.

## `0.34.2` — `internal` (dev) — 2026-07-21

Les interactions images — Lot 2 (#958) de la passe images (#876, contrat v1.5 §5) :
tap sur une image liée = ouvre le lien, appui long = menu image, désormais AUSSI pour
les miniatures liées en pleine phrase (split de la `LinkAnnotation` — le lien
n'intercepte plus le geste, bug CharLee/B6) ; hitbox du geste = le bitmap (hors
padding §4) ; MP, aperçu éditeur et signatures : images totalement inertes
(capability hôte) ; a11y A11Y-1..5 (alt HFR + fallback « [image] », nœud stable en
erreur, aucune action fantôme). [AMENDEMENT-Lot2-1] (garde sélection retirée — le
tap ouvre le lien et ferme la sélection) et [AMENDEMENT-Lot2-2] (`Role.Image` +
`onClickLabel`, `Role.Link` inexistant en Compose stable) approuvés + gatés Sol.

## `0.34.1` — `internal` (dev) — 2026-07-20

Le renderer segmenté branché — Lot 1B (#957, PR #966), le lot visible de la passe
images (#876, contrat v1.4).

- Modifié : la topologie inline/bloc des images est désormais STRUCTURELLE (§2) — les
  galeries deviennent des colonnes de blocs espacés de 8 dp (fini le collage B3), les
  petites images isolées deviennent des blocs à taille native, les images en pleine
  phrase restent dans le flux du texte (fini la rupture de paragraphe), les fragments
  de texte entre galeries survivent à leur place (le tout-ou-rien tinc est mort).
- Ajouté : respiration de 4 dp de chaque côté des images de contenu dans la prose (§4) ;
  boîte d'attente déterministe des blocs non mesurés (cold §6, plafond fenêtre v1.4) ;
  le rafraîchissement explicite (#813) recrée aussi les painters des blocs.
- Retiré : la promotion mesurée (#224 option B) et ses seuils — la mesure ne fait plus
  que dimensionner.
- Verdicts banc S10e (topic 148760) : structure 2.7/3.1/3.x/4.x/5.x/8.x/13.1 verte,
  témoins #175/#256 inchangés ; B1/B4 partiels attendus (Lot 3), B5 au Lot 4.
- Gate GPT-5.6 Sol : GO au r3 (2 NO-GO instructifs, dont un vrai bug §4 corrigé avant
  merge). 427 tests :core:ui.

## `0.34.0` — `internal` (dev) — 2026-07-20

Premier lot applicatif de la passe images (#876, contrat v1.4 gelé) — Lot 1A (#956, PR #964).

- Ajouté : policy pure de segmentation `InlineSegment`/`MediaRun` (topologie inline/bloc
  décidée par la structure de l'AST, contrat §2) + 48 tests dont 14 sur la fixture réelle
  du banc images (topic 148760, 45 cas). AUCUN changement visuel : la policy n'est pas
  encore branchée au renderer (Lot 1B).
- Fixture `topic_page_banc_images_876.html` + provenance ; divergence de canal de capture
  fichée #963.

## `0.33.0` — `internal` (dev) — 2026-07-19

Premier lot du chantier « barres » #882 (P1) — deux reviews indépendantes (Codex GO + code-review
multi-agents, aucune issue retenue).

- Vue Topic : la **rangée d'actions du post** (Répondre, Citer, etc.) passe à une hauteur de
  **48 dp réels** (cible tactile conforme) et la **pill dynamique « Ajouté à la citation »** est
  supprimée. Résultat : **zéro décalage de mise en page** au tap « + Citer » (plus de saut du post)
  et un gain d'environ **16 dp par post** en préset Confort.

## `0.32.0` — `internal` (dev) — 2026-07-15

Décision produit #951 : la feuille de réponse rapide n'est pas correctement terminée.

- Éditeur : la **feuille de réponse rapide passe en expérimental** (opt-in). Le défaut de
  « Surface d'écriture » devient **« Toujours plein écran »** ; les deux presets feuille sont
  étiquetés « (expérimental) » dans les réglages (pattern #805). Les utilisateurs qui avaient
  déjà choisi un preset gardent leur choix.

## `0.31.0` — `internal` (dev) — 2026-07-15

Premier lot du chantier couleurs #883 (arbitrage XaTriX sur la galerie V2 : la refonte
complète des palettes part en phase suivante, seul le tertiaire change maintenant).

- Thème : l'accent tertiaire **ambre** (« jaune ») est remplacé par une **ardoise** dans les
  5 palettes (Rose clair/sombre, AMOLED, Rouge REDFACE1 clair/sombre). Visible sur la bande du
  post ciblé (ancre de scroll), le bouton d'envoi armé, les barres des citations imbriquées
  alternées et le badge sticky/lock des listes forum. Le jaune des drapeaux favoris
  (FlagPalette) est découplé et ne change pas.

## `0.30.1` — `internal` (dev) — 2026-07-15

Lot v1.1 de la loupe — retours communautaires 0.30.0 (fil DEV), même journée.

- Loupe : plafond de zoom relevé à **3×** (demande unanime) ; au-delà, place au futur viewer d'images.
- Loupe : **glisse verticale amortie** après un déplacement zoomé (décélération rapide, bornée, jamais après un pincement — le « lancer en sucette » de RF1 est structurellement impossible) (#182).
- Fix #946 : pincer sur une citation dépliée ne la replie plus (le changement structurel du mode replié jetait l'état des citations).

## `0.30.0` — `internal` (dev) — 2026-07-15

Chantier pinch-to-zoom #182 (option A) — POC #935 GO (3 relevés, matrices émulateur + S10e), durcissement #936, production #937.

- **Loupe globale de lecture** : pinch-to-zoom graphique éphémère de la page topic (esprit RF1) — plafond 2,5×, rubber-band saturant, pan 1 doigt (Y = vrai scroll + complément borné au bord bas), chip « 1× », reset au changement de page/sujet (#182, #935, #937).
- Pendant le zoom : swipe de page, pull-to-refresh, double-tap refresh et sélection suspendus ; **taps/appuis longs inertes (mode replié annoncé sur le fil DEV)** — dézoomer pour interagir.
- `topicPageSwipe` : annulation multi-touch native (2e doigt pendant un drag non commité = spring-back, jamais de navigation) (#936).
- 32 tests ajoutés (maths de mapping, matrice de gestes, multi-touch swipe) ; gates croisés Sol/gpt-5.5/review Claude indépendante.

## `0.29.2` — `internal` (dev) — 2026-07-13

**Batch autonome de l'après-midi** (5 chantiers, gates croisés Claude Fable 5 ↔ GPT 5.6 Sol : deux fixes codés par Sol et gatés par Claude, trois l'inverse).

### Fixes
- **#918 — recherche globale** : la rangée d'onglets catégories ne disparaît plus après une bascule de catégorie (HFR n'embarque pas le pivot dans les réponses mono-catégorie ; il est désormais conservé). [codé par Sol]
- **#545 — édition de ses propres posts** : les profils HFR avec « Affichage des outils » désactivée (affichoutils=0) retrouvent Modifier/Supprimer — l'ownership est reconnu par le pseudo de session quand HFR ne sert pas la toolbar (cause reproduite live ; les gardes MP-à-soi/auto-masquage suivent).
- **#532 — lignes vides alignées sur le web** : contrat serveur capturé live (HFR compresse : n lignes vides → floor(n/2)+1 visibles, sans plafond) ; l'app sous-rendait ces runs depuis #466. Rendu visible sur les posts multi-paragraphes. [codé par Sol]
- **#872 (a) — libellé « Contenu BBCode »** : épinglé au-dessus du viewport scrollable des éditeurs plein écran — il ne peut plus être rogné par le scroll d'ouverture, à aucun fontScale (nom accessible conservé via la sémantique du champ).

### Améliorations
- **#900 (volet 2) — panneau smileys** : la grille se cale sur 62 % de la hauteur d'écran (plancher 320 dp) — la feuille atteint ~3/4 de l'écran (mesuré 73,8 % au dogfood), réponse au retour de CharLee.

## `0.29.1` — `internal` (dev) — 2026-07-13

**Trio multiquote** (#868/#869/#870, PR #920 — le lot retenu de la nuit, mergé après dogfood émulateur complet des 8 contrats du cadrage, au matin post-reboot).

### Vue topic / composer
- **#868 — le FAB « Citer N » survit** à l'ouverture de l'éditeur + retour sans envoi : le panier n'est plus vidé à l'OUVERTURE mais à l'**envoi réussi** d'une session qui l'a consommé (flag explicite porté par le chemin d'ouverture — « Citer » simple, réponse, édition et échecs d'envoi ne le vident jamais ; « Tout vider » reste le reset manuel).
- **#869 — le compteur repart de N**, plus jamais de reset à 1 après un aller-retour.
- **#870 — plus de citations fantômes** : la feuille de réponse remet ses citations exactement au set livré à chaque ouverture (plus de fusion avec une session précédente).

## `0.29.0` — `internal` (dev) — 2026-07-13

**Lot de nuit 12→13/07** (#813 images fantômes, #862 épinglés drapeaux, trio éditeur #873/#900/#872, garde citation #583, fix loupe #913 de la veille). Chantiers cadrés + gatés hors-bande (GPT-5.6 Sol) ; vérification visuelle émulateur non réalisée cette nuit (hôte KVM HS — reboot machine requis), couverture par tests JVM/Robolectric + fixtures serveur réelles.

### Vue topic
- **#813 — les images fantômes se récupèrent** : une image inline dont le premier chargement a échoué (hébergeur en panne, coupure) restait un carré quasi invisible jusqu'à « citer puis revenir ». Le tirer-pour-rafraîchir (et le double-tap) relance désormais mesure ET chargement. (PR #919)
- **#913 — la loupe de recherche reste cohérente** après la fermeture d'une recherche pendant une transition de page (reliquat de la veille). (PR #914)

### Drapeaux
- **#862 — les sujets épinglés flaggés apparaissent dans les listes** (favoris/cyan/rouges) : le serveur les omet de ses buckets dans TOUTES les catégories (prouvé par captures) ; le supplément `topics/last` couvre maintenant les 19 catégories, en UN balayage partagé par les trois types (barrière de génération au refresh — cadrage Sol 5 rounds). (PR #922)

### Éditeur
- **#873 — l'aperçu affiche les smileys standards** (`:jap:`, `:lol:`, `:pt1cable:`… — 51 tokens), validés contre la table canonique (pas de faux positifs type `10:30:45`). Les persos `[:name]` restent en texte (résolution d'URL = suivi) ; les émoticônes ponctuation (`:)`, `:D`) volontairement non converties en aperçu. (PR #921)
- **#900 — panneau smileys wiki compacté** : ligne de titre retirée (les onglets nomment la surface, TalkBack garde une annonce paneTitle) — une rangée de plus visible. (PR #921)
- **#872 — le libellé « Contenu BBCode » ne se rogne plus** aux grandes tailles de police (réservation du label indexée sur l'échelle de police). (PR #921)
- **#583 — une citation qui ne se matérialise pas bloque l'envoi** (erreur retryable) au lieu de poster silencieusement SANS le bloc cité. (PR #922)

## `0.28.1` — `internal` (dev) — 2026-07-13

**Stale-while-switching** (#910, PR #911 — retour immédiat de XaTriX sur la 0.28.0 : « ça saute/flash toujours sur une page non connue »).

### Vue topic
- **Plus de squelette flashé sur un changement de page rapide** : la page quittée reste affichée (hairline discrète) pendant que la nouvelle charge ; le squelette n'apparaît que si le chargement dépasse ~250 ms. Une page en cache Room (session antérieure) bascule sans aucun flash.
- La pilule ne dit plus jamais « Chargement… » par-dessus un contenu affiché ; la barre (loupe, repli auto-hide, titre) ne change plus d'état pendant le chargement d'un switch.
- Gardes : un switch échoué affiche l'erreur (jamais un état figé) ; pull-to-refresh inactif pendant la transition.

## `0.28.0` — `internal` (dev) — 2026-07-12

**Zéro flash au changement de page** (#895 étapes 4-5, PRs #905/#907/#908 — le fond du chantier, après les quick wins de 0.27.3).

### Vue topic — moteur de pagination in-ViewModel
- **Plus aucun flash au changement de page** : la pagination vit dans un seul écran retenu (une seule entrée de navigation pour tout le topic) — plus de squelette plein écran ni de barre recréée entre deux pages ; le squelette ne reste que pour une page jamais visitée.
- **Revisite instantanée** : les 5 dernières pages lues sont gardées en mémoire — y revenir est immédiat, à la position exacte où on les avait laissées.
- **Retour de citation fidèle** (#782 renforcé) : après « aller au message cité », le retour ramène à la position exacte du tap, même en chaîne ; un changement de page manuel réinitialise la chaîne (comportement navigateur), un 2e retour sort du topic.
- **Landing « page précédente » conservé** (#412) : reculer d'une page atterrit en bas (sens de lecture), sauf position déjà connue pour cette page.
- Post-submit : la publication rafraîchit la bonne page dans le MÊME écran (débordement #226 géré en interne, plus de re-navigation) ; la position de lecture survit à la mort de process (page + ancre).
- Nettoyage : transition instantanée topic→topic supprimée (code mort de l'ère « une route par page »).

## `0.27.4` — `internal` (dev) — 2026-07-12

**Duo picker smileys** (retours tinc/nicko du jour sur le fil DEV — fixes livrés en parallèle par sub-agents, gates Codex GO).

### Éditeur — sélecteur de smileys
- **Fini les petits smileys flous** (#871, PR #903) : les persos plus petits que la cellule s'affichent à leur taille intrinsèque (même pipeline de mesure que le rendu des posts, #175), jamais étirés ; cap 44 dp conservé, builtins inchangés.
- **Curseur en fin de mot** (#901, PR #904) : la recherche restaurée à la réouverture du panneau (#824) garde le terme et place le curseur à la FIN — effacer ou compléter est immédiat.

## `0.27.3` — `internal` (dev) — 2026-07-12

**Quick wins zéro-flash** (#895, PR #899 — premier lot du chantier « zéro flash au changement de page » ; le fond, pagination intra-topic dans un même ViewModel, suit dans un lot dédié).

### Vue Topic — changement de page
- **La pilule de la barre dit la vérité** : une page servie du cache pendant son actualisation affiche « page X / Y » (le contenu réellement à l'écran) au lieu de « Chargement… » ; « Chargement… » est réservé au vrai chargement sans contenu.
- **Signal d'actualisation discret** : fine barre de progression (2 dp) sous la top bar pendant l'actualisation d'une page en cache — bande toujours réservée, aucun décalage de mise en page ; annonce d'accessibilité « actualisation en cours » sur la pilule.
- **Prefetch sans réseau inutile** : le préchargement des pages voisines ne refait plus de requête quand la page est déjà en cache (et n'écrase jamais une version authentifiée — garde couverte par tests).

## `0.27.2` — `internal` (dev) — 2026-07-12

**Recherche intra-topic v2** (#894, retours XaTriX sur 0.27.1 — contrat `transsearch` re-vérifié live, cadrage + gates Codex, dogfoods sur le serveur réel).

### Vue Topic — recherche
- **Le mode non-filtré refonctionne** (PR #896) : le form des réponses transsearch (sans `firstnum`) parse à nouveau — le curseur de match n'est plus perdu (« Aucun résultat » systématique corrigé).
- **Ancrage parité web** (PR #897) : la recherche part de la page courante vers la fin (ancre de session explicite) ; nouvelle option « **Chercher depuis le début du sujet** » (défaut décoché).
- **« Afficher les résultats suivants »** (PR #897) : quand HFR tronque sa fenêtre de scan (~200 matches), un footer de continuation reprend au curseur annoncé — le batch suivant remplace la liste et s'ouvre en haut. Le pager `p` de 0.27.1 (sans effet serveur) est supprimé.
- Les étapes ‹ › et le retour au premier résultat re-soumettent les critères figés de la recherche affichée, plus jamais la barre en cours d'édition.

## `0.27.1` — `internal` (dev) — 2026-07-12

**Lot de 4 fixes Vue Topic** (#877, #879, #880, #863 — cadrage groupé, gate Codex par PR, dogfood émulateur de chaque flux dont migration Room v14→v15 en conditions réelles).

### Vue Topic
- #877 : **top bar stable pendant les chargements** (PR #889) — la pilule affiche « Chargement… » tant que la page est provisoire (fini le numéro de page périmé pendant les transitions), la loupe reste visible en mode chargé authentifié, fetch du formulaire de recherche latest-wins.
- #879 : **recherche intra-topic conforme au contrat transsearch** (PR #891) — le mode filtré couvre TOUT le sujet (plus d'ancrage à la page courante), pagination des résultats filtrés (« résultats suivants ») avec critères figés à la soumission et retour en tête de liste.
- #863 : **badge « cité ×N » = compteur serveur** (PR #892) — « Message cité N fois », cross-pages et autoritaire, persisté en cache (migration Room v15) ; l'index local limité à la page courante est supprimé.

### Éditeur & réponse rapide
- #880 : **le curseur reste visible au-dessus du clavier** à l'escalade réponse rapide → plein écran (PR #890) — le suivi du caret se re-déclenche quand le clavier finit de s'installer.

## `0.27.0` — `internal` (dev) — 2026-07-12

**Lot d'arbitrages et reliquats de la phase** (#792, #809, #881, #805-exp — cadrage + gates Codex par PR, review multi-angles sur #809, dogfood émulateur de chaque flux).

### Vue Topic
- #809 (#tagsuggestion tinc) : **appui long sur le titre → retirer le drapeau** du sujet courant (PR #887) — confirmation avant retrait, résultat en toast, la liste Drapeaux se met à jour sans refetch. Un drapeau d'un onglet jamais ouvert est retrouvé à la demande. Le tap court (dépliage du titre) est inchangé.
- #792 (suggestion Dintr-un lemn) : **« Envoyer un MP »** dans le menu contextuel « … » d'un post (PR #886) — ouvre le composeur avec l'auteur pré-rempli. Absent sur ses propres posts et hors session.

### Éditeur & réponse rapide
- #881 (arbitrage du fil) : **le curseur démarre sous la citation** (PR #885) — un retour à la ligne unique après le bloc [quotemsg] quand il termine le champ, feuille et plein écran.
- #805 : le réglage **« Citations en cartes » est étiqueté (expérimental)** (PR #885) — défaut inchangé (désactivé) ; le chantier design des cartes est reporté à l'itération Vue · Topic 2.

## `0.26.3` — `internal` (dev) — 2026-07-10

**Suite de la nuit rf2-12** (lot d'issues non démarrées de la phase).

### Éditeur
- #816 (suggestion thibw) : le **sélecteur de smileys respecte l'échelle du forum** (PR #865) — les standards s'affichent près de leur taille native (petits, pixel-art net), les persos remplissent la cellule. Fini l'uniforme 30 dp qui rendait les standards énormes et flous et les persos à l'étroit.

### Messages privés
- #812 : **tourner l'écran dans une conversation ne ramène plus à la liste des MP** (PR #866) — le nettoyage de session se rejouait à chaque recréation d'activité et résetait la pile de navigation Messages ; il est désormais limité aux vraies transitions de session (login, logout, changement de compte).

## `0.26.2` — `internal` (dev) — 2026-07-10

**Écriture sur écran court** (retour de la checklist de test, thibw — PR #861, cadrage + gate Codex GO, dogfood émulateur 1080×1700 et 1080×2400).

### Éditeur & réponse rapide
- Le **champ de saisie n'est plus jamais écrasé par le clavier** (réf #555) : dans l'éditeur plein écran, tout ce qui concurrence le champ (bannière de brouillon, bandeaux d'erreur, cartes de citation) vit dans une zone haute budgétée qui défile au-delà de son budget — le champ garde 96 dp minimum par construction. À l'apparition d'une alerte, la zone se recale en haut.
- **« Envoyer » toujours visible dans la réponse rapide** (réf #855) : seule la zone des champs défile, la rangée « Envoyer » est épinglée au-dessus du clavier.
- **Fermer la réponse rapide = un seul retour** (réf #854) : la feuille ne s'arrête plus à mi-hauteur quand un petit écran l'avait forcée en pleine hauteur (fini le « 3× retour pour revenir au sujet »).

## `0.26.1` — `internal` (dev) — 2026-07-07

**Réponses aux deux premiers retours du fil DEV sur la 0.26.0** (même soirée).

### Lecture (vue Topic)
- #842 : le **plafond de hauteur des images bloc est recalibré pour mobile** — `max(400 dp, 0,5 × hauteur d'écran)` au lieu du flat 200 dp de #610 (PR #844). Une image quasi carrée/portrait remplit maintenant ~90 % de la largeur (mesurée à ~48 % sur le retour du fil) ; paysage inchangé ; toujours borné (pas d'explosion du scroll), aucun upscale. Le 200 n'avait pas de base web réelle (la seule règle HFR est `max-width: 90%`) — l'étiquette « parité web » de #610 est corrigée dans le code. Chemin inline inchangé (200 sp, conservateur dans la prose).

### Éditeur & brouillons
- #843 : la **bannière « Un brouillon non envoyé a été retrouvé » (Restaurer / Ignorer) est de retour sur les ouvertures à froid** de l'éditeur plein écran (PR #845) — FAB en preset plein écran, « Citer » routé vers l'éditeur, appui long #823, « Citer N » 3+. `resumeSharedDraft` avait dérivé de son contrat #790 : ces chemins ré-appliquaient silencieusement un vieux brouillon, sans choix d'ignorer. L'escalade feuille → éditeur garde l'append silencieux (#790 inchangé).

## `0.26.0` — `internal` (dev) — 2026-07-07

**Vague 5 Vue Topic (#604)** : interactions image, lot citations, rendu média parité web, gestes d'appui long, recherche smileys, fix Drapeaux.

### Lecture (vue Topic)
- #831 (partiel) : **appui long sur une image de post → menu contextuel** (PR #837) — Copier l'URL, Ouvrir dans le navigateur, Enregistrer (octets originaux via le cache disque Coil, GIF préservés ; « Taille réelle » arrive avec le viewer #182). Le tap court sur une image liée ouvre toujours le navigateur (#257). Limite connue : le menu n'est pas accessible sur les images `[url=][img]` restées inline dans un paragraphe mixte — l'issue #831 reste ouverte pour ce cas.
- #610 : le dimensionnement des `[img]` s'aligne sur HFR web (max-width 90 %, max-height ~200 dp, PR #836) — parité visuelle avec le site, ni upscale ni débordement.
- #256 : fast-path de rendu pour le marqueur `hfr-cc-image` (PR #835) — matching strict sur la query (garde anti-fragment), règle « un intrus = pas de marqueur ».

### Citations
- #785 : la black-list masque aussi le **contenu des citations** d'un auteur bloqué (PR #838) — bandeau « Citation de X masquée », tap pour révéler.
- #782 : après un saut vers un post cité, **le retour ramène à la position de lecture précédente** (PR #839) — pile de retour par onglet, vidée à la sortie du topic.
- #784 : les **citations longues sont repliées** avec un aperçu (PR #840) — tap sur le corps pour déplier, l'en-tête continue de sauter au post cité.

### Gestes
- #822/#823 : appui long sur les **FAB de pagination → première/dernière page**, appui long sur **Citer → éditeur plein écran** (PR #833) — haptique + libellés TalkBack.

### Éditeur
- #441/#824 : recherche de smileys unifiée derrière `SmileyPickerController` (un seul chemin feuille/plein écran) et **recherche restaurée à la réouverture** du picker, onglet inclus (PR #834).

### Drapeaux
- #825 : le filtre « masquer les catégories vides » ne s'applique plus à l'**onglet lus** (PR #832).

## `0.25.2` — `internal` (dev) — 2026-07-05

**Presets de surface d'écriture (#806)** + lot d'hygiène d'état (audit rf2-10) + deux fixes de veille.

### Éditeur & réponse rapide
- #806 : réglage « **Surface d'écriture** » (Réglages > Édition et publication, PR #829) — « Toujours la feuille » (défaut, comportement actuel), « Feuille sauf citations » (toute citation ouvre l'éditeur plein écran — la demande du fil DEV), « Toujours plein écran ». L'escalade feuille → plein écran reste disponible partout ; réglage orthogonal à « Citations en cartes ».
- #808 : dans la feuille de réponse rapide, le bloc de cartes de citation est plafonné (~2 cartes, scroll interne, PR #827) — le champ et « Envoyer » restent toujours visibles clavier levé.
- #794 : la recherche du wiki smileys applique un **ET implicite** entre les termes (PR #828) — « chat noir » cherche l'intersection, plus l'union ; les opérateurs saisis (`+`/`-`) sont préservés.

### Corrections d'état (audit 05/07, PR #826)
- Réglages : les valeurs ne sont plus jamais périmées au retour sur l'écran (re-synchronisation continue, gate #788).
- Éditeurs (sujet, MP compose/réponse) : la dernière frappe n'est plus perdue à la fermeture (flush du brouillon avant fermeture, pattern #803) ; une fermeture ne peut plus rester bloquée par un stockage défaillant.
- Lecture : les couleurs `[color]` illisibles sont éclaircies/assombries a minima selon le thème (teinte préservée) — un `[#000080]` redevient lisible en sombre/AMOLED.

## `0.25.1` — `internal` (dev) — 2026-07-05

**Citation multiple : action « Tout vider »** (dernier volet de #436, PR #820).

### Éditeur & réponse rapide
- #436 : un **appui long sur le FAB « ❝N »** vide toute la sélection de citation multiple d'un coup (haptique + libellé TalkBack « Vider la sélection de citations »). Le tap court reste inchangé (ouvre l'éditeur / la feuille). Rendu en FAB « maison » (`Surface` + `combinedClickable`) pour que le geste long soit reconnu là où le compteur est visible. Clôt #436 (les volets marquage des posts et panier survivant au back étaient déjà livrés).

## `0.25.0` — `internal` (dev) — 2026-07-05

**Citations : retour du BBCode inline par défaut** (arbitrage XaTriX sur #805, PR #818) + remise en phase des docs (PR #817).

### Éditeur & réponse rapide
- #805 : les **cartes de citation deviennent une option, désactivée par défaut**. Par défaut, « Citer » et « Citer N » insèrent le BBCode `[quotemsg]` directement dans le champ — modifiable, réponses intercalées possibles, parité avec le site. Les cartes compactes (#604 lots 2-3) restent disponibles via Réglages > Édition et publication > « Citations en cartes ».
- La citation s'insère **à la fin du texte en cours, sans jamais perdre la frappe** (matérialisation à l'ouverture, annulée si la feuille est fermée) ; « Citer N » ≥ 3 ouvre toujours l'éditeur plein écran, désormais pré-rempli des blocs `[quotemsg]` fusionnés ; l'escalade feuille → plein écran et les brouillons (#405) suivent sans changement.

### Docs
- PR #817 : vitrine et specs réalignées sur le code (bêta 0.18.0, gestes Drapeaux post-#603, couverture Roborazzi réelle, ADR-001 amendé, specs v0.11.0).

## `0.24.1` — `internal` (dev) — 2026-07-03

**Fix express dogfood v220** (PR #810).

### Vue Topic
- #807 (nicko, Dintr-un lemn) : **capitalisation automatique en début de phrase dans la réponse rapide** — régression de surface du fix #237 (le champ de la feuille ne passait pas la consigne autoCap à l'IME).

## `0.24.0` — `internal` (dev) — 2026-07-03

**Phase « Vue Topic » (#604) — vague 4 « Postage », lot 4a polish** (PR #803 — cadrage + gate Codex NO-GO→fixes→GO, dogfood émulateur).

### Vue Topic
- **Réponse rapide** : le contenu de la feuille défile — plus de bouton « Envoyer » hors d'atteinte clavier ouvert sur petit écran ou en paysage.
- **Éditeur plein écran** : quitter l'éditeur (retour système) enregistre d'abord le brouillon — les dernières frappes ne sont plus perdues si on sort dans la foulée ; le retour est inerte pendant un envoi en cours (impossible d'interrompre un POST en quittant).
- **Accessibilité des cartes de citation** : TalkBack annonce le résultat des actions (« Citation de X retirée », « déplacée en position N ») et le focus est rendu à la carte voisine après un retrait.

## `0.23.0` — `internal` (dev) — 2026-07-03

**Phase « Vue Topic » (#604) — vague 4 « Postage », lot 3** (PRs #800 #801 — cadrage Codex 8 forks, gates GO-avec-réserves/GO, dogfood émulateur).

### Vue Topic
- **Citations en cartes dans l'éditeur plein écran** (mockup P3) : fini le pavé de BBCode `[quotemsg]` dans le champ — les citations s'affichent en cartes compactes au-dessus (réordonnables ↑/↓, supprimables ✕, « Tout vider » #436), le champ ne contient que votre texte, le BBCode est assemblé à l'envoi (un échec ne perd rien). La bascule réponse rapide → plein écran transporte les cartes.
- **Le panier multi-citations (« Citer N ») choisit sa surface** : 1 ou 2 citations ouvrent la réponse rapide avec les cartes pré-armées ; 3 et plus filent directement en plein écran.

## `0.22.0` — `internal` (dev) — 2026-07-03

**Phase « Vue Topic » (#604) — vague 4 « Postage », lot 2** (PRs #797 #798 — cadrage 9 forks + gates Codex gpt-5.5, dogfood émulateur).

### Vue Topic
- **Citations-cartes dans la réponse rapide** : « Citer » ouvre désormais la feuille de réponse rapide avec une carte compacte « ❝ auteur — extrait » (citer un autre message ajoute une carte) ; suppression et réordonnancement ↑/↓ par carte ; l'envoi matérialise les `[quotemsg]` dans l'ordre des cartes, le texte à la suite ; la bascule plein écran emporte les citations. Un échec d'envoi ne perd ni le texte ni les cartes.
- #790 (styx42, Dintr-un lemn) : **la bascule réponse rapide → plein écran reprend le texte automatiquement** — plus d'étape « Restaurer » sur ce chemin (la bannière reste pour les brouillons de sessions antérieures).

## `0.21.0` — `internal` (dev) — 2026-07-03

**Phase « Vue Topic » (#604) — vague 4 « Postage », lot 1** (PR #788 — cadrage + gate Codex gpt-5.5 NO-GO→fixes→GO, dogfood IME émulateur).

### Vue Topic
- **Réponse rapide en feuille** : le bouton ✎ ouvre une bottom sheet (champ texte, Envoyer, bouton plein écran) au lieu de l'éditeur complet. Le brouillon est partagé avec l'éditeur plein écran (#405) : l'escalade transfère le texte, la fermeture ne perd jamais la saisie (autosave), la réouverture reprend où on en était. Erreurs typées (anti-flood, sujet fermé, session) et « Confirmation avant publication » (#312) respectées. Citations, upload et smileys restent en plein écran (lots 2-4 à venir).

## `0.20.2` — `internal` (dev) — 2026-07-03

**Retours bêta gestes + top bar Topic** (PRs #781, #786 — gates Codex gpt-5.5, dogfoods émulateur).

### Gestes (Drapeaux & Vue Topic)
- #752 : **zone morte au départ des swipes sur les bandes de gestes système** — un swipe horizontal qui démarre dans la bande latérale (navigation gestuelle) est laissé au geste back système au lieu d'entrer en compétition avec le changement d'onglet (Drapeaux) ou de page (Topic, #282) ; fini la frontière imprévisible au ras du bord. Navigation 3 boutons : comportement strictement inchangé (insets nuls). Bornes clampées contre les insets aberrants (split-screen/foldable).

### Vue Topic
- #772 (tinc) : **titre dépliable au tap** — le titre tronqué de la top bar se déplie sur 2 lignes au tap (la barre grandit d'une ligne), re-tap ou changement de page le replie ; la pilule « page X / Y » garde sa propre cible (sélecteur de page). Annonces TalkBack dédiées (afficher/réduire + état).

## `0.20.1` — `internal` (dev) — 2026-07-03

**Quick wins vue Drapeaux — retours bêta 0.18.0** (PRs #776, #777 — cadrage + gate Codex gpt-5.5).

### Drapeaux
- #751 (thibw) : le raccourci **« +lus »** (tap sur la zone type de la pilule) fonctionne désormais sur **tous les onglets** — Lu et Favoris rejoignent Cyan et DT (même chemin persisté, même anti-double-tap ; l'indicateur œil/anneau et le suffixe du picker suivent l'état sur chaque onglet).
- #753 (Dintr-un lemn) : le texte de l'état vide « aucune catégorie avec un message non lu » pointe la bonne action — la bascule « +lus » pour revoir les sujets lus (l'ancien texte prescrivait « Masquer les catégories sans non-lu », qui ne réaffiche que les catégories vides).

## `0.20.0` — `internal` (dev) — 2026-07-03

**Phase « Vue Topic » (#604) — vague 3 : redesign de la lecture** (PRs #770, #771, #773, #774 — cadrage + gates Codex gpt-5.5, dogfoods émulateur par lot).

### Vue Topic
- #599 : **slots FAB figés** — ‹ › ✎ ❝N occupent des emplacements réservés (40 dp) dès le squelette ; plus aucun décalage quand une action apparaît/disparaît (retour antiseptiqueIncolore). Le slot multi-citation vit à l'extrême gauche du cluster.
- **Header dissous** (mockup « Lecture A ») : la carte d'en-tête du sujet disparaît — le titre et « page X / Y » vivent dans la top bar ; la **pilule « page X / Y » devient cliquable** et ouvre un sélecteur de page en feuille (préc./suiv., saisie, grille) ; « Modifier le premier message » migre dans le menu « … » du premier post (gates #148/#213 inchangées) ; le sondage devient une carte autonome en tête de liste (invariants d'index préservés). L'indicateur scrollTo disparaît (la surbrillance d'arrivée suffit).
- **Frontières de page lisibles** (retours thibw & styx42) : en fin de page intermédiaire, carte primaire « Page N terminée » + « Continuer vers la page N+1 » (tap = même navigation que le FAB ›, arrivée en haut) ; en fin de sujet, carte outline calme « Fin du sujet » — les deux états ne se confondent plus.
- #600 : **repère « Dernier message lu » traversant** (retour Colonel MythO) — règle primaire + pilule centrée sous le dernier message lu, à l'ouverture depuis un drapeau uniquement (gate sémantique testée) ; couche distincte de la surbrillance d'arrivée (#200) et de la teinte d'ancre (#104).

## `0.19.2` — `internal` (dev) — 2026-07-03

**Phase « Vue Topic » (#604) — fin de la vague 2 : #459 complet** (PR #768, gate Codex gpt-5.5).

### Éditeurs
- #459 (2/2) : **upload d'images dans les deux composeurs MP** (réponse et nouvelle conversation) — bouton Uploader, sélection multiple (max 10), un `[img]` par image dans l'ordre, compteur n/N, erreurs typées ; le vocabulaire d'upload (erreurs, progression) est promu dans `:core:ui`, partagé par toutes les surfaces d'édition.

## `0.19.1` — `internal` (dev) — 2026-07-02

**Phase « Vue Topic » (#604) — vague 2 : quick wins navigation & éditeurs** (PRs #763, #764, #765, #766 — cadrage + gates Codex gpt-5.5).

### Vue Topic
- #699 : l'en-tête d'une citation sourcée (« Citation de X ») devient cliquable (teinté couleur primaire) — tap = saut vers le message cité, avec scroll et surbrillance à l'arrivée, même s'il est sur une autre page. Chaînable de citation en citation.
- #750 : un lien de notification **email** ouvre enfin le sujet au bon message — HFR met toujours `page=1` dans ces liens ; la vraie page est résolue via le redirect serveur (mécanisme #277 de la recherche) pendant que le squelette s'affiche. Échec réseau = comportement d'avant, jamais pire.
- #762 : le titre du sujet s'affiche désormais réellement dès la première frame quand on ouvre depuis la liste Drapeaux ou un listing de forum (le cache de titres n'était alimenté qu'après un premier chargement — l'annonce 0.19.0 est maintenant vraie).

### Éditeurs
- #555 : ouvrir un éditeur (répondre, citer, **éditer un long message**) lève le clavier immédiatement, champ déjà focus — plus besoin de taper dans le champ pour commencer.
- #250 : l'onglet Wiki du sélecteur de smileys donne le focus à la recherche dès l'ouverture — on tape directement.
- #459 (1/2) : **upload d'images dans le composeur « nouveau sujet »** — bouton Uploader, sélection multiple (max 10), un `[img]` par image dans l'ordre, compteur n/N, erreurs typées ; même moteur que l'éditeur de réponse. (Reste : le composeur MP.)

## `0.19.0` — `internal` (dev) — 2026-07-02

**Phase « Vue Topic » (#604) — vague 1 : écran de chargement** (mockup « Chargement A » arbitré sur le fil DEV, cadrage + gate Codex gpt-5.5).

### Vue Topic
- Chargement d'une page : loader centré + « Chargement de la page demandée » + cartes squelettes animées (fini le spinner nu en haut à gauche). Nouvelle primitive partagée `SkeletonBox` dans `:core:ui` (le shimmer d'images #249 y délègue) ; l'animation respecte le réglage système « réduire les animations ».
- #622 : le compteur de la barre du haut affiche « Chargement… » tant que la page n'est pas parsée — plus jamais un total périmé d'une navigation précédente (« 3 / 10 » corrigé en « 3 / 20 » à l'arrivée). Le contexte du chemin d'erreur (grille des pages connues) est conservé.

## `0.18.0` — `open` (beta) — 2026-06-29

**Promotion bêta — clôture de la phase « refonte de la vue Drapeaux » (#603)** (cumul des dev 0.17.0 → 0.17.30 + audit de clôture multi-agent Claude Opus + Codex gpt-5.5).

### Vue Drapeaux — refonte
- Nouvelle top bar : conteneur gauche (drapeau du type + nom court + indicateur « +lus ») et conteneur droit (loupe rétractable + avatar rond), recherche à hauteur constante.
- Barre translucide : la liste glisse sous la barre avec un dégradé au défilement (#665).
- Loader « redface » au tirer-pour-rafraîchir, repositionné sur la rangée des pastilles, pastille ronde (#728).
- Indicateur « +lus » configurable (œil / anneau coloré), option d'avatar (bordure), glyphe drapeau/pastille (#661/#717/#718).
- Appui-long : actions rapides distinctes de la liste ; aller à une page ; poster un message (#676/#729) ; recherche sur l'onglet DT.
- Cyan sticky récupéré (#251), états vides homogénéisés (#662), couleur favori ambre (#690), scroll indépendant par onglet (#695), swipe directionnel (#660), bandes catégorie, barre du bas compacte (#666/#671).

### Corrections d'audit (clôture)
- Les réglages d'affichage GLOBAUX (glyphe, « +lus », barre de chargement) sont conservés sur les onglets DT/Super.
- Recherche : le retour système referme la loupe ; champ avec libellé et action clavier ; cible tactile et icône conformes.
- Préservation du défilement par onglet ; accessibilité (TalkBack) renforcée sur la barre, le loader, les pastilles et l'avatar ; libellés au pluriel corrects.

## `0.17.30` — `internal` (dev) — 2026-06-29

> Vue Drapeaux (#603) — **correctif du loader pull-to-refresh** : au tirer lent, la pastille du redface
> se rend de nouveau en cercle (et non en carré à coins arrondis). versionName 0.17.29 → 0.17.30.

**Drapeaux — vue (#603)**

- **Pastille du loader ronde au tirer lent (#603)** : au tirer-pour-rafraîchir LENT, le fond du redface
  s'affichait comme un carré à coins arrondis au lieu d'un cercle. Cause : le fondu d'apparition
  (`alpha < 1`) forçait une couche offscreen RECTANGULAIRE (bornes 48 dp) qui rognait l'ombre non-clippée
  de la pastille ; réduite à petite échelle, elle se lisait comme un squircle. Corrigé via
  `CompositingStrategy.ModulateAlpha` (module l'alpha par opération de dessin au lieu d'un buffer
  offscreen) — le cercle ET l'ombre douce sont préservés (un clip dur aurait coupé l'ombre). Signalé par
  thibw au dogfood ; gaté Codex (cause + fix + diff) et vérifié émulateur (pull + refreshing).

---

## `0.17.29` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — **quatre correctifs de finition** : le « petit saut » du contenu en fin de
> chargement, le flash de la liste derrière la top bar au basculement « +lus », le menu « Réglages
> d'affichage » fantôme sur DT/Super, et le fond blanc derrière un avatar transparent.
> versionName 0.17.28 → 0.17.29.

**Drapeaux — vue (#603 / #728)**

- **Fin du « petit saut » en fin de chargement (#728)** : le contenu ne descend plus puis remonte
  juste après l'animation redface, ni en tirer-pour-rafraîchir manuel ni en rechargement automatique. Le
  « content-push » reste désormais relâché pendant TOUTE la rétraction post-refresh (le facteur de
  relâche est tenu à zéro tant que la distance de pull n'est pas revenue au repos), au lieu de remonter
  vers 1 pendant que la distance redescend — c'est leur produit qui produisait le rebond. Le guard de
  rétraction est mutualisé entre le puck et le content-push (une seule source de vérité).
- **Fin du sursaut « +lus » derrière la top bar (#603)** : en basculant « +lus » (re-tap de la pill
  Cyan/DT), les sujets lus réapparaissant au-dessus de l'ancre ne flashent plus brièvement derrière la
  barre du haut translucide avant de se replacer. Le recentrage en haut utilise désormais le même
  mécanisme robuste que le rappel au top du changement d'onglet (`requestScrollToItem` réasséné sur
  quelques frames, au lieu d'un `scrollToItem` unique exécuté une frame trop tard).
- **« Réglages d'affichage » sur DT/Super (#603)** : l'entrée n'est plus proposée sur les onglets DT et
  Super (qui n'ont pas de réglages d'affichage propres). Avant, l'y taper ne faisait rien sur le moment
  puis ouvrait le panneau au prochain changement d'onglet (à la place de la liste). L'entrée reste sur
  les onglets de drapeaux (Mes sujets / Lu / Favoris).

**Compte — avatar (#718)**

- **Plus de fond blanc derrière un avatar transparent (#718)** : le badge du compte suit désormais
  TOUJOURS la couleur du container de la barre du haut (`surfaceContainerHigh`). Un avatar PNG à fond
  transparent ne laisse plus apparaître le fond `surface` quasi-blanc du thème clair.
- **Retrait de l'option « Fond transparent » (#718)** : sans effet réel dans la barre du haut (le badge
  est imbriqué dans un container, transparent == container), elle est supprimée. L'option **bordure**
  est conservée.

---

## `0.17.28` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — **loader « redface » repositionné** au niveau des pastilles du top bar, plus deux
> nouveaux réglages (barre de chargement, options avatar). versionName 0.17.27 → 0.17.28.

**Drapeaux — vue (#603 / #728 / #718)**

- **Loader repositionné (#728)** : le puck redface n'apparaît plus sous toute la barre mais sur la
  **rangée des deux containers du top bar** (centré entre eux). Au tirer-pour-rafraîchir, le contenu
  descend avec le geste ; pendant le rafraîchissement il **revient à ras** et le puck reste en place
  (anneau de progression). Correction du **gap fantôme** qui réservait un espace vide au rechargement
  automatique (un état d'animation résiduel fuyait au changement d'onglet). Vrai redface (asset vectoriel).
- **Réglage « Barre de chargement » (#728)** : option GLOBALE pour afficher ou masquer la fine barre de
  progression des rechargements automatiques. Le tirer-pour-rafraîchir reste signalé par le redface quoi
  qu'il arrive. Dans « Réglages d'affichage ».
- **Options avatar du compte (#718)** : **bordure** (liseré fin optionnel) et **fond transparent**,
  configurables dans « Réglages d'affichage ». Appliquées au badge « PP » de la barre du haut sur tous
  les écrans (préférence globale dédiée, lue par le menu compte).

---

## `0.17.27` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — **le loader « redface »**. L'amorce de rafraîchissement (tirer vers le bas)
> n'est plus l'indicateur Material standard : c'est une petite tête « redface » qui émerge sous la barre
> et **roule sur elle-même** au fil du tirage. versionName 0.17.26 → 0.17.27.

**Drapeaux — vue (#603)**

- **Loader « redface » à l'amorce du pull** : en tirant vers le bas pour rafraîchir, une tête ronde
  souriante (dessinée, pas le GIF HFR) apparaît dans une pastille sous la barre et tourne sur elle-même
  selon la distance de tirage. « Amorce seule » : dès que le rafraîchissement démarre, elle disparaît et
  la fine barre du haut reste le seul repère de chargement (pas de double indicateur). La rotation
  respecte le réglage système « réduire les animations ». S'applique aux deux listes (drapeaux + DT).

---

## `0.17.26` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — **la loupe de recherche revient sur l'onglet DT**. La barre du haut harmonisée
> avait laissé la loupe réservée aux onglets de drapeaux ; elle filtre désormais aussi la liste des
> discussions à interlocuteurs multiples (DT). versionName 0.17.25 → 0.17.26.

**Drapeaux — vue (#603)**

- **Recherche sur l'onglet DT** : la loupe « rechercher dans les drapeaux » est désormais offerte sur
  l'onglet DT (comme sur les onglets de drapeaux) et filtre les conversations par sujet. L'onglet Super
  (sans liste) reste sans loupe. Filtre client-side (sujet, insensible à la casse) avec un état « aucun
  résultat » dédié.

---

## `0.17.25` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603/#665) — **top bar « overlay translucide »** : la barre du haut ne pousse plus la
> liste vers le bas, elle la **survole**. Au scroll, le contenu **glisse sous la barre** avec un voile
> dégradé (opaque derrière la status bar pour garder l'horloge lisible, transparent au bas pour laisser
> le contenu apparaître en fondu sous le centre vide). versionName 0.17.24 → 0.17.25.

**Drapeaux — vue (#603/#665)**

- **Top bar en surimpression (#665)** : la liste remplit désormais tout l'espace et passe **sous** la
  barre du haut au lieu d'être poussée dessous. Le premier élément reste calé juste sous la barre (la
  hauteur de la barre est mesurée et réservée), mais au défilement le contenu glisse dessous.
- **Voile translucide au scroll (#665)** : quand le contenu est défilé sous la barre, un **dégradé**
  (couleur du fond de page, donc cohérent dans tous les thèmes y compris AMOLED) apparaît — opaque
  derrière la barre d'état (horloge lisible) et **transparent au bas** pour que le contenu apparaisse en
  fondu sous le centre vide. La barre reste transparente en haut de liste (rien à masquer).

---

## `0.17.24` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — suite de la review dogfood : **repère « +lus » = anneau autour du drapeau**
> (variante A choisie par XaTriX, défaut ; œil en option), **avatar du compte à la taille standard**
> (32 dp, validé Codex), et **sheet d'appui-long enrichi** (« Aller à une page » + « Poster un
> message »). versionName 0.17.23 → 0.17.24.

**Drapeaux — vue (#603)**

- **Repère « +lus » sur l'icône (#661/#603)** : quand les sujets lus sont visibles, le glyphe de type
  (drapeau ou pastille) est entouré d'un **anneau** coloré (nouveau défaut). L'**œil** à droite du nom
  reste disponible en option dans les réglages d'affichage. Plus de double repère.
- **Avatar du compte — taille standard (32 dp)** : l'avatar de la barre du haut passe de 40 dp (taille
  d'avatar de *liste*) à 32 dp, la taille usuelle d'un avatar de *barre du haut* (validé Codex). Cible
  tactile 48 dp inchangée.
- **Sheet d'appui-long — « Aller à une page » (#15)** : nouvelle action qui ouvre un champ de saisie
  (validé 1…N) pour ouvrir le sujet directement à une page précise. Masquée pour les sujets d'une page.
- **Sheet d'appui-long — « Poster un message » (#15)** : nouvelle action qui ouvre directement
  l'éditeur de réponse du sujet (sur la dernière page, là où HFR ajoute le message).

---

## `0.17.23` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — passe de review top bar dogfood : **noms d'onglets courts** (Cyan/Lurk/Fav/DT/
> Super), **option drapeau/pastille** du glyphe de type (#717), **avatar du compte** qui épouse le
> container (fond du container, **sans bordure**), **« Réglages d'affichage » disponible sur tous les
> onglets** (dont DT/Super), et **tap propre** des deux zones du container gauche. versionName
> 0.17.22 → 0.17.23.

**Drapeaux — vue (#603)**

- **Option drapeau / pastille (#603/#665, #717)** : nouveau réglage « Repère du type actif » — le glyphe
  du type dans la barre du haut est l'icône drapeau colorée (défaut) ou une pastille colorée.
- **Noms d'onglets courts** : le container gauche affiche Cyan / Lurk / Fav / DT / Super.
- **Avatar du compte** : le fond suit le container (`surfaceContainerHigh`) et la bordure est retirée —
  le PP rond s'intègre proprement ; l'identité reste lisible via l'initiale teintée. Options bordure /
  fond transparent à venir (#718, placeholders grisés dans le sheet).
- **« Réglages d'affichage » sur tous les onglets** : l'entrée est désormais disponible sur DT et Super
  (qui n'ont pas la loupe) — les réglages sont globaux et le menu n'existe que pour un compte connecté.
- **Tap propre du container gauche** : chaque zone (drapeau / type) épouse son extrémité de pilule au
  tap (demi-pilules), fini le rectangle arrondi flottant.

---

## `0.17.22` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — finitions top bar + correctifs dogfood : **avatar du compte rond**, **ombre
> moche au swipe supprimée**, **container gauche en 2 zones** (drapeau → menu, type + « +lus » → toggle
> direct), **menu rapide restylé** (drapeaux trailing à droite, « Afficher les lus » retiré),
> **recherche sans saut de hauteur**, **indicateur « +lus » configurable** œil/anneau (#661), et
> **sheet d'appui-long v2** (rangée rapide ≠ liste, #676). versionName 0.17.21 → 0.17.22.

**Drapeaux — vue (#603)**

- **Container gauche — 2 zones (#603/#665)** : le sélecteur devient deux zones distinctes — le drapeau
  de la section (drapal-icône coloré) ouvre le menu rapide ; le nom du type + l'indicateur « +lus »
  bascule directement le « +lus » au tap (Cyan/DT). L'indicateur « +lus » vit dans la zone type.
- **Menu rapide restylé (#603)** : libellé à gauche, drapeau coloré du type à droite (trailing) ;
  l'entrée « Afficher les lus » disparaît (le toggle est désormais direct sur la zone type).
- **Recherche — hauteur constante (#603)** : ouvrir la recherche ne décale plus le contenu (le champ
  adopte la hauteur des containers).
- **Avatar du compte rond** : le badge compte (PP) devient circulaire (M3) pour épouser son container ;
  les avatars d'en-tête de posts gardent le carré arrondi.
- **Ombre de swipe supprimée (#660)** : le cadre gris d'élévation parasite autour du panneau pendant le
  balayage entre onglets est retiré (le geste reste signalé par le retour haptique + la transition).
- **Indicateur « +lus » configurable (#661)** : choix œil (défaut) ou anneau coloré, dans les réglages
  d'affichage Drapeaux.
- **Sheet d'appui-long v2 (#676)** : la rangée rapide (Ouvrir · 1er non-lu · Super favori · Partager)
  et la liste (Ouvrir à la dernière page · Copier le lien · Ouvrir dans le navigateur · Retirer) portent
  désormais des actions distinctes (fini le doublon).

---

## `0.17.21` — `internal` (dev) — 2026-06-28

> Vue Drapeaux (#603) — **refonte de la top bar** (nouveau look : deux containers arrondis, loupe
> rétractable, avatar, indicateur « +lus » en forme d'œil) + animation de **swipe entre onglets « slide
> au commit »** (#660) + **sheet d'appui-long** avec liste d'actions complète ET rangée d'accès rapide
> (#676). versionName 0.17.20 → 0.17.21.

**Drapeaux — vue (#603)**

- **Refonte de la top bar — nouveau look (#603)** : la barre plate laisse place à deux « containers »
  arrondis flottant sur un centre transparent. À gauche, le sélecteur d'onglet (drapal de la section +
  nom court + indicateur « +lus » en forme d'œil quand les lus sont affichés). À droite, une loupe
  rétractable (qui s'ouvre en champ de recherche plein largeur) et l'avatar du compte. Les bascules
  existantes (onglets, +lus, réglages d'affichage) restent dans le menu du container gauche. *(Suite à
  venir : défilement du contenu sous la barre #665, loader « redface », option D/C de l'indicateur.)*
- **Swipe entre onglets « slide au commit » (#660)** : le changement d'onglet par balayage glisse
  désormais directionnellement du bon côté (Material Shared Axis X) au lieu d'apparaître brusquement.
- **Sheet d'appui-long (#676)** : la liste verticale d'actions (libellés complets) revient et coexiste
  avec la rangée d'accès rapide en icônes ; « Retirer » reste en dernier, toujours confirmé par dialog.

## `0.17.20` — `internal` (dev) — 2026-06-27

> Vue Drapeaux (#603) — le texte explicatif du balayage de la section DT quitte la vue (où il flottait
> en bas de liste) pour rejoindre la **description du réglage « Section DT »**, au niveau de son
> activation (#662, demande XaTriX). La vue DT reste épurée. versionName 0.17.19 → 0.17.20.

**Drapeaux — vue (#603)**

- **Texte explicatif DT déplacé vers les réglages (#662)** : l'avertissement « seule la première page de
  la boîte de réception est balayée » n'apparaît plus dans la vue DT (ni en pied de liste, ni en
  sous-texte d'état vide). Il est désormais porté par la **description du réglage « Section DT »**
  (Réglages › Drapeaux), là où l'utilisateur active la section — l'explication est lue au bon moment et
  la vue reste épurée.

## `0.17.19` — `internal` (dev) — 2026-06-27

> Vue Drapeaux (#603) — finitions sur retour dogfood : les états vides visuels (#662) couvrent
> maintenant aussi les onglets **DT** et **Super Favoris**, le smiley de l'option humoristique n'est
> plus pixelisé (#662), et le drapeau favori ambre (#690) gagne une **option de contour** (fin liseré
> sombre) pour mieux ressortir sur fond clair. versionName 0.17.18 → 0.17.19.

**Drapeaux — vue (#603)**

- **États vides DT & Super Favoris (#662)** : les onglets DT et Super Favoris affichent désormais le
  même état vide visuel (icône/smiley + titre + sous-texte) que les onglets de drapeaux, au lieu d'un
  simple libellé. L'état DT « aucune non-lue » conserve le caveat de balayage (seules les conversations
  récentes sont listées) en sous-texte.
- **Smiley de l'option humoristique dé-pixelisé (#662)** : le smiley perso `[:eric le looser]` est une
  petite photo (~47×50 px), pas du pixel-art — l'agrandissement « net » précédent le transformait en
  blocs. Filtrage lissé + taille réduite : il reste propre.
- **Option « Contour du marqueur » (#690)** : nouvelle bascule GLOBALE (réglages d'affichage des
  Drapeaux, désactivée par défaut) qui dessine un fin liseré sombre (0,5 dp) autour de l'indicateur de
  couleur, pour mieux détacher l'ambre des favoris sur fond clair. S'applique à toutes les formes de
  marqueur (barre / pastille / point) et à tous les onglets.

## `0.17.18` — `internal` (dev) — 2026-06-27

> Vue Drapeaux (#603) : les onglets vides ont enfin un vrai état visuel (icône + message par onglet,
> avec une option « états vides humoristiques » qui affiche un smiley perso HFR, #662), et les drapeaux
> cyan des sujets épinglés des catégories sans sous-catégorie (ex. « IA ») réapparaissent (#251).
> versionName 0.17.17 → 0.17.18.

**Drapeaux — vue (#603)**

- **États vides visuels (#662)** : un onglet sans élément n'affiche plus un simple libellé mais un état
  vide visuel — icône fine + titre + sous-texte propre à l'onglet (Mes sujets / Lu / Favoris). Une
  nouvelle option **« États vides humoristiques »** (Réglages › Drapeaux, désactivée par défaut)
  remplace l'icône par un smiley perso HFR. Le texte porte tout le sens, donc TalkBack lit le même état
  dans les deux cas. La vue groupée filtrée (« masquer les catégories sans non-lu ») garde un message
  factuel distinct.
- **Drapeaux cyan des sujets épinglés récupérés (#251)** : les endpoints REST des drapeaux excluaient
  les sujets épinglés (sticky) flaggés dans les catégories **sans sous-catégorie** (ex. cat « IA ») —
  un drapeau cyan posé sur le sujet épinglé des règles restait invisible. Un supplément REST-only
  (lecture de la première page de `topics/last`, sans repli HTML) les récupère et les fusionne dans la
  liste. Best-effort : un échec de cette lecture ne fait pas échouer tout l'écran.

## `0.17.17` — `internal` (dev) — 2026-06-27

> Vue Drapeaux (#603) : la couleur du drapeau favori passe à un jaune-ambre lisible sur clair comme sur
> sombre (#690, choix « D » des testeurs), et le défilement de chaque onglet (Mes sujets / Lu / Favoris)
> est désormais indépendant — il ne « bave » plus d'un onglet à l'autre (#695). versionName 0.17.16 → 0.17.17.

**Drapeaux — vue (#603)**

- **Couleur du favori lisible sur fond clair (#690)** : le jaune Material Yellow 600 (`#FDD835`) se
  noyait sur le thème clair (fond crème, contraste ~1,2:1). Il passe à Material Amber 600 (`#FFB300`),
  un jaune-ambre qui tient sur clair comme sur sombre (choix « D » des testeurs CharLee/thibw/XaTriX),
  sans aller jusqu'à l'ambre `#F9A825` jugé trop loin de l'identité du favori. Cyan et rouge inchangés.
- **Défilement indépendant par onglet (#695)** : les onglets Mes sujets / Lu / Favoris partageaient un
  seul état de liste, donc la position de défilement « bavait » d'un onglet à l'autre. Chaque onglet a
  désormais son propre état de liste (préservé au changement d'onglet et à la rotation).

## `0.17.16` — `internal` (dev) — 2026-06-27

> Vue Drapeaux (#603), correctifs : les sujets cyan d'une catégorie ajoutée récemment sur HFR (ex. « IA »)
> réapparaissent — le fan-out interrogeait une liste de catégories périmée et sautait silencieusement la
> nouvelle catégorie (#251) ; et la couleur du drapeau favori repasse au jaune, elle virait au vert (#690).
> Build dev ; versionCode au dispatch. versionName 0.17.15 → 0.17.16.

**Drapeaux — vue (#603)**

- **Cyan manquant d'une catégorie récente corrigé (#251)** : pour aller chercher les drapeaux, l'app
  énumère les catégories du forum ; elle lisait la liste mise en cache 24 h via son émission « périmée
  d'abord », donc une catégorie créée sur HFR après le dernier rafraîchissement (ex. cat 32 « IA ») n'était
  jamais interrogée et ses sujets cyan restaient invisibles. Le fan-out demande désormais une liste de
  catégories fraîche quand le cache est périmé (`ForumRepository.getCategories(forceRefreshIfStale = true)`,
  une lecture en cache si frais, un appel réseau si périmé/froid).
- **Couleur du drapeau favori repassée au jaune (#690)** : le marqueur favori était en Material Lime 500,
  qui se lit vert à l'écran ; il passe en Material Yellow 600 (`#FDD835`), franchement jaune, sans dériver
  vers l'ambre. Cyan et rouge inchangés.

## `0.17.15` — `internal` (dev) — 2026-06-27

> Refonte de la vue Drapeaux (#603), polish : la barre du bas en mode icônes seules passe à 52 dp avec
> un item dédié (14 dp d'air autour de l'icône, indicateur M3 actif, nom accessible des onglets corrigé),
> et l'espacement des bandes de catégorie passe du preset D au preset C (les bandes paraissaient trop
> serrées) (#671). Build dev ; versionCode au dispatch. versionName 0.17.14 → 0.17.15.

**Drapeaux — vue (#603)**

- **Barre du bas icônes seules à 52 dp (suite #666)** : quand les libellés sont masqués, la barre du bas
  passe de 56 à 52 dp, construite à partir d'un item dédié — l'icône 24 dp est centrée avec 14 dp d'air
  au-dessus et en dessous, l'indicateur actif (pilule M3) revient derrière l'icône sélectionnée. Corrige
  au passage un manque d'accessibilité : en mode icônes seules les onglets n'avaient pas de nom annoncé.
- **Espacement des bandes de catégorie — preset C (#671)** : l'air autour des bandes de catégorie passe
  du preset D (trop serré pour la hauteur des bandes) au preset C ; un réglage utilisateur dédié arrivera
  plus tard.

## `0.17.14` — `internal` (dev) — 2026-06-27

> Refonte de la vue Drapeaux (#603), lot suivant : le menu déroulant du sélecteur d'onglet expose
> maintenant le « +lus » et les réglages d'affichage (#661), le re-tap de l'onglet Drapeaux depuis un
> sous-écran ramène à la racine (#679), et le sheet d'appui-long sur un sujet passe à 5 actions sur une
> ligne (#676). Build dev ; versionCode au dispatch. versionName 0.17.13 → 0.17.14.

**Drapeaux — vue (#603)**

- **Menu du sélecteur d'onglet plus découvrable (#661)** : le menu déroulant (icône drapeau colorée en
  haut à gauche) propose désormais, en plus du changement d'onglet, l'entrée contextuelle « Afficher /
  Masquer les lus » (sur Cyan et DT) et « Réglages d'affichage » — deux actions auparavant atteignables
  seulement par un re-tap d'onglet ou de la barre du bas.
- **Re-tap de l'onglet Drapeaux = retour à la racine (#679)** : re-taper l'onglet Drapeaux alors qu'on
  est dans un sous-écran (un sujet ouvert depuis un drapeau) revient à la liste des drapeaux, au lieu
  d'armer par erreur le menu de configuration rapide.
- **Sheet d'appui-long — 5 actions sur une ligne (#676, mockup F2)** : l'appui long sur un sujet présente
  ses actions (Ouvrir / Super favori / Copier / Navigateur / Retirer) sur une seule rangée de boutons au
  lieu d'une liste verticale ; « Retirer » reste rouge et passe toujours par la confirmation. Le
  sous-titre redondant sous le titre du sujet a été retiré (l'info reste dans le bloc métadonnées).

## `0.17.13` — `internal` (dev) — 2026-06-27

> Suite #666 : la barre du bas raccourcit réellement (icônes seules, 56 dp) quand les libellés sont
> masqués — auparavant elle conservait la hauteur réservée aux libellés. Build dev ; versionCode au
> dispatch. versionName 0.17.12 → 0.17.13.

**Réglages / Navigation (#666)**

- **Barre du bas plus courte sans les libellés** : libellés masqués (*Réglages > Affichage > Barre de
  navigation*), la barre du bas passe à 56 dp en icônes seules au lieu de garder les ~64 dp réservés à la
  rangée de libellés (téléphone uniquement ; rail / tiroir des écrans larges inchangés). Cible tactile
  conservée ≥ 48 dp. Le « short bar » expressif Material 3 étant `internal` dans le BOM courant, la barre
  compacte est bâtie sur le `NavigationBarItem` stable. Signalé par XaTriX.

## `0.17.12` — `internal` (dev) — 2026-06-26

> Fix dogfood : l'amorce du pull-to-refresh ne « repop » plus en fin de chargement. Build dev ;
> versionCode au dispatch. versionName 0.17.11 → 0.17.12.

**Drapeaux (#603)**

- **Amorce pull-to-refresh — plus de re-pop en fin de load** : à la fin d'un rechargement, l'indicateur
  rond réapparaissait brièvement (puis disparaissait) pendant que la liste revenait à sa position de
  repos — `isRefreshing` repassait à `false` alors que la distance de tirage n'était pas encore revenue
  à 0. Une garde « settling » masque l'amorce jusqu'au retour complet au repos (signalé par XaTriX).

## `0.17.11` — `internal` (dev) — 2026-06-26

> Polish dogfood : retour visuel au tirage du swipe-to-refresh. Build dev ; versionCode au dispatch.
> versionName 0.17.10 → 0.17.11.

**Drapeaux (#603)**

- **Amorce au pull-to-refresh** : tirer la liste vers le bas affiche de nouveau un indicateur pendant
  le geste (il avait été retiré), puis il s'efface dès que le rechargement démarre — la fine barre du
  haut prend le relais (plus de double indicateur). Vaut pour les onglets de drapeaux et la liste DT.

## `0.17.10` — `internal` (dev) — 2026-06-26

> Lot 1 dogfood (4 sur 4) : le réglage des libellés de la barre du bas, dernier item du lot. Build dev ;
> versionCode au dispatch. versionName 0.17.9 → 0.17.10.

**Réglages / Navigation (#666)**

- **Afficher / masquer les libellés de la barre du bas** : nouveau réglage dans *Réglages > Affichage >
  Barre de navigation* (activé par défaut). Désactivé, la barre du bas n'affiche que les icônes (plus
  compacte). Réglage global, persistant.

## `0.17.9` — `internal` (dev) — 2026-06-26

> Lot 1 dogfood (3 sur 4) : bugs swipe + navigation et icône de catégorie. Le réglage des libellés de
> la barre du bas (#666) suivra dans une release dédiée. Build dev ; versionCode au dispatch.
> versionName 0.17.8 → 0.17.9.

**Drapeaux (#603)**

- **Swipe entre onglets — animation du bon côté (#660)** : au changement d'onglet validé, le nouvel
  onglet apparaît centré net au lieu de glisser depuis le mauvais côté.
- **Swipe cyclique (#663)** : balayer au-delà du dernier onglet revient au premier (et inversement).
- **Icône Intelligence Artificielle (#664)** : la catégorie IA (nouvelle sur HFR) a enfin son icône au
  lieu du glyphe générique.

**Navigation (#667)**

- **Retour depuis un onglet secondaire** : à la racine des Réglages (ou Forum/Recherche/Messages), le
  bouton retour revient à l'onglet précédent au lieu de fermer l'application.

## `0.17.8` — `internal` (dev) — 2026-06-26

> Polish dogfood : resserrage de l'espacement entre la barre de recherche et les bandes de catégorie
> (preset D « minimal » validé par XaTriX sur l'inspecteur d'espacement). Build dev ; versionCode au
> dispatch. versionName 0.17.7 → 0.17.8.

**Drapeaux (#603)**

- **Espacement resserré (vue groupée)** : l'air au-dessus et autour des bandes de catégorie était jugé
  trop grand. Slot de la barre de chargement 10 → 4 dp, marge basse de la barre de recherche 8 → 2 dp
  (la bande remonte sous la barre), hauteur min des bandes 44 → 34 dp et padding vertical → 4 dp.
  La cible tactile de la bande passe sous la reco Material 48 dp / WCAG AAA 44 dp mais reste au-dessus
  du plancher WCAG 2.2 AA (24 dp) — compromis densité assumé.

## `0.17.7` — `internal` (dev) — 2026-06-26

> Hotfix dogfood : le menu de réglages rapides ne défilait pas, rendant le sélecteur de style de bande
> inaccessible. Build dev ; versionCode au dispatch. versionName 0.17.6 → 0.17.7.

**Drapeaux (#603, #673)**

- **Menu de réglages rapides scrollable** : le bottom sheet a grossi (marqueur, titre 1 ligne, style de
  bande…) et débordait sans défiler — les options du bas (dont le style de bande) étaient injoignables sur
  petits écrans. Le contenu défile désormais ; tout reste accessible.

## `0.17.6` — `internal` (dev) — 2026-06-26

> Suite dogfood : la barre de chargement décalait le contenu, et les états vides étaient hétérogènes.
> Build dev ; versionCode au dispatch. versionName 0.17.5 → 0.17.6.

**Drapeaux (#603)**

- **Barre de chargement** (#671) : un slot de hauteur fixe lui est réservé sous la barre du haut — elle
  n'apparaît/disparaît plus en décalant la liste et les bandes de catégorie. Le slot fait aussi office
  d'espace dédié entre la search bar et le début de la liste.
- **Messages d'état vide homogénéisés** (#662) : forme courte uniforme (« Aucun(e)… », sans point ni
  instruction collée) ; le « Aucune conversation non lue » du DT ne traîne plus son rappel « re-tape ».

## `0.17.5` — `internal` (dev) — 2026-06-26

> Suite dogfood v184 : le rond de chargement persistait sur le pull-to-refresh manuel. Build dev ;
> versionCode au dispatch. versionName 0.17.4 → 0.17.5. Codex GO + CI verte.

**Drapeaux (#603, #659)**

- **Plus aucun rond de chargement** : l'indicateur circulaire du pull-to-refresh est retiré
  (`indicator = {}`) sur les onglets drapeaux ET DT. La fine barre de progression en haut devient
  l'unique repère de chargement (manuel, auto et initial). Le geste swipe-down déclenche toujours le
  refresh. Retrait du flag `isManualRefreshing` introduit en v184 (devenu inutile).

## `0.17.4` — `internal` (dev) — 2026-06-26

> Vue Drapeaux : 4 styles de bande de catégorie au choix (#656) + correction du rond de chargement à
> l'auto-refresh (#657). Build dev ; versionCode au dispatch. versionName 0.17.3 → 0.17.4. Codex GO + CI verte.

**Drapeaux (#603)**

- **Styles de bande de catégorie au choix** (menu d'affichage, vue groupée) : Sobre (défaut, = l'actuel),
  Bloc (bloc tonal), Accent (barre latérale + icône teintée), Puce (nom dans une pastille). Réglage global ;
  les 2 styles d'origine transparents sont opacifiés pour le sticky header.
- **Bug corrigé** : le rond de chargement (pull-to-refresh) ne s'affiche plus pendant l'auto-refresh
  (atterrissage / changement d'onglet / retour) — seule la barre du haut reste. Le geste manuel garde son rond.

## `0.17.3` — `internal` (dev) — 2026-06-25

> Suite du dogfood 0.17.2 (#653 + #654). Build dev ; versionCode au dispatch. versionName 0.17.2 → 0.17.3.

**Finitions (#653, #654)**

- **Option « titre sur une seule ligne »** dans le menu d'affichage des Drapeaux : tronque le titre des
  sujets à une ligne au lieu de deux (réglage global, via CompositionLocal).
- **Bande de catégorie allégée** (vue groupée « par catégorie ») : sous-titre minimal (nom en capitales
  espacées + filet fin) au lieu du bloc plein `surfaceVariant`, moins lourd.

## `0.17.2` — `internal` (dev) — 2026-06-25

> Polish #2 de la vue Drapeaux suite au dogfood 0.17.1 (#651). Build dev ; versionCode au dispatch.
> versionName 0.17.1 → 0.17.2. Bug du menu rapide analysé + corrigé (Codex). CI verte.

**Finitions (#651)**

- **Couleurs retonées** (les valeurs HFR pures étaient criardes) : favori → vert-lime `#CDDC39`, DT →
  fuchsia `#D500F9`, cyan/rouge reviennent aux tons Material.
- **Icône du menu Drapeaux** : retour au glyphe propre (le drapeau pixel-art était trop brut à petite taille).
- **Bug corrigé** : le menu de config rapide s'ouvrait tout seul en changeant de catégorie ou au retour.
- « +lus » : libellé nettoyé (plus de point médian).
- Liseré discret autour de l'avatar du compte.
- Bandes de catégorie un peu moins hautes ; flèche « › » de catégorie à la bonne taille (était un bug).

## `0.17.1` — `internal` (dev) — 2026-06-25

> Polish de la vue Drapeaux suite au dogfood (#648). Build dev ; versionCode alloué au dispatch.
> versionName bumpé 0.17.0 → 0.17.1 (évite le doublon F-Droid). Codex GO + CI verte.

**Finitions (#648)**

- **Couleurs des drapeaux** alignées sur les valeurs exactes des gifs HFR : cyan `#00FFFF`, rouge
  `#FF0000`, favori `#F0F83F` (jaune-lime) ; nouveau **DT en fuchsia** `#FF00FF`.
- **Icône d'ouverture du menu Drapeaux** = le drapeau de Redface 2 (vectorisé, teinté selon l'onglet),
  à la place du glyphe Material générique.
- **Barre de recherche sur une seule ligne** (plus de retour à la ligne du contenu).
- **Suppression du bouton d'affichage en double** dans l'app bar — le menu s'ouvre en re-tapant
  l'onglet Drapeaux de la barre du bas.
- **Suppression du rond de chargement central** : la barre fine du haut couvre désormais aussi le
  chargement initial.
- La **barre de couleur épouse la hauteur de la ligne** (correct sur les titres 2 lignes).

## `0.17.0` — `internal` (dev) — 2026-06-25

> Build dev (Play internal « Redface 2 dev » + F-Droid .dev) ; versionCode alloué au dispatch par le
> registre de tags git. versionName bumpé 0.16.0 → 0.17.0. Chaque PR du payload : review Codex + CI
> verte ; passe de revue à 4 agents (opus) sur le code mergé, corrections appliquées (#643).

Refonte complète de la **vue Drapeaux** (#603, ADR-017) — 8 PRs.

**Nouveautés**

- **Search app bar** « façon Réglages » : icône drapeau de l'onglet courant (sélecteur d'onglet),
  barre de recherche (filtre client des drapeaux), photo de profil. Remplace l'ancien header + tab row.
- **Liste refondue** : marqueur gauche configurable (**barre de couleur** par défaut, pastille à icône,
  ou point), pastille « pages à lire », en-têtes de catégorie à vraies icônes Material Symbols.
- **Appui long** sur un drapeau → **bottom sheet** : métadonnées du sujet (créateur, dernier répondant,
  dates, position, réponses, catégorie), actions (ouvrir, copier le lien, navigateur), **super-favori
  local**, retrait. Plus de choix de couleur.
- **Barre de progression** M3 fine sous l'app bar pendant un chargement (manuel **et** auto-refresh).
- **Menu de config rapide** : re-tap de l'onglet Drapeaux de la barre du bas → réglages d'affichage
  (groupement, masquer lus, non-lus, **forme du marqueur**).

**Interne** : modèle de présentation pur testé en `:core:model` ; ADR-017 + rapport des 4 spikes.

**Différé** : hide-on-scroll, onglets configurables, densités avancées, indicateur « cité » (aucune
source serveur — jamais simulé).

## v179 — `0.16.0` — `open` (beta) — 2026-06-21

> Shippé en bêta (Play open testing « committed » + F-Droid .beta) le 2026-06-21, tag `app-v179`.
> versionName bumpé 0.15.0 → 0.16.0 ; versionCode alloué au dispatch par le registre de tags git.

Bêta **0.16.0** (open testing) — consolide le dev depuis la bêta 0.15.0 (`v174`). Double review
pré-promotion (Codex + workflow multi-flavors, 2 passes, **3 MAJOR corrigés**) ; chaque PR du payload
déjà review Codex + CI verte.

**Nouveautés**

- **DT/MultiMP — gérer les membres** (#606/#612) : le créateur d'un DT peut ajouter ou retirer des
  destinataires (via un post de réponse) ; HFR ajoute un message « Modération ». Correctif décisif :
  le formulaire de gestion est lu depuis `message.php` (le champ `newdest` y vit, pas dans la réponse
  rapide) — sans quoi l'éditeur ne s'affichait jamais.
- **DT — liste des participants** (#612) : bouton « Participants » → feuille déroulante (gère les gros DT).
- **Synchro position de lecture DT** (#597) : remontée auto vers le stockage MPStorage partagé (DTCloud),
  **en option, désactivée par défaut**, et seulement pour les DT déjà suivis (jamais de création/pollution).
- **Emojis qui tronquaient un message** (#594/#114) : caractères non supportés par HFR retirés avant l'envoi.

**Coulisses**

- Réconciliation lu/non-lu des MP avec le serveur (#531, cas re-unread, best-effort, gaté par date).
- Factorisation de la liste de posts topic↔MP (#351 c1/c2/c3 : `PostCardShell`/`PostListScaffold`/
  `PostIdentityHeader` partagés) — aucun changement visible (diff topic strictement nul).

---

## v174 — `0.15.0` — `open` (beta) — 2026-06-19

Bêta **0.15.0** (open testing) — promotion de tout le dev depuis la bêta 0.14.0 (`v156`). Revue 4-flavor
(code-review + review holistique + superpowers + Codex, seuil >60, **0 bloquant** ; 2 MAJOR corrigés avant le cut).

**Nouveautés**
- **Blacklist** #509 : masquer un utilisateur (menu post + fiche profil + sous-page Réglages) ; ses posts se replient derrière un placeholder.
- **Plein écran** #518 : masquer la barre de navigation Android (révélation auto + bouton retour flottant, options).
- **Pseudo créateur en doré** #221.
- **Couleur d'accent « Rouge REDFACE1 »** (option) + retrait du fond de mise en avant (TU 2788511).
- **Onglet DT** : MultiMP + reprise de lecture MPStorage, **non-lus par défaut**, clic sur l'onglet pour « +lus », pull-to-refresh (#6/#509).
- **Recherche dans le sujet** : mot/pseudo + filtre, **saut précédent/suivant** entre résultats, couvre **tout le sujet**, « aucun résultat » distinct d'une erreur (#546).
- Ligne « Suite à la page suivante » (#110), retour en haut au changement d'onglet Drapeaux (#106), option « Afficher l'ascenseur » (#105).

**Correctifs (revue bêta)**
- Blacklist : repli **en direct** désormais sur tous les chemins (refresh / réponse / suppression / recherche) #509.
- Badge MP décrémenté à l'ouverture d'un MultiMP via l'onglet DT.
- Recherche : ne renvoie plus « aucun résultat » à tort sur un gros sujet (#586).

**Coulisses**
- Mécanique d'écriture MPStorage v1 (RMW guardée) ; contrat d'écriture validé en live (activation opt-in à venir, #6/#577).

Consolide les builds dev `v157–v173`. La bêta précédente `0.14.0`/`v156` reste documentée ci-dessous.

## v157–v170 — `0.14.0` — `internal` (dev) — 2026-06-18/19

Builds **dev (internal)** accumulés depuis la bêta 0.14.0 (`v156`), même `versionName 0.14.0`. À consolider en
entrées propres + bump `versionName 0.15.0` avant la prochaine bêta (le `CHANGELOG.md` racine reste à mettre à jour aussi).

- **v157** — Blacklist locale #509 (masquer un utilisateur : menu post + fiche profil + sous-page Réglages).
- **v158** — Plein écran #518 : masquer la barre de navigation Android (+ pseudo créateur doré #221).
- **v159** — Bouton retour flottant en plein écran (#518, option).
- **v160** — Fix régression images postées seules (#568).
- **v161** — Révélation auto de la barre système en plein écran (#518, multi-comportements).
- **v162** — Fix jitter de la barre système à l'arrivée en bas (#518).
- **v163** — Option couleur d'accent « Rouge REDFACE1 » + retrait du fond de mise en avant (TU 2788511).
- **v164** — Liseré post ciblé + pastille « Ajouté à la citation » hors du bandeau d'identité.
- **v165** — Post ciblé : bandeau d'identité teinté `tertiaryContainer` (remplace le liseré).
- **v166** — Onglet « DT » des Drapeaux = liste des MultiMP + reprise de lecture MPStorage (#509/#6).
- **v167** — Recherche intra-topic (`transsearch.php`) : mot/pseudo + filtre serveur (#150 suite ; next/prev différé).
- **v168** — Ligne « Suite à la page suivante » sur les pages intermédiaires d'un sujet (#110).
- **v169** — Changement d'onglet Drapeaux → retour en haut de liste (#106).
- **v170** — Option « Afficher l'ascenseur » (#105).
- *(mergé sans release)* — Mécanique d'écriture MPStorage v1 RMW **guardée** (POST différé, non observé live, #6/#577).

## v156 — `0.14.0` — 2026-06-17

**Statut** : `open` (track open testing — canal beta, Play Edit committed) + F-Droid `.beta`
**Commit** : promotion dev→main #558 (`182c4fb`), tag `app-v156`.
**Contenu depuis la 0.13.0/v145** : dogfoodé sur le canal dev (v146 → v155).

> `0.13.0` ayant déjà été shippé en bêta (v145), le `versionName` est bumpé en `0.14.0` (la garde CI refuse deux bêtas au même `versionName`). Le `versionCode` final a été alloué au dispatch par le registre de tags : **`app-v156`** (le candidat noté ici était `v155`, décalé d'un cran par les builds dev intercalés).

### Added
- **Signatures des posts** (#330) : la signature de l'auteur s'affiche sous le message, derrière un réglage dédié.
- **Avatar du compte connecté** (#479) dans la barre du haut des listes.
- **Repli des longues citations** (#332) : les citations de premier niveau trop longues sont repliées avec un bouton afficher/masquer, désactivable dans les réglages.
- **Barre d'actions des drapeaux** (#411) : masquée au défilement vers le bas, révélée vers le haut, et toujours visible en bas de page.

### Changed
- **Ligne de métadonnées des sujets unifiée** (#376) entre Drapeaux, Catégorie et Recherche.
- **Boutons-icônes harmonisés** (#360) sur des vecteurs en trait, flèche retour plus épaisse.
- **FAB « nouveau sujet »** (#482) réduit en icône seule au défilement.

### Fixed
- **Drapeaux — recalage en haut** (#546) après le rafraîchissement auto à l'atterrissage : les sujets fraîchement remontés sont visibles sans scroller, le retour depuis un topic garde la position.
- **Séparateur de signature** (#550) : la ligne web « --------------- » n'est plus rendue dans les signatures sous les posts.
- **Couleurs de signature** (#553) : les signatures sont rendues dans la couleur neutre du thème ; les couleurs `[color]` de l'auteur (pensées pour le fond blanc web, illisibles sur le thème de l'app) sont ignorées.

### Perf
- **Images de bloc** (#249) : encart réservé + shimmer + crossfade, anti-saut de mise en page (CLS).

### Infra
- **Overlay de debug** (#445, canal dev) : contours des composants Compose pour le diagnostic de layout.

---

## v145 — `0.13.0` — 2026-06-16

**Statut** : `open` (track open testing — canal beta, Play Edit committed) + F-Droid `.beta`
**Commit** : promotion dev→main (cf. PR de promotion)
**Contenu depuis la 0.12.0/v136** : dogfoodé sur le canal dev (v137 → v144).

> `0.12.0` ayant déjà été shippé en bêta (v136), le `versionName` est bumpé en `0.13.0` (la garde CI refuse deux bêtas au même `versionName`).

### Added
- **Refonte complète des Réglages** (#494) : Réglages devient un 5ᵉ onglet dédié de la barre du bas (icônes Material Symbols, #511), racine « catégories d'abord » avec sous-vues par catégorie (#512), recherche dans les réglages avec résultats à plat + fil d'Ariane (#514), catalogue « À venir » et microcopie épurée (#517).
- **Transitions de navigation** (#513) : shared-axis X + fade-through entre onglets, geste de retour prédictif.
- **Search app bar translucide** (#519, #515) : barre de recherche qui se fond au scroll, bottom bar plus compacte (~64 dp), contenu qui passe sous la barre.
- **Marqueur « · édité »** (#483) inline sur la ligne de date d'un post édité.
- **Persistance de l'état des sondages** (#465) : déplié/replié conservé en changeant de page dans un sujet.

### Fixed
- **Drapeaux — rafraîchissement auto** (#501) au changement d'onglet et à la reprise de l'app.
- **Badge MP non-lus** (#452, #453) : l'option de désactivation coupe réellement le réseau ; rafraîchissement à la lecture.
- **Lignes vides parasites** (#466) : paragraphes séparés par des `&nbsp;` orphelins correctement rendus.
- **En-tête de post stable** (#476) quand on coche/décoche pour le multiquote.
- **Upload d'images durci** (#474) : provider Imgur + repository, erreurs réseau mieux typées.
- **Bande noire sous le contenu** au-dessus de la bottom bar (#529) supprimée (inset bottom-only).
- **Écritures de préférences** (#507) déplacées sur un scope applicatif.

### Infra
- CI éclatée par type de tâche (#491), garde-fou de test drapeaux « dernier posteur = last_author » (#331), doc vivante des limitations connues (#419).

---

## v136 — `0.12.0` — 2026-06-14

**Statut** : `open` (track open testing — canal beta, Play Edit committed) + F-Droid `.beta`
**Commit** : promotion dev→main (cf. PR de promotion)
**Contenu depuis la 0.11.0/v132** : dogfoodé sur le canal dev (v133 → v135).

> `0.11.0` ayant déjà été shippé en bêta (v132), le `versionName` est bumpé en `0.12.0` (la garde CI refuse deux bêtas au même `versionName`).

### Added
- **Éditeur — mode d'insertion d'image** (#500) : choix entre image réduite (défaut) et pleine taille dans les Réglages ; saut de ligne automatique entre les images d'un upload multiple ; bouton « Uploader » désormais toujours visible.
- **(DT) Stockage MP cross-app** (#499, #502) : moteur de découverte/lecture du conteneur MPStorage partagé (compatible DTCloud/MultiMP) + écran d'inspection en lecture seule, accessible depuis Réglages → section DT (caché pour les utilisateurs normaux).

### Fixed
- **Pagination des listes MP au-delà de la page 2** (#503) : sur une boîte authentifiée, les numéros de page sont des liens obfusqués (`md_cryptlink`) dès la page 2 ; ils n'étaient pas lus, donc le total de pages retombait à la page courante. Affectait notamment la découverte du conteneur MPStorage (« aucun MP de stockage » à tort sur des comptes qui en possèdent un). Test de régression ajouté.

---

## v132 — `0.11.0` — 2026-06-13

**Statut** : `open` (track open testing — canal beta, Play Edit committed) + F-Droid `.beta`
**Commit** : `4fd2fb02` (promotion #493 dev→main)
**Fichier** : AAB `redface2-beta-v132-4fd2fb0.aab` → track open testing + tag `app-v132` pour F-Droid beta

**Contenu depuis la 0.10.0/v126** : dogfoodé sur le canal dev (v127 → v131).

### Added
- **Upload d'images depuis l'éditeur** (#459) : bouton « Uploader » → sélection galerie, upload chez l'hébergeur, insertion `[img]` au curseur.
- **Upload multi-images** (#490) : sélecteur multi (jusqu'à 10), upload séquentiel dans l'ordre de sélection, compteur « n/N », arrêt à la première erreur (images déjà insérées conservées) ; validé sur S10e.
- **Choix de l'hébergeur d'images** dans les Réglages (#459/#474) : diberie ou imgur (Client-ID Imgur perso) ; message d'erreur d'upload précis (hébergeur + code HTTP).
- **Écran « Mes images uploadées »** avec suppression (#459).
- **Brouillons d'éditeur** (#405) : sauvegarde et restauration automatiques (cache Room).
- **Multi-quote** : bouton « + » par post pour empiler des citations (#436).
- **Sondages** : repliés par défaut + réglage « Déplier les sondages » (#456).
- **Drapeaux** : swipe entre les onglets + suppression par appui long (#457) ; filtre « Mes drapeaux » par (sous-)catégorie (#455) ; bandeau de catégorie cliquable vers le listing (#414).
- **Messages privés** : ouverture d'un MP sur sa dernière page + reprise de lecture locale (#430).
- **Affichage** : préréglages de densité + taille de police de lecture sur 3 crans (#287) ; écran de démarrage configurable — onglet + catégorie Forum au lancement (#458).

### Fixed
- **Upload diberie cassé** (#459/#474) : le `picID` renvoyé par diberie est un nombre JSON (pas une chaîne) — chaque upload échouait au parsing. Corrigé + test de régression sur fixture réelle.
- **Cloisonnement par compte** (#495/#496) : brouillons d'éditeur et positions de lecture MP liés au compte qui les a écrits — un changement de compte ne fuite plus le brouillon ni la position d'un compte vers l'autre.
- **Suppression de brouillon best-effort** (#497) : un post réussi n'est plus jamais bloqué (ni le brouillon laissé restaurable) si le nettoyage local échoue.
- **Garde-fous d'upload** (#496) : « Envoyer » désactivé tant qu'une image est en cours d'upload ; suppression diberie honnête (jamais annoncée « confirmée » côté hôte).

### Changed
- **`versionName` 0.10.0 → 0.11.0**.
- Listes densifiées : gouttière globale du NavHost retirée (#398/#287).

---

## v126 — `0.10.0` — 2026-06-12

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : merge de promotion `fe6bda5e` (#451), tag `app-v126` (versionCode 126)
**Fichier** : AAB `bundleProdRelease` → track open testing + tag pour F-Droid beta

**Contenu depuis la 0.9.0/v113** : night-run 2026-06-11→12 + arbitrages du 12 + dernier round, dogfoodés sur le canal dev (v114 → v125).

### Added
- **Messages privés — écriture complète** : composer un nouveau MP (#301/#404) ; **picker de smileys** (Standard + recherche wiki, favoris priorisés) dans les deux éditeurs MP (#387) ; **badge de MP non lus** sur l'onglet Messages, cap « 9+ », désactivable (Réglages › Notifications, #313).
- **Messages privés — gestes de lecture** (#351 a+b) : pull-to-refresh, ascenseur, swipe de pages in-place ; chargement keep-content (la page affichée reste visible pendant le rechargement).
- **Citation multiple** (#291) + **marquage visuel** des posts ajoutés au panier — bordure + pastille « Ajouté à la citation » (#436, point 1).
- **Recherche** : filtre par auteur (`pseud=`) + « Derniers messages » depuis le profil (#403) ; **repli du formulaire en bandeau compact** une fois la recherche lancée, résultats pleine hauteur, « Modifier » ré-étend (#433).
- **Lecture topic** : marqueur « Dernier message du sujet » (#379) ; réglage pour masquer les boutons flottants de page (#383) ; pseudo cliquable vers le profil dans le menu de post (#395) ; « Supprimer » dans le menu de post (#418) ; palette smileys complète dans l'éditeur (#415).
- **MPStorage lecture seule v0.1** (#406, ADR-014) — fondation de l'onglet DT.

### Fixed
- **Saisie du nouveau MP sous le clavier** (#434, #275, #410) + curseur visible dans les éditeurs (#422) ; **le champ suit le curseur pendant la frappe** (#447 point 1, retour bêta-dev — le viewport défile pour garder le caret visible sous le clavier).
- **Parser** : lignes vides préservées et interligne naturel (#333, #280) ; citation contenant un spoiler (#393) ; **smiley inexistant rendu en token texte lisible** (#416).
- **Drapeaux** : favoris perdus au retour d'onglet (#384) ; pastille favori jaune dans « Mes sujets » (#432, Room v9) ; auto-refresh au retour d'un sujet (#431, #378, #331) ; snackbar invisible (#417) ; « +lus » masqués (#385) ; onglet « Mes sujets » sans retour à la ligne (ellipse, #446).
- Flash de thème clair au lancement (#407) ; un résultat de recherche atterrit sur le bon post via la redirection HFR (#277) ; « page précédente » atterrit en bas (#412) ; fuite inter-session du compteur de MP non lus (review PR #439).

### Changed
- **`versionName` 0.9.0 → 0.10.0**.
- **Le flavor dev ajoute `-dev.<build>` au versionName** (ex. `0.10.0-dev.124`) : les builds du canal dev sont enfin distinguables dans F-Droid et dans le footer (avant : dix entrées « 0.9.0 » identiques v114→v123).

---

## v113 — `0.9.0` — 2026-06-11

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : merge de promotion `0313e8f4` (#400, candidat dev `bb3ee57b` + review Codex SHIP), tag `app-v113` (versionCode 113, ledger 112→113)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) → track open testing + tag pour F-Droid beta

**Lot dogfooding du 2026-06-10 soir** : 7 PR mergées sur dev (#388–#392, #397, #399), dogfoodées en continu sur le canal dev (v106 → v112).

### Added
- **Barre d'actions de l'éditeur** — « Options | Smileys | Envoyer » épinglée au-dessus du clavier sur les trois éditeurs (post, sujet, MP) ; les toggles HFR (signature/smileys/notification) passent dans un bottom sheet ouvert par « Options » ; boutons secondaires en pilules tonales (#390).
- **Confirmation par double-bouton** (#312 v2) — le dialog modal disparaît : « Envoyer » s'arme (« Confirmer », couleurs tertiary), le fond se vide pendant les 4 s du compte à rebours (désarmement auto), le 2ᵉ appui envoie.
- **Champ de rédaction extensible** — le champ BBCode s'étire jusqu'à la barre d'actions (éditeur de post et réponse MP) ; aperçu ouvert = partage 50/50 avec scroll interne.
- **Double-tap pour rafraîchir** (#382) — double-tap n'importe où dans une page de sujet = re-fetch réseau (même retour visuel que le pull-to-refresh, tic haptique).
- **Section DT (opt-in)** — toggle Réglages › Drapeaux faisant apparaître un onglet « DT » placeholder ; le contenu (drapeaux synchronisés via MPStorage, #6) arrivera plus tard.

### Fixed
- **Pastilles lu/non-lu de l'onglet Forum** (#329) — la pastille de drapeau d'une ligne lue est atténuée (même grammaire que l'onglet Drapeaux) ; l'état visuel n'avait jamais été implémenté.
- **Libellé « Confirmer » cassé sur deux lignes** — les déclencheurs secondaires s'effacent pendant l'armement.
- **Gouttières réelles des posts** — le NavHost ajoutait déjà 8 dp par côté (hérité du bootstrap) : la liste n'ajoute plus rien, les posts ont enfin 8 dp réels par côté (dette du padding global tracée en #398).

### Changed
- **Lecture des posts** — bande d'identité teintée (avatar/pseudo/date) sur toute la largeur de la carte ; ~24 dp de largeur de lecture en plus ; grille uniforme 8 dp (rythme vertical aligné sur les côtés).
- **`versionName` 0.8.0 → 0.9.0**.

---

## v104 — `0.8.0` — 2026-06-10

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : merge de promotion `918bb619` (#373), tag `app-v104` (versionCode 104, ledger 103→104)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) → **track open testing** + tag pour F-Droid beta

**Troisième batch** : night-run 2026-06-10 (8 items, dont 7 features/fixes code) + lot dogfooding même journée, dogfoodé sur le canal dev (v102 → v103).

### Added
- **Menu contextuel de post** (#362) — icône « ⋯ » dans la barre du post : pseudo + avatar, numéro du post (déplacé ici), « Copier le lien de ce post », « Ouvrir dans le navigateur », date d'édition, nombre de citations sur la page ; « Alerter (à venir) » grisé.
- **Horodatage du dernier message** (#325) — dans les listes catégorie, recherche et drapeaux ; sur les drapeaux il est aligné à droite et jamais tronqué (le compteur de réponses, redondant avec p.X/Y, disparaît).
- **Vider le cache des images** (#314) — Réglages › carte Maintenance (mémoire + disque Coil).
- **Confirmation avant publication** (#312) — toggle Réglages (désactivé par défaut) ; couvre réponse, création de topic, édition de post et réponse MP, avec wording dédié MP.
- **Panne HFR vs coupure réseau** (#324) — les écrans de lecture distinguent « HFR est en panne » (5xx serveur) de « pas de connexion » ; la session expirée garde son bouton de reconnexion.

### Fixed
- **Résultats de recherche** (#277) — ouvrir un résultat atterrit sur la bonne page et le bon post (résolution par redirection HFR côté serveur ; budget réseau 3 s appliqué par OkHttp avec repli page 1 — durci post-review Codex de promotion).
- **Position de lecture par page** (#307) — revenir sur une page déjà visitée d'un sujet (swipe, pager, retour) reprend la position quittée au lieu du haut de page (cache session borné, priorité aux atterrissages deep-link/post-publication).
- **En-tête Recherche** réaligné sur le gabarit des autres onglets (titre et avatar n'étaient pas aux mêmes marges).

### Changed
- **`versionName` 0.7.0 → 0.8.0**.
- **En-tête Drapeaux** : icône engrenage à la place du libellé « Affichage » (libellé conservé pour TalkBack).
- ADR-013 « Lecture MP : partage topic↔MP, cache 3 étages, prefetch borné » ajoutée (statut Proposé).

---

## v101 — `0.7.0` — 2026-06-09

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : tag `app-v101` (versionCode alloué par le registre de tags, plancher 72)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) → **track open testing** + tag pour F-Droid beta

**Deuxième batch de fonctionnalités** dogfoodé sur le canal dev (v93 → v100) : écriture MP, ascenseur fast-scroll, pull-to-refresh, accès rapide poster, écran Réglages. Promotion `dev → main` puis ship beta.

### Added
- **Répondre à une conversation privée** (#301) — éditeur BBCode complet (barre d'outils, aperçu, options signature/smiley/notification e-mail), bouton « Envoyer » épinglé au-dessus du clavier ; calqué sur l'éditeur de post. La composition d'un nouveau MP depuis zéro reste à venir.
- **Ascenseur (scrollbar) de sujet** (#300) — indicateur de position + fast-scroll par glisser. Modèle de pouce à taille fixe avec ancrage intra-post fluide (pas d'à-coups, pas de « respiration » de la hauteur).
- **Pull-to-refresh d'une page de sujet** (#335) — tirer vers le bas recharge la page courante.
- **Accès rapide « poster » + changement de page en bas de sujet** (#283).
- **Écran Réglages « menu vitrine »** (#288) — catalogue des réglages présents et à venir (grisés, étiquetés par issue ou phase).

### Fixed
- **Flèche retour incohérente** (#355/#356/#357) — remplacement du glyphe texte « ← » (taille dépendante de la police et de la baseline système) par une icône vectorielle 24 dp rendue via material3 `Icon`, sur les écrans sujet, profil et messages privés.

### Changed
- **`versionName` 0.6.0 → 0.7.0**.
- Post-review Codex : le refetch silencieux après `hash_check` expiré en réponse MP **ne réécrase plus** les options choisies par l'utilisateur (#301, garde `optionsHydratedFromForm` alignée sur l'éditeur de post) ; le catalogue Réglages n'annonce plus l'écriture MP comme « à venir » (reformulé vers la composition d'un nouveau MP).

---

## v92 — `0.6.0` — 2026-06-08

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : tag `app-v92` (versionCode alloué par le registre de tags, plancher 72)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) → **track open testing** + tag pour F-Droid beta

**Première bêta du batch Phase 5** dogfoodé sur le canal dev (v87 → v91) : thème, lecture topic, suppression de post, bouton Envoyer, correctifs. Promotion `dev → main` (#342) puis ship beta.

### Added
- **Thème clair / sombre / système + AMOLED** (#286) — sélecteur dans les réglages, barres système synchronisées au thème effectif.
- **Suppression de ses propres posts** (#292) — bouton « Supprimer » (même gate que « Modifier »), dialog de confirmation, refresh in-place. Posts normaux uniquement (le 1er post = suppression du sujet entier, différée).
- **Barre de titre du topic** (#285) — rappel du titre + bouton retour vers la liste.
- **Compteur de page** (#284) — « page X/Y » visible en lecture.
- **Option « masquer la barre de titre en défilant »** (#338) — top bar repliable au scroll (réglage).
- **Badge « cité N fois »** (#239) sur les posts.
- **Bouton « Envoyer » épinglé au-dessus du clavier** — éditeurs de réponse et de nouveau topic en plein écran.

### Fixed
- **Sélection de texte impossible** en lecture d'un topic (#281).
- **Page bloquée après un post qui déborde** sur une nouvelle page (#226) — atterrissage force-refreshé sur la dernière page + scroll vers le post (contrat de nav `postSubmitOverflowLanding`).
- **Pseudo à espace mal décodé** (« Dintr-un+lemn ») (#260).
- **Titre du top bar qui devenait « Sujet »** au changement de page (#338).
- **Bouton « Envoyer » coupé** en plein écran (nav masquée sur les routes éditeur).
- **Barres système** incohérentes avec le thème.

### Changed
- **`versionName` 0.5.1 → 0.6.0**.
- Polish post-review (#341) : cache de titres (court-circuit recompose), couverture de tests `withTitle` / gates suppression.
- Dépendances : navigation androidx, kotlin runtime, mockk, github-actions, compose-bom 2026.05.01 ; routage CI Dependabot → dev.

---

## v86 — `0.5.1` — 2026-06-07

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : tag `app-v86` (versionCode alloué par le registre de tags, plancher 72)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) → **track open testing** + tag pour F-Droid beta

**Bump `versionName` 0.5.0 → 0.5.1.** Aucun changement fonctionnel vs v85 : même code (MP lecture, swipe, drapeaux par catégorie/type/non-lus, fix vie privée). Le bump corrige l'historique de version : v84 et v85 avaient été shippés tous deux sous `0.5.0`, créant un doublon « 0.5.0 » sur F-Droid (qui affiche par `versionName`). L'APK v84 a été retiré du dépôt F-Droid (workflow `prune.yml` de redface2-fdroid) et la v86 repart proprement en `0.5.1`.

### Changed
- **`versionName` 0.5.0 → 0.5.1** (re-label, pas de changement de code).
- **Guard CI anti-doublon** : `release.yml` refuse désormais un ship `channel=beta` dont le `versionName` n'a pas été bumpé vs la release beta précédente. Doc (guide release, instruction CHANGELOG) : bump `versionName` obligatoire avant un ship public.

---

## v85 — `0.5.0` — 2026-06-07

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : tag `app-v85` (versionCode alloué par le registre de tags, plancher 72)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) → **track open testing** + tag pour F-Droid beta

**Affichage des drapeaux dans la bêta 0.5.0** — les trois fonctionnalités drapeaux, déjà éprouvées sur le canal dev (v81/v82/v83), rejoignent la bêta ouverte ; la v84 livrait 0.5.0 sans elles.

### Added
- **#179 — Drapeaux/favoris regroupés par catégorie** dans les 4 onglets (Cyan/Lu/Favoris/Super), vue groupée par défaut (parité web), en-têtes de catégorie collants. Toggle vue plate/groupée + masquage des catégories sans non-lu dans les Réglages.
- **#309 — Affichage configurable par type de drapeau** : un menu « Affichage » (bottom sheet sur l'en-tête Drapeaux + miroir Réglages) permet à chaque onglet de résoudre son propre regroupement / masquage (master switch `flagsPerTabOverride`, fallback global).
- **#317 — Filtre « non-lus uniquement » par type de drapeau** : défaut adapté au type (Cyan = non-lus, Lu/Favoris = tout afficher), toggle persistant par onglet ; le re-tap cyan « +lus » bascule désormais un réglage persistant.

---

## v84 — `0.5.0` — 2026-06-07

**Statut** : `open` (track open testing) + F-Droid `.beta`
**Commit** : tag `app-v84` (versionCode alloué par le registre de tags, plancher 72)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) → **track open testing** + tag pour F-Droid beta

**Deuxième bêta — premières vraies fonctionnalités utilisateur depuis l'ouverture du canal** (la 0.4.0/v72 n'apportait que l'industrialisation de la livraison) : lecture des messages privés et navigation par swipe dans les topics.

### Added
- **#298 — Messages privés classiques en lecture.** L'onglet Messages affiche
  maintenant l'inbox MP (`forum1.php?cat=prive`) et ouvre une conversation
  (`forum2.php?cat=prive&post=...`) en lecture seule. Les états MP se purgent au
  logout / changement de session et l'ouverture d'une conversation marque la ligne
  comme lue côté UI pour éviter un indicateur stale au retour.
- **#282 — Swipe gauche/droite pour changer de page dans un topic.** Geste horizontal
  « drag-follow » (la page suit le doigt, résistance amortie aux bords, retour haptique
  à l'armement et au commit, edge-glow discret), transition Topic→Topic instantanée pour
  supprimer la fenêtre morte. Le swipe ne déclenche jamais d'action destructive.

### Fixed
- **#316 — Fuite potentielle d'identifiant de conversation privée.** Les écrans MP
  n'affichent plus le message d'erreur brut : un throwable réseau/auth pouvait contenir
  l'URL `forum2.php?cat=prive&post=<id>`. Désormais message générique + « réessayer »
  uniquement, et le détail brut ne transite plus par l'état UI ni par le journal de
  diagnostics exportable.
- **Robustesse compteur de MP non lus** (relevé pendant la revue beta) : le fetch du
  compteur « MPs non lus » n'avale plus `CancellationException` via `runCatching` —
  l'annulation (changement de session, arrêt du collecteur) se propage désormais au lieu
  d'être journalisée comme un échec réseau, préservant la concurrence structurée.

### Changed
- **CD rev. 4 (#304)** : le `versionCode` n'est plus bumpé à la main — il est alloué au
  dispatch par le **registre de tags git** (`max(app-v<N>, plancher) + 1`), partagé entre
  les canaux beta et dev. Le dispatch se fait par canal (`workflow_dispatch -f channel=beta|dev`).
- **Durcissement CD beta (#316)** : `release.yml` échoue désormais **avant tout effet de
  bord** (création de tag/Release, notification F-Droid) si le secret Play est absent sur
  un canal qui publie sur Play, pour ne pas « brûler » un versionCode sans publication ; et
  un dispatch `channel=beta` exige `ref=main`.
- **Build** : `versionName` `0.4.0 → 0.5.0`.

---

## v72 — `0.4.0` — 2026-06-02

**Statut** : `open`
**Commit** : tag `app-v72` (Release GitHub cochée *pre-release* → track open testing)
**Fichier** : AAB `bundleProdRelease` (`fr.forumhfr.redface2`) uploadé sur le **track open testing** de l'app unique par la CD + tag pour F-Droid beta

**Passage en bêta.** Première release distribuée par la nouvelle CD routée par release-event (#233) : une Release GitHub *pre-release* déclenche le build prod + l'upload sur le **track open testing** de l'app `fr.forumhfr.redface2` + la notification F-Droid. Le bump mineur `0.3.x → 0.4.0` matérialise la sortie d'alpha. Pas de nouvelle fonctionnalité utilisateur depuis v71 (le rendu `[quote]`/`[img]` de v70/v71 est inclus) — la valeur de cette version est l'ouverture du canal bêta public et l'industrialisation de la livraison.

### Changed
- **Canal de distribution** : alpha (closed testing) → **bêta (open testing)**, **tracks de la même app `fr.forumhfr.redface2`** (modèle Play standard, pas une app par canal — un seul applicationId, plusieurs tracks).
- **Libellés in-app neutres** : « Paramètres bêta / Diagnostics bêta / Maintenance bêta » → « Paramètres / Diagnostics / Maintenance ». Le binaire est identique sur tous les tracks (promouvable open testing → production sans re-build), donc aucun marqueur de canal n'y est gravé ; l'indication « test » vient de la bannière testeur Play. (Le marqueur de phase éventuel reviendra/partira avec la 1.0.)
- **CD** : `release.yml` route par déclencheur vers le **track Play** — prerelease→open testing (beta), stable→production (draft, après approbation via l'Environment GitHub `production`), `workflow_dispatch`→internal (dev). Tous buildent et uploadent le **package prod** ; seul le track diffère. Durci par 5 passes de review Codex gpt-5.5 xhigh.
- **Build** : `app/build.gradle.kts` passe à `versionCode = 72`, `versionName = "0.4.0"`. Les 3 product flavors `channel` (prod/beta/dev, applicationId distincts) servent au **sideload dogfood local uniquement** — la CD n'uploade que `prod` sur Play.

---

## v71 — `0.3.31` — 2026-06-02

**Statut** : `closed`
**Commit** : tag `app-v71` après merge de la PR #258
**Fichier** : AAB uploadé sur le canal Play closed alpha + tag pour F-Droid

Hotfix rendu des images (régression #224 remontée en dogfood).

### Fixed
- **#257 — grandes images lentes/pixelisées + images-liens rendues petites.** Trois causes corrigées : (1) une image dans un `[url=…][img]` (« cliquable pour agrandir ») est désormais promue en **bloc pleine largeur cliquable** (ouvre le lien) au lieu de rester une petite vignette inline ; (2) le décode des `[img]` inline se fait à une **taille stable** (cap inline en px, `FIT`+`INEXACT`, mémoïsé) → plus de bitmap upscalé pixelisé pendant la croissance cold→mesuré ; (3) `measureIntrinsicMediaSize` utilise une **sonde bornée 1024 + `Precision.INEXACT`** au lieu de `Size.ORIGINAL` → plus de décode pleine résolution juste pour mesurer (ni d'upscale des petits médias). Gate : Codex gpt-5.5 xhigh + review 4-flavor opus (re-review Codex a attrapé un P1 de précision, corrigé).

### Changed
- **Build / release** : `app/build.gradle.kts` passe à `versionCode = 71`, `versionName = "0.3.31"`.

---

## v70 — `0.3.30` — 2026-06-02

**Statut** : `closed`
**Commit** : tag `app-v70` après merge des PR #248 / #254 / #246
**Fichier** : AAB uploadé sur le canal Play closed alpha + tag pour F-Droid

Phase 2 finish — rendu des `[quote]` / `[img]` (dogfood S25 + double review Claude/Codex).

### Fixed
- **#247 — `[quote]` nu non rendu en mode connecté.** Le parser reconnaît `table.oldquote` (variante servie au profil « classique » connecté, symétrique d'`oldcitation`) aux 3 sélecteurs de citation → le bloc est encadré au lieu de tomber en texte brut.

### Added
- **#252 — distinction visuelle du `[quote]` nu manuel.** Accent gris neutre `outline` + header « Citation » pour un `[quote]` tapé à la main (sans source), distinct de la citation sourcée (`[quotemsg=]`, accent rouge/or alterné par profondeur). `isBareQuote` dérive de l'absence de **toute** métadonnée source (author / numreponse / page) pour ne pas misclassifier un `[quotemsg]` de l'aperçu éditeur.
- **#224 — dimensionnement intrinsèque des `[img]` inline + promotion en bloc.** Mesure native (no-upscale + caps absolus, cap relatif converti en sp donc fontScale-safe) au lieu de la box fixe 240×180 ; un paragraphe « galerie » (images seules) dont une image dépasse les caps inline est promu en blocs full-width centrés (les images dans un `[url=]` restent inline pour garder le tap-through). Cold-fallback réduit à un carré d'une ligne (#253, plus de flash d'emoji géant avant mesure). Alignement vertical `TextBottom` pour les `[img]` et les smileys.

### Changed
- **Build / release** : `app/build.gradle.kts` passe à `versionCode = 70`, `versionName = "0.3.30"`.

---

## v69 — `0.3.29` — 2026-06-01

**Statut** : `closed`
**Commit** : tag `app-v69` après merge de la PR #245
**Fichier** : AAB uploadé sur le canal Play closed alpha + tag pour F-Droid

Lecture topic — rendu des blocs `[code]` (suite du dogfood #244).

### Fixed
- **#244 — Blocs `[code]` illisibles sur mobile.** `[code]` wrappe désormais dans la largeur de la carte au lieu de scroller horizontalement (une ligne longue ne montrait que son début). `[fixed]` conserve le no-wrap + scroll horizontal pour l'ASCII art / tableaux alignés en colonnes.

### Added
- **Gouttière de numéros de ligne sur `[code]`** (parité avec le rendu web HFR). Un numéro par ligne logique, peint en `drawBehind` via `TextLayoutResult` ; une continuation de soft-wrap n'est pas numérotée, ce qui lève l'ambiguïté wrap ↔ nouvelle ligne. Bloc forcé en LTR (review Codex). Couvert par `PostRendererCodeBlockRoborazziTest` (ligne qui wrappe + cas >9 lignes).

### Changed
- **Build / release** : `app/build.gradle.kts` passe à `versionCode = 69`, `versionName = "0.3.29"`.

---

## v68 — `0.3.28` — 2026-05-31

**Statut** : `closed`
**Commit** : tag `app-v68` après merge de la PR #230
**Fichier** : AAB uploadé sur le canal Play closed alpha + tag pour F-Droid

Phase 2 finish — corrections de fin de dogfood sur les actions d'écriture et l'écran Drapeaux.

### Fixed
- **#220 — Actions d'écriture masquées hors connexion.** Répondre, Citer, Modifier et Modifier-FP sont maintenant gated sur `canReply && isAuthenticated`, afin d'éviter d'ouvrir un éditeur qui ne peut échouer qu'au submit après logout ou cache topic périmé.
- **#225 — Drapeaux : suppression du double loader au swipe-to-refresh.** La liste reste affichée sous l'indicateur Material 3 au lieu de disparaître derrière un spinner central.
- **#229 — Drapeaux : swipe-to-refresh sur état vide ou erreur.** Les états vides/erreur remplissent maintenant l'écran et restent scrollables pour que le geste de refresh soit capté.

### Changed
- **Build / release** : `app/build.gradle.kts` passe à `versionCode = 68`, `versionName = "0.3.28"`.

---

## v67 — `0.3.27` — 2026-05-31

**Statut** : `closed`
**Commit** : tag `app-v67` après merge de la PR #228
**Fichier** : AAB uploadé sur le canal Play closed alpha + tag pour F-Droid

Phase 2 finish — correction du flux écrire/citer sur les catégories HFR sans sous-catégorie, notamment Intelligence Artificielle (`subcat=0` réel). La version durcit aussi le cache topic pour éviter qu'une ancienne base Room masque les boutons Reply/Citer/Edit après mise à jour.

### Fixed
- **#213 — Répondre sur une catégorie sans sous-catégorie.** Les formulaires HFR dont `force_subcat=false` et `subcat=0` sont maintenant considérés comme valides : le topic expose `canReply`, l'éditeur accepte `subcat=0`, et les guards réseau n'assimilent plus cette valeur à une sous-catégorie inconnue.
- **Citer sans `quoteRef` extrait du HTML topic.** L'action Citer dépend maintenant de `Topic.canReply`, pas de la présence d'un lien de citation dans chaque post. Le repository retombe sur le `numreponse` du post quand HFR ne fournit pas de `quoteRef` explicite.
- **Migration cache topic v6→v7.** Les pages topic migrées sont marquées stale (`fetchedAt=0`) pour forcer un refresh post-upgrade et éviter que `canReply=false` injecté par défaut ne cache les actions d'écriture jusqu'à expiration TTL.

### Changed
- **Tests / docs** : les fixtures browser-save ne sont plus utilisées pour prétendre valider `quoteRef` brut ; elles restent utiles pour `canReply` et `subcat=0`. Les specs et KDocs documentent `quoteRef` comme une optimisation optionnelle, pas comme une condition d'affichage de Citer.
- **Build / release** : `app/build.gradle.kts` passe à `versionCode = 67`, `versionName = "0.3.27"`.

---

## v66 — `0.3.26` — 2026-05-30

**Statut** : `closed`
**Commit** : tag `app-v66` après merge de la PR #222
**Fichier** : AAB uploadé sur le canal Play closed alpha + tag pour F-Droid

Phase 2 finish — dogfood du rendu adaptatif des smileys inline (#175) après les retours sur les buckets fixes. Cette version garde les smileys builtin sur leur petite taille connue et mesure les smileys perso à leur taille intrinsèque pour éviter à la fois les micro-smileys agrandis et les gros smileys qui chevauchent le texte.

### Changed
- **#175 — Smileys inline à taille intrinsèque.** Les smileys perso ne passent plus par un bucket fixe unique `70×50` : l'app mesure leur taille native via Coil, applique un no-upscale, un cap absolu et un cap relatif de largeur façon RF1/HFR web. Les micro-smileys restent petits, les `70×50` dominants restent lisibles, et les très gros sprites sont réduits au lieu de déborder.
- **Ligne de texte adaptative pour les smileys hauts.** Les paragraphes contenant des smileys inline retirent le `lineHeight` fixe pour laisser la ligne grandir autour du placeholder baseline-aligned. Objectif : zéro chevauchement avec les lignes voisines.
- **Build / release** : `app/build.gradle.kts` passe à `versionCode = 66`, `versionName = "0.3.26"`.

### Known issues
- **#175 / #131 — Gate dogfood encore ouvert.** Les specs canoniques documentent encore la stratégie bucket fixe tant que ce rendu adaptatif n'est pas validé en alpha. Si le dogfood confirme le choix, `protocol-hfr.md`, `roadmap.md` et l'ADR de rendu smileys seront actés dans une PR dédiée.
- **Cap relatif sous `fontScale > 1`.** Le cap de largeur relatif est exact au fontScale standard et légèrement permissif avec les grandes tailles de police. À calibrer si le dogfood accessibilité montre un débordement réel.

---

## v65 — `0.3.25` — 2026-05-30

**Statut** : `closed`
**Commit** : tag `app-v65` après merge des PR #215, #216 et #217
**Fichier** : AAB uploadé sur le canal Play closed alpha + tag pour F-Droid

Phase 2 finish — polish lecture topic après retours alpha : baseline smileys, stabilité du scroll deep-link pendant le chargement des images et régression de header après liens profil. Embarque aussi les corrections #206/#214 restées en *Unreleased* depuis v64.

### Added
- **#206 — Highlight du topic fraîchement créé dans la liste (workaround).** La navigation directe vers le sujet créé étant impossible — HFR redirige vers la **liste de la catégorie** sans jamais renvoyer l'id du topic (confirmé live, cf. #214) — l'app **met en évidence le sujet fraîchement créé dans la liste sur laquelle elle atterrit**, par **correspondance exacte du titre** posté (titre trimé, insensible à la casse). Match exact (un `contains` highlighterait à tort un ancien sujet dont le titre est un préfixe) ; seul cas de sur-match résiduel : deux sujets au titre strictement identique seraient mis en évidence ensemble. La mise en évidence reste affichée uniquement sur la page/sous-catégorie d'arrivée ; elle disparaît dès que l'utilisateur change de page ou de sous-catégorie. Ligne accessible (`stateDescription` « Sujet que vous venez de créer ») et texte en `onSecondaryContainer` pour le contraste M3. Plumbing : l'effet `NewTopicCreated` porte désormais le `subject` saisi → propagé jusqu'à `CategoryRoute.highlightTitle` sur le path fallback (toujours le cas pour un create) → descendu jusqu'à la ligne de liste. Surbrillance sobre réutilisant le rôle M3 `secondaryContainer` (même style que le highlight d'un post cible dans `TopicScreen`, aucune couleur en dur). Dégrade proprement : `highlightTitle == null` sur tous les chemins de navigation normaux (forum, deep link, switch de sous-catégorie) → aucun highlight. C'est la version réalisable de #206.

### Fixed
- **#214 — Création de topic : succès ne s'affiche plus en erreur.** Le submit create-topic réussit côté HFR mais l'app affichait « HFR a renvoyé une réponse inattendue » (le topic était pourtant créé → risque de doublons). Cause confirmée par capture live (`write_create_topic_success_response.html`) : HFR renvoie une phrase de succès propre au create — **« Votre message a été posté avec succès ! »** — que `ReplySubmitResponseParser` ne connaissait pas (il ne matchait que reply « réponse postée » et edit « message édité »). Fix : ajout du marker create. Validé contre la vraie fixture.
- **#203 — Smileys inline alignés sur la baseline.** Le rendu Compose aligne désormais les smileys inline sur la baseline du texte, pour se rapprocher du rendu web HFR et limiter les sauts visuels entre texte et smileys.
- **#197 — Re-ancrage du scroll deep-link pendant le chargement des images-blocs.** Quand un lien pointe vers un post précis, `TopicScreen` continue de surveiller la position pendant une fenêtre de décodage initiale afin de compenser les images qui gonflent au-dessus de la cible après le premier scroll. Le watcher reste annulable dès que l'utilisateur scrolle manuellement.
- **Régression #208 — Header de post compact après liens profil.** Le pseudo cliquable n'étire plus le header du post : la zone tappable reste limitée à l'avatar/pseudo et le layout garde une hauteur stable.

### Changed
- **Build debug** : le libellé du lanceur de la variante `debug` (installée côté-à-côté via `applicationIdSuffix=.debug`) devient **« Redface 2 ADB »** (au lieu de « Redface 2 ») pour distinguer l'install dogfood adb. La release garde `@string/app_name`.
- `app/build.gradle.kts` : `versionCode = 65`, `versionName = "0.3.25"`.

### Known issues
- **#206 — « Navigation directe vers le sujet créé » impossible (remplacée par le highlight, cf. *Added*).** La capture live montre qu'après un create réussi, HFR redirige vers la **liste de la catégorie** (`…/liste_sujet-1.htm`), **sans jamais renvoyer l'id du sujet créé** : `newTopicId`/`newNumreponse` sont toujours `null`. La fonctionnalité d'origine de #206 (ouvrir directement le topic) n'est donc pas réalisable. **Solution livrée** (voir *Added* ci-dessus) : l'app met en évidence le sujet fraîchement créé dans la liste par correspondance exacte du titre — le workaround validé « Exact post-création ». La branche `newTopicId != null` (jump direct) reste dans le code mais est morte pour le create ; conservée par sécurité si HFR se mettait un jour à ancrer un segment `sujet_`.
- **#213 — Catégorie sans sous-catégorie (ex. « Intelligence Artificielle », `force_subcat=false`)** : création ET réponse cassées (le formulaire create exige un `<select subcat>` absent ; le bouton Répondre est désactivé faute de `subcat` valide). Fix non livré ici : changement multi-couches (modéliser `force_subcat`, relâcher `canSubmit`/guard/buildBody, distinguer subcat réel 0 vs sentinelle `SUBCAT_UNKNOWN`) + vérification d'un POST 0-sous-cat. Tracé dans #213.

---

## v64 — `0.3.24` — 2026-05-28

**Statut** : `closed`
**Commit** : head de `feature/phase2-finish-create-topic-206` (#206 ; profil #208 déjà mergé via PR #211), dispatch `release.yml` `play_track=alpha`
**Fichier** : AAB uploadé sur le canal Play closed alpha

Phase 2 finish — profil utilisateur (#208) + première tentative #206 de navigation create-topic, invalidée ensuite par la capture live #214 (voir Unreleased). Bump versionCode 63→64.

### Added
- **#206 — Create topic : tentative initiale de navigation directe** : cette build a tenté d'extraire un `topicId` depuis les refresh URLs `sujet_{topicId}_{page}` connues sur reply/quote/edit. Le dogfood suivant a prouvé que le succès create-topic réel est différent (`liste_sujet-1.htm`, aucun topic id) ; cette entrée est conservée comme historique, le correctif livré est documenté en *Unreleased* (#214 + highlight).
- **#208 — Profil utilisateur** : tap sur l'avatar ou le pseudo d'un post ouvre une `ModalBottomSheet` résumé (avatar carré/arrondi, pseudo, localisation, date d'inscription, nombre de posts, bouton « Voir le profil complet »). Naviguer vers la page complète affiche en plus la signature. Le bouton « Derniers messages » est désactivé (marqué « à venir ») faute de route stable.
- **Parser profil** : `ProfileParser` extrait `UserProfile` depuis `/hfr/profil-{userId}.htm` (tolérant aux champs absents).
- **`Post.profileId`** : champ nullable extrait par `TopicPageParser` depuis le lien `<a href="/hfr/profil-{N}.htm">` du toolbar. Persisté en Room (migration v5→v6).
- **`:feature:profile`** : nouveau module Gradle (`ProfileViewModel` AssistedInject, `ProfileScreen`, `ProfilePreviewSheet`).

### Fixed (review Opus 4-flavor sur PR #208)
- **Signature en clair** : `UserProfile.signatureHtml` (HTML brut) devient `UserProfile.signatureText` (texte plat extrait par `Jsoup.text()` côté parser). L'écran ne rend plus les balises `<br>` / `<div>` comme caractères littéraux.
- **A11y bouton retour** : le bouton retour du `TopAppBar` profil garde le glyphe `←` mais porte maintenant `contentDescription = stringResource(R.string.profile_back)` sur l'`IconButton` (audible TalkBack).
- **Sheet vs onglets** : `ProfileSheetRequest` capture l'onglet d'origine ; tap « Voir le profil complet » route la page complète vers le back stack de cet onglet (et revient dessus) au lieu de le pousser sur l'onglet courant.
- **Sheet dismiss animé** : « Voir le profil complet » joue `sheetState.hide()` avant la navigation au lieu de couper la sheet abruptement.
- **Cancellation propagée** : `DefaultProfileRepository` n'utilise plus `runCatching` (avale `CancellationException`) ; try/catch manuel qui rethrow `CancellationException` pour préserver la concurrence structurée.
- **Zone tappable** : dans `TopicScreen`, la zone d'ouverture profil est restreinte à l'avatar et au pseudo (la date n'ouvre plus le profil par erreur).
- **i18n** : strings UI de `:feature:profile` externalisées dans `feature/profile/src/main/res/values/strings.xml` ; `ProfileViewModel` expose `ErrorKind` + `cause` (plus de string `"Erreur inconnue"` côté VM).
- **Retry race** : `ProfileViewModel` cancelle le `loadJob` précédent avant chaque retry pour empêcher les coroutines concurrentes de race sur `_state`.
- **Konsist** : nouveau test qui vérifie qu'aucun fichier de `:feature:topic` n'importe `fr.forumhfr.redface2.feature.profile.*`.

### Changed
- `app/build.gradle.kts` : `versionCode = 64`, `versionName = "0.3.24"`.

---

## v63 — `0.3.23` — 2026-05-28

**Statut** : `closed`
**Commit** : head de `feature/phase2-finish-delflag-99` (PR #99), dispatch `release.yml` `play_track=alpha`
**Fichier** : AAB uploadé sur le canal Play closed alpha

Phase 2 finish — refonte de la page Drapeaux + retrait d'un drapeau (#99). Premier AAB distribué à embarquer aussi le correctif citations connecté de v62 (resté `local`).

### Added
- **Retirer un drapeau (#99, Phase 2 finish)** : **swipe-to-remove** sur chaque ligne de la liste des drapeaux (`SwipeToDismissBox` Material 3, swipe vers la gauche / end-to-start) qui ouvre un **dialog de confirmation Material 3 obligatoire** (titre du topic + type de drapeau) avant tout appel réseau, puis snackbar de succès/échec. Le swipe ne supprime jamais la ligne seul : elle revient en place (`reset()` vers `Settled`, déclenché depuis `onDismiss` pour éviter une race d'annulation de la coroutine) tant que l'utilisateur n'a pas confirmé — la suppression réelle n'a lieu qu'à la confirmation, quand le repo évince l'item du cache. Fond destructif `errorContainer` + libellé « Retirer » (pas de couleur en dur). Suppression unitaire via `GET /user/delflag.php` authentifié (mapping `FlagType`→`owntopic` : CYAN=1, RED=2, FAVORITE=3), classée sur le texte « Drapeau effacé avec succès ». En cas de succès, le drapeau est retiré des caches mémoire et Room et la liste se met à jour immédiatement ; en cas d'échec, aucun cache n'est touché. Pas d'undo optimiste. Geste désactivé pendant l'appel (anti double-tap). Le swipe étant la seule affordance de retrait, une action d'accessibilité (`customActions` « Retirer ») est exposée à TalkBack / switch-access. Suppression en masse hors scope.
- **Onglet « Super » (placeholder)** : 4e onglet sur la page Drapeaux, à droite de Favoris, pour les futurs « super favoris ». Pour l'instant un écran placeholder M3 sobre (« Super favoris — à venir » + explication), sans liste ni appel réseau. Modélisé via un type UI local `FlagTab` (Cyan / Red / Favorite / Super) qui mappe vers `FlagType` pour les 3 onglets réels ; l'enum domaine `FlagType` n'est pas touchée.

### Changed
- **Toggle « cyans lus » intégré à l'onglet Cyan** : le `FilterChip` « Cyans lus » séparé est retiré. Re-cliquer sur l'onglet **Cyan déjà sélectionné** bascule l'affichage des cyans déjà lus (premier clic depuis un autre onglet : sélection simple, sans bascule). Indicateur discret « · +lus » ajouté au libellé de l'onglet Cyan quand les cyans lus sont affichés. Le filtre reste sans effet sur les onglets Lu / Favoris.
- **Onglet « Lus uniquement » renommé « Lu »** sur la page Drapeaux (gain de place sur le tab row).
- **Pull-to-refresh sur la liste des drapeaux** : le bouton « Actualiser » du header est remplacé par un `PullToRefreshBox` Material 3 (swipe vers le bas) autour de la liste, branché sur un état `isRefreshing` exposé par le ViewModel — même pattern que la page Forum. Sans effet sur l'onglet Super (no-op).
- `app/build.gradle.kts` : `versionCode = 63`, `versionName = "0.3.23"`.

---

## v62 — `0.3.22` — 2026-05-27

**Statut** : `local`
**Commit** : `156a858` sur `feature/phase2-finish-ui-polish-198-199-201-202` avant merge PR #207
**Fichier** : à produire via `workflow_dispatch` du job `release.yml` (ou push d'un tag `app-v62`)

Correctif du bug de citations invisibles en mode connecté — la vraie cause, trouvée via la boucle de feedback émulateur.

### Fixed
- Citations (`PostBlock.Quote`) cassées en mode **authentifié** : HFR sert `<table class="oldcitation">` pour un compte connecté utilisant le style de citation classique, vs `<table class="citation">` en anonyme. Le parser ne connaissait que `citation`/`quote` → la citation était avalée et rendue en texte brut côté connecté uniquement (rendu OK en anonyme). `PostContentParser` reconnaît désormais `oldcitation` aux 3 points de classification + le sélecteur d'auteur. Test de régression avec fragment HTML réel capturé en mode connecté. Limitation connue tracée (TODO Phase 2) : le href de citation loggé `forum2.php?...#tM` n'est pas matché par `CITATION_HREF_REGEX`, donc le « aller au message cité » reste inactif en connecté (la citation s'affiche correctement).

### Changed
- `app/build.gradle.kts` : `versionCode = 62`, `versionName = "0.3.22"`.

---

## v61 — `0.3.21` — 2026-05-25

**Statut** : `closed`
**Commit** : `workflow_dispatch` sur `feature/phase2-finish-ui-polish-198-199-201-202` (run #26388655525, success)
**Fichier** : AAB uploadé sur le canal Play closed alpha

Slice maintenance alpha sur la PR #207 — réponse à la régression bordure invisible AMOLED v60 et bug quote stale persisté.

### Added
- Paramètres alpha : carte « Maintenance alpha » avec une action « Vider le cache des topics » (dialogue de confirmation Material 3, feedback inline succès / échec, indicateur de progression M3 pendant le wipe). Wipe les tables Room `posts` + `topic_pages` au sein d'une `@Transaction` ; **ne touche pas** aux drapeaux, à la session HFR, aux préférences proxy ni à la base de données globale (pas de `clearAllTables()`). Escape hatch pour forcer un reparse au prochain affichage quand le `PostContent` AST persisté est devenu obsolète.
- Paramètres alpha : switch « Ignorer le cache topic » dans la carte « Maintenance alpha ». Quand actif, `TopicRepositoryImpl.observeTopicPage` saute la lecture Room et part directement sur le réseau (le résultat est toujours persisté pour rester cohérent avec le parser courant), et `prefetch()` devient no-op. Outil de dogfood alpha uniquement — les drapeaux, l'authentification, le proxy et les préférences non liées sont intacts. Préférence persistée dans DataStore (`ignore_topic_cache`, default `false`).

### Fixed
- Settings : race d'hydratation du toggle « Ignorer le cache topic ». Quand l'utilisateur flippait le switch avant que la coroutine d'init n'ait fini de lire la valeur DataStore initiale, l'hydration tardive écrasait le flip optimiste avec la valeur stale (le toggle pouvait afficher `false` alors que DataStore était à `true`). Guard ajouté : `ignoreTopicCacheTouchedLocally` empêche l'hydratation d'écraser une modification locale, et `onSuccess` ré-affirme `ignoreTopicCache = desired` pour une cohérence finale quel que soit l'interleaving.

### Changed
- `app/build.gradle.kts` : `versionCode = 61`, `versionName = "0.3.21"`.

---

## v60 — `0.3.20` — 2026-05-24

**Statut** : `closed`
**Commit** : workflow_dispatch sur `feature/phase2-finish-ui-polish-198-199-201-202` avant merge PR #207
**Fichier** : artefact CD `dispatch-v60`

Codex rereview corrections appliquées au polish v59 — pas de nouvelle feature, uniquement des fixes ciblés.

### Fixed
- QuoteFrame : la bordure verticale d'accent est désormais dessinée via `Modifier.drawBehind` sur la Column (largeur hard-codée en pixels), au lieu d'un `Box.matchParentSize().width(4.dp)` qui risquait de peindre l'accent sur toute la largeur du card selon l'ordre de résolution des contraintes Compose. Aucune mesure intrinsèque ni enfant match-parent — sans danger pour les quotes contenant `[img]` (SubcomposeLayout).
- A11y avatar : la branche image chargée annonce maintenant « Avatar de <pseudo> » comme la branche placeholder standalone (avant : pseudo brut sans préfixe « Avatar de »). Une seule string localisée `R.string.avatar_content_description` utilisée pour les 2 modes.
- KDoc `BADGE_SIZE` : retiré la mention erronée que `Surface(onClick = ...)` injecte automatiquement le 48dp interactif. C'est `Modifier.minimumInteractiveComponentSize()` appliqué explicitement qui fait le travail.

### Changed
- `app/build.gradle.kts` : `versionCode = 60`, `versionName = "0.3.20"`.

---

## v59 — `0.3.19` — 2026-05-24

**Statut** : `closed`
**Commit** : tag `app-v59` après merge PR #207
**Fichier** : artefact CD `app-v59`

Phase 2 finish UI polish (#198 / #199 / #201 / #202).

### Added
- Menu compte global accessible depuis Drapeaux, Forum, Recherche et Messages : avatar / login-logout / paramètres alpha / diagnostics / signalement / version. Sortie unique de l'onglet `Messages` qui devient un placeholder Phase 3 sobre (#198).
- Avatars des auteurs HFR dans chaque post du topic (carré à coins arrondis, placeholder initiale quand l'URL est nulle / erreur, partagé via `:core:ui/RedfaceUserAvatar`) (#201).

### Changed
- Drapeaux : refresh manuel déplacé dans le header compact (`TextButton` à côté du menu compte) au lieu d'un bouton pleine largeur en fin de liste. Toggle « cyans déjà lus » passé en `FilterChip` Material 3 sous le tab row CYAN (#199).
- Citations : `QuoteBlock` et `CollapsedQuoteBlock` gagnent une bordure verticale d'accent (4dp, `primary`/`tertiary` alterné par profondeur) sur le `surfaceContainerHighest` existant — la régression d'invisibilité AMOLED est résolue, les quotes restent identifiables sur les 3 thèmes (clair/sombre/AMOLED). La règle `MAX_VISIBLE_QUOTE_DEPTH = 3` et le collapse au-delà sont préservés (#202).
- `app/build.gradle.kts` : `versionCode = 59`, `versionName = "0.3.19"`.

### Fixed
- A11y : badge compte expose `Role.Button` + `Modifier.minimumInteractiveComponentSize()` (48dp touch target sur 40dp visuel), `semantics(mergeDescendants=true)` empêche la double annonce TalkBack (review round 2 PR #207).
- A11y : avatar utilisateur announce « Avatar de <pseudo> » dans les deux modes (image chargée et placeholder initiale standalone), au lieu de rester muet.
- QuoteFrame : la bordure verticale d'accent est dessinée directement via `Modifier.drawBehind` (sans mesure intrinsèque ni enfant `matchParentSize`), ce qui évite le crash `IllegalStateException` "Asking for intrinsic measurements of SubcomposeLayout" qui touchait les citations contenant un `[img]` et garantit une bordure 4dp exacte indépendamment de l'ordre de résolution des contraintes Compose.

### Removed
- `MessagesViewModel` + son test (logique compte/logout déplacée dans `AppAccountViewModel` côté `:app`).
- Strings devenues mortes dans `:feature:messages` : 13 strings `messages_section_account`, `messages_auth_loading`, `messages_anonymous_intro`, `messages_login_cta`, `messages_logged_in_as`, `messages_logout_cta`, `messages_section_alpha_tools`, `messages_app_version_footer`, `messages_diagnostics_cta`, `messages_settings_cta`, `messages_report_content_cta`, `messages_report_email_subject`, `messages_report_no_email_client`. Les versions globales équivalentes vivent dans `:core:ui/account_menu_*`.
- String `flags_show_read_participated_toggle` (remplacée par `flags_show_read_participated_chip` pour le FilterChip).

---

## v58 — `0.3.18` — 2026-05-24

**Statut** : `closed`
**Commit** : tag `app-v58` après merge PR #204
**Fichier** : artefact CD `app-v58`

Phase 2 finish — rechargement du topic après publication.

### Fixed
- Reply / quote / edit / edit FP : après une soumission acceptée par HFR, l'écran topic force maintenant le rafraîchissement de la page cible au lieu de réafficher un cache stale.
- Reply simple : retour en bas de la page fraîchement rechargée quand HFR renvoie l'ancre `#bas`.
- Quote / edit / edit FP : extraction de `#t{numreponse}` depuis l'URL de succès HFR pour revenir directement au post créé ou modifié.
- Échec de rafraîchissement post-submit : feedback utilisateur explicite via Toast, avec fallback sur le cache existant plutôt qu'un écran cassé.

### Changed
- `app/build.gradle.kts` : `versionCode = 58`, `versionName = "0.3.18"`.

---

## v57 — `0.3.17` — 2026-05-24

**Statut** : `closed`
**Commit** : à venir (workflow_dispatch sur branche PR #194 avant merge / tag)
**Fichier** : artefact CD `dispatch-v57`

Hardening review PR #194 : proxy HFR-only, `isSaving` non-zombie, dropdown EditFP M3, charset UTF-8 credentials proxy.

### Fixed
- Paramètres proxy : un échec DataStore ne laisse plus le bouton Enregistrer bloqué en chargement.
- Edit FP : le dropdown sous-catégorie utilise le composant Material 3 `ExposedDropdownMenuBox` pour fiabiliser le tap sur champ read-only.
- Proxy : retrait du champ `scheme` non livré pour éviter de promettre un proxy HTTPS natif alors que le MVP supporte le proxy HTTP classique avec `CONNECT`.
- Proxy : le proxy utilisateur est désormais limité aux domaines HFR (`hardware.fr` / `*.hardware.fr`) pour éviter de casser les images externes lorsque le proxy ne route que HFR.
- Proxy : credentials Basic encodées en UTF-8 (plutôt que ISO-8859-1) pour éviter une boucle 407 silencieuse sur mots de passe proxy accentués.

### Docs
- ADR-012 ajoutée pour cadrer le stockage local des credentials proxy.
- Specs architecture réalignées : `:feature:settings` contient maintenant le `SettingsScreen` alpha.

### Changed
- `app/build.gradle.kts` : `versionCode = 57`, `versionName = "0.3.17"`.

---

## v56 — `0.3.16` — 2026-05-23

**Statut** : `closed`
**Commit** : build de test avant merge PR #194
**Fichier** : artefact CD `dispatch-v56`

Phase 2 close-out — réglage proxy alpha, polish recherche / Edit FP et helper image URL éditeur.

### Added
- Paramètres alpha accessibles depuis l’onglet Messages, avec proxy HTTP utilisateur : hôte, port, auth Basic optionnelle, persistance DataStore et application au réseau OkHttp + images Coil après redémarrage.
- Guide utilisateur `docs/guides/proxy.md` pour configurer et dépanner le proxy.
- Dialog d’insertion d’image par URL dans la toolbar BBCode partagée : génère `[img]https://...[/img]` depuis Reply / Quote / Edit / Edit FP / New topic.

### Fixed
- Recherche : les pivots de catégories restent sur une seule ligne horizontale avec ellipsis, évitant les libellés verticaux sur mobile.
- Edit FP : la sous-catégorie est modifiable via le même dropdown que la création de topic.

### Changed
- `app/build.gradle.kts` : `versionCode = 56`, `versionName = "0.3.16"`.

---

## v55 — `0.3.15` — 2026-05-23

**Statut** : `closed`
**Commit** : `8691c69`
**Fichier** : artefact CD `app-v55`

Phase 2G-B — release finale recherche après rebase sur `main`, hotfix release workflow et publication `app-v55`.

### Fixed
- Le changement de mode Titres + messages / Titres / Messages conserve maintenant la catégorie HFR déjà sélectionnée via le pivot au lieu de repartir silencieusement sur toutes les catégories.
- KDoc restante `SearchUiState` alignée sur Phase 2G-A/B.

### Changed
- `app/build.gradle.kts` : `versionCode = 55`, `versionName = "0.3.15"`.
- Release publiée via tag `app-v55` depuis `main`, pour éviter de réutiliser le `versionCode=54` déjà consommé par la build Alpha de test.

---

## v54 — `0.3.14` — 2026-05-23

**Statut** : `closed`
**Commit** : `04d8944` (build de test avant rebase/sync PR #183)
**Fichier** : artefact CD `dispatch-v54`

Phase 2G-B — polish final recherche avant test Alpha.

### Fixed
- Le texte d'accueil de la recherche reflète maintenant le comportement réel : ouverture du message correspondant quand HFR fournit un `numreponse`, sinon ouverture du topic.
- Les libellés Phase 2G-A/B et la roadmap sont réalignés après l'ajout des modes Titres + messages / Titres / Messages.

### Changed
- `app/build.gradle.kts` : `versionCode = 54`, `versionName = "0.3.14"`.
- Notes Play Console conservées sur le périmètre recherche 2G-B.

---

## v53 — `0.3.13` — 2026-05-23

**Statut** : `closed`
**Commit** : `0ae8d75` (build de test avant merge PR #183)
**Fichier** : artefact CD `dispatch-v53`

Phase 2G-B — polish recherche avant nouvelle alpha.

### Added
- Recherche par défaut en mode « Titres + messages » (`titre=3`), avec choix explicite Titres + messages / Titres seuls / Messages seuls.
- Affichage de l'extrait « Dernier message correspondant » quand HFR le fournit pour une recherche dans le contenu.
- Navigation vers le post exact depuis un résultat de recherche contenu quand le lien HFR porte `numreponse`.
- Indication sobre des filtres auteur/date/pagination à venir.

### Fixed
- Le pivot catégories HFR est affiché comme un sélecteur horizontal de périmètre, pas comme une liste de résultats à ouvrir.
- Les cartes de résultats affichent leur catégorie/sous-catégorie pour clarifier le contexte.

### Changed
- `app/build.gradle.kts` : `versionCode = 53`, `versionName = "0.3.13"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v52 — `0.3.12` — 2026-05-22

**Statut** : `closed`
**Commit** : à venir (tag `app-v52` après merge de la PR de release)
**Fichier** : artefact CD `app-v52`

Phase 2G-A — recherche réelle de topics HFR par titre.

### Added
- Onglet Recherche branché sur l'endpoint HFR réel `forum1.php?recherches=1`.
- Recherche par titre en mode toutes catégories, avec pivots de catégories quand HFR renvoie plusieurs familles de résultats.
- Recherche scoped par catégorie depuis les pivots HFR.
- Fixtures réelles et tests parser / repository / ViewModel pour les quatre formes observées : aucun résultat, pivot unique, pivot multiple, catégorie explicite.

### Fixed
- Les résultats d'une recherche en vol ne peuvent plus remplacer l'état après saisie d'une nouvelle query.
- Les lignes de résultats malformées échouent explicitement au parser au lieu d'être silencieusement ignorées.
- La construction de l'URL `searchTopics()` est maintenant couverte par MockWebServer, y compris le mode anonyme.

### Changed
- `app/build.gradle.kts` : `versionCode = 52`, `versionName = "0.3.12"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v51 — `0.3.11` — 2026-05-22

**Statut** : `closed`
**Commit** : à venir (build de test avant merge PR #174)
**Fichier** : artefact CD `app-v51`

Phase 2F-B — premier picker de smileys dans l'éditeur BBCode.

### Added
- Bouton « Smileys » dans l'éditeur Reply / Quote / Edit.
- Bottom sheet Material 3 avec onglet Standard (25 smileys HFR intégrés) et onglet Wiki.
- Recherche live des smileys perso via l'endpoint HFR `message-smi-mp-aj.php`, avec debounce 300 ms et seuil de 3 caractères comme le composer web HFR.
- Insertion du token BBCode brut dans le texte (`:jap:`, `;)`, `[:haha jap]`, variantes `[:name:N]`) en conservant la convention HFR d'espaces autour.

### Fixed
- Les diagnostics du picker ne loggent plus la query complète ni l'identifiant numérique HFR.
- La recherche wiki en vol est annulée quand un smiley est sélectionné ou quand le picker est fermé.

### Changed
- `app/build.gradle.kts` : `versionCode = 51`, `versionName = "0.3.11"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v50 — `0.3.10` — 2026-05-22

**Statut** : `closed`
**Commit** : à venir (tag `app-v50` après merge de la PR de release)
**Fichier** : `redface2-v50-<sha>.aab`

Hotfix alpha — amélioration du remplissage automatique sur l'écran de connexion.

### Fixed
- `LoginScreen` expose les hints Android Autofill corrects : pseudo en `ContentType.Username`, mot de passe en `ContentType.Password`.
- Proton Pass, Bitwarden, Google Password Manager et les autres services Autofill ont maintenant un contrat explicite pour distinguer les deux champs.

### Changed
- `app/build.gradle.kts` : `versionCode = 50`, `versionName = "0.3.10"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v49 — `0.3.9` — 2026-05-21

**Statut** : `closed`
**Commit** : `c79789b`
**Fichier** : artefact CD `app-v49`

Phase 2E — création de topic depuis l'app. Un compte authentifié peut ouvrir le composer depuis une liste de catégorie, saisir un titre + contenu BBCode, choisir la sous-catégorie et poster via `bddpost.php` sans navigateur.

### Added
- FAB « Nouveau topic » dans `ForumCategoryScreen`, visible uniquement quand `AuthState.Authenticated`.
- `TopicFormMode.New` fonctionnel dans `TopicFormScreen` : titre, dropdown sous-catégorie obligatoire, toolbar BBCode, preview locale et options HFR.
- `NewTopicContext` / `NewTopicSubmitResult`, `TopicFormParser.parseNewTopic`, `HfrClient.getNewTopicForm()` / `submitNewTopic()`, et `TopicFormRepository.fetchNewTopicForm()` / `submitNewTopic()`.
- Fallback honnête après succès : navigation directe si un futur parser extrait `newTopicId`; sinon retour sur la sous-catégorie cible avec Toast.

### Changed
- `app/build.gradle.kts` : `versionCode = 49`, `versionName = "0.3.9"`.
- Notes Play Console mises à jour pour le track alpha.

### Fixed
- Documentation/KDoc nettoyées : `TopicFormMode.New` n'est plus décrit comme placeholder ou futur.
- `protocol-hfr.md` aligne la limite restante : le POST création est livré, seule la fixture de réponse succès dédiée manque encore pour extraire les ids du nouveau topic.

---

## v48 — `0.3.8` — 2026-05-21

**Statut** : `closed`
**Commit** : `265877c`
**Fichier** : artefact CD `app-v48`

Stabilisation post-review de l'édition du premier post avant d'attaquer la création de topic.

### Changed
- Hydratation `subject` et `draft` indépendante dans `TopicFormViewModel`.
- Parser FP durci : aucun fallback silencieux quand le `<select name=subcat>` n'a pas de sélection valide.
- Champs sondage sortis de `hiddenFields`; `TopicPollForm.fields` devient la source unique de passthrough.

---

## v47 — `0.3.7` — 2026-05-21

**Statut** : `closed`
**Commit** : `9256298`
**Fichier** : artefact CD `app-v47`

Phase 2D-B — édition du premier post d'un topic owned.

### Added
- `TopicFormScreen` réel pour `TopicFormMode.EditFirstPost` : sujet, contenu BBCode, options HFR et sous-catégorie.
- Parser topic-level `TopicFormParser` avec préservation des champs sondage et filtrage `password` / `delete`.
- Bouton « Modifier le premier message » quand HFR expose l'action sur le premier post.

---

## v46 — `0.3.6` — 2026-05-20

**Statut** : `closed`
**Commit** : `11ae858`
**Fichier** : artefact CD `app-v46`

Phase 2D-A — édition d'un post existant appartenant au compte authentifié.

### Added
- `PostEditorMode.Edit` fonctionnel via `EditPostRepository`.
- Détection `Post.isEditable` / `Post.isOwnPost` depuis la toolbar HFR.
- Submit edit via `bdd.php`, avec refresh topic + scroll vers le post édité.

---

## v45 — `0.3.5` — 2026-05-19

**Statut** : `closed`
**Commit** : `3e2a350`
**Fichier** : artefact CD `app-v45`

Correctif options HFR et radios/checkboxes du formulaire d'écriture.

### Fixed
- `ReplyFormParser` respecte la sémantique browser : radio/checkbox non cochée absente du POST.
- `MsgIcon` ne dérive plus vers la dernière radio du formulaire.

### Added
- Toggles signature, smilies et notification email dans `PostEditorScreen`.

---

## v44 — `0.3.4` — 2026-05-18

**Statut** : `closed`
**Commit** : `577c6b6`
**Fichier** : artefact CD `app-v44`

Phase 2C complète — reply + quote MVP.

### Added
- Bouton « Citer » par post quand HFR expose `numrep` + `ref`.
- Hydratation du draft avec le `[quotemsg=…]` prérempli par HFR.
- POST quote via le même `ReplyRepository` que la réponse simple.

---

## v43 — `0.3.3` — 2026-05-18

**Statut** : `closed`
**Commit** : `59667a3`
**Fichier** : artefact CD `app-v43`

Hotfix `NetworkOnMainThreadException` sur le flow reply.

### Fixed
- `DefaultReplyRepository.fetchReplyForm()` et `submitReply()` passent par le dispatcher IO injecté avant les appels OkHttp bloquants.

---

## v42 — `0.3.2` — 2026-05-18

**Statut** : `closed`
**Commit** : `7929ff8`
**Fichier** : artefact CD `app-v42`

Instrumentation alpha du flow reply.

### Added
- Diagnostics transport et mapping ViewModel autour du GET/POST reply.
- Logs sans `hash_check`, utiles pour qualifier les échecs alpha sans `adb`.

---

## v41 — `0.3.1` — 2026-05-18

**Statut** : `closed`
**Commit** : `9edfc21`
**Fichier** : artefact CD `app-v41`

Première instrumentation diagnostics du flow reply.

### Added
- `DefaultReplyRepository` écrit dans `DiagnosticsLog` sur GET, parse, POST et erreurs classifiées.
- Extraits HTML redacted sur échec parser.

---

## v40 — `0.3.0` — 2026-05-18

**Statut** : `closed`
**Commit** : `9679a51`
**Fichier** : artefact CD `app-v40`

Premier flux de mutation HFR réelle : réponse à un topic depuis l'app.

### Added
- `PostEditorMode.Reply` branché sur HFR : GET `message.php`, POST `bddpost.php`.
- `ReplyRepository`, `ReplyFormParser`, `ReplySubmitResponseParser` et erreurs HFR typées.
- Anti-double-submit et refresh de la page topic après succès.

### Changed
- `versionName` passe à `0.3.0` pour marquer la première mutation réelle.

---

## v39 — `0.2.0` — 2026-05-18

**Statut** : `closed`
**Commit** : `2ffdc39`
**Fichier** : artefact CD `app-v39`

Passage Phase 2 : protocole d'écriture HFR cartographié et socle éditeur local livré.

### Added
- Fixtures Phase 2A pour reply, quote, edit, création topic, anonyme, topic fermé, succès et erreurs HFR.
- `PostEditorScreen` / `PostEditorViewModel` local-only avec toolbar BBCode, preview locale et sélection préservée.
- `BbcodeContentParser` + `BbcodePreviewParser` pour la preview BBCode locale.

### Changed
- Convention app : `versionName` passe au semver pur (`0.2.0`), distinct de la version des specs/site.

---

## v38 — `0.1.0-phase1.7` — 2026-05-11

**Statut** : `closed`
**Commit** : `b5ef0b8`
**Fichier** : `redface2-v38-b5ef0b8.aab`

Build de polish pré-Phase 2 pour le track alpha Play. L'objectif est de présenter une app de lecture cohérente pendant la revue manuelle Play, sans faux boutons laissant croire que Recherche ou Messages sont déjà livrés.

### Changed
- Écran Drapeaux recentré sur la lecture : footer alpha retiré, liste + refresh + login/reconnect gardés.
- Les sujets CYAN déjà lus (`hasUnread = false`) sont masqués par défaut, avec un toggle « Afficher les sujets participés déjà lus ».
- Écran Messages transformé en surface temporaire « Compte + Outils alpha » : login/logout, version, diagnostics et signalement.
- Écran Recherche remplacé par une annonce sobre de la future recherche HFR Phase 2, sans bouton de topic démo.

### Docs
- Specs `architecture.md`, `mvi.md`, `navigation.md` et `roadmap.md` alignées avec le polish #154.

---

## v37 — `0.1.0-phase1.6` — 2026-05-10

**Statut** : `closed`
**Commit** : `1832ed1`
**Fichier** : `redface2-v37-1832ed1.aab`

Build de finalisation Phase 1. Pas de changement fonctionnel visible utilisateur — uniquement de l'instrumentation perf et des tests qui figent les invariants du `PostRenderer` pour Phase 2.

### Added
- `androidx.tracing` 1.3.0 (#143, closes #117) : 7 sections `rf2.topic.*` couvrent le parcours « ouvrir un topic et commencer à lire ». 4 sections sync (`network`, `body_read`, `parse_html`, `map_domain`) + 3 async (`room_read`, `room_write`, `first_content`). Catalogue stable dans [`docs/guides/profiling.md`](https://github.com/ForumHFR/redface2/blob/main/docs/guides/profiling.md), prêt à être consommé par un `TraceSectionMetric` macrobenchmark futur (#142).
- Test `core/ui` qui fige le contrat de profondeur de quote ≥ 3 collapsable (#138, closes #83).
- Test `core/ui` qui fige la symétrie d'ensemble du `MediaCounter` sur un AST non-trivial (#140, closes #139).

### Closed-out
- Phase 1 marquée ✅ livrée dans [`docs/specs/roadmap.md`](https://github.com/ForumHFR/redface2/blob/main/docs/specs/roadmap.md).
- Issues finalisation Phase 1 fermées : #28 (référence behaviors HFR — repris dans #81), #51 (primitives UI — `FlagItem` livré, `TopicRow`/`PostCard` reportés au 2e usage réel), #117 (tracing).
- Follow-ups Phase 2 ouverts : #141 (microbench parser), #142 (macrobench parcours topic), #131 (validation visuelle smileys dogfood), #130 (test Robolectric `fillMaxSize`).

---

## v36 — `0.1.0-phase1.5` — 2026-05-10

**Statut** : `closed`
**Commit** : `100038d`
**Fichier** : `redface2-v36-<date>-<sha>.aab`

Premier build CD avec auto-publish sur le track alpha. Pas de changement fonctionnel de l'app — c'est l'AAB qui valide bout-en-bout le nouveau pipeline avec `status: completed` (par défaut sur les tracks de test), pour ne plus avoir à activer le draft manuellement dans la Play Console après chaque upload.

### Changed
- `.github/workflows/release.yml` : ajout d'un input `play_release_status` au `workflow_dispatch` et d'un défaut intelligent (`completed` pour testing tracks, `draft` pour production).
- `app/build.gradle.kts` bump `versionCode = 36`, `versionName = "0.1.0-phase1.5"`. Le slot `v35` est marqué `closed` (uploadé manuellement sur le track alpha avant la mise en place du push API).

---

## v35 — `0.1.0-phase1.4` — 2026-05-08

**Statut** : `closed`
**Commit** : `4bc6210`
**Fichier** : `redface2-v35-4bc6210.aab`

Patch dogfood après extraction exhaustive du wikismilies HFR : le bucket carré `56sp × 56sp` de v34 rendait les petits smileys lisibles, mais ne respectait pas la forme dominante réelle du corpus. Sur 34 139 smileys perso, la première taille est `70×50` (8047 occurrences), suivie de `50×50` (2811), `67×50` (1142), puis de nombreuses variantes `W×50`.

### Changed
- `PostMediaDisplayPolicy.persoSmiley` : `56sp × 56sp` → `70sp × 50sp`.
- `ContentScale.Fit` reste la règle des smileys, mais le bucket cible devient corpus-first :
  - `15×15` devient `50×50`, lisible sans réserver une ligne carrée de 56sp.
  - `39×15` devient `70×27`, ratio préservé.
  - `50×50` reste `50×50`, taille native dominante.
  - `70×50` reste `70×50`, taille la plus fréquente du wikismilies.
  - `200×150` devient `67×50`, borne haute conservée.
- Les images inline `[img]` restent en `ContentScale.Inside` dans le bucket `240×180`.
- Invariant typographique resserré : `persoSmiley.placeholderHeight ≤ 2.5 × bodyMedium.lineHeight`.

### Tests
- `PostMediaDisplayPolicyTest` : dimensions 70×50sp, corpus `Fit` aligné sur wikismilies, séparation `smileyContentScale` / `inlineImageContentScale`, ratios extrêmes `1×100` / `100×1`, invariant `2.5×`.
- `PostRendererInlineTest` : bucket perso 70×50sp explicitement distinct du builtin 18sp.

---

## v34 — `0.1.0-phase1.3` — 2026-05-05

**Statut** : `burnt`
**Commit** : `21e04d6`
**Fichier** : `redface2-v34-20260505-21e04d6.aab`

Patch dogfood après retour visuel sur v32/v33 : les smileys perso en bucket `40sp` corrigent le chevauchement, mais sont trop petits sur smartphone. v34 garde le correctif clé de #129 (`fillMaxSize()` dans le placeholder `sp`), remonte le bucket perso à `56sp`, et repasse les smileys en `ContentScale.Fit` pour restaurer leur lisibilité.

Slot remplacé par v35 après analyse du crawl exhaustif wikismilies : `56×56` est lisible, mais la distribution réelle justifie un bucket `70×50`.

### Changed
- `PostMediaDisplayPolicy.persoSmiley` : `40sp × 40sp` → `56sp × 56sp`.
- Corpus attendu à density 1 avec `ContentScale.Fit` côté smileys :
  - `15×15` devient `56×56`, lisible sur smartphone.
  - `39×15` devient `56×22`, ratio préservé.
  - `50×50` devient `56×56`, léger upscale assumé.
  - `70×50` devient `56×40`, ratio préservé.
  - `200×150` devient `56×42`, borne haute conservée.
- Les images inline `[img]` restent en `ContentScale.Inside` pour ne pas agrandir une petite image arbitraire dans le bucket `240×180`.
- Invariant typographique assoupli de `2.5×` à `2.8× bodyMedium.lineHeight` : on privilégie la lisibilité des perso HFR sans revenir au bucket cassé `64sp`.

### Tests
- `PostMediaDisplayPolicyTest` : dimensions 56sp, corpus `Fit`, séparation `smileyContentScale` / `inlineImageContentScale`, ratios extrêmes `1×100` / `100×1`, invariant `2.8×`.
- `PostRendererInlineTest` : bucket perso 56sp explicitement distinct du builtin 18sp.

---

## v33 — `0.1.0-phase1.2` — 2026-05-05

**Statut** : `burnt`
**Commit** : `a55453a` puis `535b839`
**Fichier** : `redface2-v33-20260505-a55453a.aab`, `redface2-v33-20260505-535b839.aab`

Slot brûlé pendant le dogfood du correctif smileys perso. La trajectoire finale passe par v35 avec un nouveau `versionCode` pour éviter tout conflit Play Console / distribution interne.

---

## v32 — `0.1.0-phase1.1` — 2026-05-05

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v32-<date>-<sha>.aab`

Release Phase 1 close-out après merge de [#126](https://github.com/ForumHFR/redface2/pull/126) (rendu Compose des images et smileys HFR avec Coil 3) **et** [#129](https://github.com/ForumHFR/redface2/pull/129) (correctif visuel sur les perso smileys inline). Ferme [#109](https://github.com/ForumHFR/redface2/issues/109) et donc l'umbrella Phase 1 [#87](https://github.com/ForumHFR/redface2/issues/87).

Le `versionName` perd le suffixe `-phase1d` parce que toutes les sous-phases 1A → 1D sont désormais sur `main` ; on entre dans la stabilisation Phase 1 avant ouverture du canal Play Console internal testing ([#72](https://github.com/ForumHFR/redface2/issues/72)). Note sur la trajectoire : `versionCode 31` (`0.1.0-phase1.0`) a été buildé localement avec le bucket perso 64×64 + `ContentScale.Fit` issu de #126 ; un bug visuel a été reproduit en dogfood sur le post HFR #74625731 (perso smileys oversize, lignes de texte intrudées). Pas de v31 distribuée — on saute directement à v32 avec le fix.

### Added
- **`PostMediaDisplayPolicy`** (`:core:ui`) : politique de buckets pure JVM-testable pour les médias inline. Builtin smiley `18×18`, perso smiley `40×40`, inline image `240×180`, block image `min 160dp / max 480dp`. `ContentScale.Inside` (downscale only, **jamais d'upscale**) pour les médias inline — un perso 70×50 est ramené à un ratio préservé (≈ 40×29 à density 1), un perso 15×15 reste à 15×15 centré avec padding visible (pas de pixelisation par 4× upscale).
- **`SingletonImageLoader.Factory`** sur `RedfaceApplication` avec `AnimatedImageDecoder.Factory()` (`coil-gif`) : autoplay GIFs builtin (`:bounce:`, `:pt1cable:`) ET perso sans configuration par-call-site. minSdk 29 → pas de fallback `GifDecoder` legacy.
- **`SubcomposeAsyncImage`** sur `PostRenderer.ImageBlock` : slots loading / error visibles pendant le fetch ou si l'host (rehost.diberie.com, super-h.fr…) est offline. `defaultMinSize(160dp)` réserve une hauteur de placeholder pour éviter un layout jump quand la bitmap résout (cf. review Codex sur PR #126).
- **3 strings FR** pour les états image : `post_image_loading`, `post_image_error`, `post_image_error_with_alt`.
- **Aliases libs** `coil-core`, `coil-gif`, `coil-network-okhttp` exposés au module `:app` pour mettre le décodeur GIF + le fetcher OkHttp sur le classpath du `SingletonImageLoader`.
- **Fonction pure `insideScaledMediaSize(source, bucket)`** miroir de `ContentScale.Inside`, exposée pour tester le corpus HFR réel sans Compose runtime. `coerceAtLeast(1)` sur les sorties pour éviter qu'un ratio extrême (1×100) ne collapse une dimension à 0.

### Fixed
- **Smileys perso inline oversize** ([#129](https://github.com/ForumHFR/redface2/pull/129)) : trois facteurs cumulés diagnostiqués via arbitrage Codex et corrigés ensemble.
  1. Bucket perso `64sp × 64sp` dans un paragraphe `bodyMedium` avec `lineHeight = 20.sp` explicite : le placeholder faisait 3.2× la hauteur de ligne, le `LineHeightStyleSpan` figé contraignait l'expansion automatique du `PlaceholderSpan` → débordement vertical sur les lignes adjacentes. Bucket réduit à `40sp × 40sp` (`≤ 2.5 × bodyMedium.lineHeight`, invariant pinned dans les tests).
  2. `ContentScale.Fit` upscalait les petits sprites (`tinostar` 15×15 → 64×64 = 4× upscale pixelisé). Remplacé par `ContentScale.Inside` (downscale only) pour les trois call-sites inline.
  3. `Modifier.size(64.dp)` côté `AsyncImage` figé en `dp` pendant que le placeholder est en `sp` → divergence sous `fontScale ≠ 1`. Remplacé par `Modifier.fillMaxSize()` : l'image suit le placeholder en `sp`, robuste sous `fontScale ≠ 1` (accessibility).

### Tests
- `PostMediaDisplayPolicyTest` (pure JVM) : pin les dimensions des 4 buckets, invariant typographique `persoSmiley.placeholderHeight ≤ 2.5 × bodyMedium.lineHeight` (lecture dynamique via `RedfaceTypography`), invariant `inlineMediaContentScale === ContentScale.Inside`. Test corpus HFR réel `[(15,15), (39,15), (40,40), (50,50), (70,50), (200,150)]` via `insideScaledMediaSize`. Test ratios extrêmes `1×100` / `100×1` (garde anti-collapse via `coerceAtLeast(1)`).
- `PostRendererInlineTest` (pure JVM) : assert `PlaceholderVerticalAlign.Center` sur les trois chemins (builtin, perso, inline image). Vérifie que le bucket perso est bien `40sp` et **pas** le builtin (garde anti-collapse).

---

## v30 — `0.1.0-phase1d.2` — 2026-05-04

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v30-<date>-<sha>.aab`

Release Phase 1D après merge de [#123](https://github.com/ForumHFR/redface2/pull/123) : support natif des blocs monospace HFR `[fixed]` / `[code]`.

### Added
- **Parser PostContent** : `PostBlock.Fixed(text)` et `PostBlock.CodeBlock(text, language?)` sont produits depuis `<table class="fixed">` / `<table class="code">`, y compris quand les blocs sont imbriqués dans une citation.
- **Hints langue `[code lang]`** : la classe `<pre class="<lang>">` est exposée via `CodeBlock.language` (`cpp`, `java`, etc.). La coloration syntaxique HFR est volontairement aplatie en texte brut en Phase 1.
- **PostRenderer** : rendu Compose natif en `Card` monospace avec scroll horizontal et `softWrap = false`.

### Fixed
- **Indentation monospace** : le parser ne fait plus de `trim()` global sur les blocs `[fixed]` / `[code]`; seules les lignes vides structurelles en bordure sont retirées.
- **Scroll horizontal** : le conteneur monospace ne clamp plus sa largeur interne avant `horizontalScroll`.

### Tests
- Tests parser sur les fixtures réelles `topic_page_multipage.html` et `topic_redface2_p16.html`.
- Test de sérialisation JSON pour les nouveaux variants `Fixed` / `CodeBlock`.

---

## v24 — `0.1.0-phase1b.10` — 2026-05-01

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v24-<date>-<sha>.aab`

Durcissement final Phase 1B avant 1C : login failure, cookies, session expirée et spec HFR.

### Changed
- **Login HFR** : le POST `login_validation.php` utilise maintenant un cookie jar de staging avec redirects désactivés. Les `Set-Cookie` reçus sur une réponse 200 ou une redirection 302 ne sont commités dans le `PersistentCookieJar` qu'après classification `Authenticated`, donc `InvalidCredentials`, `RateLimited` ou `Unknown` ne peuvent plus installer une session par effet de bord.
- **Cookies persistants** : `PersistentCookieJar` sérialise les écritures avec une version de mutation ; un `clear()` plus récent gagne contre un `saveFromResponse()` plus ancien qui n'aurait pas encore atteint le store. `DataStoreCookieStore.observe()` fail-close sur payload corrompu sans terminer le flow, afin qu'une future écriture valide soit observée.
- **Session expirée** : `HfrClient.getFlagsPage()`, `getPrivateMessageListPage()` et `getTopicPage(useAuth = true)` lèvent maintenant `SessionExpiredException` si HFR redirige vers `/login.php` ou renvoie un formulaire login en HTTP 200. `getTopicPage(useAuth = false)` garde le passthrough anonyme pour le prefetch. L'écran Drapeaux affiche un message dédié avec action de reconnexion au lieu d'une liste vide trompeuse.
- **`protocol-hfr.md`** : documente le cookie `md_user` form-url-encoded (`Colonel MythO` -> `Colonel+MythO`), distingue absence de cookie et mismatch après décodage, et acte le staging cookie du login.

### Tests
- Tests MockWebServer sur login failure + `Set-Cookie` non persisté, login success + commit explicite, session expirée flags/MP/topic authentifié, passthrough topic anonyme, vraie page vide non confondue avec login.
- Tests cookie store sur `clear()` vs save stale et recovery après payload DataStore corrompu.

---

## v23 — `0.1.0-phase1b.9` — 2026-04-29

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v23-<date>-<sha>.aab`

Hotfix login pour les pseudos avec espace ou caractères spéciaux.

### Fixed
- **`AuthRemoteDataSource.classify`** décode maintenant la valeur du cookie `md_user` via `URLDecoder` avant comparaison avec le pseudo soumis. HFR pose le pseudo URL-form-encodé dans le cookie (espace → `+`, accents → `%XX`), donc un pseudo comme `Colonel MythO` matchait `Colonel+MythO` octet-à-octet → `LoginError.Unknown` alors que la session était en réalité valide. Confirmé sur l'alpha grâce au trail diagnostics. URLDecoder est wrappé dans `runCatching` pour fall-back sur la valeur brute si l'encodage est malformé. Test ajouté : `pseudo with space matches md_user cookie URL-form-encoded`.

### Notes
- v22 est restée locale (jamais distribuée) — bumpée à v23 directement pour livrer ce hotfix avec les diagnostics login + bouton « Copier ».

---

## v22 — `0.1.0-phase1b.8` — 2026-04-29

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v22-<date>-<sha>.aab`

Diagnostics login plus utiles : trace de la requête HTTP envoyée + bouton « Copier ».

### Added
- **`AuthRemoteDataSource`** logue maintenant le wire-form body envoyé à HFR (`Log.d login request: POST <url> body=pseudo=...&password=<redacted>`). Permet au testeur alpha de voir comment son pseudo est URL-encodé (espace → `+`, `@` → `%40`, accents → `%XX%XX`) avant qu'il n'arrive au PHP HFR — utile quand un pseudo « spécial » est rejeté et qu'on suspecte un désaccord d'encoding entre `FormBody` et le décodeur côté serveur. Le password est masqué via regex sur le buffer dumpé avant tout `Log.d` ou `diagnostics.record`.
- **Bouton « Copier »** dans `DiagnosticsScreen` : copie tout le ring buffer dans le presse-papiers en plain text (`HH:mm:ss.SSS  L  TAG  message` par ligne). Désactivé buffer vide. Toast de confirmation sur Android < 13 (Android 13+ affiche déjà l'overlay système « copié dans le presse-papiers »).

### Notes
- v21 buildée localement mais non distribuée — bumpée à v22 directement.

---

## v21 — `0.1.0-phase1b.7` — 2026-04-29

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v21-<date>-<sha>.aab`

Diagnostics in-app + corrections post-review round 2.

### Added
- **`DiagnosticsLog`** dans `:core:domain` — ring buffer 200 entrées en mémoire, exposé via `StateFlow<List<Entry>>`. In-memory only par design, pas de persistance disque.
- **`DiagnosticsScreen`** dans `:feature:flags` — viewer in-app accessible via le bouton « Diagnostics (alpha) » dans le footer. Auto-scroll vers la dernière entrée, code couleur par niveau (I vert, D bleu, W orange), boutons « Vider » / « Fermer ».
- **`DiagnosticsRoute`** dans la navigation `:app`.
- **Trail logcat + in-app** sur `AuthRemoteDataSource` : `Log.i` à chaque tentative (pseudo + length + codepoints, jamais le password), `Log.d` après réponse (HTTP code, body length, cookie names, présence de md_user en length seulement), `Log.w` sur chaque échec classifié.

### Changed
- **`LoginError.Unknown` mismatch cookie/pseudo** : embed maintenant un diagnostic factuel (`submitted len=X vs cookie len=Y, sameLength=true/false, caseInsensitiveMatch=true/false`) sans jamais embed la valeur du cookie.
- **`LoginUiState.Mode.Error`** gagne un champ `detail: String?` propagé depuis `LoginError.Unknown.detail` et `LoginError.Network.cause`.
- **`LoginScreen.ErrorBanner`** rend le détail en monospace sous le message localisé — un testeur diagnostique sans `adb logcat`.
- **`FlagsListParser`** : `replyCount` et `views` strippent maintenant tout char non-numérique via `digitsOnly()` au lieu de juste l'espace ASCII (robust contre NBSP `U+00A0` que HFR utilise pour grouper les milliers).
- **`FlagsListParser.totalPages`** : `coerceAtLeast(1)` au niveau parser, élimine "p.X/0" sur topic neuf.
- **`FlagsListParser.lastReadPage`** : fallback `totalPages` quand le row est lu et n'a pas d'anchor (drapeau lu sur favorisn).
- **`FlagRepository` cache mémoire** : le cache par onglet est maintenant explicitement borné à la session HFR courante (`clearSessionCache()` au logout / retour anonyme). Un nouvel accès avec un autre compte ne peut plus réémettre les drapeaux du compte précédent.
- **`FlagRepository.refresh()`** : émet `Loading` puis le résultat réseau frais, met à jour le cache session, et alimente le bouton « Actualiser » affiché sur les listes en succès.
- **`FlagItem`** dans `:core:ui` reçoit maintenant `metadata: String` pré-formaté par le caller — i18n boundary clean (plus de littéral français hardcodé dans le module partagé).
- **`FlagsRoute` `LazyColumn`** ajoute `Modifier.weight(1f)` pour que `FooterSlot` reste visible même avec 127 drapeaux (cyan tab) — `AuthenticatedBody` devient extension `ColumnScope`.
- **`FlagsRoute` `LazyColumn` `key`** passe de `flag.topicId` à `"${cat}-${topicId}"` — élimine le crash latent `IllegalArgumentException` si HFR retourne le même topicId dans deux cats.
- **Onglets HFR mapping** corrigé via fixtures réelles : `FlagType.CYAN` = `owntopic=1` = sujets participés (« Mes sujets »), `FlagType.RED` = `owntopic=2` = lus uniquement, `FlagType.FAVORITE` = `owntopic=3`.
- **`DefaultFlagRepository`** : cache mémoire par onglet après le premier succès — changer d'onglet puis revenir ne refetch pas et ne fait pas bouger l'état lu/non-lu.

### Fixed
- Tests `:core:network` activent `testOptions.unitTests.isReturnDefaultValues = true` pour mocker `android.util.Log` en JVM unit tests.
- **`DiagnosticsScreen`** : clés `LazyColumn` basées sur un `Entry.id` monotone au lieu de `timestampMillis + hash(message)`, supprimant le crash théorique sur deux logs identiques dans la même milliseconde.

### Notes
- v20 a été uploadée Play Console — versionCode 21 obligatoire pour cette release. CHANGELOG v20 existant conservé pour historique.

---

## v20 — `0.1.0-phase1b.6` — 2026-04-28

**Statut** : `internal`
**Commit** : à venir
**Fichier** : `redface2-v20-<date>-<sha>.aab`

Phase 1B.2-1B.5 livrée d'un trait : liste réelle des drapeaux HFR (parser + repository + UI + module feature).

### Added
- **`FlagsListParser`** dans `:core:parser` — parse `forum1f.php?owntopic={1,2,3}` (mes sujets cyan / lus uniquement rouges / favoris). Détection unread canonique sur `td.sujetCase1` (`closedb*` vs `closed`), classification via icône `td.sujetCase5` (`flag1` → cyan, `flag0` → rouge, `favoris` → favori) avec fallback sur le `defaultType` du listing. 6 tests, 3 fixtures HTML capturées sur HFR réel avec données sensibles nettoyées.
- **`Flag` data class** dans `:core:model` (remplace l'ancien placeholder `FlaggedTopic`) + `FlagType { CYAN, RED, FAVORITE }`. Champs `totalPages` (`td.sujetCase4`), `replyCount` (`td.sujetCase7`), `views` (`td.sujetCase8`) — colonnes alignées sur les headers HFR « Dern. page » / « Rép. » / « Lues ».
- **`FlagRepository`** dans `:core:domain` (`observe(type)` / `refresh(type)`) + `DefaultFlagRepository` dans `:core:data` (network-only, broadcast refresh via `MutableSharedFlow` par `FlagType`). 4 tests Robolectric+MockK.
- `DefaultFlagRepository` garde un cache mémoire par onglet après le premier succès : changer d'onglet puis revenir sur Favoris ne refetch pas implicitement et ne fait pas bouger l'état lu/non-lu sans action utilisateur.
- **`HfrClient.getFlagsPage(owntopic: Int)`** sur `@AuthenticatedClient` (les drapeaux sont une vue par utilisateur, owntopic ∈ 1..3 enforced par `require`).
- **`FlagItem` composable** dans `:core:ui` — pastille couleur (cyan/rouge/jaune, dimmed à 35% si lu), titre semi-bold si unread, footer `metadata: String` reçu pré-formaté par le caller (i18n boundary clean : `:core:ui` n'a pas de `strings.xml`).
- **Module `:feature:flags`** avec `FlagsRoute` + `FlagsViewModel` :
  - `flatMapLatest(authState)` pour ne montrer la liste que quand authentifié, retour anti-flicker en attendant `authState ≠ null`.
  - 3 onglets `PrimaryTabRow` (« Mes sujets » = `owntopic=1` cyan / « Lus uniquement » = `owntopic=2` rouge / « Favoris » = `owntopic=3`), source flow change avec `selectTab`.
  - Footer auth (pseudo, MP unread, version, bouton CSAE, bouton logout).
  - Bouton « Réessayer » sur état d'erreur (pas de `PullToRefreshBox` en 1B — viendra en 1D quand un cas d'usage le justifie).
  - Footer `FlagItem` formaté côté `:feature:flags` via `stringResource` (`flags_item_metadata_with_author` / `_no_author`).
  - 5 tests Turbine couvrant `flatMapLatest` + `selectTab` + `refresh` + `logout`.
- Navigation `:app` migre `entry<FlagsListRoute>` du placeholder `FlagsScreen` (supprimé) vers `FlagsRoute`. Lambda `onOpenFlag` pousse la vraie `TopicRoute(flag.cat, flag.topicId, flag.lastReadPage, scrollTo = flag.firstUnreadPostId.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt())` au lieu du `DEMO_TOPIC` hardcodé.

### Changed
- `docs/specs/architecture.md` documente `:feature:flags` (mermaid + tableau des modules).
- `docs/specs/models.md` remplace le placeholder `FlaggedTopic` par le vrai `Flag` + drift `lastReplyAt: String` justifiée (HFR renvoie une chaîne FR pré-formatée).
- `docs/specs/navigation.md` réécrit le snippet `entry<FlagsListRoute>` autour de `FlagsRoute(...)`.
- `docs/specs/roadmap.md` coche l'item « Écran Drapeaux ».

### Removed
- `app/src/main/kotlin/.../FlagsScreen.kt` (placeholder Phase 0).
- `app/src/main/kotlin/.../FlagsHomeViewModel.kt` (placeholder ViewModel à 1 string).
- Strings du placeholder dans `app/src/main/res/values/strings.xml` (déplacées dans `:feature:flags`).

### Notes
- L'API HFR `forum1f.php?owntopic=N` est **par utilisateur** : chaque rafraîchissement marque les topics vus côté HFR. Pas d'`@AnonymousClient` ici, c'est intentionnel — le prefetch non-authentifié est réservé à la pagination des topics.
- `Flag.firstUnreadPostId` est un `Long` côté domain mais `TopicRoute.scrollTo` un `Int` (limite Compose Navigation 3) ; le narrowing `takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()` est sûr en pratique (HFR `numreponse` plafonne ~10M).
- Konsist : architecture rules toujours vertes après ajout de `:feature:flags`.

---

## v19 — `0.1.0-phase1b.5` — 2026-04-28

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v19-<date>-<sha>.aab`

In-app reporting channel pour la conformité Google Play CSAE.

### Added
- **Bouton "Signaler un contenu"** sur `FlagsScreen` — `Intent.ACTION_SENDTO` avec `mailto:xat@azora.fr` + sujet pré-rempli `Redface 2 — Signalement`. Catch `ActivityNotFoundException` → Toast français quand aucun client mail n'est dispo.
- 3 strings : `report_content_cta`, `report_email_subject`, `report_no_email_client`.

### Notes
- La page CSAE (`docs/legal/csae/index.html`, déployée Phase 1B.1 sur GitHub Pages) **claim** explicitement ce mécanisme de signalement in-app — sans cette livraison, la déclaration au Play Console serait factuellement incohérente avec l'app.
- Reste sur `FlagsScreen` (point d'entrée toujours visible) en attendant un écran `:feature:settings` réel.

---

## v18 — `0.1.0-phase1b.4` — 2026-04-28

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v18-<date>-<sha>.aab`

Polish post-review : on traite la liste des findings encore ouverts (non-bloquants flaggés par superpowers + Codex + nouveaux surgis avec le feature MP).

### Added
- 5 tests `:core:data.messages.DefaultMessagesRepositoryTest` (Anonymous→null, Authenticated→count, network error→null, logout→null, refetch on re-Authenticated)
- 1 test `:core:network.cookie.PersistentCookieJarTest` `saveFromResponse with expired non-empty cookie removes the entry` (defensive complément à la deletion-marker)

### Changed
- **`PersistentCookieJar.loadForRequest`** — guard JVM-safe : refuse de bloquer le Main thread si un Looper est dispo. La Mutex `storeMutex` sérialise les écritures `save()` et `clear()` côté DataStore pour éliminer la race logout-vs-save sur disque
- **`PersistentCookieJarTest.loadForRequest before first store emission blocks until cookies arrive`** — `Thread.sleep(100ms)` fragile remplacé par `CountDownLatch` (supplier started) + boucle de poll (isDone stays false). Déterministe même sur runner lent
- **`DefaultMessagesRepository`** — log `Log.w` sur échec de fetch au lieu de swallow silencieux. Une ligne "MPs non lus" manquante dans FlagsScreen est maintenant diagnostiquable
- **`DefaultAuthRepository.observeAuthState`** — `distinctUntilChanged()` final, plus d'émissions Anonymous→Anonymous redondantes
- **Konsist anti-leak `@AnonymousClient`** — scope étendu à `/auth/` + `/messages/` (toutes deux authenticated-by-construction). Hardened contre star-import et FQN annotation usage
- **`docs/specs/protocol-hfr.md:40`** — URL canonique "Liste des MPs" corrigée : `forum1.php?cat=prive&...` (pas `message.php` qui ouvre le composer). Note ajoutée
- **`docs/specs/architecture.md`** — `MessagesRepository` documentée dans le bloc des interfaces `:core:domain`, paragraphe sur le pipeline 1B.1
- **`docs/guides/contributing.md`** — section "Dogfood : installer en parallèle d'une release Play" décrit l'overlay `.gradle-user/dogfood.init.gradle` (gitignored)

### Notes
- Aucun changement de comportement utilisateur observable vs v17
- L'overlay dogfood reste gitignored — pas de scénario `:app:bundleRelease` qui expose `applicationIdSuffix=.dogfood` à Play Console

---

## v17 — `0.1.0-phase1b.3` — 2026-04-28

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v17-<date>-<sha>.aab`

Bonus Phase 1B.1 : compteur de MPs non lus sur l'écran d'accueil, comme preuve « réellement loggé HFR » au-delà de la simple présence du cookie `md_user`.

### Added
- **`MessagesRepository.observeUnreadMpCount(): Flow<Int?>`** dans `:core:domain` — `null` quand anonyme ou avant la première résolution, non-null Int sinon. Sur logout, retourne à `null` à l'émission `Anonymous` suivante.
- **`PrivateMessageListParser`** dans `:core:parser/messages/` — Jsoup parse `tr.sujet img[src]`, compte les filenames `closedbp` (icône HFR « MP non lu »). Convention extraite du legacy v1 `HTMLToPrivateMessageList.java:31-32`, prouvée en prod sur ~10 ans.
- **`HfrClient.getPrivateMessageListPage(page = 1)`** — fetch authentifié `forum1.php?config=hfr.inc&cat=prive&page=1&...` (URL canonique du legacy v1, pas `message.php` que la spec citait par erreur — `protocol-hfr.md:40` à corriger plus tard).
- **`DefaultMessagesRepository`** dans `:core:data/messages/` — combine `AuthState` avec le fetch via `transformLatest` : seul un état `Authenticated` déclenche un fetch ; un échec réseau emit `null` (pas d'affichage spéculatif).
- **`FlagsHomeViewModel.unreadMpCount: StateFlow<Int?>`** + ligne `MPs non lus : N` rendue dans `FlagsScreen` sous le pseudo connecté.

### Tests
- 4 tests `:core:parser.messages.PrivateMessageListParserTest`
  - fixture HFR réelle (50 MPs, tous lus → 0 non lus)
  - HTML synthétique mixed read/unread (validation positive)
  - inbox vide
  - rows non-`tr.sujet` ignorés (anti-faux-positif)
- Fixture `private_messages_list_all_read.html` (122 KB) reprise du legacy `ForumHFR/Redface` (origine HFR prod, 2015 — DOM identique aujourd'hui).

### Notes
- Pas de pagination des MPs : seule la page 1 est fetchée (50 MPs/page côté HFR ; les non-lus sont triés en tête, donc cette page suffit pour l'UX « est-ce qu'il y a du nouveau ? »). La pagination sera traitée si une vraie liste UI atterrit (Phase 1C ou plus tard).
- Pas de pull-to-refresh : la valeur est rafraîchie au prochain login / kill+relance d'app. Suffisant pour preuve d'auth ; un refresh manuel viendra avec l'écran Messages dédié.

---

## v16 — `0.1.0-phase1b.2` — 2026-04-28

**Statut** : `local`
**Commit** : `15c6c34`
**Fichier** : `redface2-v16-20260427-15c6c34.aab` *(le stamp date utilise l'UTC du runner Docker — la build a été lancée le 28 avril ~00:12 Paris, soit encore le 27 en UTC)*

Rebuild administratif de Phase 1B.1 — `versionCode` 15 brûlé côté Play Console, nouveau code `16` requis. Aucun changement code vs v15.

### Notes
- Voir entrées v15 et v14 ci-dessous pour le contenu Phase 1B.1.

---

## v15 — `0.1.0-phase1b.1` — 2026-04-27

**Statut** : `local`
**Commit** : à venir (rebuild Phase 1B.1)
**Fichier** : `redface2-v15-20260427-<sha>.aab`

Rebuild administratif de Phase 1B.1 — `versionCode` 14 déjà uploadé sur Play Console, nouveau code `15` requis pour pouvoir réuploader. Aucun changement fonctionnel vs v14 ; le seul écart code est un polish post-review superpowers.

### Changed
- `AuthRemoteDataSource.classify()` — `LoginError.Unknown` distingue maintenant `"expected md_user cookie not set"` (cookie absent) de `"md_user cookie value mismatched the submitted pseudo"` (cookie présent mais valeur ≠ pseudo soumis). Auparavant les deux cas retournaient le même message « not set » menteur. Diagnostic logs côté dev plus précis ; comportement utilisateur identique (bandeau `LoginError.Unknown` localisé).

### Notes
- Voir entrée v14 ci-dessous pour le contenu Phase 1B.1 complet (login HFR + cookies persistants + AuthState global + Konsist anti-leak).

---

## v14 — `0.1.0-phase1b.0` — 2026-04-27

**Statut** : `local`
**Commit** : à venir (PR feature/1b-1-auth)
**Fichier** : `redface2-v14-20260427-<sha>.aab`

Phase 1B.1 livrée : login HFR utilisable de bout en bout.

### Added
- **Login HFR fonctionnel** — `LoginScreen` (`:feature:auth`) appelle `AuthRepository.login()`, qui POSTe `login_validation.php?config=hfr.inc` via le `@AuthenticatedClient`. Le cookie `md_user` retourné est persisté par `PersistentCookieJar` ↔ `DataStoreCookieStore`, donc la session survit kill/restart de l'app.
- **`AuthState` global** — `FlagsScreen` affiche maintenant `Connecté en tant que <pseudo> · Se déconnecter` ou un CTA `Se connecter à HFR`, alimenté par `FlagsHomeViewModel.authState`.
- **Erreurs typées** — `LoginError.{InvalidCredentials, RateLimited, Network, Unknown}` mappées en bandeaux français localisés dans `LoginScreen`.
- **`:core:auth` non créé** — l'architecture spec place le backbone auth dans `:core:network` (login + cookies) et `:core:data` (repository impl). Le module `:feature:auth` (déjà bootstrap Phase 0) ajoute juste l'UI.
- **Sécurité au repos** — `android:allowBackup="false"` + `fullBackupContent="false"` dans `AndroidManifest.xml` pour exclure les cookies des backups Google Drive (cf. ADR-002 amendé).
- **Konsist @AnonymousClient** (Refs #42, auth-side seulement) — règle architecturale qui interdit aux fichiers sous `/auth/` d'importer le qualifier `@AnonymousClient`. Catch le mismatch silencieux (cookies pas envoyés → session vue comme déconnectée) au build. Le pendant prefetch-side (assertion que `HfrClient.prefetch*` utilise `@AnonymousClient`) reste à activer quand le code prefetch atterrira — `#42` doit donc rester ouvert.
- **DataStore Preferences 1.2.1** — ajouté au version catalog. Persiste les cookies non chiffrés (cf. ADR-002 amendé : password en plaintext POST → chiffrement local redondant face à un attaquant runtime).

### Changed
- **ADR-002 amendé** — alignement avec la décision originale issue [#24 thème 13](https://github.com/ForumHFR/redface2/issues/24#issuecomment-3526003625) : DataStore non chiffré + FBE plateforme, sans clé Keystore custom (la rédaction initiale avait dérivé en réintroduisant AES/GCM Keystore).
- `InMemoryCookieJar` supprimé (aucun consumer prod ni test). Si un futur test a besoin d'un CookieJar isolé en mémoire, il sera réintroduit sous `src/test/`.

### Tests
- 6 tests `:core:data.auth.DataStoreCookieStore` (Robolectric, persist + filter expired + payload corrompu fail-closed)
- 10 tests `:core:network.cookie.PersistentCookieJar` (cache snapshot + merge + deletion-marker + init cold-start)
- 6 tests `:core:network.auth.AuthRemoteDataSource` (MockWebServer, success + 4 erreurs typées + identity mismatch)
- 7 tests `:core:data.auth.DefaultAuthRepository` (MockK + vrai `PersistentCookieJar` + fake CookieStore)
- 3 tests `:core:data.auth.AuthChainIntegrationTest` (MockWebServer + chaîne auth complète)
- 11 tests `:feature:auth.LoginViewModel` (Idle → Submitting → Authenticated/Error, debounce, error mapping)
- 1 test Konsist nouveau (anti-leak `@AnonymousClient` sur les paquets `/auth/`)

### Notes
- Drapeaux réels (parseFlags + `:feature:flags`) attendus en 1B.2 + 1B.3
- Détection session expirée (Interceptor sur 302 → `/login.php`) reportée — Phase 1B.3 ou plus tard
- Pas de biométrie / pas de relogin transparent (Option A actée dans ADR-002)

---

## v13 — `0.1.0-phase1a.1` — 2026-04-27

**Statut** : `local`
**Commit** : `7e42d89` (avant merge PR [#89](https://github.com/ForumHFR/redface2/pull/89))
**Fichier** : `redface2-v13-20260427-7e42d89.aab`

Premier AAB qui affiche sa propre version dans l'UI.

### Added
- `BuildConfig.VERSION_NAME` / `VERSION_CODE` exposés à Kotlin via `buildFeatures.buildConfig = true` ([4d469b8](https://github.com/ForumHFR/redface2/commit/4d469b8))
- `FlagsScreen` rend un footer `Redface 2 — v0.1.0-phase1a.1 (build 13)` (style `labelSmall`, `onSurfaceVariant`) — temporaire jusqu'à ce que `:feature:settings` exporte un About screen

### Changed
- `versionCode` / `versionName` migrent de l'init-script de signing (gitignored) vers `app/build.gradle.kts` (tracké), pour qu'ils soient lisibles, diffables, et accessibles à `BuildConfig`

---

## v12 — `0.1.0-phase1a` — 2026-04-26

**Statut** : `local`
**Commit** : `e749dbf` (avant footer version)
**Fichier** : `redface2-v12-20260427-e749dbf.aab`

Premier AAB Phase 1A complète : la lecture topic passe par le vrai pipeline réseau au lieu d'une fixture embarquée.

### Added
- `:core:network` complet : `HfrClient.getTopicPage(cat, post, page, useAuth)`, `InMemoryCookieJar` host-keyed, qualifiers `@AuthenticatedClient` / `@AnonymousClient` / `@HfrBaseUrl` (PR [#88](https://github.com/ForumHFR/redface2/pull/88))
- `:core:database` v1 : `TopicEntity` (PK `cat,post,page`) + `PostEntity` (PK `cat,numreponse`) + `TopicDao` transactionnel + `PostContent` JSON converter via kotlinx.serialization (PR [#88](https://github.com/ForumHFR/redface2/pull/88))
- `:core:domain.TopicRepository` interface (`Flow<Topic>` cache-aside) + impl `:core:data.TopicRepositoryImpl` (PR [#88](https://github.com/ForumHFR/redface2/pull/88))
- `TopicScreen` lit le vrai topic HFR via `TopicRepository.observeTopicPage(...)` au lieu de `TopicFixtureRepository` (PR [#89](https://github.com/ForumHFR/redface2/pull/89))
- 3 tests d'intégration `:core:data` (`MockWebServer` + Robolectric, fixture `topic_page_single.html`)
- 10 tests `:core:ui` (`parseColor`, `buildInlineText`, `collectInlineMedia` — invariant `MediaCounter` + récursion sur les 6 containers)
- 5 tests `:feature:topic` (cache+fresh, fail-no-cache, fail-after-cache → cache préservé, retry)

### Changed
- `Mode.Placeholder` retiré de `TopicUiState` — toute paire `(cat, post)` est légitime
- `availablePages` dérivé de `topic.totalPages` à chaque émission
- UX cache-first : un échec réseau **après** un cache hit garde le contenu visible

### Removed
- `TopicFixtureRepository`, `FixedTopicFixtures`, `AssetTopicFixtureRepository`, `provideTopicFixtureRepository` (mort code après le rebind)
- 3 fixtures HTML embarquées dans `app/src/main/assets/topic_khakha/` (~415 KB)

### Notes
- Pull-to-refresh, Snackbar pour le silent fail réseau, snapshot test quote depth ≥ 3 sont déférés à Phase 1D Polish
- Konsist `@AnonymousClient` rule (issue [#42](https://github.com/ForumHFR/redface2/issues/42)) déférée à Phase 1B (besoin du vrai code prefetch à scanner)

---

## v11 — `0.1.0-conventions` — 2026-04-26

**Statut** : `local`
**Commit** : `2f86051` (avant les 1A backbone + bind)
**Fichier** : `redface2-v11-20260426-2f86051.aab`

Premier AAB signé reproduit-iblement via `--init-script` Gradle. Phase 0 finie + slice topic fixe.

### Added
- Slice topic fixe : `TopicScreen` rend une fixture HFR réelle (`topic_khakha_page_146.html`) via `:core:parser` → AST `PostContent` → `:core:ui` `PostRenderer` (PRs [#78](https://github.com/ForumHFR/redface2/pull/78), [#80](https://github.com/ForumHFR/redface2/pull/80))
- Bottom-nav 4 onglets, navigation Compose Navigation 3, thème Material 3 clair / sombre / AMOLED
- Pipeline build signed AAB stamping `redface2-v<N>-<YYYYMMDD>-<sha>.aab`

### Notes
- Pas de réseau réel — `TopicFixtureRepository` charge un asset embarqué
- Login HFR / Drapeaux réels / Forum réel arrivent en Phase 1B / 1C
