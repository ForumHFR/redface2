---
title: Contrat de rendu des images
parent: Spécifications
nav_order: 11
permalink: /specs/images
---

# Contrat de rendu des images
{: .fs-8 }

Le contrat normatif du rendu des images dans les posts (topologie bloc/inline, sizing, états anti-CLS, décodage, smileys, interactions), passe [#876](https://github.com/ForumHFR/redface2/issues/876). Version **v1.6** (amendements v1.6-1 à v1.6-9 du 12/09/2026 : plafond d'agrandissement par densité, régularisation des dérives D1-D8). Source canonique depuis le 12/09/2026 ; les versions antérieures vivaient hors dépôt.
{: .fs-5 .fw-300 }

> Historique : v1.5 gelée le 20/07/2026, amendements v1.5-1 à v1.5-5 (juillet), v1.6-1 à v1.6-10 (12-13/09/2026 : option A arbitrée par XaTriX après audit et challenge Astra ; dérives D1-D8 régularisées, textes challengés par Astra — chapeau [#1334](https://github.com/ForumHFR/redface2/issues/1334)). Les chemins `redface2-work/…` et les journaux `SUIVI-*` cités dans le corps désignent l'espace de travail interne du chantier, non versionné.

> Statut : v1.4 GELÉ — six amendements [AMENDEMENT-Lot0-1..6] gatés GO par Sol (xhigh, 19/07) et
> approuvés par XaTriX (19/07, #876) ; E12 GO r3, gel consigné. Remplace la v1.3.
> **v1.5 GELÉ (20/07)** : #884 intégré à la passe (nouveau Lot 5) + fraction largeur d'image DÉDIÉE
> (Lot 3, `IMAGE_RELATIVE_MAX_WIDTH_FRACTION`, défaut **0,95**). Découpage cadré Sol GO
> (`logs/SOL-884-integration-cadrage.log`) ; gel gaté Sol, GO consigné au dernier re-gate
> (`logs/SOL-884-v15-gel-r*.log`). **D1 (défaut
> 0,95) approuvée XaTriX** ; **D2 (configurabilité du Lot 5) différée au prototype du Lot 5**.
> Amendements v1.5 marqués `[AMENDEMENT-v1.5-n]`. Trace #876/#884 par §0.
> **[AMENDEMENT-v1.5-3] (27/07)** : §3 bis — contrat DÉDIÉ aux smileys HFR (builtin, perso, état MORT
> #416, picker), créé pour que leur rendu cesse d'être une simple exclusion « intouchable » et devienne
> opposable. Insertion **additive** : aucune clause existante n'est modifiée ni supprimée. Structure
> seule — **aucune valeur cible** n'est fixée, les arbitrages Q1-Q5 et Q6-B relèvent de #989 après
> constat mesuré. Rédigé par **Sol**, gaté par **Claude Fable 5** (NO-GO r1 et r2, GO r3), approuvé
> XaTriX le 27/07.

> Historique : v1.2 co-rédigée Claude ↔ GPT-5.6 Sol (xhigh). r1 (cadrage) : « à retravailler » →
> prescriptions intégrées. r2 (gate) : NO-GO → 7 corrections + G1/G2/G3 intégrés. r3 : NO-GO
> résiduel sur la seule formule de décodage (ratio) → rectifiée ; Sol : « après cette
> rectification : GO, Lot 0 seul démarrable avant gel ». v1.4 hérite du cadrage Sol GO r2
> (`redface2-work/logs/SOL-955-cadrage-r2.log`) : les items E1-E12 sont CONFORMES. Chaque
> évolution normative vs v1.3 est marquée `[AMENDEMENT-Lot0-n]` inline, avec sa justification
> sourcée (gate `redface2-work/logs/SOL-955-amendements-gate.log`, 19/07/2026). v1.3 archivée :
> `contrat-images-876-v1.3-archive.md`.
> Remplace la « Spec v1 » du 11/07 (via v1.2/v1.3).
> Fixtures : `fixture-tinc.html` (cat=13 p.15919), `fixture-charlee.html` (cat=14 p.2793),
> `hfr-forum2.js`, `core/parser/src/test/resources/fixtures/topic_page_single.html`.

> **[AMENDEMENT-v1.5-4] (28/07)** : §8 — option 3 approuvée par XaTriX pour le chantier #876 ; gate r2 GO, trois nits intégrés.
> **[AMENDEMENT-v1.6-1] (12/09/2026)** : §3 — plafond d'agrandissement par densité `max(1, min(densité, 3))` pour toutes les images de contenu (option A, XaTriX) ; profil GIF S/M/L retiré ; challenge Astra intégré. Version du contrat : **v1.6**.

## 0. Gouvernance

- Ce contrat est LA source de vérité du rendu des images.
- **Une PR d'implémentation ne modifie JAMAIS le contrat en silence** : toute modification
  normative passe par un amendement explicite (commentaire #876 approuvé) AVANT la PR.
  Le cadrage de chaque lot est un contrôle de CONFORMITÉ au contrat, pas une occasion de
  recalibrer (prescription Sol r1).
- Invariants d'architecture : policy pure JVM-testable ; caps relatifs passés par l'appelant ;
  anti-CLS au sens §6.
- Directives produit intouchables : fast-path cc-image (#256) ; smileys #175 (caps 240×70 sp +
  90 %, no-upscale — constantes séparées, intouchables).

## 1. Le contrat WEB réel (fixtures 15/07)

- **W1 — CSS** : `img { max-width: 90% }`.
- **W2 — JS `md_verif_size(_, _, 2, 250)`** sur TOUTES les images de contenu : si largeur
  native > viewport − 250 px → largeur imposée à viewport − 250 (ratio préservé), titre
  « Cliquez pour agrandir », clic → `window.open(originale)`. **Aucun cap de hauteur.**
- **W3 — espacement** : `style="margin: 5px"` serveur sur toutes les images de contenu
  (✅ vérifié sur les 3 pipelines, y compris `[img]` pur — fixture `topic_page_single.html`).
- **W4 — pipelines serveur** (le BBCode d'origine est invisible côté app, tout est `<img>`) :
  (a) `[img]u[/img]` → `<img>` nue ; (b) `[url=cible][img]u[/img][/url]` (dont le pattern
  rehost officiel) → `<a href=cible><img></a>` ; (c) une URL nue reste un LIEN texte `<a>` —
  **le forum n'affiche jamais d'image sans `[img]`** (erratum 15/07 : l'« auto-embed d'URL
  nue » décrit en v1.2 n'existe pas — c'est le FORM DE CITATION qui transforme `[img]X[/img]`
  en `[url]X[/url]`, d'où l'inférence erronée depuis hfr_quote ; prouvé sur le post DEV
  #2790879 : citation `[url=f][url]r[/url][/url]` vs HTML `<a href=f><img src=r>` vs pattern
  rehost source `[url=f][img]r[/img][/url]`).
  **Piège de tooling documenté** : ne JAMAIS inférer le BBCode source d'un post depuis le form
  de citation — il réécrit les images.
- Transposition mobile : W2 strict est dégénéré sur téléphone (~400 − 250 = 150 px). L'app
  transpose l'ESPRIT : bornée à l'espace, jamais upscalée, « agrandir » à portée de tap
  (point d'extension viewer, §8).

## 2. LE MODÈLE — topologie décidée par la STRUCTURE, jamais par la mesure

Principe cardinal (Sol r1) : la décision inline/bloc est prise sur l'AST seul, déterministe au
premier layout. Les dimensions mesurées ne servent QUE au sizing. Il n'existe AUCUNE
transition inline→bloc après coup.

### 2.1 Partition en segments

Chaque paragraphe est partitionné (ordre préservé) en `InlineSegment` (prose) et `MediaRun`.

**Un `MediaRun`** est une séquence maximale, dans un même conteneur structurel, composée de :
images de contenu ; `LineBreak` ; texte blanc ; wrappers de style (gras/italique/souligné/
barré/couleur) ne contenant que ces éléments ; liens ne contenant QUE des images (le `href`
reste attaché à chaque image du lien).

**Bornent un run** : texte non blanc, smiley, lien textuel (ou mixte texte+image), tout autre
inline, frontière de paragraphe, quote, spoiler. Un run ne traverse jamais quote/spoiler
(chaque conteneur a ses propres runs, avec sa largeur réduite).

### 2.2 Décision de rendu

Un `MediaRun` contient AU MOINS une image (une séquence de `LineBreak`/blancs seuls n'est pas
un run). La partition s'applique RÉCURSIVEMENT au contenu interne des quotes/spoilers (chaque
conteneur partitionne ses propres paragraphes, avec sa largeur réduite).

| Topologie | Rendu |
|---|---|
| Run de ≥ 2 images | BLOC (galerie empilée), toujours |
| Singleton dont les DEUX voisins structurels sont chacun une frontière de paragraphe ou un `LineBreak` (AST — une simple newline non transformée en `LineBreak` ne compte pas) | BLOC (G1 : l'isolation sur sa propre ligne est volontaire ; le paragraphe se compose en 3 morceaux en préservant ordre, sélection, a11y) |
| Singleton directement inséré dans du texte significatif | INLINE |
| Lien MIXTE texte+image (G3) | reste un `InlineSegment` entier : l'image demeure inline dans le lien, son tap ouvre le MÊME `href` que le texte — mais la hitbox de l'image porte EXPLICITEMENT le href + long-press (ne pas supposer que l'annotation texte la couvre ; garde de sélection retirée, [AMENDEMENT-Lot2-1]) |
| cc-image (marqueur #256) | INLINE fast-path 16×16, jamais promu, zéro probe |
| Smiley | INLINE (#175), jamais un « image de contenu » |

**Consommation des séparateurs** : les `LineBreak`/blancs absorbés dans un run bloc (ou qui le
bordent immédiatement) sont CONSOMMÉS et remplacés par l'unique espacement du §4 — ni ligne
vide ni espacement additionnel, y compris pour des `LineBreak` MULTIPLES (un multi-br autour
d'un bloc ne produit pas plus de 8 dp). Autour d'un rendu INLINE, les séparateurs sont
conservés tels quels (c'est du texte).

Conséquences sur les cas du fil : tinc → plusieurs runs blocs séparés par « 6 », « filles »
et le spoiler (qui contient son propre run) ; CharLee → un run de 3 blocs liés + prose.

`imageOnlyParagraphImages()` est REMPLACÉE par la partition (pas complétée) ;
`shouldPromoteImagesToBlocks()` disparaît comme arbitre de topologie.

## 3. Sizing (les mesures ne font QUE dimensionner)

| Chemin | Règles |
|---|---|
| BLOC | largeur ≤ `fImage` du conteneur (défaut 0,95, §9) ; hauteur ≤ `capBloc = min(hauteurUtile, max(400 dp, 0,5×hauteurUtile))` (#842, [AMENDEMENT-Lot0-3]) ; ratio préservé ; no-upscale |
| Équation unique (bloc ET inline) | `scale = min(1, maxWidthPx/wNatifPx, maxHeightPx/hNatifPx)` puis `wAffiché = round(wNatif×scale)` et `hAffiché = round(wAffiché × hNatif/wNatif)` — **la hauteur est DÉRIVÉE de la largeur arrondie par le ratio natif** (jamais arrondie indépendamment) ; no-upscale par le terme 1. Bloc : `maxHeightPx = capBlocPx` (formule clampée, [AMENDEMENT-Lot0-3] ci-après) ; inline : `200sp.toPx()`. **[AMENDEMENT-Lot0-3]** L'hôte calcule, sans arrondi intermédiaire : `hauteurUtilePx = max(0, containerSize.height − max(systemBars.top, displayCutout.top) − max(systemBars.bottom, displayCutout.bottom))` puis `capBlocPx = min(hauteurUtilePx, max(400 dp en px, 0,5 × hauteurUtilePx))`. `capBlocPx` est passé par l'appelant à la policy pure JVM comme `maxHeightPx` ; les types Compose et la lecture des insets restent dans l'hôte. Cette mesure ne participe JAMAIS à la décision inline/bloc de §2. **Mesures E11 (émulateur API 37, density 2,625, 19/07/2026)** : portrait containerSize 1080x2400 px, insets union t/b 279/63 px → hauteur utile 2058 px = 784,0 dp (écart de 130 dp pile vs `Configuration.screenHeightDp` = l'erreur que cette métrique corrige) ; paysage 2400x1080 px, union 200/63 px → 311,2 dp utile (écart ~100 dp, arrondi flottant ~0,4 dp). La formule est mesurable et cohérente sur les deux orientations ; multi-fenêtre non mesurable sur émulateur. **Mesures S10e (device réel, density 3,0 exacte, 19/07)** : portrait 360x760 dp, insets union 285/45 px → hauteur utile **650,0 dp** (Configuration : 706 dp) ; paysage 288,0 dp utile ; **split-screen réel (--windowingMode 3) : containerSize 360x388 dp → hauteur utile 301,0 dp** → `capBlocPx = min(301, max(400, 150,5)) = 301 dp`. **Cas limite RÉSOLU par le clamp** (tranché au gate A3, plus d'arbitrage E12 à faire sur ce point) : le cap suit désormais la fenêtre au lieu de la dépasser, sur toute configuration mesurée. Base validée CONFORME par Sol r2 (arbitrage A3) ; clamp ajouté et gaté GO par Sol au gate des
amendements du 19/07 (`SOL-955-amendements-gate.log`). `wNatif/hNatif` = dimensions natives ORIENTÉES (EXIF appliqué AVANT ce calcul — **confirmé par mesure sur API 37 et API 31 (S10e)** : Coil rapporte des dims orientées probe ET painter sur les deux ; API 29 non mesurée, hypothèse documentaire non bloquante, [AMENDEMENT-Lot0-6], §13). `coil3.Image.width/height` = px source : c'est LA source normative des dimensions natives |
| Autorité des dimensions | la PREMIÈRE paire de dimensions valides et orientées (probe ou painter) fixe la boîte ; aucune seconde correction si l'autre source diverge ensuite (le désaccord est logué, pas appliqué) — **confirmé nécessaire par mesure** : à cache chaud, l'ordre probe→painter fait varier la paire rapportée (painter observé 1051×788 après un probe distinct sur le même asset). La probe bornée 1024 ne doit JAMAIS écrêter les dimensions natives rapportées — **écart existant CONFIRMÉ** (mesuré sur API 37 : 4000×3000 → 1024×768 rapporté, EXIF 900×1200 → 768×1024 rapporté orienté ; le KDoc actuel d'`IntrinsicMediaSizeMeasurer` affirmant « reports past the cap » est FAUX sur ces API) — cette clause du contrat ne change PAS : l'écart est une non-conformité de l'implémentation actuelle, sa correction est **CIBLE Lot 3**, ce n'est pas un amendement de ce document |
| INLINE | hauteur ≤ 200 sp ; largeur ≤ `fImage` (défaut 0,95, §9) ; ratio préservé ; no-upscale. Le plancher 16 sp ne concerne QUE le slot/hitbox (placeholder), jamais le bitmap rendu |
| GIF | mêmes règles que statique ; boîte et ratio figés AVANT l'animation, aucune frame ne change le layout ; animation seulement visible+lifecycle actif, pause hors écran |
| Smileys / cc | inchangés (#175/#256) |

**[AMENDEMENT-v1.5-5 — 30/07/2026, décision #993, arbitré XaTriX (f = 0,70, plancher 400 dp conservé)]** — Relèvement du coefficient proportionnel du cap de hauteur des images BLOC. Section normative UNIQUE de ce changement ; `[AMENDEMENT-Lot0-3]` ci-dessus reste le texte historique gelé, il n'est PAS réécrit — la présente section le supplante sur la SEULE valeur du coefficient :

- **Formule effective** : `capBlocPx = min(hauteurUtilePx, max(400 dp en px, 0,70 × hauteurUtilePx))`. Tout le reste de `[AMENDEMENT-Lot0-3]` (métrique « hauteur utile de fenêtre », union des insets par bord, absence d'arrondi intermédiaire, aucune participation à la décision inline/bloc du §2, mesures E11) demeure normatif tel quel.
- **Ce qui change** : le coefficient passe de `0,5` à `f = 0,70` — une image bloc peut occuper 70 % de la fenêtre utile. Conséquence ASSUMÉE (arbitrage XaTriX, #993) : une capture d'écran de téléphone occupe au plus environ les deux tiers de l'écran ; l'en-tête du post et deux à trois lignes de prose restent visibles autour.
- **Rôle du plancher 400 dp (CONSERVÉ)** : il devient le garde des fenêtres COURTES — sous `400/0,70 ≈ 571 dp` d'utile c'est lui qui gouverne, puis le clamp fenêtre. Cela préserve à l'identique le comportement « cap = fenêtre entière » acté au gate A3 : split-screen S10e (301 dp utile → cap 301 dp) et paysage (288 dp → 288 dp) sont INCHANGÉS.
- **Effet mesuré (S10e portrait, densité 3,0)** : sur la fenêtre utile mesurée par sonde pour #993 (2124 px = 708 dp), cap = `round(0,70 × 2124) = 1487 px ≈ 496 dp` — la cible des ~500 dp — contre 400 dp avant amendement ; sur la mesure E11 du 19/07 (1950 px = 650 dp d'utile), cap = 1365 px = 455 dp contre 400.
- **Garde-fou de monotonie** : ce changement ne peut jamais RÉDUIRE le cap — pour toute hauteur utile `u`, `min(u, max(400, 0,70u)) ≥ min(u, max(400, 0,5u))` ; il l'augmente (dès que `0,70u > 400`, soit `u > 571 dp`) ou le laisse identique. Toute taille d'image bloc qui DIMINUE après cet amendement est une régression, pas un ajustement.
- **Ce qui ne change PAS** : aucun cap fixe en dp n'est introduit (pas de « 500 dp » codé en dur — le cap reste proportionnel, clampé par la fenêtre) ; slot COLD §6 (plancher 160 dp, ratio 0,75), `fImage`, équation unique §3, no-upscale et chemins inline/smileys/cc intouchés.

**[AMENDEMENT-v1.6-1 — 12/09/2026, décision XaTriX (option A « go A »), plan challengé par Astra le 12/09 (NO-GO du brouillon → GO conditionnel ; conditions intégrées ci-dessous)]** — Plafond d'agrandissement par densité pour TOUTES les images de contenu. Section normative UNIQUE de ce changement ; les textes v1.5-2 et v1.5-4 du §8 restent l'historique gelé et sont SUPPLANTÉS par la présente section sur le SEUL plafond no-upscale :

- **Objet** : correction bornée de lisibilité, PAS une invariance. Constat (audit 08-12/09, protocole des 5 liens sur S10e) : le no-upscale en pixels physiques rend la taille affichée dépendante de la densité (`natif / densité` dp) ; une photo de 820 px occupe 85 % de la colonne à densité 3,0 et 63 % à 3,5.
- **Formule** : pour tout média de contenu BLOC et INLINE, le plafond `1,0` de l'équation unique du §3 est remplacé par `mContenu = max(1, min(densitéÉcran, 3))` : `scale = min(mContenu, maxWidthPx/wNatif, maxHeightPx/hNatif)`, puis `wAffiché`/`hAffiché` comme au §3 (hauteur dérivée de la largeur arrondie). Hard caps INCHANGÉS : `fImage × largeurConteneur` (#991), `capBloc` (v1.5-5), `200 sp` inline, borne inline `largeurConteneur − 8 dp` de padding. Smileys (#175, §3 bis), cc-images (#256) et slot COLD §6 : INTOUCHÉS.
- **Portée assumée** : un pixel source est rendu par au plus `min(densité, 3)` pixels physiques — parité navigateur / RF1 jusqu'à densité 3 ; au-dessus (QHD+ 3,5) une image sous les caps reste `3/densité` de son équivalent dp (100 px → 86 dp), résiduel ASSUMÉ. Les caps empêchent le débordement, pas le flou : l'agrandissement jusqu'à ×3 d'une petite source est ASSUMÉ (renversement de la position « netteté » de juillet, arbitré XaTriX 12/09). `FilterQuality.Low` conservé.
- **Décodage §7 INCHANGÉ** : la boîte calculée avec `mContenu` alimente `decodeSizePx` ; le clamp au natif reste terminal ; pour `mContenu > 1` le décodage reste au natif et l'agrandissement se fait au draw ; budget 2048² inchangé.
- **Anti-CLS §6 INCHANGÉ** : l'unique correction cold → boîte exacte est calculée DIRECTEMENT avec `mContenu`, jamais une boîte à `1,0` corrigée ensuite. Les écarts E4 (painter lancé sur le slot froid) et E5 (cache géométrique évictable) sont des non-conformités PRÉEXISTANTES fichées séparément ; le présent amendement ne les corrige ni ne les aggrave.
- **Leviers §8 absorbés** : `mEffectif = max(mContenu, mGif, mApercu)` vaut `mContenu` dès densité ≥ 2,5 (S = M = L). En conséquence : (a) le profil `MediaDisplayProfile` (v1.5-2, levier C, réglage S/M/L) est RETIRÉ de l'UI et de la formule — la préférence persistée est ignorée (lecture tolérante, aucune migration destructive) ; régression assumée : profil L (×2,5) à densité < 2,5 ; (b) `mApercu = min(densité, 3)` (v1.5-4) est absorbé par `mContenu` — les gardes G1-G3 n'ont plus d'effet sur le sizing (la clause reste valide comme cas particulier) ; (c) l'exclusion « INLINE : plafond 1,0 » de v1.5-2 est SUPPLANTÉE : l'inline reçoit `mContenu`, sans toucher la conversion px→dp→sp, le padding §4 ni la hitbox.
- **§9** : nouvelle constante `CONTENT_UPSCALE_CEILING_MAX = 3,0` ; les facteurs S/M/L disparaissent. **§14** : les leviers A (fraction `PostImageMaxWidth`) et B (pleine largeur) restent indépendants ; le levier C disparaît. **§12** : le lot « profil GIF » est clos par retrait.
- **Garde de monotonie** : hors le cas L ci-dessus, aucune image de contenu ne peut être rendue PLUS PETITE qu'avant cet amendement (`mContenu ≥ 1` et `mContenu ≥ mApercu`). Toute diminution est une régression.
- **Tests exigés AVANT le code (TDD, liste Astra 12/09)** : policy pure JVM aux densités 0,75 / 1 / 2 / 2,625 / 3 / 3,5 (jamais au-dessus des caps ; jamais au-dessus de `min(densité,3) × natif` ; monotone en densité ; arrondis et ratios extrêmes ; hauteur dérivée) ; inline : fontScale élevé, P99/P100, padding et hitbox inchangés, smileys/cc inchangés ; GIF sans extension / extension mensongère / GIF statique → même plafond que statique ; miniature liée éligible et non éligible → même plafond ; décodage demandé ET obtenu au natif ; Roborazzi `PostRendererImageParityRoborazziTest` recapturée, `PostRendererGifProfileRoborazziTest` retirée ; réglages : ligne GIF absente, préférence ancienne ignorée. **Banc S10e (5 liens tinc)** attendu : L4a 820×545 → 95 % (cap), L2 562×768 → ~92 % (cap hauteur), L1 GIF 320×480 → ~83 %.
- **Gouvernance §0** : approbation XaTriX du 12/09 consignée dans #876 (commentaire) AVANT la PR d'implémentation ; le contrat est publié dans le dépôt (`docs/specs/images.md`), nouvelle source canonique ; cette copie de travail est archivée.

**[AMENDEMENTS-v1.6-2 à v1.6-9 — 12/09/2026, régularisation des dérives D1-D8 (audit 08-12/09, chapeau #1334), décision XaTriX « go sur tout le reste », textes issus du plan v1.6 et du challenge Astra du 12/09 (GO AMENDÉ intégrés, D3 réécrit), puis challenge Astra du 12/09 20:20-20:45 (NO-GO en l'état → textes de remplacement D1, D2, D3, D7, D8 intégrés VERBATIM, GO D4-D6)]** — Chaque section ci-dessous est la section normative UNIQUE de son objet ; les textes historiques qu'elle supplante restent gelés.

**[AMENDEMENT-v1.6-2 — D1 — §5 action primaire, §8 visionneuse]** Sur hôte actif (Topic, MP), les actions visionneuse et menu exigent une source `url` HTTP(S) éligible ; les cc-images gardent leur régime spécifique. Pour ces images : image BLOC non liée → tap = visionneuse interne sur `url` ; image INLINE non liée → aucun tap, menu par appui long uniquement ; image liée, inline ou bloc → tap = visionneuse sur `linkUrl` si `linkUrl` est une URL HTTP(S) éligible et « image » au sens de l'heuristique documentée de `PostImageViewerPolicy` (extension `jpg`/`jpeg`/`png`/`gif`/`webp`/`avif`, OU hôte de l'allowlist `reho.st`, `rehost.diberie.com`, `i.imgur.com` ; le type réel n'est PAS vérifié — limite assumée), sinon ouverture de `linkUrl` dans le navigateur. La source principale chargée par la visionneuse est `linkUrl` ; `url` peut fournir l'aperçu mémoire. Le point réservé « visionneuse » du §8 est CLOS (livré #182-C, #1279) ; renvoi normatif à `PostImageViewerPolicy`.

**[AMENDEMENT-v1.6-3 — D2 — §5 matrice par hôte]** Hôte MP : §5 complet (tap, appui long, a11y) comme Topic, avec la politique disque de v1.6-8. Aperçu éditeur et Signature n'exposent aucune action §5 sur l'image rendue ; le retry du slot d'erreur §6 reste disponible. `PostRendererHostMatrixTest` vérifie les actions MP et une demande de visionneuse portant `diskCache=false` ; `ImageViewerScreenTest` vérifie la politique disque de la requête Coil. La description de la matrice [AMENDEMENT-Lot0-1] est mise à jour en ce sens.

**[AMENDEMENT-v1.6-4 — D3 (réécrit) — §3 bis, picker de smileys]** Le picker de smileys de l'éditeur (`SmileyPickerLayout`, `smileyGridGeometry`, #989) est une surface déclarée au §3 bis au titre d'I5. Sa géométrie est distincte du rendu de post et n'en modifie aucun cap.

La largeur disponible est celle de la grille après marges de 8 dp par côté. Le solveur vise une cellule de 75,6 dp de largeur : nombre nominal arrondi, plancher de cinq colonnes dès 324 dp disponibles et quatre en dessous, borné par le nombre de cellules minimales de 56 dp pouvant tenir, avec au moins une colonne. Les seuils et divisions de largeur utilisent les dimensions arrondies en pixels physiques. La largeur de référence est celle de la cellule la plus étroite ; sa hauteur vaut max(largeur / 1,4 ; 48 dp). Espacement nominal : 4 dp ; rendu : 0 dp en mode séparateurs, 4 dp sinon.

Le cap perso soustrait 4 dp au total par axe. À 360 dp, densité 3, sans séparateurs : cap 61,33×44 dp ; source 70×50 px rendue dans une boîte 61×44 dp. Perso mesuré : échelle min(capLargeurDp/largeurNativePx, capHauteurDp/hauteurNativePx, 1), dimensions arrondies séparément en dp ; perso non mesuré : boîte égale au cap. Builtin : boîte permanente 20×20 dp, sans probe.

« Preseed » désigne les dimensions initiales, pas un préchargement de bitmaps. Le picker mesure les cellules WIKI composées et alimente le cache intrinsèque partagé ; ses images sont chargées par `AsyncImage`. Dans les posts, les valeurs initiales restent 16×16 pour les builtin et 70×50 pour les perso.

I1-I8 restent intégralement opposables, aux politiques propres comme aux chemins partagés. Toute évolution concernée exige déclaration, amendement explicite du §3 bis et vérification des chemins smileys.

**[AMENDEMENT-v1.6-5 — D4 — §3, §6, §9, §14]** `fImage` est la valeur du réglage utilisateur `PostImageMaxWidth` ∈ {0,90 ; 0,95 ; 0,99 ; 1,00}, défaut 0,95 (#991), appliquée à l'identique aux trois chemins (inline, bloc mesuré, slot froid). Inline, la largeur du bitmap est de plus bornée par la largeur du conteneur moins les 8 dp de padding horizontal (`inlineImageMaxWidthPx`) ; le padding reste extérieur au bitmap. Le cap des smileys (0,9) n'est pas réglable. §9 : `fImage` cesse d'être une constante nommée (résultat attendu, pas mécanisme) ; les autres constantes sont inchangées. §14 : le levier A est ce réglage ; A et B (pleine largeur) restent indépendants ; le levier C (profil GIF) a été supprimé par v1.6-1.

**[AMENDEMENT-v1.6-6 — D5 — §10.2, §11, §13]** Un SVG est décodé et rendu comme image de contenu (`SvgDecoder` installé, #960 P4). Particularité normative : la probe d'en-tête ÉCHOUE sur un SVG (pas de dimensions bitmap) et la géométrie vient du painter — chemin « probe KO, painter OK » (G2), qui DOIT être testé. Le cas 10.2 du banc attend un rendu visible, non plus une erreur. Les formats sans décodeur sur l'appareil (AVIF selon plateforme, #962) restent des erreurs stables §6. La prise en charge du format ne préjuge ni du succès réseau ni de l'éligibilité d'un lien SVG à la visionneuse (`svg` n'est pas dans l'heuristique v1.6-2).

**[AMENDEMENT-v1.6-7 — D6 — extension §3/§6]** Les images BLOC sont découpées (clip) avec le rayon du réglage `PostImageCorners` ∈ {8 ; 4 ; 0} dp, défaut 8 dp (#985). Le clip s'applique APRÈS le dimensionnement et ne change ni la boîte ni le slot §6 ; inline, smileys et cc-images ne sont pas découpés.

**[AMENDEMENT-v1.6-8 — D7 — §3 bis I5, politique disque de l'hôte MP]** Les requêtes de rendu issues de l'hôte MP désactivent les lectures et les écritures du cache disque : corps du message, probes et painters des smileys (chemin de mesure partagé), aperçus de l'éditeur MP, miniature du menu image et visionneuse (chemin dédié évitant `WRITE_ONLY`). Le cache mémoire des painters reste autorisé ; les probes désactivent leur cache mémoire. Les surfaces publiques, dont le picker de smileys même ouvert depuis un éditeur MP, conservent le cache disque. Cet impact partagé sur les smileys est DÉCLARÉ et VÉRIFIÉ au titre d'I5 (#1096). Conséquence connue : en MP, une éviction mémoire n'a pas de repli disque (exposition de l'écart E1, #1335).

**[AMENDEMENT-v1.6-9 — D8 — §11 banc, §5 visionneuse]** La fixture de référence `topic_page_banc_images_876` (sujet 148760) contient 29 messages, dont 16 posts de banc : le post 1 expose le protocole ; les posts 2-16 portent 52 cas numérotés distincts. Ce décompte remplace les 45 cas antérieurs ([AMENDEMENT-Lot0-4]). Toute modification d'un attendu normatif suit §0 ; toute recapture de la fixture et sa provenance sont tracées dans #876 ou le chapeau #1334 — une fixture n'est jamais une voie détournée d'amendement. Visionneuse : un tap simple sur l'image bascule l'affichage des contrôles (#1308).

**[AMENDEMENT-v1.6-10 — E4/E5/E11 — §3 autorité géométrique, §6 séquençage, §7 G2, §8 métadonnées ; cadrage Astra 12/09, contre-expertise Sol 12/09, rounds 2-3 du 13/09 : Sol ACCEPTE 30 s]** **Autorité.** Pour chaque URL de média de contenu, la première paire valide de génération courante acceptée atomiquement par le ledger (`MediaAttemptLedger`) fixe dimensions et provenance pour la durée du processus. Elle survit aux générations, retries et recyclages. Le cache intrinsèque est un mémo, jamais une autorité. Chaque occurrence applique ses contraintes ; à contraintes identiques, au plus une correction cold→exacte est admise. **Séquençage.** Une géométrie connue autorise immédiatement le painter. Sinon, avec cache disque, il attend le succès, l'échec ou l'échéance dédiée de 30 000 ms de la probe (Coil 3.6 écrit le corps complet dans le cache disque avant de décoder : l'attente ne coûte aucun transfert supplémentaire). L'expiration clôt PROBE en échec ; G2 attend la terminaison effective de la probe. Sans cache disque (hôte MP, v1.6-8), aucune probe de contenu n'est lancée : G2 immédiat. L'annulation par destruction du propriétaire reste un rollback. Smileys et fast-path cc inchangés. **Métadonnées.** Une probe fiable de la génération courante peut enrichir un MIME absent sans modifier dimensions, boîte ou cible. Un MIME connu n'est jamais effacé ni remplacé ; les divergences sont diagnostiquées (journal). Aucun résultat périmé ne fixe ni n'enrichit l'état. Cette règle supplante v1.5-2 pour le seul cas `null→connu`. **G2 et comptage.** Sans dimensions, la cible figée vaut `(min(2048, ceil256(maxWidthPx)), min(2048, ceil256(maxHeightPx)))` avec `FIT`, depuis les caps d'affichage de l'occurrence. Exception explicite au §7 : aucun second décodage cold→measured. Au plus un painter par montage d'occurrence, génération et contexte de contraintes ; aucun relancement sur recomposition identique. Remontages et changements de contraintes sont comptés séparément. Aucune unicité physique globale par URL n'est promise.

**Erratum v1.6 (12/09/2026)** : l'« écart existant CONFIRMÉ » du §3 (probe bornée 1024 écrêtant les dimensions natives, « CIBLE Lot 3 ») est CLOS — la probe est devenue une lecture d'en-tête sans bitmap (`IntrinsicMediaSizeMeasurer`, `ProbeMetadataDecoder`) ; elle ne démontre aucun protocole HEAD/Range particulier.

## [AMENDEMENT-v1.5-3] — Contrat dédié aux smileys avant arbitrage #989

### 0. Objet et portée

Le présent amendement ajoute un §3 bis consacré au rendu des objets sémantiques `Smiley`.

Il sépare strictement :

1. l’état de départ constaté ;
2. les invariants empêchant les effets de bord implicites ;
3. les choix produit encore ouverts dans #989.

Pour le dimensionnement, la mesure et l’affichage des smileys, le §3 bis est la source canonique du contrat. Aucun comportement, constante, profil ou effet de bord ne peut lui être appliqué ou en être dérivé implicitement.

Un couplage explicite reste possible, mais exige un amendement explicite du présent §3 bis. Le présent texte ne tranche donc pas l’éventuelle extension aux smileys HFR du profil S/M/L de #973.

Le présent amendement constate l’existant ; il ne fixe aucune nouvelle valeur cible pour #989.

### 1. Mode d’intégration

Le présent bloc est une insertion additive après le §3.

Aucune phrase existante n’est supprimée, remplacée ou raccourcie. En particulier, aucune clause normative relative aux images, cc-images, chemins inline/bloc/cold, diagnostics ou non-régressions n’est modifiée.

Toutes les mentions des smileys présentes dans le contrat restent intégralement en vigueur. L'audit du round 1 en avait recensé huit, regroupées en six groupes ; le présent inventaire les complète à douze après relecture exhaustive du contrat — l.73-74 avait été omise au round 1, et les quatre mentions « intouchable(s) » ci-dessous sont listées explicitement parce que leur coexistence avec le statut d'« état de départ non-cible » du présent §3 bis est le point doctrinal de cet amendement :

- l.73-74 : le smiley borne un `MediaRun` ; cette topologie reste propriété du §2 ;
- l.90 : mention existante conservée sans remplacement ;
- l.245-246 : mention existante conservée, y compris « constantes §9 inchangées » ;
- l.255 : non-régression #175 conservée ;
- l.271-273 : clauses image conservées, notamment l’application identique aux trois chemins, `COLD_BLOCK_WIDTH_FRACTION`, son alias verrouillé et la lecture de `fImage` par les caps image ;
- l.283 : mention diagnostique conservée ;
- l.353 : non-régression #175 conservée ;
- l.357 : clauses « 3 chemins (inline / bloc mesuré / cold) » et « smileys laissés à 0,9 (§9,
  intouchable) » conservées. **Note du 27/07** : la clause « Pas de réglage “taille des images” »
  de ce même emplacement, également conservée par le présent amendement, a été **retirée
  ultérieurement le 27/07** par la `[RECTIFICATION 27/07/2026]` du §12 — décision distincte et
  postérieure, sans rapport avec les smileys (justification caduque + clause jamais arbitrée).
  Le §3 bis n'en est pas affecté : il ne dérive aucune règle smiley de cette clause ;
- **l.33-34 (§0) : « smileys #175 (caps 240×70 sp + 90 %, no-upscale — constantes séparées, intouchables) » conservée** ;
- **l.114 (§3) : « Smileys / cc | inchangés (#175/#256) » conservée** ;
- **l.265 (§9) : « Smileys 240/70/0,9 : séparées, intouchables. » conservée** ;
- **l.409-410 (§14) : « constante DÉDIÉE, séparée des smileys (0,9 intouchable) » conservée**.

Pour ces quatre dernières, « intouchable » conserve sa portée normative en tant qu'interdiction de
couplage implicite et de fusion des constantes ; il ne vaut pas approbation des valeurs comme cibles
de #989, lesquelles relèvent du présent §3 bis et de son I8.

Le présent amendement ne modifie aucune constante du §9.

En cas d’ambiguïté entre une clause générale relative aux médias et le traitement d’un objet `Smiley`, le §3 bis gouverne son dimensionnement, sa mesure et son affichage. Cette règle de portée ne modifie pas le normatif propre aux images ou aux cc-images.

## §3 bis — Contrat des smileys

### 3 bis.1 — Périmètre sémantique

Relèvent du présent paragraphe :

- les smileys builtin HFR ;
- les smileys perso ;
- l’état de smiley MORT #416 : sprite en 404 remplacé par un token texte via `deadSmileyTokenBox` ;
- leurs représentations dans le renderer ;
- leurs représentations dans le picker, y compris le chemin WIKI.

L’appartenance à ce périmètre dépend du type sémantique `Smiley` produit par le parser.

L’extension de l’URL n’est JAMAIS autoritaire. L’hôte de l’URL ne l’est pas davantage. Cette exigence relève du parser et du §8, non du §14.

Le §14 continue de désigner les quatre surfaces UI — Topic, MP, aperçu éditeur et signature — et non des hôtes d’URL.

La règle du §2 selon laquelle un smiley borne un `MediaRun` est conservée : la topologie reste propriété du §2, tandis que le présent §3 bis possède le contrat de dimensionnement, mesure et affichage du smiley.

Les images de contenu et les cc-images ne relèvent pas du présent périmètre, sauf lorsqu’une dépendance partagée avec les smileys est expressément documentée ci-dessous.

### 3 bis.2 — État de départ constaté

| Chemin | État de départ | Mesure et évolution |
|---|---|---|
| Renderer — builtin HFR | `builtinPreseedSize` : 16×16 | Taille de production permanente. Les builtin sont délibérément exclus de `collectMeasurableSmileyUrls` ; ils ne sont pas en attente d’une mesure ultérieure (`PostRenderer.kt:1719-1724`). |
| Renderer — perso | `persoColdFallbackSize` : 70×50 | Boîte provisoire avant mesure ; elle peut être remplacée après obtention de la taille intrinsèque. |
| Renderer — perso MESURÉ (chemin principal) | `intrinsicSmileyDisplaySize` : taille native en px convertie en boîte `sp` (« 1 px natif → 1 sp »), bornée par les caps absolus `SMILEY_MAX_WIDTH_SP` = 240 et `SMILEY_MAX_HEIGHT_SP` = 70, puis `capToWidth` au cap relatif 0,9 | Le facteur d'échelle est `minOf(maxW/w, maxH/h, 1f)` : le `1f` interdit tout agrandissement (no-upscale). Références : `PostMediaDisplayPolicy.kt:196-197`, `:205`, `:338-353`. |
| Renderer — smiley MORT #416 | Token texte via `deadSmileyTokenBox` | Largeur de 8 sp par caractère, hauteur de 20 sp, avec cap relatif (`PostMediaDisplayPolicy.kt:119-131`). |
| Picker — builtin | Taille builtin de 20 dp | Politique propre au picker dans l’état de départ. |
| Picker — chemin WIKI non mesuré | Carré `WIKI_CELL_IMAGE_SIZE_DP` de 44 dp | Le picker réutilise `intrinsicSmileyDisplaySize` avec des caps 44/44 (`SmileyPickerSheet.kt:333-337`). |

Dans le renderer, les boîtes de smileys sont exprimées en sp.

Deux mécanismes distincts, à ne pas conflater : le **cap relatif** vaut actuellement `0,9`
(`SMILEY_RELATIVE_MAX_WIDTH_FRACTION`) et borne la largeur de la boîte à une fraction de la largeur
de contenu ; le **plafond `1f`** est le clamp du facteur d'échelle dans `intrinsicSmileyDisplaySize`
et interdit l'agrandissement au-delà de la taille native. Le `0,9` n'est pas plafonné par le `1f`.

La taille 16×16 des builtin n’est pas un fallback susceptible de reflow après mesure. Seule la boîte perso 70×50 est provisoire dans ce sens.

#### Infrastructure de mesure partagée

Les smileys perso utilisent :

- le même `IntrinsicMediaSizeCache` que les images ;
- le même ledger d’échecs issu de #960 ;
- le même effet de mesure.

Ce partage est présent dans `PostRenderer.kt:384-416`.

Par conséquent, un changement relevant notamment du §6 — échecs, TTL ou générations — ou du §7 — décodage — peut modifier le rendu d’un smiley perso sans modifier directement une clause consacrée aux smileys.

#### Cap actuellement partagé avec les images

L’état de départ comporte également une dépendance dans le sens smiley → image :

- `PostRenderer.kt:489-491` calcule `maxMediaWidthSp` à partir de `SMILEY_RELATIVE_MAX_WIDTH_FRACTION` ;
- `PostRenderer.kt:520-522` transmet ce même `maxMediaWidthSp` à `imageDisplayBox` ;
- `PostRenderer.kt:1883-1887` l’utilise pour caper le slot du fast-path cc-image ;
- `PostRenderer.kt:1892-1896` l’utilise pour caper le slot cold des images de contenu.

Cette dépendance est un carve-out constaté, non une autorisation générale de couplage. Une modification du `0,9` peut donc déplacer des slots d’images ou de cc-images et doit être traitée explicitement.

Pour les images visées par #959, la taille intrinsèque mesurée reste stable en pixels physiques. Cela ne signifie pas que tout leur affichage est indépendant de `fontScale` : le cap inline de 200 sp et les slots cold/cc exprimés en sp suivent `fontScale`.

### 3 bis.3 — Invariants normatifs

#### I1 — Classification sémantique

Une exclusion, inclusion ou politique de rendu des smileys DOIT être fondée sur le type sémantique `Smiley`.

L’extension ou l’hôte d’une URL NE DOIT PAS servir d’autorité de classification.

#### I2 — Propriété canonique et anti-effet de bord

Tout comportement de dimensionnement, mesure ou affichage d’un `Smiley` DOIT être déclaré dans le présent §3 bis, y compris lorsqu’il dépend d’une infrastructure ou d’un paramètre partagé.

Aucun autre paragraphe ne peut modifier implicitement ce comportement.

#### I3 — Profils image et option S/M/L

Aucun facteur S/M/L, seuil GIF ou choix de profil image NE DOIT être appliqué aux smileys ou en être dérivé implicitement.

Un couplage explicite, notamment l’extension aux smileys HFR du profil S/M/L de #973, exige un amendement explicite du présent §3 bis. Il n’est pas interdit par le présent texte.

#### I4 — Sens image ou cc-image vers smiley

Un paramètre gouvernant une image ou une cc-image NE DOIT PAS modifier implicitement le rendu d’un `Smiley`.

Toute dépendance intentionnelle dans ce sens DOIT être nommée dans le présent paragraphe, accompagnée de son périmètre et arbitrée explicitement.

Le carve-out de `maxMediaWidthSp` documenté en 3 bis.2 est dans le sens opposé et ne crée aucune exception à I4.

#### I5 — Couverture de tout lot extérieur

Tout lot du présent contrat hors §3 bis qui peut modifier directement ou indirectement le rendu, la mesure, le cache, le ledger d’échecs, le décodage ou le reflow d’un `Smiley` DOIT :

1. déclarer cet impact ;
2. amender explicitement le §3 bis ;
3. vérifier les chemins smileys concernés.

Cette règle couvre notamment les effets possibles des §6 et §7 via l’infrastructure partagée. Elle ne se limite pas à une liste fermée de paragraphes.

#### I6 — Mesure et reflow

Le 16×16 builtin DOIT être traité comme une taille permanente dans l’état de départ, et non comme une boîte provisoire en attente de mesure.

Le 70×50 perso PEUT être remplacé après mesure et constitue le seul des deux preseeds susceptible de ce reflow.

Toute modification des échecs, TTL, générations, effets de mesure ou règles de décodage DOIT vérifier séparément le chemin perso et ne peut supposer que le builtin suit la même dynamique.

#### I7 — Sens smiley vers image ou cc-image

Un paramètre gouvernant les smileys NE DOIT PAS modifier implicitement une image ou une cc-image.

La dérivation actuelle de `maxMediaWidthSp` depuis `SMILEY_RELATIVE_MAX_WIDTH_FRACTION`, puis son emploi par `imageDisplayBox`, constitue l’unique carve-out documenté par le présent amendement.

Toute modification de cette fraction, de `maxMediaWidthSp` ou de leur relation DOIT :

- soit démontrer que les slots image et cc-image restent inchangés ;
- soit déclarer leur déplacement et amender explicitement les clauses image concernées ainsi que le présent §3 bis.

Aucun nouveau partage dans ce sens ne peut être introduit implicitement.

#### I8 — État de départ et valeurs cibles

Avant l’arbitrage produit de #989, les valeurs cibles envisagées NE DOIVENT PAS remplacer dans le présent état de départ les valeurs constatées.

I8 n’interdit pas abstraitement toute modification du dépôt avant le constat. Il interdit :

- de présenter une cible comme un état constaté ;
- de modifier une valeur ou une dépendance couverte sans maintenir le présent contrat ;
- de contourner I1 à I7.

### 3 bis.4 — Points de vigilance inter-paragraphes

#### §4

Une règle générale visant les images ou les médias NE DOIT PAS englober implicitement les objets `Smiley`. Tout impact voulu doit passer par un amendement du §3 bis.

#### §6

Le chemin perso partage l’infrastructure de mesure et peut refitter après le fallback 70×50.

Le builtin 16×16 ne refitte pas après mesure, puisqu’il est délibérément exclu de cette mesure.

#### §7

Une évolution du décodage susceptible de modifier le résultat ou l’échec d’un smiley relève de I5, même sans modification directe du renderer de smileys.

#### §8 et parser

La classification reste sémantique. L’extension et l’hôte de l’URL ne sont jamais autoritaires.

#### §14

Le §14 couvre les surfaces UI Topic, MP, aperçu éditeur et signature. Il ne définit aucune règle de classification par hôte d’URL.

### 3 bis.5 — Questions d’arbitrage

Les questions Q1 à Q4 du contrat principal ne sont pas tranchées par le présent amendement.

#### Q5 — Politique renderer/picker : OUVERTE

Le picker possède des valeurs propres — 20 dp pour le builtin et fallback WIKI 44 dp — tout en réutilisant `intrinsicSmileyDisplaySize` sur le chemin WIKI.

Reste à arbitrer si le renderer et le picker doivent :

- conserver des politiques cibles distinctes ;
- ou partager explicitement tout ou partie d’une même politique.

Le partage technique actuel ne vaut pas décision produit.

#### Q6-A — Propriété et effets de bord implicites : TRANCHÉE

Le §3 bis est la source canonique du dimensionnement, de la mesure et de l’affichage des `Smiley`.

Aucun profil, constante ou comportement extérieur ne peut lui être appliqué ou en être dérivé implicitement. Toute dépendance voulue exige un amendement explicite du présent §3 bis.

#### Q6-B — Couplage explicite au profil S/M/L de #973 : OUVERTE

L’option C de #989 — étendre le réglage existant afin de faire porter aux smileys HFR le profil S/M/L de #973 — reste admissible.

Son chiffrage comme option la moins chère et sa recommandation par Opus ne valent pas arbitrage. Si elle est retenue, son couplage devra être spécifié explicitement dans un amendement du §3 bis, avec ses effets sur le renderer, le picker et les dépendances partagées.

### 3 bis.6 — Vérifications minimales de conformité

Toute évolution couverte par ce paragraphe DOIT distinguer au minimum :

- builtin HFR permanent 16×16 ;
- perso avec fallback 70×50 puis mesure ;
- smiley MORT #416 rendu en token texte ;
- picker builtin 20 dp ;
- picker WIKI avec fallback 44 dp et `intrinsicSmileyDisplaySize` ;
- images de contenu et cc-images affectées par le partage actuel de `maxMediaWidthSp` ;
- classification sémantique indépendante de l’extension et de l’hôte de l’URL ;
- effets possibles des lots §6 et §7 sur le chemin perso.

### 4. Traçabilité

| Étape | Acteur | Résultat |
|---|---|---|
| Rédaction r1 | **Sol** (GPT-5.6 Codex xhigh) | livrée — `logs/SOL-989-amendement-r1.log` |
| Gate r1 | **Claude Fable 5** | **NO-GO** — 5 bloquants, 8 non-bloquants |
| Correction r2 | **Sol** | 13 points corrigés sans contestation ; remplacements abandonnés au profit d'une insertion additive — `logs/SOL-989-amendement-r2.log` |
| Gate r2 | **Claude Fable 5** | **NO-GO résiduel** — 3 défauts factuels : 4 mentions « intouchables » omises de l'inventaire, état de départ du chemin perso mesuré disparu du r1 au r2, conflation cap relatif 0,9 / clamp `1f` |
| Correction r3 | **Claude Opus 5** | transcription des 3 correctifs dictés par le gate (exception « edit purement mécanique » de la cadence du projet) ; écart au dispositif Sol-rédige assumé et déclaré |
| Gate r3 | **Claude Fable 5** | **GO** — correctifs conformes, aucune modification hors périmètre, 5 citations vérifiées verbatim au contrat, références R2 vérifiées au code |
| Approbation XaTriX | — | **APPROUVÉ le 27/07/2026** (« ok go », session rf2-17) |
| Application | Claude Opus 5 | **inséré le 27/07/2026** après le §3, insertion additive, aucune clause existante modifiée |

Date de la chaîne de gates : 27/07/2026, session rf2-17.

**Réserves acceptées au gate** (tracées, non bloquantes) :

1. **I5 déclenché sur la potentialité** (« peut modifier directement ou indirectement ») : tout lot
   extérieur touchant au cache, au ledger ou au décodage partagés devra déclarer son impact sur le
   §3 bis, même à impact nul démontré. Charge de déclaration large, assumée.
2. **Q6-B** : si l'option C de #989 est retenue, le **§8** ([AMENDEMENT-v1.5-2], exclusion des
   smileys du profil) et le **§12** (S/M/L séparés) devront être **co-amendés** — amender le seul
   §3 bis ne suffira pas.
3. **Tension documentaire « intouchables » vs « état de départ »** : adressée par la clause de portée
   du §1, mais sa résolution matérielle n'interviendra qu'à l'arbitrage #989 approuvé.

## 4. Espacement (transpose W3 sans l'imiter)

- 8 dp entre deux images d'un même run bloc ; 8 dp entre un run et le segment voisin.
- Aucune marge horizontale bloc (centrage + `fImage` suffisent).
- Inline : 4 dp horizontaux de part et d'autre (→ 8 dp entre deux inline adjacentes), zéro
  marge verticale externe.
- UNE seule mécanique d'espacement — pas de `spacedBy` cumulé avec des marges individuelles.

## 5. Interactions & accessibilité

Résolution normative de l'action primaire :
1. image liée → tap = ouvrir `linkUrl` (jamais `imageUrl`) — inline COMME bloc (répare CharLee,
   parité #257) ;
2. image non liée → pas de tap primaire aujourd'hui (réservé au futur viewer, §8) ;
3. long-press → menu image (#831) dans tous les cas, mutuellement exclusif du tap.

Gardes (Sol r1) : geste porté par la hitbox exacte de l'image (jamais le paragraphe) ;
touchSlop/délais plateforme, aucune durée maison ; sémantique `Role.Image` + `onClickLabel` + action long-clic
distincte ; tests des pixels frontière (image / lien texte voisin / texte sélectionnable).

**[AMENDEMENT-Lot3-1 — 21/07/2026, gate Sol Lot 3, approbation XaTriX a posteriori (mandat de
nuit)]** La « hitbox exacte de l'image » s'entend AU-DELÀ du **minimum touch target** a11y de la
plateforme (48 dp) : une image de contenu rendue sous cette taille (possible depuis le no-upscale
physique §3 — ex. 80×60 px = 26,7×20 dp à densité 3) reçoit l'expansion de cible tactile
standard d'Android, et un tap dans cette zone étendue (au-delà du bitmap, p. ex. la bande de
padding §4) déclenche l'action primaire. Comportement a11y plateforme CONSERVÉ (pas de
contournement) ; épinglé par test sur petite image réelle ; les tests de frontière stricte du
padding utilisent des fixtures ≥ 48 dp rendus.

**[AMENDEMENT-Lot2-1 — 20/07/2026, approuvé XaTriX, spike device #958]** Une sélection de texte
active ne modifie PAS la résolution de l'action primaire : le tap sur une image liée ouvre
`linkUrl` — jamais `imageUrl` — et met fin à la sélection, comme un lien texte. La clause v1.4
« en sélection active, le tap image n'ouvre pas le lien » est RETIRÉE : aucun mécanisme public ne
l'implémente de façon fiable/testable en Compose stable 1.11.x (Foundation n'expose pas l'état de
sélection ; le down n'est pas pré-consommé par la sélection — prouvé au spike S10e, tap → Chrome).
La conserver imposerait un hack non testable (charte anti-dérive).

**[AMENDEMENT-Lot0-1] Matrice par hôte** (r2, correction 4 — reformulée et complétée au Lot 0) :

> **Lecture normative explicite** : l'inertie totale décrite pour les hôtes `null` ci-dessous
> (MP, aperçu éditeur, Signature) — y compris le TAP sur une image liée — est la **CIBLE
> contractuelle imposée au Lot 2**, PAS une description de l'état actuel du code. Constat
> sourcé (base 164b6da5, `drafts/constat-signatures-876.md`, cadrage Sol r2 § F3) : aujourd'hui,
> `LocalPostImageActions = null` ne rend PAS les images inertes — une image BLOC liée conserve
> un `Modifier.clickable` qui ouvre le lien (`PostRenderer.kt:1022-1026`, branche
> `imageActions == null && linkUrl != null`), et un lien inline conserve sa
> `LinkAnnotation.Url` (`PostRenderer.kt:1316`) qui couvre aussi une image inline dans le lien.
> Le `null` supprime aujourd'hui SEULEMENT le long-press contextuel (#831), pas le tap lien
> historique. Les tests par hôte (`PostRendererHostMatrixTest`, matrice invariants I5.5/I5.9)
> imposent la cible — tap compris — pour les trois hôtes.

| Hôte | `LocalPostImageActions` | Comportement |
|---|---|---|
| Topic | fourni | §5 complet (tap lié, long-press menu, a11y complète) |
| MP (`PrivateMessageThreadScreen`) | null | CIBLE : images INERTES — aucun tap primaire (y compris image liée), aucun long-press, AUCUNE sémantique interactive (`Role`/`onClick`/long-clic) annoncée |
| Aperçu éditeur (`BbcodePreview`) | null | CIBLE : idem MP |
| Signature (`TopicPostCard`, opt-in #330) | null | CIBLE : idem MP — images INERTES (tap compris) ; en outre `mediaRefreshGeneration` figé à 0 (pas de retry média, `TopicScreen.kt:2822-2825`), rendu atténué conservé (`LocalIgnoreInlineColors`/#553, alpha #330) |

L'a11y est NORMATIVE et gelée AVANT le lot 2 (alt/contentDescription, focus, sémantique des
états erreur et retry, image sans action) — rédaction au Lot 0 (voir
`redface2-work/specs/annexe-a11y-876.md`, annexe normative de ce contrat).

## 6. États & anti-CLS (table normative)

Définition MESURABLE de l'anti-CLS : **zéro changement de topologie, placeholder déterministe,
au plus UNE correction de hauteur** (à l'arrivée de la mesure ou du bitmap). Le zéro-décalage
absolu est impossible sans dimensions dans le HTML — l'invariant est celui-ci, pas plus.

| État | Slot bloc | Slot inline | Peut évoluer vers |
|---|---|---|---|
| cold (rien) | largeur = `fImage`×dispo (défaut 0,95, §9) ; hauteur = `min(capBloc, max(160 dp, 0,75×largeur))` (fallback 4:3, plafond 480 supprimé) | carré 16 sp | measured / painter-success / painter-error |
| probe OK | boîte exacte (§3) | boîte exacte | painter-success / painter-error |
| probe KO, painter OK (G2) | dimensions painter valides → boîte exacte §3 (= l'unique correction depuis le cold) ; aucune dimension exploitable → boîte cold CONSERVÉE | dimensions valides → boîte exacte ; sinon slot 16×16 sp, bitmap `Fit` centré, sans upscale (16 sp = minimum de slot/hitbox, jamais une obligation d'agrandir le bitmap) | — |
| painter KO | état erreur DANS le slot réservé (pas d'effondrement) + retry manuel | idem | retry → nouvelle génération |

Échecs (#813) : TTL négatif 60 s ; 0 retry par recomposition ; 1 tentative par génération —
au sens : AU MAXIMUM une probe ET un chargement painter par génération, chacun sans retry
automatique (probe et painter sont des tentatives distinctes : « probe KO, painter OK » reste
donc un état atteignable). Portée : la génération et le cache négatif sont par URL — deux
occurrences de la même URL dans la page partagent le même état ;
refresh du post ou retry manuel → nouvelle génération + invalidation négative de l'URL ;
jamais d'invalidation des caches positifs/octets disque. **La première géométrie valide (probe ou painter) produit l'unique
correction puis VERROUILLE le slot pour cette occurrence d'URL ; un retry conserve ce slot**
(pas de ré-effondrement ni de re-cold). La requête décodée (§7) n'est lancée qu'une fois la
boîte fixée ; elle n'est pas redimensionnée après coup.

## 7. Décodage (netteté, remplace le 1024 plat)

Ordre EXACT (ratio préservé par facteur COMMUN, r3) : partir de la boîte affichée §3 en px
physiques (`wPx = wAffichéDp × density`) ; étendre la LARGEUR au bucket de 256 px supérieur ;
si une dimension dépasse alors 2048 px ou le natif, réduire par UN facteur d'échelle COMMUN
jusqu'à ce que les deux tiennent ; la hauteur est toujours DÉRIVÉE de la largeur par le ratio
natif, jamais arrondie indépendamment. Même calculateur inline et bloc (l'inline demande
naturellement moins). `INLINE_IMAGE_DECODE_CAP_PX = 1024` disparaît.
Garde-fou budget : à vérifier au banc sur une série de ~15 photos (risque n°3 de Sol) —
protocole E5 exécuté (15 assets distincts + 2 GIF animés simultanément, framestats + pic
mémoire + RSS + borne théorique 2048² ARGB_8888 = 16 MiB/bitmap), verdict chiffré final : TENABLE (mesures émulateur + S10e consignées dans RESULTATS-E5-E11.md).

## 8. Points d'extension réservés (HORS passe — tickets séparés)

- `MediaDisplayProfile` : ACTIVÉ par [AMENDEMENT-v1.5-2] ci-dessous (chantier #973).
- Viewer par-image #182-C = l'équivalent app du « Cliquez pour agrandir » W2, branché sur
  l'action primaire « image non liée » (§5.2). Chantier séparé.
- Agrandissement des miniatures-aperçus liées (option 3 du chantier #876) : ACTIVÉ par [AMENDEMENT-v1.5-4] ci-dessous.

**[AMENDEMENT-v1.5-2 — 26/07/2026, chantier #973, approuvé XaTriX (défaut M + amendement
allégé §8-only + réglage exposé), re-gate Sol : voir SUIVI-973]** — Activation du point
réservé `MediaDisplayProfile` : profil d'agrandissement des GIF de contenu en bloc. Section
normative UNIQUE du profil (le §3 admet déjà « un multiplicateur de cap » — aucune autre
section n'est modifiée) :

- **Éligibilité** : média de contenu BLOC dont le MIME fourni par une probe RÉUSSIE est
  `image/gif` (`eligibleGifBloc`). Le MIME forme une métadonnée atomique avec les dimensions
  en cache ; l'extension d'URL n'est JAMAIS autoritaire ; MIME absent/inconnu ou probe
  échouée → non éligible ; AUCUN reclassement tardif après fixation de la boîte. Les GIF
  statiques (conteneur GIF8) sont éligibles (l'animation n'est pas discriminable à la probe).
- **Formule** : pour un média éligible, le plafond no-upscale `1,0` du §3 est remplacé par
  `mEffectif` : `scale = min(mEffectif, maxWidthPx/wNatif, maxHeightPx/hNatif)`. Les hard
  caps (`fImage × largeurConteneur`, `capBloc`) re-clampent toujours le résultat ; dès qu'une
  dimension native atteint son cap, `scale ≤ 1`. Média non éligible : no-upscale strict v1.5
  inchangé. INLINE : formule v1.5 inchangée (plafond 1,0, cap 200 sp), même pour un GIF.
- **Décodage (§7 inchangé)** : la boîte calculée avec `mEffectif` alimente `decodeSizePx`
  (le multiplicateur est appliqué UNE fois, avant) ; le clamp au natif reste terminal — pour
  `m > 1` le décodage reste au natif et l'agrandissement se fait au draw.
  `FilterQuality.Low` conservé pour toutes les images de contenu.
- **Réglage utilisateur (EXPOSÉ — exigence XaTriX)** : enum `MediaDisplayProfile` persisté
  par son nom, lu défensivement (valeur inconnue → défaut) — `S = ×1,0`, `M = ×1,5`,
  `L = ×2,5`. **Défaut = M (×1,5), choisi par XaTriX (26/07).** Présenté dans la section
  Affichage des réglages, facteurs numériques visibles, à côté de la densité. Portée : les
  4 hôtes du renderer partagé (Topic, MP, aperçu éditeur, signature).
- **Exclusions** : smileys (#175) et cc-images (#256) hors profil, règles existantes
  inchangées ; constantes §9 inchangées (les facteurs S/M/L vivent dans ce §8).
- **Indépendance des leviers (§14 inchangé par renvoi)** : ce profil est un 3e levier C,
  indépendant et cumulatif de A (fraction) et B (pleine largeur) ; aucun réglage ne couple
  A, B et C.
- **Re-gate** : éligibilité MIME/probe/cache (GIF statique inclus, URL non autoritaire, MIME
  absent/inconnu ou probe échouée non éligible, aucun reclassement tardif), profils S/M/L,
  persistance et fallback inconnu/absent → M, non-éligibles et INLINE à `1,0`, caps
  largeur/hauteur, chemins measured et cold, décodage au natif avec multiplicateur appliqué
  une seule fois et `FilterQuality.Low`, 4 hôtes, interaction levier B, banc 148760
  (3.1/3.2, 9.1/9.2, 2.7), non-régressions #175/#256.

**[AMENDEMENT-v1.5-4 — 28/07/2026, chantier #876, approuvé XaTriX (option 3 : véhicule §8 + `min(densité, 3)` + image liée distincte/même hôte + grand axe natif ≤ 400 px + hard caps inchangés)]** — Activation de l’agrandissement automatique des miniatures-aperçus liées. Section normative UNIQUE de ce comportement ; aucune autre section n’est modifiée :

- **Éligibilité — garde G3** : après application prioritaire des exclusions ci-dessous, est éligible un média de contenu BLOC dont `linkUrl` est non nul, dont `url` et `linkUrl` sont des URL absolues HTTP(S), distinctes — `linkUrl ≠ url` par inégalité de chaînes exactes après `trim`, sans normalisation : un auto-lien qui ne diffère que par le schéma `http`/`https` ou par un slash final passe G1, résiduel assumé et borné par G2 — et de même hôte, et dont le plus grand axe des dimensions natives orientées EXIF — source normative du §3 — ne dépasse pas `400 px`. Toute dimension native inconnue fait échouer la garde de taille : le comportement est fail-closed.

  « Même hôte » signifie : comparaison insensible à la casse des champs `host` parsés ; aucune autre normalisation d’hôte ; aucun retrait de `www.` ; ports ignorés ; sous-domaines distincts ; schéma, chemin, query et fragment sans effet ; aucune résolution DNS ni comparaison de domaine enregistrable. Deux sous-domaines différents sont donc des hôtes différents : `i.imgur.com ≠ imgur.com` ; le motif miniature Imgur liée vers une page de l’autre sous-domaine n’est PAS éligible, ce qui est assumé. Le point final terminal est conservé : `example.com. ≠ example.com` ; une miniature dont l’image et le lien ne diffèrent que par ce point perd donc silencieusement l’agrandissement, résiduel fail-closed assumé. Hôte absent, ou URL non parsable selon le parseur d’URL retenu par l’implémentation — notamment une URL réelle non encodée contenant des espaces ou `|`, ou un hôte contenant un underscore tel que `foo_bar.com`, pour lequel ce parseur renvoie un champ `host` nul → non éligible ; une vraie miniature peut ainsi perdre silencieusement l’agrandissement. Ce comportement sur l’underscore est une limitation connue du parseur, acceptée en mode fail-closed. Aucune probe, extension ou validation du type de la cible liée : l’éligibilité vient du média `img` enveloppé par le lien, pas du seul test HTTP(S) de `isEligiblePostImageUrl`.

- **Fondement empirique du seuil** (constatées : fixtures + manifeste du banc + mesure S10e réelle ; classes d'hébergeurs connues pour Imgur ; Zupimages `/up/` supposé pleine taille, NON mesuré) : toutes les miniatures constatées ont un grand axe ≤ `330 px` : `70×150` réel S10e, `150×112` pour `/t/` diberie, `250×250` dans le banc et `330 px` pour Wikimedia `/thumb/`. Toutes les non-miniatures constatées ont un grand axe ≥ `640 px` : Imgur « l » à `640 px`, diberie `/r/` de `450×800` à `800×600`, et Zupimages `/up/` en pleine taille. Le seuil `400 px` se place dans le trou observé `]330, 640[`.

- **Arbitrages assumés** :

  1. Faux négatif assumé : la classe d’aperçus `500–800 px` liés vers leur full perd l’agrandissement. À densité `3`, ils occupent déjà au moins `213 dp` ; la sous-taille physique motivant le présent amendement ne les concerne pas.
  2. Faux positif résiduel assumé et non corrigeable : un `[img]` pointant l’URL d’un smiley personnel est classé `InlineImage`, la classification reposant sur les tokens alt/title et JAMAIS sur l’URL conformément à l’invariant I1. S’il est isolé sur sa ligne, lié vers une page distincte du même hôte et mesure au plus `400 px` sur son grand axe, il passe les deux gardes. Toute règle d’hôte destinée à l’attraper est INTERDITE par I1. Ce cas est consigné au re-gate comme résiduel connu.
  3. G1 ne perd rien d’observé : l’auto-lien est exclu par `linkUrl = url`, puisque sa cible EST l’image affichée ; l’agrandir n’ajouterait que du flou.

- **Formule** : pour un aperçu lié éligible, `mApercu = min(densitéÉcran, 3,0)`. Le plafond effectif vaut `mEffectif = max(mApercu, mGif)`, où `mGif` vaut le facteur du `MediaDisplayProfile` si le média est aussi un GIF éligible, `1,0` sinon. Les multiplicateurs ne se multiplient JAMAIS : ils assouplissent le même plafond no-upscale et le plus grand gagne. Le `max` avec `mGif` garantit un plancher de `1,0`, y compris pour une densité d’écran `< 1`.

  La formule reste `scale = min(mEffectif, maxWidthPx/wNatif, maxHeightPx/hNatif)`. Les hard caps (`fImage × largeurConteneur`, `capBloc`) re-clampent toujours le résultat et restent inchangés. Le débat #993 est indépendant.

- **Anti-CLS (§6 inchangé)** : l’arrivée des métadonnées produit au plus l’unique correction du §6 — slot cold → boîte exacte — calculée DIRECTEMENT avec `mEffectif`, jamais une boîte à `1,0` corrigée ensuite ; aucun reclassement ni correction supplémentaire après. Le slot cold du §6 reste sans facteur : il ne dépend pas des dimensions natives et `mApercu` n’y participe pas.

  Une lecture atomique du cache fournit dans la MÊME recomposition les dimensions natives, les entrées du plafond et la boîte : la garde de taille se résout à l’instant exact du premier calcul §3, en UN mouvement. Le chemin « probe en échec mais painter OK » reste couvert : le painter dépose ses dimensions dans ce même cache, sans MIME, ce qui est sans effet puisque `mApercu` ne dépend pas du MIME. Tant qu’aucune dimension n’est connue, la garde ne passe pas et la boîte cold est conservée sans facteur.

- **Décodage (§7 inchangé)** : la clause existante couvre ce nouveau cas sans variante. La boîte calculée avec `mEffectif` alimente `decodeSizePx`, le multiplicateur est appliqué UNE fois et le clamp au natif reste terminal. Pour `mEffectif > 1`, le décodage reste au natif et l’agrandissement se fait au draw. `FilterQuality.Low` est conservé.

- **Réglage utilisateur NON EXPOSÉ** : aucun enum, aucune persistance et aucun réglage supplémentaire. Le facteur corrige automatiquement la sous-taille physique des miniatures exprimées en pixels ; ce n’est pas une préférence de présentation. Ajouter un quatrième contrôle d’image avant la décision de cohabitation portée par #991 dupliquerait les leviers déjà ouverts ou livrés. `MediaDisplayProfile` reste inchangé.

- **Exclusions et portée** : l’ordre existant de classification reste impératif : smileys (§3 bis, [AMENDEMENT-v1.5-3]) et cc-images (#256) sont classés avant les images de contenu. Il garantit structurellement qu’un smiley ne peut pas atteindre le chemin BLOC avec un lien ; aucune garde supplémentaire n’est ajoutée. Les quatre intouchables du §3 bis restent en vigueur. INLINE reste hors périmètre : plafond `1,0` et cap `200 sp`, même avec `linkUrl`. La portée BLOC couvre les quatre contextes du renderer partagé : Topic, MP, aperçu éditeur et signature.

- **Re-gate** : vérifier la priorité smileys/cc-images avant aperçu ; `linkUrl` absent ; auto-lien `linkUrl = url` exclu ; URL ou hôte invalide ; URL réelle non encodée avec espaces ou `|` non parsable ; hôte différent ; casse seule ; `www.` ; port ; sous-domaines distincts, notamment `i.imgur.com ≠ imgur.com` ; grand axe natif orienté EXIF `≤ 400 px` inclus et `> 400 px` exclu ; dimensions inconnues fail-closed.

  Couvrir les chemins MEASURED et COLD : slot cold sans facteur, puis au plus une correction directe vers la boîte exacte calculée avec `mEffectif`, sans boîte intermédiaire à `1,0`, reclassement ni correction tardive ; lecture atomique du cache ; probe en échec mais painter fournissant les dimensions sans MIME. Couvrir les densités `< 1`, `< 3`, `3` et `> 3`, notamment le plancher `1,0` assuré par `max(mApercu, mGif)`.

  Le banc actuel ne contient AUCUNE miniature `/t/`. Il doit être complété explicitement par un post de miniatures réelles, puis sa fixture recapturée, avec les cas à ajouter suivants, attendus hors re-clamp à densité `3` : `/t/ 150×112 → 450×336`, `70×150 → 210×450` et `150×70 → 450×210`.

  Vérifier enfin le cumul GIF par `max` et jamais par produit ; les profils S/M/L ; les caps largeur/hauteur et leur interaction avec `fImage`/`capBloc` ; le décodage au natif avec multiplicateur appliqué une seule fois et `FilterQuality.Low` ; INLINE inchangé ; les quatre contextes du renderer ; les non-régressions #175/#256 ; le faux positif résiduel `[img]` de smiley personnel consigné comme connu ; et l’indépendance de #993.

## 9. Constantes (état cible post-passe)

Supprimées : `IMAGE_PROMOTION_WIDTH_UNITS` (240) ; usage promotionnel d'`IMAGE_MAX_HEIGHT_UNITS` ;
plafond cold 480 ; `INLINE_IMAGE_DECODE_CAP_PX` (1024) ; buckets legacy 240×180 si plus utilisés.
Renommées : `IMAGE_MAX_HEIGHT_UNITS` → `INLINE_IMAGE_MAX_HEIGHT_SP = 200` ;
`INLINE_IMAGE_MIN_HEIGHT_SP` → `INLINE_IMAGE_PLACEHOLDER_MIN_HEIGHT_SP = 16`.
Conservées/introduites : bloc `capBloc = min(hauteurUtile, max(400 dp, f × hauteurUtile))` avec
**`f = 0,70`** depuis [AMENDEMENT-v1.5-5] (#993 ; formule d'origine à 0,5 : [AMENDEMENT-Lot0-3]) ;
cold 160 dp + ratio 0,75 ; decode 2048 px + bucket 256 ; largeur `fImage`.
Smileys 240/70/0,9 : séparées, intouchables.

**[AMENDEMENT-v1.5-1 — 20/07/2026, D1 approuvée XaTriX]** La fraction de largeur d'image devient
une constante DÉDIÉE `IMAGE_RELATIVE_MAX_WIDTH_FRACTION = fImage`, **défaut 0,95** (était 0,9,
portée du `max-width:90%` web W1 — divergence produit assumée pour mieux occuper l'écran mobile).
Appliquée à l'IDENTIQUE sur les 3 chemins : inline (`imageDisplayBox`), bloc mesuré
(`blockImageDisplaySize`), cold (`COLD_BLOCK_WIDTH_FRACTION = IMAGE_RELATIVE_MAX_WIDTH_FRACTION`, alias verrouillé). Le cap smileys reste **0,9**
(`SMILEY_RELATIVE_MAX_WIDTH_FRACTION`, §9 intouchable) — les deux constantes sont désormais
distinctes. Les caps image de §3/§4/§6/§8 lisent désormais `fImage` (remplacement direct effectué,
plus aucune double vérité) ; les invariants no-upscale / capBloc / ratio / plancher 16 sp inchangés ;
le `max-width:90%` WEB de §1 et le cap smileys restent à 90 %. Implémentation au **Lot 3** ; interaction
avec le mode d'affichage #884 (Lot 5) régie par §14.

## 10. Diagnostics des cas du fil (ancrés fixtures)

- **tinc** : ~15 `<img>` (des `[img]`, cf. erratum W4) + fragments « 6 »/« filles » + spoiler dans UN `<p>` →
  le tout-ou-rien par paragraphe annule la promotion → 15 photos inline 200 sp collées.
  Racine : granularité (résolue par §2). Espacement : §4.
- **CharLee** : 3 `<a><img></a>` + phrase + smiley → non promues + inline long-press-only →
  tap mort. Racines : granularité (§2) + action primaire (§5).
- **nicko** : **[AMENDEMENT-Lot0-5 — 19/07/2026]** Le cas réel de nicko ne conditionne plus le
  gel du Lot 0. Les classes de comportement invoquées sont couvertes par le banc général à 45
  cas et par les lots 1A à 4. Si ses données deviennent disponibles et restent pertinentes, le
  cas sera vérifié en live sur la première release dev contenant les changements concernés. Tout
  écart relèvera soit d'une non-conformité au contrat, soit d'un nouvel amendement explicite
  selon §0. Ce retrait est limité au cas nicko et ne constitue pas une dérogation générale aux
  sorties bloquantes.

## 11. Banc de test

**[AMENDEMENT-Lot0-4]** Le banc compte 45 cas : 44 initiaux + le cas 14.1 EXIF, servi verbatim
par Pages après abandon de la copie Diberie qui ré-encodait l'image, cuisait la rotation et
supprimait le tag EXIF. Les errata du 19/07 concernent neuf cas numérotés — 2.1, 2.2, 2.5, 2.7,
3.1, 4.4, 5.3, 6.3 et 9.2 — plus le post 1. Le cas 14.1 est un ajout, pas un dixième cas corrigé.
Banc réel posté sur HFR (topic 148760, cat=10), asset `mire-exif-o6-1200x900.jpg` (orientation
EXIF 6) hébergé verbatim sur `forumhfr.github.io/artifacts/banc-images-876/`. Source BBCode :
`redface2-work/images-chantier/sujet-banc-posts-final.md` ; assets :
`redface2-work/images-chantier/assets-banc/MANIFEST.md`.

**Errata du 19/07 consignés** (E1, 1re passe d'édition, sous XaTelitte) — les attendus publiés
suivants étaient non conformes au contrat (topologie STRUCTURELLE §2, aucun seuil mesuré ne
décide inline/bloc) et ont été corrigés sur le banc HFR + synchronisés dans la source locale,
`SUIVI-876.md` et la recapture web anonyme :
- **3.1** (correctif MAJEUR) : titre + attendu INVERSÉS — l'attendu publié promettait « pas de
  promotion bloc » ; le contrat impose BLOC par isolation (§2 G1) pour un singleton isolé.
- **2.7** : titre « seuil de promotion 240 » → reformulé en isolation structurelle (§2), aucun
  seuil mesuré n'intervient.
- **2.1, 2.2, 2.5** : attendus de sizing reconditionnés au no-upscale/caps (pas de promesse
  « ~90 % »/« cap hauteur » quand le natif est plus petit que les caps).
- **4.4** : vocabulaire aligné sur l'invariant anti-CLS mesurable (§6 : au plus une correction
  de hauteur, pas « sans aucun saut »).
- **5.3** : « promotion par-run » → « découpage par runs » (vocabulaire legacy retiré).
- **6.3** : attendu réécrit au contractuel pur (le constat du comportement courant — appui long
  intercepté par le lien — sort de l'attendu et va dans le constat « avant », pas dans le
  banc figé).
- **9.2** : clause « Enregistrer garde les octets d'origine » annotée comme extra-contractuelle
  (#831, hors périmètre de ce contrat).
- **Post 1** : version de référence corrigée à 0.33.0 (0.32.0 était périmé) + note d'erratum
  datée.

**2de passe FAITE (10.1/10.2, 19/07)** : reformulation de **10.1** (AVIF) et **10.2** (SVG)
réalisée le soir même, une fois la matrice Coil probe/painter/API consolidée (E4 fait au 19/07
pour API 37 et API 31 (S10e) ; API 29 non mesurée, hypothèse documentaire non bloquante,
[AMENDEMENT-Lot0-6]). Attendus précisés (matrice invariants I6.10/I6.11) :
10.2 est un cas d'ÉTAT D'ERREUR stable (jamais « visible », le décodeur SVG plateforme est
absent, aucune case fantôme) ; 10.1 est conditionné au décodeur du device — mesuré OK sur
Android 16 émulé (API 37), KO sur S10e Android 12 (API 31) — et ne promet plus un rendu
visuellement correct universel (hors modèle d'état §6, ticket séparé #962). Le banc HFR (post 10),
sa source BBCode (`sujet-banc-posts-final.md`) et la recapture web
(`sujet-148760-page1-2026-07-19-final-gel.html`) sont synchronisés sur ces attendus, banc v1.4.

Matrice invariant → cas → test automatique : `redface2-work/specs/matrice-invariants-876.md`
(E9, complète §2-§7 + directives intouchables + dimension plateforme/API + trous connus).

## 12. Plan d'opérations (lots — découpage Sol r1)

| Lot | Contenu | Sortie |
|---|---|---|
| **0 — contrat & banc figés** | amendements XaTriX ; rehost diberie ; poster le sujet bac-à-sable ; captures web+RF2 de référence ; vérifs runtime restantes (matrice Coil AVIF/SVG/GIF, budget mémoire 2048, EXIF) ; matrice invariants→cas→tests | contrat gelé + constat initial versionné |
| **1A — policy pure de segmentation** | partition InlineSegment/MediaRun, frontières, tests JVM exhaustifs (fixtures réelles) | policy mergée, aucun changement visuel |
| **1B — renderer segmenté** | branchement topologie structurelle + boîte COLD §6 + espacement §4 + non-régression hôtes MP/aperçu/signature. **LOT LE PLUS RISQUÉ** (ordre de composition, liens, sélection, quotes, spoilers, a11y) | tinc vert (nicko : verdict au banc seulement) |
| **2 — interactions** | action primaire §5 + matrice hôtes (4 hôtes : Topic/MP/aperçu/Signature) + a11y gelée (CharLee vert) | |
| **3 — sizing & décodage** | §3 (équation + autorité des dimensions + correction de la violation d'écrêtage probe 1024, I3.7) + §7 + GIF sizing ET lifecycle (« visible » = item composé dans le viewport ET lifecycle RESUMED — définition à confirmer au cadrage du lot) | netteté |
| **4 — erreurs/retry** | §6 échecs (#813 rouvert et fermé pour de bon) | |
| **5 — présentation des posts / pleine largeur (#884)** *(v1.5, cadré Sol 20/07)* | levier B (encart carte vs pleine largeur), APRÈS 2/3/4, **prototype-first** + arbitrage visuel XaTriX ; matrice de surfaces normative (4 hôtes ≠ même `PostCardShell`) + banc B dédié ; éventuel réglage « présentation : carte / pleine largeur » (au plus UN **pour ce levier B** — la borne ne vaut pas pour les autres réglages d'affichage, cf. [RECTIFICATION 27/07/2026] du levier A ; décision XaTriX) | prototype → arbitrage → (v1.6 si besoin) → impl |
| ~~S/M/L, #182-C~~ | S/M/L + viewer #182-C : RESTENT séparés (§8), PAS dans #876 | |

Chaque lot : cadrage Sol (conformité au contrat) → implémentation → gate Sol → banc (cas verts
listés) → non-régression #256/#175/#257 → release dev. #813 et GIF ne sont PAS groupés.

**Levier A — largeur d'image** *(v1.5, cadré Sol 20/07)* : absorbé par le **Lot 3** (c'est un cap
de sizing, §3/§6/§9). Split en constante image DÉDIÉE (`IMAGE_RELATIVE_MAX_WIDTH_FRACTION`) sur les
3 chemins (inline / bloc mesuré / cold) ; smileys laissés à 0,9 (§9, intouchable). Défaut **0,95** (D1 approuvée XaTriX le 20/07). Spike de séparation déjà écrit (branche jetable
`exp/img-width-test`), testé S10e 90/95/99/100 % (`reports/RAPPORT-largeur-images-90-95-99-100.md`).

**[RECTIFICATION 27/07/2026 — configurabilité de `fImage` : clause retirée, puis TRANCHÉE par XaTriX le même jour]**
La v1.5 du 20/07 portait ici la clause « **Pas de réglage « taille des images »** (ré-introduirait le
S/M/L exclu par §8) ». Cette clause est **retirée**, pour deux motifs constatés le 27/07 (analyse
Opus, recoupée par Sol) :

1. **Sa justification est devenue fausse.** `[AMENDEMENT-v1.5-2]` (#973, approuvé XaTriX, livré en
   0.35.0) a *créé* un profil d'agrandissement S/M/L réglable par l'utilisateur, qui vit précisément
   dans le §8 — « les facteurs S/M/L vivent dans ce §8 ». Le S/M/L n'y est donc plus « exclu » : il y
   est normatif. Le motif unique de l'interdiction a disparu le jour même de sa rédaction.
2. **Elle n'a jamais été arbitrée en tant que telle.** D1 (approuvée XaTriX, 20/07) porte sur la
   VALEUR 0,95 ; D2 portait exclusivement sur le **levier B** (carte / pleine largeur), tranché
   depuis par le réglage `topic_full_width_posts`. Aucune trace d'un arbitrage produit sur le
   caractère configurable ou non du **levier A** : la clause était un cadrage opérationnel de Sol,
   déduit du §8 tel qu'il était le 20/07. La demande existait pourtant côté testeurs le jour même
   (thom@s, fil TU du 20/07 : « 95 % (voire personnalisable) »).

**Ce qui reste acté** : la valeur par défaut **0,95** (D1, approuvée XaTriX) ; la constante DÉDIÉE
`IMAGE_RELATIVE_MAX_WIDTH_FRACTION` et son alias cold verrouillé ; l'unicité de cette valeur sur les
3 chemins (inline / bloc mesuré / cold).

**Ce qui reste interdit** (§14, inchangé) : **coupler** le levier A et le levier B dans un même
réglage. Un réglage INDÉPENDANT de `fImage` n'est pas visé par cette interdiction.

**Ce qui est TRANCHÉ (XaTriX, 27/07, après lecture de la présente rectification)** : le réglage de
largeur maximale d'image est **RETENU**. L'implémentation est **différée à juste après la promotion
bêta**, avec une priorité haute (« pas à implémenter maintenant mais à faire rapidement après la
bêta »). Le suivi vit dans l'**issue #991** ; le présent contrat n'est pas amendé sur les §3/§6/§9 tant
que la forme du réglage n'est pas cadrée.

**Ce qui reste à cadrer** (avant tout code, et hors du présent contrat) :

1. **la forme** — jeu de valeurs discrètes (le spike a mesuré 90 / 95 / 99 / 100 %) ou plage continue ;
2. **le libellé** — « largeur des images » est ambigu avec l'agrandissement des GIF du §8 ; il faudra
   des intitulés que l'utilisateur puisse distinguer ;
3. **la cohabitation** — ce serait le troisième réglage d'affichage d'images (avec l'agrandissement GIF
   du §8 et le mode carte / pleine largeur du levier B) ; à cadrer avec les deux autres questions de
   même famille, smileys (§3 bis / #989) et rayon des coins (#985), pour ne pas livrer des réglages
   voisins que l'utilisateur devrait accorder à la main ;
4. **le défaut** — reste **0,95** (D1) sauf arbitrage contraire explicite.

**Aucun code n'est engagé par la présente rectification** : le défaut 0,95 reste en vigueur tel quel
jusqu'à l'implémentation.

## 13. Faits vérifiés / restants

Vérifiés 15/07 (lecture) : W3 universel (les 3 pipelines, `[img]` pur inclus) ; unités
`measuredSizes` = px natifs source (`coil3.Image.width/height`, probe bornée 1024) ;
**[AMENDEMENT-Lot0-2]** MP (`PrivateMessageThreadScreen`) et aperçu éditeur (`BbcodePreview`)
consomment le MÊME renderer que Topic (`LocalPostImageActions = null` par défaut) — **CORRECTION
de l'affirmation v1.3** : ce `null` NE rend PAS les images inertes aujourd'hui, contrairement à
ce que disait v1.3 (« actions image inertes par design »). État réel sourcé (cadrage Sol r2, F3
corrigé) : une image BLOC liée garde un `Modifier.clickable` qui ouvre le lien
(`PostRenderer.kt:1022-1026`), un lien inline garde sa `LinkAnnotation.Url`
(`PostRenderer.kt:1316`) qui couvre aussi une image inline dans le lien — seul le long-press
contextuel (#831) est supprimé par le `null`. L'inertie TOTALE (tap compris) décrite au §5 est
donc une CIBLE contractuelle du Lot 2, pas l'état actuel (voir §5, matrice par hôte amendée) ;
AST : quote/spoiler = blocs séparés (les runs ne les traversent pas par construction).

**[AMENDEMENT-Lot0-6 — 19/07/2026]** La cellule API 29 est **NON MESURÉE** et consignée comme
hypothèse documentaire non bloquante du seul Lot 0 : JPEG/GIF/EXIF attendus décodables ; SVG et
AVIF attendus en échec avec la configuration actuelle. Aucun de ces attendus API 29 n'est
présenté comme un fait vérifié ; la cellule sera mesurée au premier environnement compatible
disponible. API 31 est mesurée sur S10e et API 37 sur émulateur : ces résultats établissent que
le niveau d'API seul ne permet pas de garantir le décodage AVIF. #876 impose dans tous les cas
l'état stable de §6, sans case fantôme ; le support logiciel AVIF relève de #962. Cette
qualification est circonscrite à E4/API 29 et ne crée aucun précédent de substitution silencieuse
d'une preuve par une hypothèse.

Restants (→ Lot 0, état au 19/07 post-gate) : matrice Coil probe/painter AVIF/SVG/GIF (**API 37
et API 31 (S10e) faits ; API 29 non mesurée, hypothèse documentaire non bloquante,
[AMENDEMENT-Lot0-6], E4**) ; budget mémoire décodage 2048 sur séries longues (**E5 FAIT — verdict TENABLE : théorique 42 MiB, delta graphics mesuré ~40-45 Mo, pic PSS 117 Mo (émulateur, memClass 192) et 137 Mo (S10e, memClass 256), jank scroll < 1 % sur device réel ; croisement in-app posts 2→5 fait sur S10e**) ;
cas nicko (**sorti du périmètre bloquant, [AMENDEMENT-Lot0-5], §10 — test live différé et non
bloquant pour le gel, E10**) ; orientation EXIF dans la mesure (**API 37 et API 31 (S10e)
confirmés orientés ; API 29 non mesurée, même hypothèse documentaire, E6**) ; signatures
(**chemin de rendu vérifié, E7 — voir §5 amendé**) ; règles a11y détaillées (**rédigées, E8 —
voir `annexe-a11y-876.md`**) ; matrice invariants→cas→tests (**rédigée, E9 —
voir `matrice-invariants-876.md`**) ; métrique fenêtre §3 (**base CONFORME Sol r2, formule
clampée gatée GO au gate des amendements du 19/07, [AMENDEMENT-Lot0-3], valeurs E11 mesurées
émulateur + S10e — TERMINÉ**) ; passe device réel S10e
(**condition de gel A4=i SATISFAITE, terminée le 19/07**).

## 14. (v1.5) Largeur de contenu & modes d'affichage — intégration #884

> **Statut : GELÉ (v1.5, 20/07).** Cadré Sol GO (`logs/SOL-884-integration-cadrage.log`) ; gel gaté Sol, GO au dernier re-gate
> (`logs/SOL-884-v15-gel-r*.log`). **D1 TRANCHÉE (XaTriX, 20/07) : défaut fraction image = 0,95** —
> bakée en §9 [AMENDEMENT-v1.5-1]. **D2 (réglage carte/pleine largeur du Lot 5 vs mode figé) :
> différée au prototype du Lot 5** (Sol : arbitrage visuel d'abord).

**Deux leviers INDÉPENDANTS et cumulatifs** sur l'axe « débordement de l'image » — ne jamais les
coupler techniquement ni dans un même réglage (annulerait la découverte du spike) :

- **Levier A — fraction de largeur d'image** (Lot 3) : constante DÉDIÉE, séparée des smileys (0,9
  intouchable). Amende §3/§6/§9 (le `0,9` normatif devient `fImage`, défaut 0,95 approuvé).
- **Levier B — mode d'affichage du post** (Lot 5, #884) : carte (actuel) vs pleine largeur.

**Invariants à pinner au gel v1.5 (imposés Sol) :**
1. `Wdispo` défini précisément = largeur fournie à `PostRenderer` APRÈS gouttière de liste, bordure,
   padding du body, et conteneur quote/spoiler éventuel — une formule de `Wdispo` PAR MODE de post.
2. Équation cumulative : `Wimage ≤ fImage × Wdispo`.
3. Non-débordement inline (padding §4 de 4 dp/côté) : `Wbitmap ≤ min(fImage × Wdispo, Wdispo − 8 dp)`.
4. « Pleine largeur » NE supprime PAS les paddings propres aux quotes/spoilers, et n'implique PAS
   que toute image atteigne le bord : no-upscale et `capBloc` continuent de s'appliquer.
5. MÊME fraction sur les 3 chemins (cold / mesuré / inline) — pas de saut de largeur au chargement.
6. Changement de mode/contraintes = nouvelle ÉPOQUE de géométrie/décodage, distincte de la
   correction probe→painter (bornée anti-CLS §6).
7. Interaction avec le réglage de densité existant (qui pilote déjà `cardBodyHorizontal`).
8. Préserver : highlight d'ancre (#197), bordure multi-citation (#882), clés/items de liste,
   cibles tactiles (§5).

**Piège de non-régression n°1 (Sol)** : les 4 hôtes ne partagent PAS `PostCardShell` uniformément
— Topic et MP l'utilisent avec des gouttières différentes ; l'aperçu éditeur (`BbcodePreview`)
appelle `PostRenderer` dans sa propre surface paddée ; la signature est imbriquée dans le body du
Topic. `PostCardShell` lui-même n'ajoute AUCUN padding (les ≈61 px mesurés = 8 dp gouttière liste +
12 dp body à densité 3). → **matrice de surfaces normative** obligatoire, pas une option ajoutée au
seul shell.

**Banc** : le banc 45 cas couvre le contenu image du levier A ; le levier B exige une **matrice B
dédiée** — carte/pleine largeur × Topic/MP, + aperçu et signature comme surfaces distinctes ;
image bloc, image en quote imbriquée, texte long, signature ; post normal / highlighted /
sélectionné / avec badges-footer ; deep-link cold/warm ; changement de mode si configurable.

**Cadence (Sol)** : (1) gel v1.5 AVANT Lot 3 = fraction dédiée + défaut approuvé + 3 chemins + banc A
+ réservation organisationnelle du Lot 5. (2) prototype Lot 5 → arbitrage XaTriX (surface,
séparateurs, défaut, configurabilité). (3) amendement v1.6 avant l'implémentation du Lot 5 si les
décisions n'étaient pas mûres au gel v1.5.

## Risques majeurs (Sol r1)

1. Perdre l'ordre ou les interactions lors du découpage texte/runs (lot 1B).
2. Confondre échec de mesure, échec d'affichage et décision de layout.
3. Corriger la netteté au prix d'une explosion mémoire (longues séries, GIF).

## Journal des amendements Lot 0 (résumé, pour la revue XaTriX)

Six amendements, tous **gatés GO par Sol** (`redface2-work/logs/SOL-955-amendements-gate.log`,
19/07/2026) ; A3, A4, A5 et A6 sont des GO AMENDÉS dont la formulation exacte imposée par le
gate est intégrée aux sections normatives (§3/§9, §11, §10/§12, §13) — pas seulement consignée
ici.

| Tag | §  | Résumé | Statut |
|---|---|---|---|
| `[AMENDEMENT-Lot0-1]` | §5 | Ligne Signature ajoutée à la matrice hôtes ; l'inertie totale (tap compris) des 3 hôtes `null` est explicitement reformulée comme CIBLE Lot 2, pas l'existant | Gate Sol : GO (accepté tel quel). Approuvé XaTriX (19/07, #876) |
| `[AMENDEMENT-Lot0-2]` | §13 | Correction de l'affirmation « actions image inertes par design » → état réel sourcé (bloc lié clickable, lien inline annoté) | Gate Sol : GO (accepté tel quel). Approuvé XaTriX (19/07, #876) |
| `[AMENDEMENT-Lot0-3]` | §3/§9 | Métrique « hauteur utile de fenêtre » + clamp explicite `capBloc = min(hauteurUtile, max(400 dp, 0,5×hauteurUtile))` ; mesures E11 TERMINÉES (émulateur + S10e, split-screen inclus, cas limite RÉSOLU à 301 dp) | Gate Sol : GO AMENDÉ — clamp retenu, formulation exacte intégrée §3/§9. Approuvé XaTriX (19/07, #876) |
| `[AMENDEMENT-Lot0-4]` | §11 | Banc à 45 cas (44 + cas 14.1 EXIF, un AJOUT) ; errata du 19/07 = neuf cas corrigés (2.1, 2.2, 2.5, 2.7, 3.1, 4.4, 5.3, 6.3, 9.2) + post 1 — PAS « 10 cas édités » ; 2de passe 10.1/10.2 FAITE le 19/07 (soir même) | Gate Sol : GO AMENDÉ — comptage exact intégré §11 (banc déjà édité en HFR, amendement documente rétroactivement). Approuvé XaTriX (19/07, #876) |
| `[AMENDEMENT-Lot0-5]` | §10/§12 | Cas nicko sorti du périmètre bloquant du Lot 0 — test live post-release dev si nécessaire, retrait limité à ce seul cas | Gate Sol : GO AMENDÉ — formulation exacte intégrée §10, retrait « HTML du cas nicko » fait §12. Approuvé XaTriX (session 19/07) |
| `[AMENDEMENT-Lot0-6]` | §13/E4 | Cellule API 29 = NON MESURÉE, hypothèse documentaire non bloquante ; AVIF device-dépendant DÉMONTRÉ (KO aussi sur S10e API 31) ; support logiciel AVIF → issue séparée #962 | Gate Sol : GO AMENDÉ — formulation exacte intégrée §13. Approuvé XaTriX (session 19/07) |
