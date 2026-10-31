---
title: ADR-019
parent: ADRs
grand_parent: Spécifications
nav_order: 19
permalink: /adr/019-smileys-favoris-locaux-et-top-usage
---

# ADR-019 — Smileys : favoris locaux et Top d'usage

## Statut

Accepté — 2026-10-29, décision du mainteneur pour [#1154](https://github.com/ForumHFR/redface2/issues/1154).

## Contexte

Les favoris du compte HFR sont déjà lus depuis le formulaire de rédaction. Les favoris locaux
et le Top d'usage sont deux collections distinctes, conservées sur l'appareil. Le mainteneur
demande leurs moteurs avant leur affichage ; C2 livre les favoris locaux, C1 livre le Top sans affichage.
Leur future synchronisation ne dispose pas encore d'un contrat smileys.

## Décision

- **Deux fichiers DataStore Preferences dédiés**, distincts de `user_preferences`, avec une seule
  instance singleton par fichier. Chaque magasin est isolé par compte : propriétaire explicite
  capturé par l'appelant, pseudo normalisé par `lowercase()` comme les super favoris, sans trim.
  Un propriétaire blanc est refusé. Aucune adoption de données anonymes.
- Les collections **survivent aux déconnexions** et changements de compte ; l'effacement passe
  par une API explicite `clear(owner)`. Aucun contenu n'est transféré entre propriétaires.
- **Favoris locaux** : document JSON versionné par propriétaire, exportable sans identifiant
  de compte. Il contient la liste ordonnée des favoris (`token`, `imageUrl` facultative,
  `addedAt`), les tombstones (`token`, `removedAt`) et `orderUpdatedAt` ; dates en epoch millis.
  Le jeton exact est l'identité, espaces et variantes compris. Un jeton vide ou blanc est refusé
  sans écriture (`INVALID_TOKEN`), y compris au retrait et au déplacement (ancre non nulle comprise).
  Maximum **256 favoris actifs** et **256 Kio UTF-8 par document**, tombstones comprises ;
  dépassement explicitement refusé, sauf au retrait selon la règle d'élagage ci-dessous.
  Ajout/retrait idempotents, déplacement transactionnel avant un jeton (null = fin).
  Un ré-ajout retire la tombstone et donne une nouvelle date d'ajout en fin de liste.
- Les mutations relisent et écrivent dans une même transaction DataStore. Document corrompu ou de
  version inconnue : observation vide, export et écritures refusés explicitement, document
  conservé, y compris lors d'un `clear`. Les autres propriétaires restent utilisables si le
  fichier Preferences est lisible. Aucun remplacement automatique du fichier en cas de corruption.
  Toute montée de `CURRENT_VERSION` impose une migration de lecture ; sinon les documents v1
  deviennent illisibles, préservés mais inutilisables. Cette règle vaut pour les deux magasins.
- Les tombstones sont conservées, sauf **sous pression de taille lors d'un retrait** : si le document
  résultant dépasse 256 Kio, les tombstones les plus anciennes (`removedAt` croissant) sont élaguées
  jusqu'à respecter la borne. Le retrait ne peut ainsi pas être bloqué par la limite documentaire ;
  les autres favoris, leur ordre et leurs métadonnées sont conservés. Sans dépassement, aucun élagage.
  La collecte générale reste liée à la future synchronisation.
  L'export est seulement son point d'accroche : aucun moteur de fusion, CRDT, identifiant
  d'appareil, worker ou enveloppe MPStorage smileys. Le client MPStorage v0.1 existe déjà
  ([ADR-014]({{ site.baseurl }}/adr/014-mpstorage-v01-de-facto)) ; aucune dépendance à MPStorage2.
- **Top d'usage (C1, moteur livré sans affichage)** : compter uniquement après un envoi reconnu réussi, sur le
  contenu effectivement envoyé ; en édition, seulement le delta positif d'occurrences par
  jeton. Exclure citations et blocs de code ; aucun comptage si les smileys sont désactivés.
  Suivre au plus **512 entrées**, exposer un **Top 24**, trié par compteur décroissant, puis
  dernière utilisation décroissante, puis jeton. Évincer les moins utilisées puis les plus
  anciennes. Conserver uniquement **jeton, compteur et date**, sans contenu du message ni
  identifiant de conversation. Une panne du magasin local ne change jamais un envoi HFR
  réussi en échec ; une interruption du processus peut perdre un comptage local.
  Le dernier départage de l'éviction conserve le jeton le plus petit dans l'ordre lexical,
  comme le classement. Les compteurs `Long` saturent à `Long.MAX_VALUE` sans débordement.

### Extraction et enregistrement du Top

`SmileyUsageExtractor` utilise un scanner indépendant du `Tokenizer` BBCode et du rendu de preview
qui ignore volontairement les builtins courts. Sans récursion, il indexe les fermetures de crochets
puis parcourt le texte en temps linéaire, chaque segment étant visité un nombre borné de fois.
Il reconnaît toute la table `BUILTIN_HFR_SMILEYS` et les jetons perso `[:nom]` / `[:nom:N]`,
avec espaces et casse conservés. Il exclut les blocs
`quote`, `quotemsg`, `code`, `cpp`, `fixed`, `img`, y compris imbriqués ; un bloc exclu mal fermé
reste exclu jusqu'à la fin. Les paramètres de balises, destinations `[url]…[/url]` et URL nues
(schéma `…://` ou `www.`) ne comptent pas ; le libellé de `[url=…]` reste éligible.
Une URL nue s'arrête à toute balise BBCode reconnue, ouvrante ou fermante, y compris de formatage ;
les crochets inconnus dans son chemin, ses paramètres de requête ou son fragment
(par exemple `?q[x]=:D`) restent dans l'URL exclue du comptage.
Un builtin ne peut jouxter une lettre, un chiffre, `_` ou `/` ; un `:` immédiatement avant
l'exclut aussi, pour éviter les faux positifs dans les mots, chemins et heures.

`SuccessfulSmileyUsageRecorder` capture le propriétaire courant normalisé **avant le POST**.
L'anonymat ne produit aucune écriture. Après classification `Success`, les dépôts lui passent
la valeur `content_form` du corps réellement envoyé, donc déjà assainie, et l'option finale
`smileyDisabled`. Les éditions passent aussi `form.initialContent` pour le delta positif.
Les six points d'appel sont :

- `DefaultReplyRepository.submitReply` (réponse, citation, réponse rapide) ;
- `DefaultEditPostRepository.submitEditPost` ;
- `DefaultTopicFormRepository.submitNewTopic` et `submitEditFirstPost` ;
- `DefaultPrivateMessageWriteRepository.submitReply` et `submitNewMessage`.

L'extraction et l'écriture sont lancées dans la portée applicative injectée `ApplicationScope`,
sur son dispatcher IO : aucun disque attendu au retour de l'envoi, aucune annulation par
fermeture de l'écran. Les échecs explicites et exceptions du magasin sont absorbés ; les
diagnostics ne contiennent ni contenu, ni jeton, ni message d'exception. `HfrClient` n'est pas
instrumenté : les écritures techniques MPStorage ne passent pas par ces points métier.

`SmileyUsageRepository` expose `observeTop(owner, limit = 24)`, `record(owner, usages)` et
`clear(owner)`. Le fichier dédié `smiley_usage` contient par propriétaire un document JSON v1
(`version`, `entries`), dont chaque entrée contient seulement `token`, `count`, `lastUsedAt`
(epoch millis, `Clock` injecté). Un propriétaire blanc, un jeton blanc ou un nombre
d'occurrences non positif est refusé explicitement sans écriture. Les mutations sont
transactionnelles, avec résultats d'échec explicites comme pour C2 ; aucune URL d'image requise.

## Mise à jour 29/10/2026 : affichage

Le lot D de #1154 branche les moteurs dans les quatre éditeurs, sans synchronisation MPStorage :

- Trois onglets **Favoris | Standard | Wiki**. Standard contient uniquement les builtins.
  Favoris partage une grille défilante et le budget de hauteur #900 / géométrie #989 entre
  **Favoris de l'appareil**, **Favoris HFR** (base fusionnée, smileys du compte compris), puis
  **Les plus utilisés** (Top 24). Chaque section vide est absente ; si tout
  est vide, un texte explique l'appui long et le classement des smileys envoyés.
- Le contrôleur observe les collections dès sa création dans la portée du ViewModel.
  À `open()`, Favoris est initial si une liste connue est non vide, Standard sinon ; une recherche
  Wiki restaurée (#824) ouvre toujours Wiki. Aucune arrivée tardive de données ne reprend la
  sélection à l'utilisateur. La sélection pendant l'ouverture survit à la rotation.
- `SmileyLocalCollections` (`:core:domain`), implémenté par `DefaultSmileyLocalCollections`
  (`:core:data`), observe le pseudo normalisé et bascule les deux observations ensemble.
  Anonyme ou pseudo blanc : aucune collection ni action locale. Les quatre ViewModels injectent
  ce seul composant, sans logique de propriétaire dupliquée. Le propriétaire est capturé au geste.
- À chaque changement de propriétaire, y compris via l'anonymat, le sélecteur invalide les
  données, mutations en attente et retours HFR de l'ancienne génération ; liste et commandes HFR
  restent absentes jusqu'au retour d'un formulaire démarré sous la génération courante.
  Chacun des quatre hôtes capture `favoriteFormStamp()` **avant** de lancer son chargement,
  puis transmet ce jeton à `refreshFavorites(stamp)` après remplacement du formulaire. Un retour
  d'une ancienne génération reste masqué, même après un aller-retour vers le même compte ;
  `refreshFavorites(null)` invalide les favoris quand l'hôte efface son formulaire. Le premier
  propriétaire observé établit la référence, sans invalider un formulaire initial reçu avant lui.
- Les commandes HFR nécessitent `SmileyLocalCollections` et un propriétaire observé non nul.
  Le pseudo capturé **au geste** traverse le callback jusqu'au dépôt, qui le compare à la session
  **après l'attente du mutex et le passage sur IO**, avant tout GET/POST. La normalisation des
  deux côtés est celle des collections locales : `lowercase()`, sans trim, sans dépendre du
  `userId` numérique qui peut être résolu tardivement. Un compte différent ou anonyme renvoie
  `AccountChanged`, sans requête ni retour utilisateur du sélecteur. Les contrôles précédant les
  POST et les lectures de confirmation restent liés à ce même propriétaire.
- Un favori local utilise son URL enregistrée à l'ajout. Sinon, un builtin reprend la table
  `BUILTIN_HFR_SMILEYS` ; un perso utilise une URL connue des listes chargées avant reconstruction :
  `[:nom]` → `https://forum-images.hardware.fr/images/perso/nom.gif`,
  `[:nom:N]` → `https://forum-images.hardware.fr/images/perso/N/nom.gif`.
  Le nom reste littéral, espaces compris ; l'encodage appartient au chemin Coil/OkHttp existant.
  Un jeton non résoluble est omis. Une image en échec garde le nom accessible de la cellule.
  `EditorSmileySource` reste un type de rendu : un builtin garde `BUILTIN` dans toutes les sections.
- Les actions personnalisées TalkBack proposent toutes les actions applicables : ajout/retrait
  local pour tout smiley, builtins compris ; actions HFR existantes pour les persos éligibles.
  Les mutations locales et HFR sont indépendantes. Le menu d'appui long initial est remplacé
  par le détail décrit dans la mise à jour du 30/10 ci-dessous.
  Succès local, limite de 256 ou échec de stockage empruntent le statut en tête de Favoris et la
  surcouche brève sur Standard/Wiki, avec la même durée de vie et génération de retour.
- Réordonnancement, effacement dans les Réglages, export et synchronisation restent hors périmètre.
  Les règles de comptage du Top ne changent pas.

## Mise à jour 30/10/2026 : détail du smiley

XaTriX choisit l'**option B** de #1154 : l'appui long sur tout smiley (Standard, Wiki ou n'importe
quelle section de Favoris) ouvre une **page de détail dans la même feuille**, à la place du menu
ancré. Il ne dépend plus de l'éligibilité aux favoris. Le tap continue d'insérer le jeton exact.

- La cible `EditorSmiley` vit dans `SmileyPickerState.Open`, porté par le contrôleur des quatre
  ViewModels hôtes ; elle survit à la rotation sans dépendre de la cellule paresseuse ni du
  registre du dialogue. `Hidden`, un nouvel `open()` ou un changement de compte la suppriment.
- Le détail présente une barre de retour et un titre accessible comme en-tête, l'image d'origine,
  le jeton exact en chasse fixe, le compteur local et les appartenances aux favoris. L'aperçu
  plafonne à **96 × 72 dp**, avec un agrandissement maximal de **1,5×** pour les persos mesurés ;
  les builtins passent de 20 à **32 dp**. Même URL, mêmes mesures intrinsèques et caches Coil que
  la grille : aucune image de résolution supérieure n'est supposée disponible.
- Le bouton « Insérer dans le message » appelle exactement le callback du tap. La liste propose
  « Copier le code », l'ajout/retrait local et l'action HFR éligible. Les builtins et entrées
  reconnues du compte n'ont pas d'action HFR. Une mutation HFR affiche son état en cours ; elle
  ne bloque ni l'insertion ni les actions locales. Le détail reste ouvert après un retrait ;
  actions et puces suivent les listes courantes, sans inférer l'appartenance depuis la source
  de rendu conservée dans la cible. Les retours `SmileyFavoriteFeedback` restent visibles dans
  le détail, sous les actions défilantes, avec les générations, priorités d'échec et règles de
  compte existantes. La copie utilise le retour système dès Android 13, un Toast avant.
- Une seule observation `observeTop(owner, limit = MAX_ENTRIES)` fournit les **512 entrées**
  retenues. `SmileyLocalCollectionSnapshot` transmet leur dictionnaire de compteurs et projette
  séparément les **24 premières** pour le Top affiché. Pas de lecture disque à l'appui long,
  pas de nouvelle API de dépôt ni de requête réseau. Une absence d'entrée affiche
  « Pas encore envoyé sur cet appareil » ; sinon le compteur `Long` est conservé intégralement.
- La flèche et le retour système reviennent à la grille. Un callback sur le **dispatcher du
  dialogue** intercepte le retour avant celui de Material : un retour prédictif annulé conserve
  le détail ; un retour validé le ferme sans animer la fermeture de la feuille. Onglet, recherche,
  sélection de texte et positions des grilles sont sauvegardés pendant cette visite. L'ouverture
  du détail retire le focus de saisie et masque le clavier ; le retour ne redemande pas le focus
  Wiki. Glisser la feuille ou toucher son voile ferme toujours l'ensemble.
- Les actions TalkBack des cellules sont conservées avec « Afficher le détail », également libellé
  de l'appui long. Le retour du **focus TalkBack sur la cellule d'origine n'est pas garanti** :
  les nœuds de la grille sont recréés, et la cellule peut disparaître après un retrait. Aucun
  transfert forcé de focus de saisie ne prétend résoudre ce focus d'accessibilité ; contrôle sur
  appareil nécessaire, comme pour le clavier et le retour prédictif réel.
- Le lot 1 ne chargeait pas les mots-clés réseau ; le lot 2 ci-dessous les plaçait sous
  l'en-tête. Les retours 0.64.6 plus bas révisent cette disposition.

## Mise à jour 30/10/2026 : mots-clés du wiki (lot 2)

- Le détail d'un perso charge les mots-clés publics du wiki **à son ouverture**, sans compte ni
  cookies. Aucun appel ni section pour un builtin. Le contrat et les captures anonymes du 30/10
  sont décrits dans [Protocole HFR]({{ site.baseurl }}/specs/protocol-hfr#mots-clés-du-détail-wiki).
- `HfrClient.getSmileyWikiDetail` lit la page ; `SmileyWikiKeywordsParser` (`:core:parser`) extrait
  la ligne correspondant au jeton exact. `SmileyRepository` (`:core:domain`) expose
  `getWikiKeywords` et la lecture immédiate `cachedWikiKeywords`, implémentées par
  `DefaultSmileyRepository` (`:core:data`), avec réseau et parse dans `withContext(ioDispatcher)`.
  Le résultat typé `SmileyWikiKeywordsResult` vit dans `:core:model`, partagé par le dépôt et
  les consommateurs : `Loaded(List<String>)`, `Failed`, `NotApplicable` pour les builtins.
  Inconnu du wiki et connu sans mot-clé produisent tous deux `Loaded(emptyList())` ; l'inconnu
  n'est reconnu qu'au marqueur HFR « Désolé, aucune réponse n'a été trouvée », avec un champ
  `detail` égal au jeton. Toute autre page sans ligne cible est un échec non mis en cache.
- Le **cache LRU en mémoire de 256 entrées** appartient au dépôt singleton, donc au processus,
  sans disque ni invalidation au changement de compte. Clé : jeton exact. Seuls les résultats
  réussis, listes vides comprises, sont mémorisés ; les échecs ne le sont jamais. Une lecture
  synchrone du cache évite de flasher un chargement au retour sur un smiley, y compris dans un
  nouvel éditeur. Le cache disparaît à la mort du processus ; pas de catalogue hors ligne ni TTL.
- `SmileyPickerState.Open.wikiKeywords` porte `Hidden`, `Loading`, `Loaded(List<String>)` ou
  `Failed`. Les quatre hôtes fournissent les callbacks du dépôt au contrôleur partagé. Un job
  enfant de la portée du ViewModel charge la cible ; quitter le détail, fermer/réouvrir la feuille,
  changer de cible ou de compte l'annule. Une génération de visite et le jeton rejettent aussi
  les réponses tardives d'un callback non coopératif. La rotation conserve la cible et son état.
- Dans la présentation initiale du lot 2, la section **« Mots-clés du wiki »**, titre sémantique
  TalkBack, précédait **« Insérer dans le message »**, conformément à la maquette B.
  Des `Surface` textuelles sans action de clic reprennent les puces d'appartenance, dans un
  `FlowRow` qui conserve l'ordre et passe à la ligne. Un minimum de hauteur de 72 dp réserve
  le titre et une ligne pendant le chargement ; le contenu complet défile avec le détail.
  Chargement discret, liste vide « Aucun mot-clé sur le wiki », échec « Mots-clés indisponibles »
  avec bouton **« Réessayer »**, relançant seulement le chargement de la cible courante.
- **Retour favori (finding Fable du lot 1)** : conserver le jeton de la mutation et un compteur
  d'émission à sa terminaison, distinct de la génération de geste utilisée par la surcouche.
  Le contrôleur capture ce compteur à l'ouverture du détail, avant toute composition. Le détail
  affiche un retour uniquement pour son jeton et s'il a été émis depuis cette ouverture ; un échec
  tardif d'un autre smiley reste disponible pour la grille, sans être consommé par ce détail.
  Les priorités d'échec et les règles de compte existantes restent applicables.

Le contrôle sur appareil doit inclure TalkBack (titre puis textes sans rôle de bouton), petits
écrans et grande police, rotation pendant le chargement, retour prédictif et réessai sur réseau réel.

## Mise à jour 30/10/2026 : retours 0.64.6

XaTriX signale sur S10e des commandes masquées et quelques fermetures immédiates à l'appui long,
avant l'affichage des mots-clés, sur un perso présent dans les favoris HFR **et** de l'appareil.
La fermeture ne se reproduit plus lors de ce retour ; aucune exception ni reproduction sur
l'appareil n'est disponible. Le correctif traite la continuité du geste identifié dans le code,
sans attribuer rétrospectivement l'incident à une cause certaine.

### Commandes prioritaires et budget de hauteur

- En-tête horizontal : aperçu **112 × 88 dp** conservé, puis code, compteur et appartenances
  à droite. Espacement vertical de 8 dp entre les blocs. Les puces restent informatives.
- **Insertion, copie, action locale et action HFR précèdent les mots-clés**. Les `ListItem`
  Material 3 conservent leurs dimensions et leurs cibles tactiles ; aucun bouton n'est rétréci.
- Les mots-clés arrivent **en dernier**. Une disposition mesure les puces et ne place que les
  **deux premières lignes**. Si une troisième ligne est nécessaire, **« Tout afficher (N) »**
  déroule la liste, puis **« Réduire »** la replie. Pas de seuil fondé sur le nombre de caractères.
  Le défilement vertical reste disponible en paysage, avec grande police et après expansion.

Calcul au thème par défaut, deux appartenances et dix mots-clés du retour `[:ticento:6]` :
ce sont des **budgets calculés**, pas des mesures instrumentées sur S10e.
`smileyPickerHeightCap()` borne **le contenu du détail**, pas toute la `ModalBottomSheet` :
`max(320, 760 × 0,62) = 471,2 dp`. La barre de retour occupe 48 dp, laissant **423,2 dp**
au viewport défilant, hors éventuel retour de mutation fixé en bas. La poignée M3 (48 dp),
les marges verticales du contenu (8 + 8 dp) et l'inset inférieur sont **extérieurs** à ce cap :
la feuille mesure au plus environ **535,2 dp + inset inférieur**, sous les contraintes de fenêtre.
À 360 dp de large, les marges de feuille et de bloc laissent 328 dp ; l'aperçu et l'espacement
horizontal de 12 dp laissent 204 dp aux métadonnées.

| Bloc sous la barre | Avant | Après |
|---|---:|---:|
| En-tête | 180 dp (88 + 24 + 20 + 24 + 3 × 8) | 124 dp (code 24, compteur sur deux lignes 40, puces sur deux lignes 52, 2 × 4) |
| Mots-clés : titre et deux lignes | 76 dp | 76 dp, après les commandes |
| Insertion : cible tactile | 48 dp | 48 dp |
| Séparateur | 1 dp | 1 dp |
| Trois actions M3 | 168 dp (3 × 56) | 168 dp |
| Quatre espacements entre blocs | 48 dp | 32 dp |
| Total défilant | **521 dp** | **449 dp** |
| Borne basse de la dernière commande depuis le haut du viewport | **521 dp** | **365 dp** |

Avant, environ **97,8 dp** des commandes débordaient du viewport de 423,2 dp. Après, toutes
les commandes y tiennent avec environ **58,2 dp** de marge ; la fin des informations secondaires
peut encore défiler. Si les 760 dp incluent deux barres système de 24 dp exclues de
`screenHeightDp`, le calcul conservateur à 712 dp donne un viewport de **393,4 dp**, soit encore
28,4 dp au-dessous des commandes. Un compteur long ou les métriques de police peuvent changer
ces nombres : les tests vérifient les **bornes réellement mesurées**, sans défilement préalable,
sur la feuille complète à 360 × 760 dp, et la possibilité de défiler en paysage à police ×2.

### Analyse des fermetures

Inspection locale du bytecode des artefacts **Material 3 1.4.0**, Foundation et runtime-saveable
**1.11.2** épinglés par le BOM du dépôt, avec `javap` (aucun téléchargement).
Les numéros M3 suivants sont ceux du fichier Kotlin identifié dans ses métadonnées :

| Piste | Conclusion et preuve |
|---|---|
| Clé dupliquée entre favoris HFR et appareil | **Pas de collision identifiée** : `favoriteSections` préfixe chaque clé par l'identifiant de section, puis longueur du jeton, jeton et URL ; `distinctBy` élimine les doublons exacts dans chaque section. La grille générique utilise un préfixe différent. Le test compose les deux occurrences, ouvre chacune et restaure la grille plusieurs fois. |
| `SaveableStateHolder`, `rememberSaveable`, `key()` | **Pas d'exception identifiée sur ce chemin** : un seul fournisseur `"grid"` est composé, dans la branche opposée au détail ; les fournisseurs des onglets ont leur propre holder. Les clés sont des chaînes/entiers sauvegardables ; la cible reste dans le contrôleur, hors du registre. `SaveableStateHolderImpl` peut bien lever `IllegalArgumentException` (« Key … was used multiple times »), mais aucun second fournisseur simultané de même clé n'est trouvé ici. `key(smiley.token)` groupe un seul détail, sans registre global de jetons. |
| Retours de mutation et recomposition | **Pas de fermeture identifiée** : `publishFavorites(resetDetail = false)` conserve le détail, `updateFavoriteFeedback` ne publie pas `Hidden`. Un changement de compte retire uniquement le détail. Les générations de mots-clés rejettent les retours obsolètes. Tests des deux retraits dans le cas de double appartenance. |
| Fin de l'appui long après remplacement de grille | **Mécanisme retenu** : `SmileyFavoriteTarget` appelle immédiatement `onDetailChange` depuis `combinedClickable`. `SmileyPickerSheet` retire alors la grille. Foundation `CombinedClickableNode.onPointerEvent` consomme normalement les mouvements après détection ; cette cellule disparaît, alors que le `draggable` M3 de la feuille reste attaché. `ModalBottomSheetContent` (vers 294–356) connecte le scroll résiduel et le relâchement à `settleToDismiss`. `AnchoredDraggableState.settle` peut choisir `Hidden` selon déplacement/vitesse. Cela établit un chemin possible, pas la reproduction de l'incident réel. |
| Hauteur grille → détail → mots-clés et ancres | **Écartée comme cause autonome** : les ancres M3 sont `Hidden = fullHeight`, `Expanded = max(0, fullHeight - sheetHeight)` si le contenu est non nul. Le calcul de nouvelle cible conserve `Expanded` lorsqu'elle existe (`ModalBottomSheet.kt`, vers 294–331). `updateAnchors` reprend cette cible par `trySnapTo` ou `dragTarget` ; une variation de taille ne choisit pas `Hidden` d'elle-même. Le détail conserve une barre et une image, donc ne passe jamais par une hauteur nulle. Une cible `Hidden` déjà acquise par geste peut en revanche être conservée. |
| `clampSheetTopOverscroll` | **Écartée comme déclencheur** : `onPostScroll` et `onPostFling` consomment seulement `available.y < 0` à `Expanded`. Aucun pré-scroll, aucun appel à `hide`/`dismiss` ; un geste descendant traverse cette garde. Elle ne protège pas la fin d'un appui long. |
| Retour système et `onDismissRequest` | **Pas d'appel spontané identifié** : `SmileyDetailBackHandler` retire le détail uniquement sur un retour validé. M3 appelle `onDismissRequest` après un `hide`, un settle devenu invisible, ou le retour système ; aucune branche « mots-clés chargés » ne l'appelle. Les traces distinguent désormais ces frontières. |
| Crash suivi de recréation | **Écarté par XaTriX pour cet incident** : son témoignage précise que seule la feuille se fermait, sans plantage. La lecture n'identifie pas de collision ni d'exception non interceptée sur l'ouverture du double favori ; le chargement suspendu utilise `runCatching` en propageant `CancellationException`. Cela ne garantit pas l'absence générale d'exceptions. |

`SmileySheetGestures`, attaché à la **feuille stable**, observe et consomme les événements
en passe initiale via un `PointerInputModifierNode` synchrone, avant le `draggable` M3.
Ouvrir le détail arme le verrou uniquement si un doigt est encore posé. Le verrou couvre les
mouvements et le relâchement ; une connexion `nestedScroll` sur le contenu consomme les deltas
et la vitesse résiduels de la grille retirée. Il est levé au prochain premier vrai `DOWN` ;
un doigt supplémentaire ou une annulation synthétique du détecteur ne le lèvent pas.
Une action d'accessibilité sans pointeur ne le pose pas.
Le geste suivant peut à nouveau fermer la feuille. Aucun délai arbitraire, aucun changement
d'ancres, aucune modification de `clampSheetTopOverscroll`.

La passe 1 désactivait aussi `sheetGesturesEnabled` pendant le geste et levait le verrou dès
la passe initiale du `UP`. Le bytecode M3 montre que désactiver cette option **retire sa connexion
nested-scroll** ; Foundation réinitialise également le détecteur quand `enabled` change.
La passe 2 conserve cette chaîne et son chemin de pointeurs, et ne lève plus le verrou avant
les callbacks de fin du geste. Le test d'intégration injecte le `DOWN`, les mouvements et le
`UP` sur **le même nœud de feuille**, sans nouveau `DOWN` entre grille et détail. Il vérifie
aussi la position de la feuille **avant le relâchement**, puis qu'un nouveau glissement ferme
normalement. Les validations des passes 1 et 2 échouent encore avec la garde : voir le défaut
de placement établi en passe 3 ci-dessous. Ces échecs ne permettent pas d'attribuer l'incident
réel à un callback particulier.

#### Passe 3 : bornes de pointeurs avant le décalage M3

La garde de passe 2 et le tag `smiley_picker_sheet` étaient dans le **modifier fourni à M3**.
Le bytecode M3 1.4.0 établit l'ordre `modifier → nestedScroll → draggableAnchors → draggable`
(`ModalBottomSheetContent`, offsets JVM 1450, 1649 et 1840 ; lignes Kotlin 282, 295 et 327).
`DraggableAnchorsNode` mesure sans translater sa propre boîte, puis place **son enfant** à
`requireOffset()` (lignes Kotlin 847 et 854–862). La garde et le tag extérieurs restaient donc
en haut du dialogue, avant le décalage du contenu réellement touché.

Sur la courte grille des deux favoris, le `DOWN` de la cellule peut être hors de ces bornes.
`NodeCoordinator.speculativeHit` saute alors le nœud dont `interceptOutOfBoundsChildEvents`
vaut `false` (offsets JVM 68–106 ; valeur par défaut de `PointerInputModifierNode`). La garde
manque ce `DOWN`, `pressed` reste faux et **`openingDetail()` n'arme pas `blocked`**. Son
`remember` reste stable ; il n'existe pas de coroutine `pointerInput` ni de `finally` qui
réinitialiserait le verrou pendant la recomposition. La même erreur de coordonnées explique
pourquoi l'ancien tag ne voyait ni le déplacement ni la portion de voile au-dessus du contenu.

Foundation **1.11.2** respecte la consommation : `DragGestureNode.processAwaitTouchSlop`
lit `isConsumed` en passe **Main** (offset 243), `processAwaitGesturePickup` en passe **Final**
(offset 76), et `processDraggingState` en Main (offsets 226 et 274). Le helper suspendu
`awaitPointerSlopOrCancellation` abandonne également sur `isConsumed` (offsets 397 et 745) ;
le draggable de cet artefact utilise directement la machine à états synchrone.
Une annulation d'un drag déjà engagé appelle toutefois encore `onDragStopped` avec une vitesse
nulle (`processDragCancel`, offset 191), et M3 le relie à `settleToDismiss`
(`ModalBottomSheetContent$4$1`, offset 45). De même, son `onPostFling` appelle `onFling` même
avec une vitesse disponible nulle : consommer la vitesse seule n'empêche pas un settle après
un déplacement déjà acquis. Les anciens rapports ne permettent pas de distinguer ces callbacks.

Le correctif déplace garde et tag sur une **colonne stable à l'intérieur du contenu M3**,
englobant poignée et page. Elle suit le décalage, reçoit le `DOWN` et reste sur son chemin de
pointeurs quand la grille disparaît. Sa consommation Initial précède toujours la lecture Main
du draggable parent. Aucun changement de hauteur, d'ancres ou de règle de fermeture.

Le test du geste inspecte la **vraie instance** de garde via `LayoutInfo`, vérifie sa présence
au `DOWN` et son identité après recomposition, puis compare l'offset du contenu déplacé.
Un observateur installé uniquement par ce test imprime `SmileyGuard` : passe, type, bornes,
id du pointeur, positions, pression, consommation et verrou ; chaque assertion comportementale
reprend ces événements, l'état du contrôleur, les traces M3/cause de fermeture et l'offset,
même si la sortie standard n'est pas conservée. Les tests de voile utilisent un écran explicite
360 × 1 200 dp et un tap calculé hors du **contenu déplacé**. Le petit drag dépasse le touch slop
réel de 16 dp, au lieu de dépendre d'un déplacement fixe de 48 pixels et de la densité du test.

### Diagnostic exportable

Les quatre hôtes passent leur `DiagnosticsLog` au contrôleur. Le tag **`SmileyPicker`** contient
ouverture/fermeture de feuille et détail, cause de fermeture, type `perso`/`standard`, attaches
de l'UI, paires `currentValue`/`targetValue` aux transitions, hauteur en dp stabilisée pendant
150 ms, résultat des mots-clés (`loaded`, `empty`, `failed`) et durée monotone, cache compris.
Ni jeton, ni mot-clé, ni URL, ni compte, ni message d'exception. **Limite M3 1.4.0** : son callback
`onDismissRequest` commun ne précise pas si la fermeture provient du voile, d'un glissement ou
du retour système. Ces fermetures sont tracées **`cause=unknown`**. Un déplacement antérieur
ne suffit pas à conclure `swipe` : un tap sur le voile peut fermer pendant le retour en place
d'un petit glissement. La poignée utilise toujours `BottomSheetDefaults.DragHandle`,
mais son tap et son action d'accessibilité passent par un callback explicite (`sheet_action`),
pour ne pas être confondus avec un tap sur le voile dans le callback commun de M3.
Cette poignée maison perd toutefois le `DragHandleWithTooltip` de M3 1.4.0 et son infobulle
« Poignée » à l'appui long.
Le callback du détail reste actif uniquement sur le détail ; depuis la grille, **M3 conserve
la progression, l'annulation et la validation du retour prédictif**. On renonce à identifier
`system_back` dans le callback commun plutôt que de remplacer celui de M3 par un callback sans
animation. L'insertion est explicitement identifiée dans chacun des quatre ViewModels.

Après le prochain incident, ouvrir **Réglages → Réseau et cache → Maintenance → Diagnostic → Copier**, puis
coller le texte dans le retour de bug. **Compte HFR et à propos → Diagnostic** donne aussi accès
au même écran. Le bouton existant exporte maintenant ce tag en plus des trois tags réseau
autorisés ; les autres producteurs restent visibles localement et exclus de l'export.
Le tampon reste limité à **200 entrées en mémoire** : une mort du processus efface les traces.
Cet export ne suffit donc pas à confirmer rétrospectivement un crash fatal ; il faut alors
le journal de crash Android. Aucun stockage persistant ni gestionnaire global d'exception ajouté.

Validation externe de la passe 1 : **3 171 tests exécutés, six échecs** (geste maintenu, tap sur le
voile, quatre comparaisons d'état du contrôleur) ; detekt, lint, Konsist et DocsConsistency verts.
La passe 2 ajoute le retour prédictif depuis la grille, le tap sur le voile pendant un retour
en place et le cycle du verrou. Sa validation externe exécute **3 177 tests, trois échecs**
(geste maintenu, géométrie du voile et déplacement du petit drag) ; les autres contrôles sont verts.
La passe 3 corrige le placement et instrumente ces tests. Sa validation externe (socadisc) est
**verte : 3 177 tests**, detekt, lint, Konsist et DocsConsistency verts.
La contre-épreuve remplace **`blocked = pressed` par `blocked = false`** dans
`SmileySheetGestures.openingDetail()` et neutralise consommation et protection du post-fling :
le test **`double favorite long press then downward movement before release keeps the detail open`**
échoue alors (**un échec sur les 20 tests de `SmileyPickerDetailGesturesTest`**), puis passe avec
la garde rétablie. **Contrôle S10e en attente.**
Le contrat HTTP des mots-clés est inchangé ; aucune modification de `protocol-hfr.md`.

## Conséquences

- C1 et C2 fournissent les moteurs ; D affiche leurs collections dans le sélecteur partagé,
  sans écran supplémentaire ni nouvelle valeur d'`EditorSmileySource`.
- L'utilisateur peut consulter et gérer ses favoris locaux ; leur effacement global dans les
  Réglages reste à venir.
- Les dates et tombstones préparent un export fidèle, sans définir une résolution des conflits
  entre appareils. Le Top suit une fréquence d'utilisation et reste une donnée locale personnelle.

## Alternatives considérées

- Favoris HFR seuls : ne couvrent pas une collection locale indépendante du serveur.
- Clés dans `user_preferences` : mélangent collections métier et préférences générales.
- Room, CRDT ou protocole de synchronisation immédiat : complexité sans besoin prouvé dans C2.
- Comptage à l'insertion : compte les essais abandonnés et manque les jetons saisis manuellement.
