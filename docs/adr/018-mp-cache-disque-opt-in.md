---
title: ADR-018
parent: ADRs
grand_parent: Spécifications
nav_order: 18
permalink: /adr/018-mp-cache-disque-opt-in
---

# ADR-018 — Lecture MP : cache disque opt-in activé, rémanence SQLite scrubée à l'événement

## Statut

Accepté — 2026-08-25 ([#1097](https://github.com/ForumHFR/redface2/issues/1097), lot 7 PR 3 de
[#1040](https://github.com/ForumHFR/redface2/issues/1040)).

**Supersède l'[ADR-013]({{ site.baseurl }}/adr/013-mp-lecture-cache-prefetch)**, qui passe donc au
statut `Superseded par ADR-018`. C'est le **premier usage** de ce statut dans le dépôt : il existait
dans les règles de [`docs/adr/README.md`]({{ site.baseurl }}/adr) depuis la création du format sans
avoir jamais été appliqué.

⚠ **Une supersession totale n'est pas un remplacement total.** L'ADR-013 portait **quatre**
décisions et le lot 7 n'en remplace **qu'une**. Marquer l'ADR-013 supersédée sans reprendre le reste
laisserait trois décisions vivantes dans un document sans autorité. La présente ADR devient donc la
source de vérité de **l'ensemble** de la lecture MP, et reprend explicitement ce qui reste actif :

| Décision de l'ADR-013 | Sort dans l'ADR-018 |
|---|---|
| **1** — partage topic↔MP à deux niveaux dans `:core:ui` | **reprise sans changement de fond** → décision 1 |
| **2 étages 1 et 2** — position de lecture locale, cache RAM de session | **repris sans changement de fond** → décision 2 |
| **2 étage 3** — cache Room du contenu | **remplacée** → décisions 3 à 6 (activation, frontière, désactivation, rémanence) |
| **3** — prefetch authentifié borné aux pages adjacentes | **reprise sans changement de fond** → décision 7 |
| **4** — critère de convergence topic↔MP (forme amendée du 2026-08-12) | **reprise sans changement de fond** → décision 8 |

L'ADR-013 ne subsiste que comme **historique** : elle garde la trace des arbitrages de juin 2026, du
contexte serveur mesuré en [#361](https://github.com/ForumHFR/redface2/issues/361) et de ses cinq
amendements successifs. Elle ne fait plus autorité sur aucune décision, y compris celles reprises
ici sans changement.

Le précédent de forme dans le dépôt est celui où l'**ADR-003 supersède partiellement l'ADR-009** —
la plus récente borne la plus ancienne, et seulement sur son contexte
(`docs/adr/003-api-rest-hfr-hybride.md` § Conséquences : « ADR-009 reste **Accepté** pour la décision
OkHttp 5.3+ direct […] Son contexte "pas d'API REST structurée" est partiellement superseded »). La
présente ADR va plus loin — supersession totale avec reprise — précisément parce que le document
supersédé porte plusieurs décisions indépendantes. À ne pas confondre avec l'ADR-009 elle-même, dont
le statut porte depuis le 2026-08-23 la mention inverse : « Pas de statut `Superseded` : aucune ADR
ne remplace celle-ci. »

## Contexte

### Ce que le lot 7 a rendu atteignable

L'étage 3 du cache MP (contenu des conversations en Room) était **livré dormant** par la PR 2 du lot
7 : tables `mp_thread_pages` et `mp_messages` (schéma 17, migration 16→17), façade disque
sérialisée `RoomPrivateMessageThreadDiskCache`, préférence globale par défaut OFF, borne de cinq
pages par compte, purges compte et globale. **Aucun écran ne pouvait l'activer** : la matrice de
parité classait donc la fonction « oui mais absent (opt-in OFF par décision) ».

L'ADR-013 posait explicitement une **décision-gate** à lever avant toute exposition UI, dans sa
conséquence « Rémanence SQLite » : `DELETE` ne scrubbe ni les pages libres ni nécessairement le WAL,
donc le substrat devait rester inactivable tant que le mainteneur n'avait pas arbitré et documenté
soit `PRAGMA secure_delete=ON`, soit l'acceptation explicite de la rémanence dans le modèle de
menace. C'est cet arbitrage que la présente ADR rend, en même temps qu'elle active la fonction.

### Le précédent que le dépôt vient de poser sur la rémanence

[#1096](https://github.com/ForumHFR/redface2/issues/1096) puis
[#1099](https://github.com/ForumHFR/redface2/pull/1099) ont qualifié de **défaut** — pas de compromis
acceptable — la survie des **images** de conversations privées à la déconnexion et au changement de
compte : « la donnée privée survivait à l'événement censé la faire disparaître ». La conséquence
« vie privée » de l'ADR-013 a été amendée en conséquence, et la politique média fermée sur les deux
fronts (l'avenir par `PostMediaDiskCachePolicy` à `DISABLED` sur les hôtes MP, le passé par la purge
Coil globale de `CacheInvalidator`).

Le même raisonnement vaut mot pour mot pour le **texte** : rien ne justifie de tenir pour un défaut
la rémanence des octets d'une image MP et pour un détail celle des octets d'un message MP.

### Le modèle de menace, et ce qu'il borne

L'adversaire considéré est l'**extraction forensique sur appareil déverrouillé**. Deux faits le
bornent déjà, et ne sont pas remis en cause ici :

- `android:allowBackup="false"` et `android:fullBackupContent="false"` dans le manifeste de `:app`
  ferment le vecteur sauvegarde : la base n'atteint aucune sauvegarde cloud Android ni `adb backup` ;
- le **File-Based Encryption** de la plateforme (`minSdk = 29`) ferme le vol d'appareil éteint : les
  fichiers de l'app sont chiffrés au repos tant que l'appareil n'a pas été déverrouillé une fois.

Ce qui reste est donc étroit et bien identifié — et c'est là que se joue l'arbitrage.

## Décision

### 1. Partage topic↔MP à deux niveaux dans `:core:ui` — repris de l'ADR-013, inchangé

Les **fonctions pures** du geste (`core/ui/.../pager/PageSwipe.kt`), le scrollbar générique
(`core/ui/.../list/`), puis la carte de lecture commune `ReadingPostCard`, la machine de zoom
`PinchZoomState` et le chrome `PageFab`/`PageNavigation` vivent dans `:core:ui`. La **machinerie
gestuelle** reste feature-owned : `Modifier.topicPageSwipe` et `Modifier.threadPageSwipe` gardent
chacun leur `pointerInput`, leur latch et leur protocole de sélection, sans fusionner les deux
pipelines. Le MP pagine in-place par `selectPage()`, avec release conditionnelle (slide-out si la
cible est chaude dans le cache RAM, retour à offset nul sinon — amendement du 2026-08-24).

Le partage se fait au niveau de la **primitive sans politique**, jamais des écrans ni des
ViewModels : c'est le contrat que la
[matrice de parité]({{ site.baseurl }}/specs/reading-parity) fait respecter.

### 2. Étages 1 et 2 du cache MP — repris de l'ADR-013, inchangés

1. **Position de lecture locale par conversation** (table `mp_read_positions`) : retenue
   inconditionnellement, parce qu'il n'existe **aucune** position de lecture serveur pour les MP
   ([#361 Q3](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132) : l'état
   serveur se réduit à un dot binaire par conversation). Seedée depuis MPStorage pour les DT
   ([ADR-014]({{ site.baseurl }}/adr/014-mpstorage-v01-de-facto) §5), purgée à la déconnexion.
2. **Cache RAM de session** : `PrivateMessageThreadSessionCache`, LRU globale de cinq pages, clé par
   compte canonique/conversation/page, purge synchrone et génération anti-réponse-tardive avancée
   par `CacheInvalidator` avant toute purge suspendante. Rien n'est écrit sur disque ni dans les
   diagnostics ; le cache ne survit pas au process death.

### 3. Étage 3 activé — un opt-in **global à l'application**, défaut OFF, portée multi-comptes

Le cache Room du contenu devient atteignable par **un seul réglage**, dans la section « Messages
privés » des réglages, à côté des deux réglages MP existants.

- **Granularité** : un toggle **global à l'application**, pas par compte. La donnée reste indexée
  par compte canonique en base (convention `lowercase()` de la façade disque), mais le consentement
  est unique.
- **Le libellé le dit explicitement** — « Cache disque des messages privés (tous les comptes) », et
  sa description : « Activé pour tous les comptes utilisés sur cet appareil. » Ce n'est pas une
  question de rédaction : c'est la seule chose qui empêche un utilisateur de croire que son second
  compte n'est pas concerné.
- **Défaut OFF**, et OFF signifie **zéro** lecture et **zéro** écriture de ligne MP sur le chemin de
  lecture — pas « des lignes ignorées ».
- **Ce n'est pas un mode hors-ligne** : au plus cinq pages par compte, et **toute** lecture reste
  revalidée par le réseau. La fonction accélère un retour de page, elle ne promet rien sans
  connectivité.

### 4. Frontière RAM/Room : ordre de lecture, hit provisoire, prefetch RAM-only

L'ordre est **RAM → Room si ON → réseau obligatoire** :

- un hit `SESSION_CACHE` puis, à défaut seulement, un hit `DISK` s'affichent immédiatement mais sont
  **provisoires** : ils ne déclenchent aucun effet terminal et n'autorisent aucune écriture de
  domaine ;
- **un hit disque n'alimente pas la RAM** : seule une réponse réseau visible, terminale, encore
  conforme à la cible et au sceau compte/génération peuple les caches ;
- **le prefetch reste RAM-only, même toggle ON** : `prefetchPrivateMessageThread` passe
  `persistToDisk = false`. Une page jamais affichée ne s'écrit jamais sur le disque de
  l'utilisateur ;
- un **verrou singleton** sérialise toutes les lectures, écritures et purges Room, et le sceau
  compte/génération est revérifié avant lecture, écriture et émission.

### 5. Désactiver = purge immédiate, et les chemins d'échec sont le contrat

Ce sont des données de **cache**, jamais une source utilisateur : les garder après OFF serait un
piège plus grave que les supprimer. La désactivation suit cet ordre, exactement :

1. **confirmation explicite** de l'utilisateur (dialogue nommant la portée : tous les comptes de
   l'appareil, et rappelant que rien n'est effacé sur HFR) ;
2. **persistance de `OFF`** ;
3. **invalidation synchrone** de la génération RAM ;
4. **purge Room sérialisée**, tous comptes confondus (`clearAll`).

Les chemins d'échec, qui font la fiabilité de la fonction :

- **écriture du réglage en échec** (`PreferenceWriteFailed`) → **rester ON et ne rien purger**.
  Aucune opération destructrice n'a commencé ;
- **purge en échec après OFF** (`PurgeFailed`) → **rester OFF en interdisant toute lecture et toute
  écriture disque**, afficher une erreur **réessayable**, et **retenter au démarrage**. Le marqueur
  d'échec est durable : OFF est l'état **effectif** tant qu'une purge est pendante, même si la
  préférence stockée dit ON ;
- **aucun retour silencieux à ON**, dans aucun cas.

Le **propriétaire de la reprise** est nommé : `CacheInvalidator.start()`, lancé par
`RedfaceApplication`, appelle `reconcileOnStartup()` à chaque démarrage — qui ne fait quelque chose
que si une purge est **pendante**, l'accès disque restant refusé tant qu'elle l'est ; le ViewModel
des réglages expose en plus le réessai manuel. Un utilisateur qui croit avoir purgé alors que non est le pire
état possible pour cette fonction.

### 6. Rémanence SQLite : **scrub événementiel**, pas de réglage permanent

C'est la décision-gate posée par l'ADR-013, tranchée par @XaTriX le 2026-08-25 sur recommandation
d'un gate indépendant.

**Décidé** : `DELETE` **+ `PRAGMA wal_checkpoint(TRUNCATE)` + `VACUUM` + un second
`PRAGMA wal_checkpoint(TRUNCATE)`**, exécuté aux **trois événements de confidentialité** — passage
du toggle à OFF, déconnexion, bascule de compte. Le scrub vit dans
`PrivateContentDatabaseScrubber` et s'insère dans `CacheInvalidator`, qui sérialise déjà les purges
DAO ; il y est **délibérément le dernier**, de sorte que les lignes privées supprimées par les
purges qui le précèdent (drapeaux, positions de lecture, brouillons MP, images uploadées,
localisation MPStorage) soient scrubées elles aussi.

⚠ **Le second checkpoint n'est pas une ceinture, c'est la moitié de la garantie.** En mode WAL, le
`VACUUM` est une transaction ordinaire : il écrit son image propre **dans le journal**, et le
fichier principal n'est réécrit qu'au checkpoint **suivant**. Mesuré : après
`DELETE` + `wal_checkpoint(TRUNCATE)` + `VACUUM`, la sentinelle est **toujours** présente dans
`redface.db` ; elle n'en disparaît qu'au second checkpoint. L'autocheckpoint passif d'Android
rattrape souvent le coup, mais ne le garantit jamais — ni sur une base de moins de ~400 Ko, ni sous
lecteur concurrent — donc sans ce second checkpoint la fenêtre post-purge que ce scrub existe pour
fermer resterait ouverte. Il porte le **même** contrôle `busy == 0` que le premier : un checkpoint
occupé fait échouer le scrub, donc retombe dans le mécanisme fail-closed de la décision 5. Ce
constat **confirme** le choix `VACUUM`, il ne l'invalide pas : le repli documenté plus bas n'est pas
déclenché.

Le raisonnement, qui borne exactement ce qu'on achète : l'adversaire résiduel lit les **lignes
vivantes** tant que l'opt-in est ON — rémanence ou pas. Le seul delta que la rémanence ajoute, c'est
la fenêtre **post-purge**, où l'utilisateur a désactivé le cache ou s'est déconnecté **en croyant la
donnée effacée**. C'est cette garantie-là, et seulement elle, qu'il faut payer :
**événement de purge ⇒ octets disparus**. Le coût de régime permanent est nul — rien ne change entre
deux événements de confidentialité. **Le démarrage n'en est pas un** : la réconciliation lancée par
`CacheInvalidator` ne scrubbe que si une purge est réellement **pendante**. Forcer un scrub au seul
motif que la préférence lit OFF ferait un checkpoint et un `VACUUM` de la base **entière** —
`topic_pages` et `posts` compris — à chaque lancement de tout le parc, qui est OFF par défaut, et un
checkpoint occupé y annoncerait « lectures et écritures bloquées » à des utilisateurs n'ayant jamais
rien activé.

**Explicitement accepté, et écrit ici pour que personne n'ait à le redécouvrir** : les **évictions
LRU** qui surviennent pendant que l'opt-in est ON (la sixième page d'un compte chasse la plus
ancienne) laissent des résidus dans les pages libres, non scrubés jusqu'au prochain événement de
confidentialité. C'est assumé pour deux raisons : l'utilisateur a **consenti à la persistance
disque** en activant le toggle, et l'attaquant capable de lire les pages libres lit aussi, dans le
même mouvement, les lignes vivantes de la même base.

Un `PRAGMA secure_delete=ON` posé en ceinture-bretelles par-dessus resterait **acceptable mais
optionnel** — sur cette volumétrie son coût est du bruit. Il ne doit **jamais** être présenté comme
la pièce porteuse (cf. § Alternatives considérées).

**Repli documenté** : si le `VACUUM` s'avérait disruptif à l'usage (verrous pendant la bascule de
compte, base bien plus grosse que prévu), le repli est `wal_checkpoint(TRUNCATE)` +
`secure_delete=ON`, en acceptant que le scrub des pages libres soit différé. Ce repli n'est pas
engagé : rien ne l'a rendu nécessaire à ce jour, et il doit être **mesuré** avant d'être pris.

**Comment c'est prouvé — par énumération, pas par affirmation.** `PrivateContentDatabaseScrubberTest`
écrit une **sentinelle** reconnaissable dans le contenu d'un message privé, vérifie d'abord qu'elle a
**réellement atteint SQLite**, déclenche la purge, puis **scanne les octets** du fichier `redface.db`
**et** de son `-wal` pour vérifier qu'elle a disparu. Un test qui vérifierait seulement que la table
est vide ne prouverait rien ici.

Il le fait sur **deux** scénarios, parce qu'un seul ne couvre qu'une moitié du disque :

1. **sans checkpoint intermédiaire** — la sentinelle n'a jamais quitté le `-wal` avant le scrub,
   c'est-à-dire exactement le fichier qu'un `secure_delete=ON` seul ne couvrirait pas ;
2. **avec checkpoint intermédiaire, et `PRAGMA secure_delete=OFF`** — la sentinelle est d'abord
   **constatée dans `redface.db`** lui-même, l'état que tout appareil atteint dès que SQLite
   autocheckpointe, et le zérotage à la suppression est retiré pour qu'aucune béquille ne puisse
   expliquer sa disparition à la place du scrub.

**Contre-épreuve exécutée** : le second scénario **échoue** (« sentinel must disappear from
redface.db ») quand on retire le checkpoint post-`VACUUM`, et **passe** quand on le remet — pendant
que le premier reste vert dans les deux cas. C'est ce qui établit que la garantie tient par la
séquence de scrub, et non par le mode `secure_delete` du SQLite embarqué.

Un checkpoint qui rend `SQLITE_BUSY` n'est pas silencieux : il fait échouer le scrub, donc retombe
dans le mécanisme de la décision 5 — purge échouée, OFF verrouillé, réessai au démarrage.

### 7. Prefetch : exception bornée à l'invariant « prefetch anonyme » — repris de l'ADR-013, inchangé

L'invariant général « les requêtes de prefetch ne sont jamais authentifiées »
([protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr#règle-critique--prefetch-non-authentifié))
**reste en vigueur partout ailleurs**. Pour les MP, où le prefetch anonyme est impossible
(`cat=prive` répond 403 en anonyme), l'exception reste **bornée** :

- **autorisé** — prefetch authentifié des pages **adjacentes (N−1 et N+1)** de la conversation
  **ouverte** (écran composé et au premier plan). Pas d'effet supplémentaire dans le cas nominal :
  le GET d'ouverture a déjà effacé le dot binaire de toute la conversation. Race documentée et
  assumée hors cas nominal ;
- **interdit** — prefetch depuis la liste : il effacerait un non-lu jamais vu, et en MultiMP
  retirerait l'utilisateur de la liste « pas lu par » des autres participants, sans que la
  compensation `nonlu.php` soit atomique ;
- **clause dormante conservée** — si l'app expose un jour « Marquer comme non lu », un marquage
  manuel doit **suspendre le prefetch de cette conversation jusqu'à réouverture**. Non câblée à ce
  jour faute d'affordance produit, suivi par
  [#1087](https://github.com/ForumHFR/redface2/issues/1087) ;
- la garde Konsist d'`ArchitectureKonsistTest` réserve le point d'entrée
  `MessagesRepository.prefetchPrivateMessageThread` à son interface, son implémentation et
  `PrivateMessageThreadViewModel`.

La preuve live multipage a été exécutée le 2026-08-24
([#1107](https://github.com/ForumHFR/redface2/issues/1107)) : capture `thread_multipage` de trois
pages adjacentes d'une même conversation, une seule session authentifiée, six contrôles de cohérence
passés — conversation unique, ordre rendu conforme, jeux d'ancres réelles disjoints, les deux
reprises servies avec `ref=0`, et le rabattement de la demande hors borne sur la dernière page
annoncée, prouvé par l'URL effective. La matrice de parité revendique donc désormais la livraison de
cette ligne.

### 8. Critère de convergence topic↔MP — repris de l'ADR-013 dans sa forme amendée

La formulation d'origine (« passer les MP au modèle route-driven du topic ») est **caduque** : depuis
[#895](https://github.com/ForumHFR/redface2/issues/895) étape 4, le topic pagine lui aussi in-place.
Ce qui survit et reste normatif : les deux prérequis — **cache MP a minima RAM** et **prefetch
intra-conversation borné** — restent le socle de la **parité de ressenti** de la pagination MP
(stale-while-switching et atterrissages instantanés compris). Ce qui reste à converger, ce ne sont
plus les modèles, ce sont les **garanties**. Le partage de la surface de lecture elle-même
(prolongement de la décision 1) n'a jamais attendu ces prérequis.

### 9. Politique média et irréversibilité du schéma — cadres hérités, rappelés ici

- **Médias** ([#1099](https://github.com/ForumHFR/redface2/pull/1099)) : les hôtes MP passent
  `DISABLED` à la politique de cache disque du renderer partagé (image bloc, image inline, smiley,
  probe intrinsèque, vignette de menu, aperçu BBCode — mémoire seulement), et `CacheInvalidator`
  purge les caches Coil globaux au logout et à la bascule de compte, images publiques historiques
  comprises. **Activer le cache texte ne rouvre pas le cache image** : les deux politiques sont
  indépendantes, et le défaut fail-open de `LocalPostMediaDiskCachePolicy` (`ENABLED` par défaut,
  fiché [#1100](https://github.com/ForumHFR/redface2/issues/1100)) impose que toute nouvelle surface
  MP la passe explicitement.
- **Migration irréversible** : le schéma 17 est la seule surface irréversible du chantier. Une
  migration descendante n'existe pas ; désactiver le réglage vide les tables, il ne les supprime
  pas.

## Conséquences

- **+** La décision-gate « rémanence SQLite » de l'ADR-013 est levée **explicitement et par écrit**,
  au lieu de l'être silencieusement par la livraison d'un substrat.
- **+** La garantie vendue à l'utilisateur est **exactement** celle qui est prouvée : « j'ai
  désactivé / je me suis déconnecté ⇒ les octets ont disparu », épinglée sur les **deux** fichiers —
  `redface.db` comme son `-wal` — par un test à sentinelle qui les scanne, pas par un `COUNT(*)`, et
  dont la variante `redface.db` a été vérifiée par contre-épreuve : elle échoue si le second
  checkpoint est retiré.
- **+** Le traitement du texte s'aligne enfin sur celui des médias : le précédent #1096/#1099 n'est
  plus contredit.
- **−** Un résidu subsiste dans les pages libres entre deux événements de confidentialité, du fait
  des évictions LRU. Accepté, documenté, et borné par le consentement du toggle.
- **−** Le `VACUUM` est une opération bloquante sur la base. Sa fenêtre est un événement de
  confidentialité, jamais le chemin de lecture — mais elle existe, et un échec y est traité comme
  une purge échouée, pas comme un détail.
- **−** Le dépôt a désormais une ADR supersédée. La règle qui l'accompagne : **toute référence
  normative vivante doit viser l'ADR-018**, l'ADR-013 ne devant plus être citée que comme historique.
  Les pages canoniques qui la visent encore — `architecture.md`, `navigation.md`, `protocol-hfr.md`,
  `models.md`, `roadmap.md`, `reading-parity.md` et `docs/adr/014-mpstorage-v01-de-facto.md` —
  doivent être repointées : une page canonique qui continue de renvoyer à une ADR supersédée pour
  une décision vivante est pire qu'avant la supersession.
- **Clôture** : avec l'activation de cet étage, les huit lots (0 à 7) de
  [#1040](https://github.com/ForumHFR/redface2/issues/1040) sont soldés.

## Alternatives considérées

- **`PRAGMA secure_delete=ON` seul** — **rejeté**, il ne tient pas sa promesse. Il zère les pages
  libérées, mais les **images de pages antérieures** au `DELETE` restent dans le `-wal` jusqu'au
  checkpoint, et un reset de WAL **ne tronque pas** le fichier. C'est en outre un réglage **par
  connexion**, alors que Room en WAL utilise un **pool** : rien ne garantit contractuellement qu'un
  `execSQL` dans `onOpen` couvre toutes les connexions. La mesure du test à sentinelle confirme le
  point central : tant que rien n'a été checkpointé, la sentinelle vit **uniquement dans le
  `-wal`** ; et une fois checkpointée dans `redface.db`, c'est le `VACUUM` **suivi de son
  checkpoint** qui l'en retire, `secure_delete` désactivé. Acceptable en ceinture-bretelles, jamais
  comme pièce porteuse.
- **Acceptation sèche de la rémanence** (documenter le risque et ne rien faire) — **rejetée**. Elle
  contredit le précédent que le dépôt vient de poser : #1096/#1099 ont qualifié de **défaut** la
  survie des images MP à la déconnexion et amendé l'ADR-013 en conséquence. Le même raisonnement
  vaut pour le texte, et la décision « désactiver = purge immédiate » serait un **mensonge
  documenté** si les octets restaient dans les pages libres après l'événement de purge.
- **Scrub périodique ou à chaque écriture** — rejeté. Il paierait un coût de régime permanent pour
  une garantie que personne n'a demandée : entre deux événements de confidentialité, les lignes
  vivantes sont de toute façon lisibles par le même adversaire.
- **Base MP chiffrée applicativement (SQLCipher ou équivalent)** — rejeté à ce stade. Le FBE de la
  plateforme (`minSdk = 29`) couvre déjà l'appareil éteint ; contre un adversaire sur appareil
  **déverrouillé**, la clé est accessible au processus, donc le chiffrement applicatif déplace le
  problème au lieu de le résoudre, au prix d'une dépendance native et d'une migration de schéma.
- **Un toggle par compte** — rejeté. Le désactiver ne purgerait alors que le compte courant, ce qui
  serait la pire des surprises pour qui a utilisé deux comptes sur l'appareil ; et un consentement
  éclaté serait plus difficile à énoncer qu'à respecter.
- **Cache Room par défaut (opt-out)** — rejeté, décision héritée de l'ADR-013 et inchangée : du
  contenu privé persisté sur disque sans consentement explicite irait contre l'esprit de
  [#316](https://github.com/ForumHFR/redface2/issues/316).
- **Conserver les lignes après un passage à OFF** (ne purger qu'au logout) — rejeté. Ce sont des
  données de cache, jamais une source utilisateur : les garder après OFF serait un piège plus grave
  que les supprimer.
- **Faire persister le prefetch quand le toggle est ON** — rejeté. Écrire sur le disque une page que
  l'utilisateur n'a jamais affichée élargirait la surface persistée au-delà de ce qu'il a
  consciemment lu.
- **Supersession partielle de l'ADR-013** (statut `Accepté` conservé pour les décisions 1, 3 et 4,
  sur le modèle de l'ADR-003 bornant partiellement l'ADR-009) — écartée. Elle aurait laissé la lecture MP décrite par **deux**
  documents faisant autorité chacun sur une partie, alors que le dépôt tient la règle « un sujet =
  une source canonique ». La reprise intégrale coûte quelques paragraphes et donne une source
  unique.
