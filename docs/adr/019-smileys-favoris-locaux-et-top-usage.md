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
- Les mots-clés réseau restent au **lot 2** ; leur place naturelle sera sous l'en-tête, avant
  l'insertion. Aucun chargement ni emplacement vide n'est introduit dans ce lot.

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
