---
title: Parité de lecture Topic ↔ MP
parent: Spécifications
nav_order: 10
permalink: /specs/reading-parity
---

# Parité de lecture Topic ↔ MP
{: .fs-8 }

La matrice qui empêche la surface de lecture des MP/DT de décrocher silencieusement de celle du Topic.
{: .fs-5 .fw-300 }

---

## Pourquoi cette page

Entre la migration c3 de [#351](https://github.com/ForumHFR/redface2/issues/351) (2026-06-20) et le
cadrage de [#1040](https://github.com/ForumHFR/redface2/issues/1040) (2026-08-12), **74 commits** ont
touché `feature/topic` contre **7** `feature/messages` — sans qu'aucun document ne trace l'écart :
chaque fonction de lecture était conçue, livrée et testée côté topic, et son absence côté MP n'était
écrite nulle part. Cette page est la réponse structurelle : **pour chaque fonction de la surface de
lecture, elle dit si la fonction s'applique aux MP/DT, et dans quel état elle s'y trouve.**

**Règle d'entretien** `[enforced]` : toute PR qui ajoute ou modifie une fonction de la surface de
lecture (rendu d'un `Post`, gestes de page, préférences de lecture) **ajoute ou met à jour sa ligne
ici**. Une fonction absente de cette matrice est un écart non tracé — précisément ce que cette page
existe pour empêcher. Deux gardes machine portent la règle depuis
[#1045](https://github.com/ForumHFR/redface2/issues/1045) :

- **garde A (chemins)** — job `repo-guards` de la CI (`scripts/check-reading-parity-touch.sh`) :
  une PR qui touche `feature/topic` ou `feature/messages` (`src/main`), ou `core/ui`
  (`post`/`list`/`pager`), sans toucher cette page, est bloquée — sauf ligne
  `Parity-Impact: none — <raison>` dans le corps de la PR (échappatoire documentée dans le template
  de PR ; le corps est relu en direct, relancer le job suffit après édition) ;
- **garde B (symboles)** — cas de `DocsConsistencyTest` (tourne aussi en local via `/validate`) :
  chaque symbole cité entre backticks par la colonne *Réf.* de la matrice et par la section
  « Anomalies Topic » doit encore être **défini** dans l'arbre source — déclaration Kotlin/Java,
  nom typé (`x:`), import, littéral de chaîne du code (hors arguments d'annotation), ressource
  `name="…"`, ou fichier source du même nom. Les commentaires ne comptent jamais (une citation ne
  peut pas s'auto-valider contre de la prose), un fichier de test ne peut attester que d'un
  symbole se terminant lui-même par `Test`, et le membre d'un symbole qualifié
  (`Post.postIndex`) exige une déclaration ou un nom typé dans le même fichier que son porteur.
  Dans « Anomalies Topic », tout token entre backticks est volontairement une référence couplée à
  l'arbre source ; les noms d'API externes ou les détails d'assertion qui n'ont pas à survivre comme
  référence restent en prose. Aucun compteur de lignes : la matrice est faite pour grandir.

Les gardes vérifient le **geste** (A) et la **référence** (B), pas la véracité d'une ligne : une
ligne fausse qui cite un symbole vivant leur échappe — c'est le rôle du réaudit substantiel (ligne
« Dernière vérification » sous § Matrice).

Trois verdicts possibles :

- **oui, livré** — la fonction est effective côté MP (souvent via un mécanisme partagé : `RedfaceTheme`,
  `PostListScaffold`, `PostRenderer`) ;
- **non par nature** — la fonction ne peut pas ou ne doit pas s'appliquer aux MP, avec la raison
  (contrainte serveur, confidentialité) ;
- **oui mais absent** — la fonction a du sens en MP et n'y est pas câblée. C'est le backlog du
  chantier #1040.

Contexte d'architecture : le partage se fait au niveau des **capacités de lecture** dans `:core:ui`
— `ReadingPostCard` au lot 1, puis la machine de zoom au lot 6 —, pas des écrans ni des ViewModels.
Les clés de cycle de vie, le rafraîchissement, le swipe et le chrome restent dans chaque feature —
cf. #1040 et [ADR-013]({{ site.baseurl }}/adr/013-mp-lecture-cache-prefetch) (amendée 2026-08-12).

Trois contrats de test décrivaient l'écart comme un design et **gelaient** autant de lignes « oui
mais absent ». Leur **réarbitrage a été rendu le 2026-08-12** (#1041) — propositions rédigées par
Fable, décision prise par Sol contre le code, le producteur ne pouvant pas être son propre juge :

- `MessageCardShellSmokeTest` (#884) — **amendé maintenant** (lot 0). Les deux assertions sont
  conservées : elles caractérisent le chemin par défaut (encarté) de `PostCardShell`. Seul le KDoc
  change, parce qu'il présentait la hairline du mode plat comme une affordance « topic-owned » que
  le MP « never opts into » — une caractérisation du défaut y était écrite en interdiction
  permanente. La parité pleine largeur reste un chemin opt-in du lot 2, qui devra apporter sa propre
  couverture (toggle à chaud, survie d'un repli déplié).
- `PostRendererHostMatrixTest` de `:feature:messages` (#958) — **amendé au lot 3**, dans la même PR
  que le provider `LocalPostImageActions` côté MP. Assertions **retournées** (tap et appui long
  définis, cible `PostImageTarget` vérifiée) et harnais conservé, plus un cas « callback absent ⇒
  image inerte » : la capacité vient de la présence du callback, pas de la surface hôte. Tant que le
  MP ne fournit rien, l'inertie que ce test épingle est **réelle**, pas décorative.
- le KDoc de `MessageCard` (#351c) — **amendé au lot 1, PR 2, c'est fait**, avec le comportement :
  l'amender avant aurait fait mentir le code. Tout le discours négatif est tombé, pas seulement les
  phrases densité et sélection — « no footer » et « no multi-quote border » gelaient les lots citation.
  Deux phrases devenues fausses en chemin ont été corrigées dans la même PR : « never provides
  `LocalPostImageActions` » (la carte partagée fournit désormais toujours le local, avec `null` faute
  de callback ; l'inertie épinglée reste intacte) et « Read-only for the MVP » dans le KDoc de l'écran.
  Les contrats de prose collatéraux (KDoc de `PostCardShell`, commentaire du paramètre `selectable`
  de `PostRenderer`, commentaire de densité de `ThreadMessages`) sont corrigés dans cette même PR.

Un contrat ne se flippe **jamais** en silence : le test change dans la même PR que le comportement,
sinon la CI dit — à raison — que le comportement promis a changé.

## Matrice

Dernière vérification substantielle : 2026-08-14, commit `d2ad6820`. « Substantielle » = un
réaudit complet de la matrice contre le code ; c'est le seul événement qui met à jour cette ligne —
une PR qui corrige ou bascule des lignes au fil de l'eau ne bumpe ni la date ni le SHA. Les
références sont des symboles, pas des numéros de ligne.

| Fonction | Réf. | MP/DT ? | Détail |
|---|---|---|---|
| Taille de police | #287, `RedfaceTheme` | **oui, livré** | Typo scalée par le préréglage, fournie par le thème — effective partout. |
| Pliage des longues citations | #332, `LocalFoldLongQuotes` | **oui, livré** | Fourni par `RedfaceTheme`, lu dans `PostRenderer.QuoteBlock` — effectif en MP. |
| Ascenseur intra-page | #300/#351c, `PostListScaffold` + `LazyListScrollbar` | **oui, livré** | Arrive par le scaffold partagé, qui lit `LocalShowScrollbar` lui-même. Une recherche de symbole côté MP le rate — l'écart se mesure par lecture, pas au grep. |
| Profil d'affichage des médias (GIF S/M/L) | #973, `LocalMediaDisplayProfile` | **oui, livré** | Fourni par `RedfaceTheme`, lu dans `PostRenderer.BlockImage`. |
| Retry unitaire d'un média en erreur | `PainterAttempt` (`PostRenderer`) | **oui, livré** | Le slot d'erreur + retry par image vit dans le renderer partagé. Le **retry en masse au refresh explicite** (#813/#960) est, lui, câblé côté topic seulement → « oui mais absent ». |
| Pull-to-refresh | #335/#351a, `pullToRefresh` | **oui, livré** | Keep-content (la page reste affichée pendant le rechargement). Les deux lecteurs utilisent le montage bas niveau : le geste et son indicateur sont désarmés pendant le zoom, pas seulement le callback. |
| Swipe de page horizontal | #282/#351b, `threadPageSwipe` | **oui, livré** (écart de durcissement) | Géométrie et seuils partagés (`core.ui.pager.PageSwipe`). Le cache RAM de session est disponible ; le slide-out reste différé au lot 6. Le MP n'a pas encore l'annulation multi-touch #936 ni la dead-zone des bandes système #752 — gagnées par le topic après #351b. Depuis le lot 6, le geste **et** son edge-hint sont désarmés pendant le zoom des deux côtés (`swipeEnabled = !isRefreshing && !zoomed` en MP, gate `RESUMED` + `zoomed` côté topic). |
| Reprise de la page de lecture | #430, `mp_read_positions` (ADR-013 étage 1) | **oui, livré** (contrat propre) | Position **locale** (page par conversation, par compte, purgée au logout) : il n'existe **aucune position de lecture serveur** pour les MP (#361 Q3, dot binaire par conversation) — le contrat diffère du dernier-lu topic par nature, ce n'est pas une lacune. |
| Densité structurelle | #287, `LocalDisplayMetrics` | **oui, livré** (lot 1, PR 2) | La carte MP lit le preset par `ReadingPostCard` : gouttières et inset haut réinjectés depuis `LocalDisplayMetrics`, mesurés sur device à 12 dp en Comfort contre 16 dp en dur avant. Le **chrome de liste** (`contentPadding`, espacement) reste **feature-owned des deux côtés** : le topic le code en dur dans `TopicListLayout` et le KDoc de `DisplayMetrics` exclut explicitement du preset les dimensions de chrome — aligner le MP dessus aurait été *plus* que la parité. La densité peut recomposer des valeurs, jamais remplacer la carte, ses slots ou la branche `SelectionContainer`. |
| Mode pleine largeur | #884/#1050, `PostCardShell(flat)` | **oui, livré** (lot 2, PR 1) | La préférence globale `topic_full_width_posts` pilote aussi le MP, sans clé ni réglage séparé et sans refetch. En mode plat, la liste conserve les insets haut/bas 16/88 dp mais retire gouttières et espacement ; le filet ne ferme qu'une frontière message → message, jamais le dernier message (pager compris). |
| Sélection / copie du texte | #281, `PostRenderer(selectable)` | **oui, livré** (lot 1, PR 2) | Les deux surfaces montent leur corps par `ReadingPostCard`, qui code `selectable = true` **en dur** : la capacité n'est ni un paramètre ni dérivable, donc structurellement constante sur la durée de vie de la carte. C'est l'exigence de #946 — flipper `selectable` insère/retire le `SelectionContainer` à l'entrée de `PostRenderer`, ce qui recrée le sous-arbre du corps et jette l'état `rememberSaveable` des replis de citation. Épinglé par un test sur deux axes séparés (densité seule, présence de callback seule) et vérifié sur device (poignées + barre « Copier / Tout sélectionner »). |
| Signatures | #330/#1050, `Post.signature` | **oui mais absent** (câblé, non prouvé live) | La préférence globale est observée côté MP et transmise à `ReadingPostCardPresentation.showSignature`. Le rendu est prêt si le parser reçoit une signature, mais la fixture MP de #1041 n'en contient pas : il manque encore une observation serveur réelle avant de revendiquer la livraison. |
| Marqueur EgoQuote | #874 Q4 / #1028, `LocalEgoQuotePseudo` | **oui, livré** (lot 2, PR 2) | La liste MP dérive le pseudo canonique de session (`deriveEgoCanonicalPseudo`, promu de `:feature:topic` vers `:core:domain` à comportement constant) et le route par `ReadingPostCardPresentation`. Actif en 1:1 comme en DT (arbitrage du cadrage : le MP rend des cartes uniformes sans alignement positionnel — pas de gate `isMultiRecipient`). Préférence partagée avec le topic, indépendante d'EgoPost. |
| Marqueur EgoPost | #874 P1 / #1028, `egoPostHighlighted` | **oui, livré** (lot 2, PR 2) | Résolu par la liste MP via `isEgoPost` (`:core:domain`) sur le pseudo de session exposé dans `PrivateMessageThreadUiState` (purgé au logout) — `Post.isOwnPost` délibérément ignoré : bit de cache non scopé au compte, et absent des profils `affichoutils=0` (#545). Marqueur a11y « Votre message » en StateDescription feature-owned sur le nœud d'identité, jamais un heading (#884). |
| Pinceau doré des créateurs | #221/#1060, `isRf2Creator` + `CreatorPseudoText` | **oui, livré** | Détection statique canonique (`:core:domain`) et feuille de rendu dorée (`:core:ui`) partagées. Le topic et le MP passent `CreatorPseudoText` par le slot `pseudo` de `PostIdentityHeader` uniquement quand `isRf2Creator(author)` ; le MP laisse le repli neutre aux autres auteurs. Le slot MP porte lui-même le tap profil et son unique `heading()` sur le vrai texte, conformément au contrat #884. Les tests de carte couvrent créateur doré/non-créateur neutre, exactement un heading dans les deux branches et le tap du pseudo doré ; `Rf2CreatorsTest` couvre casse, caractères de format et espaces insécables via `canonicalizePseudo`. |
| Menu contextuel de message | #362/#1051/#1074, `MessageMenuSheet` | **oui, livré** | Un appui long ouvre le sheet propre à `:feature:messages` : copie du texte complet (désactivée si la projection d'un message uniquement composé d'images est blanche), ajout/retrait du panier de citation multiple, profil et masquer/réafficher l'auteur. La commande de liste noire réutilise le snapshot vivant du lot 2 : messages et citations se replient/se restaurent ensemble, sans refetch. Le permalien reste absent (aucun contrat testé de lien précis vers un message MP, plus prudence de confidentialité) ; la citation simple « Citer » vit dans le pied de carte, pas dans ce menu. Par symétrie, `PostMenuSheet` propose les mêmes familles d'actions sans partager l'implémentation feature. |
| Actions d'image (viewer, menu appui long) | #831/#958/#1051/#1096, `LocalPostImageActions` + `PostMediaDiskCachePolicy` | **oui, livré** (menu d'appui long ; le viewer #182 manque aux deux surfaces) | Le menu d'appui long est câblé sur les images inline et bloc en MP, avec le même sheet et la même sauvegarde que côté topic. Les hôtes MP passent `DISABLED` à la politique de cache disque du renderer partagé : image bloc, image inline, smiley, probe intrinsèque, vignette du menu et aperçu BBCode restent en mémoire seulement. « Enregistrer l'image » rencontre donc normalement un miss disque en MP et retélécharge les octets originaux via le client anonyme ; le GIF n'est jamais réencodé. Au logout / changement de compte, `CacheInvalidator` purge aussi les caches Coil globaux, y compris les images publiques historiques impossibles à distinguer. Le viewer plein écran #182 reste absent des **deux** surfaces : son entrée demeure un placeholder désactivé « à venir ». `PostRendererHostMatrixTest` (`:feature:messages`) vérifie le tap lié distinct, l'appui long, `Role.Image`, la cible `PostImageTarget` par valeur et l'inertie sans callback ; `MessageCardReadingParityTest` vérifie les requêtes probe/painter du corps et `PostImageMenuSheetTest` la vignette. |
| Citation simple | #146/#1074, « Citer » par message | **oui, livré** (GET mesuré en 1:1 + DT, aucun POST live) | Le pied de chaque message visible et citable ouvre l'éditeur sur le formulaire typé `cat=prive` ; l'action est masquée si le rang `ref` manque ou si le message est sur liste noire. Le GET 1:1 a été mesuré le 2026-08-12 (#1041, fixtures `private_message_quote_form.html` + témoin `private_message_reply_form.html`), puis reproduit en DT le 2026-08-17 (`private_message_dt_quote_form.html`) : mêmes 20 champs cachés, `numrep` = **le message cité**, `content_form` prérempli `[quotemsg=numrep,ref,userId]`, `numreponse` vide, aucun champ caché `ref` et aucun `newdest`. « Citer » a aussi ouvert l'éditeur DT prérempli sur appareil sans erreur. Aucun POST live n'a été émis ; MockWebServer prouve le corps produit par le client (`numrep` cité, `numreponse` vide, aucun `ref`), pas son acceptation par HFR. Le comportement de `ReplyFormParser` reste inchangé. Détail dans [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr) § « MP/DT — citer un message ». |
| Citation multiple | #291/#1074/#1102, `QuoteScope.PrivateMessage` + `PrivateMessageReplyQuoteMaterializer` | **oui, livré** (écart d'affordance #1102 ; formulaire unitaire mesuré, enchaînement et POST non mesurés live) | L'entrée ajout/retrait vit dans `MessageMenuSheet` ; la carte expose l'état sélectionné sans changement de hauteur, et le FAB « Citer N » ouvre l'éditeur (appui long : vider). Le panier reste dans `:app`, filtré par scope avant son handoff en mémoire. La matérialisation est séquentielle, une requête par `QuoteSelection`, avec les page et ref de son `QuoteLocator` ; aucun chemin MP n'emprunte l'accesseur `MultiQuoteBasket.numreponses` ni la règle topic qui annule ref. Un préremplissage blanc fait échouer tout le chargement ; le POST conserve le premier formulaire et ses champs. La liste noire purge les sélections devenues masquées, y compris celles du même auteur prises sur une autre page ; un message explicitement révélé reste hors panier tant que l'auteur demeure bloqué. Les captures 1:1 et DT prouvent uniquement la forme du formulaire renvoyé pour **une** citation. Les tests locaux prouvent l'ordre des requêtes, la conservation de chaque locator, la concaténation des préremplissages et la forme du POST client ; aucune capture live ne prouve encore ce que HFR renvoie lors de plusieurs récupérations successives, ni qu'il accepte un POST contenant plusieurs blocs [quotemsg]. **La borne de preuve est celle de la citation simple** — contrat unitaire mesuré, étape terminale d'écriture non mesurée —, pas celle des lignes « câblé, non prouvé live » : celles-là attendent que HFR serve une donnée jamais observée en MP, alors qu'ici la fonction est utilisable et observable en production. **Écart d'affordance ouvert** ([#1102](https://github.com/ForumHFR/redface2/issues/1102)) : côté sujet, l'ajout/retrait du panier a une affordance directe par carte (parité RF1 `quote+`/`quote-`, #436) atteignable **sans** ouvrir le menu, l'entrée de menu venant en plus ; en MP l'ajout ne vit que dans `MessageMenuSheet` — la carte ne reçoit que l'état sélectionné, aucun callback de bascule. Même famille que l'écart de durcissement du swipe : la fonction est là, son accès ne l'est pas encore. |
| Saut vers le message cité | #625/#699/#782/#1074, `onGoToCitedPost` | **oui, livré** (atterrissage MP page/message) | La page et le `numreponse` cités sont extraits des liens statiques et dynamiques par le `PostContentParser`, y compris sur la fixture MP en `cat=prive` (`numreponse` vient de `#t…`, pas du paramètre de query). Le défaut de forme dynamique **n'était pas propre aux MP** : il touchait aussi les sujets en authentifié (#625) — `DYNAMIC_CITATION_HREF_REGEX` est consommé par `parseQuote` pour les **deux** surfaces, et le correctif #1092 a rétabli le saut pour tout utilisateur connecté, sujets compris. Le MP câble le geste à une cible pendante liée à la page et au compte : même page sans recharge ; autre page après l'émission réseau terminal seulement ; page effectivement parsée adoptée si HFR rabat la demande ; cible absente consommée sans scroll ; changement de page/compte invalidant. Le test de rendu couvre explicitement la priorité de l'atterrissage sur la carte ciblée face à la remise en haut. Le retour dédié #782 n'est pas empilé en MP : le bouton système conserve la sortie de conversation tant que les ancres de scroll MP (#307/#895 F3, ligne dédiée) ne sont pas livrées. Le caveat des ancres obfusquées reste distinct. |
| Marqueur d'édition | #483/#1051, `Post.editedAt` | **oui mais absent** (câblé, non prouvé live) | `MessageCard` fournit le marqueur inline « · édité » au slot `dateTrailing` de `PostIdentityHeader`, et `MessageMenuSheet` la ligne horodatée « Édité le … », strictement lorsque `editedAt != null`. Le `PostsParser` partagé extrait déjà la donnée sur `cat=prive` si HFR la sert, mais la fixture MP de #1041 ne porte aucun trailer d'édition : les deux rendus sont testés par état synthétique et restent absents sur la preuve serveur actuelle. |
| Compteur de citations | #239/#863/#1051, `Post.citedCount` | **oui mais absent** (câblé, non prouvé live) | `MessageCard` fournit la pill « cité N fois » au slot `badges` de `ReadingPostCard`, et `MessageMenuSheet` la ligne d'information, strictement lorsque `citedCount > 0`. Le compteur serveur est déjà extrait par le `PostsParser` partagé, mais la fixture MP de #1041 n'en contient pas : les deux rendus sont testés par état synthétique et restent absents sur la preuve serveur actuelle. |
| Profil au tap (avatar/pseudo) | #208, `onOpenProfile` | **oui, livré** (lot 1, PR 2) | Câblé côté MP sur le `ProfilePreviewSheet` déjà existant, gaté par `Post.profileId` (épinglé sur fixture MP, #1041). Vérifié sur device depuis le pseudo **et** depuis l'avatar, cible tactile de 48 dp et rôle `Button` exposé. Contrainte d'API toujours valable pour la suite : `PostIdentityHeader` pose `heading()` sur son pseudo **de repli** mais n'en ajoute aucun quand un slot pseudo est fourni (chemin du topic) — le MP n'en fournit pas, donc son pseudo de repli reste l'unique heading ; un futur slot d'identité MP devrait porter le sien, jamais zéro ni deux. |
| Double-tap pour rafraîchir | #382 | **oui mais absent** | Geste topic (RF1 parity), câblé au niveau de la liste dans `TopicScreen` ; le MP n'a que le pull-to-refresh — l'action (recharger la page courante) existe des deux côtés, seul le geste manque, rien de serveur. **Lot 6**, en **élargissement explicite** : la famille « gestes » du titre du lot l'accueille, mais sa cellule de cadrage ne le liste pas (et #1050 l'exclut du lot 2) — à écrire dans l'issue du lot 6 à sa création (lot encore sans issue). |
| Zoom pincé | #182/#1040, `PinchZoomState` + `pinchZoom` + `pinchZoomTransform` | **oui, livré** (lot 6, PR 1) | État, geste, transformation et calculs vivent dans `:core:ui`. Topic et MP montent la même machine ; chaque feature garde sa clé page/lecteur, son swipe, son pull-to-refresh et son chip 1×. Les suites Topic inchangées épinglent calculs, gestes, repli de citation et coexistence multi-touch ; les tests MP prouvent le pincement effectif, le reset à la clé de page et la suspension du swipe/PTR. Le **défilement natif de la liste est désarmé** pendant le zoom — `PostListScaffold` expose `userScrollEnabled`, et l'axe vertical est alors piloté programmatiquement par la machine de zoom au lieu du geste système. Les captures Roborazzi avant/après à 1× et à transformation fixe sont une preuve pixel du rendu extrait, pas une preuve gestuelle. |
| Liste noire | #509/#1050/#1074, `BlacklistRepository` + `LocalBlockedQuoteAuthors` | **oui, livré** | Filtre vivant côté MP, en 1:1 comme en DT : le message reste dans la liste mais sa carte devient un placeholder « Afficher », replié de nouveau au changement de page. La même émission alimente le masque des messages et celui des citations, sans refetch. Depuis le lot 4, elle purge aussi du panier multi-quote les messages qui deviennent masqués ; la révélation locale ne réactive pas les actions de citation tant que l'auteur reste bloqué. Aucun gate `isMultiRecipient` : `PrivateMessageThread.isMultiRecipient = false` n'exclut pas un MultiMP sur la page courante et le hint d'inbox est absent des deep links ; le comportement reste donc déterministe quel que soit le chemin d'entrée. L'édition de la liste depuis `MessageMenuSheet` est livrée par le lot 3. |
| Ancres de scroll par page (session) | #307/#895 F3, `pageAnchors` | **oui mais absent** | Le topic restaure la position par page visitée (moteur in-VM) ; le MP atterrit en haut à chaque changement de page. Depuis #1093, cette remise en haut n'est plus portée par `ScrollToTopOnPageChange` mais par `PrivateMessagePageLandingEffect` (`:feature:messages`), **autorité unique d'atterrissage** de la liste MP : elle arbitre entre remise en haut et atterrissage sur le message cité, et le second gagne. Le composable partagé de `:core:ui` n'a plus aucun site d'appel de production (son seul appelant restant est son propre test) — code mort à traiter hors de cette page. La reprise de **page**, elle, est livrée (ligne dédiée). |
| Pagination riche (picker, premier/dernier, snapshots RAM, stale-while-switching, chrome) | #895 étape 4, `TopicViewModel.switchToPage` | **oui mais absent** | Le cache RAM prérequis est livré ; le prefetch borné est câblé mais attend sa preuve live multipage (ligne dédiée). Le picker, les raccourcis premier/dernier, les ancres par page et le chrome riche restent au lot 6. Après envoi, `onSubmitSucceeded` retire encore l'entrée `PrivateMessageThreadRoute` puis en ajoute une copie avec un `submitSignal` neuf, ce qui recrée `PrivateMessageThreadViewModel` : tout état de pagination de session porté par ce ViewModel est alors jeté, annulant l'intérêt de le conserver. Supprimer ce remplacement de route est donc un point du lot 6, pas un détail ultérieur. |
| Prefetch **anonyme** de la page suivante | règle prefetch, `@AnonymousClient` | **non par nature** | `cat=prive` répond **403 en anonyme** : le prefetch anonyme qui donne au topic son swipe instantané est structurellement impossible en MP. |
| Prefetch **authentifié borné** (N±1, conversation ouverte) | ADR-013 décision 3 | **oui mais absent** (câblé, preuve live multipage manquante) | Le code borne le groupe à N−1/N+1 après la lecture réseau terminale de N, le gate à une composition `RESUMED`, l'annule à la pause/dispose, au changement de page ou de compte, et peuple le cache RAM avec les mêmes contrôles cible + sceau. Le point d'entrée dédié `prefetchPrivateMessageThread` est interdit hors `PrivateMessageThreadViewModel` par une garde Konsist à scan non vide ; `MessagesViewModelTest` épingle zéro prefetch depuis la liste. La livraison n'est **pas** revendiquée avant une capture réelle de trois pages de la même conversation via `scripts/capture-mp-quote-fixtures.sh` : la fixture actuelle, monopage, ne peut prouver ni les deux voisins ni le rabattement serveur. |
| Cache RAM de session (retours de page instantanés) | ADR-013 décision 2 étage 2 | **oui, livré** (lot 5, PR 1) | `PrivateMessageThreadSessionCache` : LRU globale de cinq pages, clé par compte canonique/conversation/page, purge logout et bascule de compte par `CacheInvalidator`. `PrivateMessageThreadPage.Source` distingue `SESSION_CACHE` de `NETWORK` ; le hit s'affiche immédiatement, la revalidation réseau reste obligatoire et porte seule les effets de bord. Preuves : `PrivateMessageThreadSessionCacheTest`, `DefaultMessagesRepositoryTest`, `PrivateMessageThreadViewModelTest` et garde confidentialité `ArchitectureKonsistTest`. |
| Cache Room du contenu | ADR-013 décision 2 étage 3 | **oui mais absent** (opt-in OFF par décision) | Décidé opt-in explicite, défaut OFF, purge au logout — non implémenté (lot 7). Le préalable #1096 est livré côté médias : aucune nouvelle entrée Coil disque depuis un `PostContent` MP et purge globale de l'historique au changement de session. Le risque confidentialité restant porte donc sur le futur stockage texte opt-in. |

## Anomalies Topic révélées par l'audit

Cette section ne reçoit **que** d'anciennes lignes de la matrice dont l'audit a réfuté
l'effectivité côté topic — jamais des fonctions projetées. Les trois verdicts présupposent une
fonction effective côté topic ; quand cette prémisse tombe, la ligne sort de la table : il n'y a
rien à porter en MP, l'écart relève du backlog topic, pas de celui de #1040. L'entrée reste ici
pour que la fonction ne redevienne pas un écart non tracé ; le jour où le topic livre la fonction,
sa ligne retourne dans la matrice.

**Index du message** (`Post.postIndex`) — retirée de la matrice le 2026-08-14. La ligne affirmait
« affiché via le menu contextuel côté topic » : réfuté. Le champ existe mais n'est jamais peuplé —
son unique producteur, `PostsParser`, le fixe à `null` (épinglé par `TopicPageParserTest`) ;
mappers (`TopicMappers`) et Room (`PostEntity`) ne font que le transporter. #1055 retient un
nettoyage sans migration : le rendu mort de `TopicPostIdentityHeader` et sa ressource ont été
supprimés, tandis que le champ et la colonne Room v1 restent réservés pour compatibilité de schéma.
Le menu contextuel (#362) continue d'afficher `numreponse` (`topic_post_menu_number`), pas l'index.
Le test qui épingle ce `null` reste la garde du contrat parser ; le champ ne doit pas être peuplé ou
réaffiché sans caractériser son sens entre pages, préférences HFR et cache à partir de fixtures
réelles.

## Ce qui reste hors matrice, par surface

Chrome de l'écran, pagination visuelle, sondages, drapeaux, recherche intra-topic (`transsearch`,
authentifiée par conception — son applicabilité à `cat=prive` n'a jamais été caractérisée), roster
des participants (#612), gestion des membres DT (#606) : propres à chaque surface, ils ne relèvent
pas de la carte de lecture partagée. Les décisions délibérées de confidentialité (#316 routes opaques, pas de persistance par
défaut) ne sont pas des écarts de parité — ne pas chercher à les effacer.
