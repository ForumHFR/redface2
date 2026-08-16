---
title: ADR-013
parent: ADRs
grand_parent: Spécifications
nav_order: 13
permalink: /adr/013-mp-lecture-cache-prefetch
---

# ADR-013 — Lecture MP : partage topic↔MP, cache à trois étages, prefetch borné

## Statut

Accepté — 2026-06-12 (proposé 2026-06-10 ; révisé le 2026-06-12 après audit adversarial : descriptions actualisées au code livré, bornes du prefetch précisées — le fond des décisions est inchangé) · **amendé 2026-08-12** ([#1041](https://github.com/ForumHFR/redface2/issues/1041), lot 0 de [#1040](https://github.com/ForumHFR/redface2/issues/1040) : la prémisse « topic = route-driven » du Contexte est périmée depuis le 12 juillet 2026 (#895 étape 4) — la décision 4 est réécrite par amendement, les décisions 1 à 3 sont inchangées sur le fond) · **étage 2 livré le 2026-08-16** ([#1080](https://github.com/ForumHFR/redface2/issues/1080))

Cette ADR formalise les arbitrages rendus dans [#351](https://github.com/ForumHFR/redface2/issues/351) ([analyse code](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989) + [addendum cache](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671)) et [#361](https://github.com/ForumHFR/redface2/issues/361) ([investigation live du contrat serveur lu/non-lu](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132), 2026-06-09). Elle n'invente aucun verdict : chaque assertion factuelle sur HFR renvoie au commentaire d'issue qui l'a vérifiée.

État d'implémentation (à jour #1080) :

- **Décision 1 — livrée** (PR [#428](https://github.com/ForumHFR/redface2/pull/428) tranche a + [#429](https://github.com/ForumHFR/redface2/pull/429) tranche b, ainsi que le prérequis UI keep-content des Conséquences ; factorisation des primitives de liste/carte topic↔MP finalisée par [#351](https://github.com/ForumHFR/redface2/issues/351) c1/c2/c3).
- **Décision 2 — étages 1 et 2 LIVRÉS** : la **position de lecture locale par conversation** existe — table Room `mp_read_positions` (`MpReadPositionEntity` / `MpReadPositionDao`), `RoomPrivateMessageReadPositionStore` (impl de `PrivateMessageReadPositionStore`), sauvegarde dans `PrivateMessageThreadViewModel`, et seed des positions DT depuis MPStorage (`DefaultMpStorageReadPositionSeeder`, ADR-014 §5). Le **cache RAM de session** est livré par `PrivateMessageThreadSessionCache` : LRU globale de cinq pages, clé par compte canonique/conversation/page, purge synchrone et génération anti-réponse tardive via `CacheInvalidator`, émission `SESSION_CACHE` immédiatement suivie d'une revalidation `NETWORK`. Suivi [#430](https://github.com/ForumHFR/redface2/issues/430)/[#1080](https://github.com/ForumHFR/redface2/issues/1080)/[#6](https://github.com/ForumHFR/redface2/issues/6). Reste **à implémenter** : étage 3 (cache Room du **contenu**, opt-in, **vérifié absent** du code).
- **Décision 3 — implémentée, preuve live multipage en attente** : le point d'entrée authentifié
  dédié, l'ordonnanceur N−1/N+1, le gate composition + `RESUMED`, l'annulation structurée et la
  garde Konsist anti-appel depuis la liste sont présents. La matrice de parité ne passe toutefois
  pas à « oui, livré » avant la capture réelle de trois pages d'une même conversation : la fixture
  MP actuelle est monopage et ne prouve ni les deux voisins ni le rabattement serveur.

## Contexte

### Le constat (#351)

Retour bêta v94 (0.6.0) : le swipe de pages ne marche pas dans les MP. Plus largement, la vue conversation (`PrivateMessageThreadScreen`) ne reprend aucun des gestes de lecture du topic (swipe [#282](https://github.com/ForumHFR/redface2/issues/282), ascenseur [#300](https://github.com/ForumHFR/redface2/issues/300), pull-to-refresh [#335](https://github.com/ForumHFR/redface2/issues/335), cluster bas de page [#283](https://github.com/ForumHFR/redface2/issues/283)), et `MessageCard` duplique une version allégée de `TopicPostCard`.

L'[analyse code sur `dev`](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989) a montré que le frein réel n'est pas la visibilité `internal` des composants topic, mais la divergence des **modèles de pagination** :

- **topic = route-driven** : le commit du swipe remplace la `TopicRoute` courante (nouvelle entrée nav, nouveau ViewModel, nouvelle composition). Toute la machinerie de `feature/topic/.../TopicSwipe.kt` repose sur cette hypothèse — le latch `committed` n'est jamais réarmé explicitement, il est détruit avec la composition au changement de route ;
- **MP = in-place** : `PrivateMessageThreadViewModel.selectPage(page)` recharge la page dans le **même** ViewModel, même composition, même entrée nav.

Porter `Modifier.topicPageSwipe` tel quel sur les MP produirait donc un écran gelé après le premier swipe (latch jamais détruit). Par ailleurs, le ressenti instantané du swipe topic repose sur le cache Room et le prefetch anonyme — tous deux absents côté MP : `cat=prive` exige l'authentification (403 anonyme, [#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)), et la décision vie privée d'origine ([#316](https://github.com/ForumHFR/redface2/issues/316) : routes opaques, pas de persistance) excluait tout cache MP. Cette décision « pas de cache MP » a été **explicitement rouverte** par XaaT le 2026-06-09 ([addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671)).

> **Amendement 2026-08-12 ([#1041](https://github.com/ForumHFR/redface2/issues/1041))** — la divergence
> des modèles de pagination décrite ci-dessus, exacte à l'acceptation, **n'existe plus** : depuis
> `1cf2a7be` puis `4248c22d` (#895 étape 4, PR [#905](https://github.com/ForumHFR/redface2/pull/905)/[#907](https://github.com/ForumHFR/redface2/pull/907),
> 2026-07-12), le topic pagine **in-ViewModel** (`TopicViewModel.switchToPage()`, snapshots RAM LRU,
> ancres par page, génération anti-réponse-tardive) et la `TopicRoute` est **figée à l'entrée**
> (entrée nav unique — le commit du swipe ne remplace plus la route, le latch est réarmé par le re-key
> `pointerInput(currentPage)`). Les deux surfaces paginent donc in-place ; ce qui les sépare encore
> n'est plus le modèle mais les **garanties** (cache RAM, prefetch, ancres, stale-while-switching).
> Les descriptions route-driven de cette section restent le contexte exact dans lequel les décisions
> de juin ont été prises — elles ne décrivent plus le code courant.

### Le contrat serveur mesuré (#361)

Le prefetch MP butait sur un contrat serveur jamais mesuré (le comportement topic — GET authentifié = drapeau déplacé — était *supposé* s'appliquer aux MP). L'[investigation live #361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132) (2026-06-09, compte XaTriX, sandbox = conversation existante déjà lue, état final restauré à l'identique) a établi :

- **Q1** — un GET authentifié de **n'importe quelle page** d'une conversation `cat=prive` efface le non-lu de **toute** la conversation ; le GET de la liste (`forum1.php?cat=prive`) est inerte ;
- **Q2** — « marquer comme non lu » = `GET /user/nonlu.php?...&cat=prive&post=<threadId>...` **sans `hash_check`** ; granularité **binaire, conversation entière** (le paramètre `page` n'encode aucune position) ;
- **Q3** — il n'existe **aucune position de lecture serveur** pour les MP : pas de drapal en `cat=prive` (zéro `new=1`, zéro `numreponse` non nul, colonne drapeau vide), l'état serveur se réduit au **dot binaire** par conversation ;
- **MultiMP** — l'état de lecture est visible des autres participants (span « Ce message n'a pas été lu par : <pseudos> ») : accusé de lecture de fait ;
- **compensation** — la boucle `nonlu` → lecture est **sans perte** précisément parce que l'état est binaire (pas de position à perdre).

C'est le cas « (b) binaire » anticipé par #361, mais avec une observation clé qui change le verdict prefetch : l'ouverture d'une conversation consomme déjà tout l'état observable.

## Décision

> La décision 1 est livrée ; la décision 2 **étages 1 et 2 sont livrés** (position de lecture locale
> et cache RAM de session), l'étage 3 reste à implémenter. La décision 3 est implémentée mais sa
> livraison reste suspendue à la preuve live multipage décrite dans le Statut.

### 1. Partage topic↔MP à deux niveaux dans `:core:ui` — LIVRÉ (PR #428/#429)

- Les **fonctions pures du swipe** (`swipeTargetPage`, `swipeCommitDirection`, `swipeCommitDistancePx`, `swipeFollowOffset`, `swipeArmed`, `swipeEdgeHintAlpha`) vivent dans `core/ui/.../pager/PageSwipe.kt` (publiques, testées par `PageSwipeTest`) — promues depuis `feature/topic/.../TopicSwipe.kt` par la PR #428. Elles portent l'intégralité du « ressenti » (seuils distance/vélocité, overpull, hint d'armement) et garantissent un geste identique sur les deux écrans.
- Le **scrollbar générique `LazyListScrollbar`** (paramétrique sur `LazyListState`, callbacks internes au composant, zéro référence à un type topic — [vérifié #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)) vit dans `core/ui/.../list/` (promu par la même PR ; sous-packages `list/` et `pager/` documentés dans [architecture.md]({{ site.baseurl }}/specs/architecture) § `:core:ui`). **Pas de nouveau module.**
- La **machinerie gestuelle nav-driven** (`Modifier.topicPageSwipe` : `pointerInput` + latch + slide-out, intrinsèquement couplée à la destruction de composition du modèle route-driven) **reste dans `:feature:topic`**.
- Les MP ont une implémentation **minimale in-place** réutilisant les mêmes fonctions pures (PR #429, `feature/messages/.../ThreadPageSwipe.kt`) : commit → `selectPage()`, gate relue en fin de chargement, sans slide-out ([décision XaaT, #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)).

### 2. Cache MP à trois étages

1. **Position de lecture locale par conversation** (« drapal local », esprit MPStorage) — **LIVRÉE** : retenue inconditionnellement. Ce n'est pas un nice-to-have : c'est la **seule** option possible, puisqu'il n'existe aucune position de lecture serveur pour les MP ([#361 Q3](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)). Implémentée en table Room `mp_read_positions` (`MpReadPositionEntity`/`MpReadPositionDao`/`RoomPrivateMessageReadPositionStore`), sauvegardée par `PrivateMessageThreadViewModel`, **seedée** depuis MPStorage pour les DT (`DefaultMpStorageReadPositionSeeder`, seed local-prioritaire, ADR-014 §5) pour la sync future via [#6](https://github.com/ForumHFR/redface2/issues/6). Corrige au passage la restauration post process-death (la route restait figée sur la page d'ouverture, [bug relevé en #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989) puis tracé [#430](https://github.com/ForumHFR/redface2/issues/430)) — mieux que `SavedStateHandle` : **survit au process death** (stockage local). **Purgé à la déconnexion** via `CacheInvalidator` comme le reste de l'état privé : la perte des positions au logout est assumée, le filet étant la sync MPStorage (écriture opt-in OFF par défaut, #593/#597).
2. **Cache RAM de session — LIVRÉ (#1080)** : `PrivateMessageThreadSessionCache`, singleton interne à `:core:data`, porte une LRU globale de cinq pages indexées par compte canonique/conversation/page. Un hit `SESSION_CACHE` s'affiche immédiatement mais ne déclenche aucun effet de bord, puis la lecture est toujours revalidée par une émission terminale `NETWORK` ; seules ces réponses réseau, et seulement si conversation/page parsées correspondent à la cible, alimentent le cache. `CacheInvalidator` le purge et avance sa génération synchroniquement avant les DAO : le sceau compte/génération capturé avant réseau est revérifié avant lecture, écriture et émission, donc une réponse antérieure à une bascule de compte ne peut ni repeupler le cache ni atteindre l'UI. Rien n'est écrit sur disque ou dans les diagnostics, et le cache ne survit naturellement pas au process death.
3. **Cache Room du contenu** : **opt-in explicite uniquement** — toggle dans les réglages, **défaut OFF**, purge à la déconnexion. ([Décisions XaaT 2026-06-09, addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671).)

Cette politique précise [#316](https://github.com/ForumHFR/redface2/issues/316) sans l'annuler : les routes MP restent opaques (`threadId`, `page`), aucune métadonnée privée dans le back stack, et rien de persistant par défaut.

### 3. Prefetch : exception bornée à l'invariant « prefetch anonyme »

L'invariant général « les requêtes de prefetch ne sont jamais authentifiées » ([protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr#règle-critique--prefetch-non-authentifié)) **reste en vigueur partout ailleurs**. Pour les MP, où le prefetch anonyme est impossible (`cat=prive` exige l'auth), une exception **bornée** est définie :

- **Autorisé : prefetch authentifié intra-conversation ouverte** — les **pages adjacentes (N−1 et N+1, le swipe est bidirectionnel)** de la conversation que l'utilisateur lit ; « ouverte » = l'écran de la conversation est composé et au premier plan (un prefetch encore en vol quand l'écran se ferme s'annule avec le scope, il n'en repart pas de nouveau). **Pas d'effet supplémentaire dans le cas nominal** : le GET d'ouverture a déjà effacé le dot binaire de toute la conversation, et en MultiMP l'utilisateur est déjà sorti de la liste « pas lu par » ([#361, verdict 1](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)). Pas de compensation nécessaire. Hors cas nominal, une race **documentée et assumée** : un message arrivant entre la lecture de N et le prefetch d'une page adjacente verrait son dot effacé (et, en MultiMP, le read-receipt mis à jour) sans avoir été affiché — effet observable mais jugé bénin, l'utilisateur est précisément dans cette conversation. **Cas particulier** : si l'app expose un jour « Marquer comme non lu » (opportunité notée en Conséquences), un marquage manuel doit **suspendre le prefetch de cette conversation jusqu'à réouverture** — le raisonnement « le dot est déjà consommé » ne tient plus après un `nonlu.php` délibéré.
- **Interdit : prefetch depuis la liste** (conversations non ouvertes, dot non-lu) : il effacerait un non-lu jamais vu par l'utilisateur **et** le retirerait de la liste « pas lu par » des autres participants en MultiMP (read-receipt). La compensation `nonlu.php` serait sans perte, mais les deux requêtes ne sont pas atomiques : un crash entre les deux corromprait un état visible des autres clients ([#361, verdict 2](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)). Interdit en v1, réévaluable.

Conséquence d'implémentation — réalisée dans #1080 : la garde Konsist
(`ArchitectureKonsistTest`, test « prefetch call sites use the prefetch entry points only ») conserve
sa règle topic (un contexte prefetch ne peut appeler `refreshTopicPage`/`refreshTopicList`, avec le
marqueur d'exemption `konsist:bypass-prefetch-guard`) et couvre désormais explicitement le domaine
MP. Elle scanne le texte intégral des fichiers de production et n'autorise le nom du point d'entrée
`MessagesRepository.prefetchPrivateMessageThread` que dans l'interface `MessagesRepository`, son
implémentation `DefaultMessagesRepository` et `PrivateMessageThreadViewModel`. Au moins un usage doit
rester dans ce ViewModel : un appel qualifié ou sans récepteur, une référence callable, y compris dans
un bloc `init` ou un initialiseur de propriété, depuis `MessagesViewModel`, un écran ou un helper
extérieur fait échouer la garde. Il ne s'agit donc pas d'une exemption au prefetch authentifié
général, mais de la frontière bornée exigée par cette ADR.

> **Contradiction documentaire relevée et TRANCHÉE le 2026-08-12 ([#1041](https://github.com/ForumHFR/redface2/issues/1041))** —
> `AGENTS.md` § « Règles spécifiques au projet » énonce la règle prefetch sous sa forme absolue
> (« utiliser des requêtes non authentifiées pour éviter de marquer les drapeaux comme lus »), sans
> mentionner l'exception MP bornée que la présente décision définit et que
> [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr#règle-critique--prefetch-non-authentifié) et
> [architecture.md]({{ site.baseurl }}/specs/architecture) actent depuis le 2026-06-12. Le lot 5 de
> [#1040](https://github.com/ForumHFR/redface2/issues/1040) (prefetch MP borné) est incadrable tant que
> les deux textes se contredisent. **Arbitrage rendu par XaTriX le 2026-08-12** : c'est `AGENTS.md` qui
> avait tort, par violation de sa propre règle « un sujet = une source canonique ». Sa ligne absolue est
> remplacée par un renvoi vers `protocol-hfr.md` § « Règle critique : prefetch non-authentifié », qui
> porte la règle générale **et** son unique exception bornée. Le lot 5 de
> [#1040](https://github.com/ForumHFR/redface2/issues/1040) est donc cadrable.

### 4. Critère de convergence route-driven topic↔MP

Le passage des écrans MP au modèle route-driven du topic (qui permettrait de porter la machinerie `topicPageSwipe` telle quelle et de fusionner les deux modèles de pagination) est **conditionné à la réunion des deux prérequis** :

1. cache MP en place (RAM a minima, Room si opt-in activé) ;
2. prefetch intra-conversation borné en place.

Tant qu'ils ne sont pas réunis, les MP restent in-place avec le swipe minimal (décision 1). Une fois réunis, la parité de ressenti avec le topic devient possible et la convergence peut être engagée — le swipe minimal in-place se remplace alors à coût nul, les fonctions pures partagées restant la base dans les deux cas ([addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671), [verdict #361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).

> **Amendement 2026-08-12 ([#1041](https://github.com/ForumHFR/redface2/issues/1041))** — cette décision
> est **caduque dans sa formulation** : il n'existe plus de « modèle route-driven du topic » vers lequel
> faire converger les MP. Depuis #895 étape 4 (cf. amendement du Contexte), le topic a rejoint la
> pagination in-place — le critère de convergence est donc **inversé** : les deux surfaces partagent
> déjà le modèle, ce qui reste à converger, ce sont les garanties. Ce qui survit de la décision :
> les deux prérequis (cache MP a minima RAM + prefetch intra-conversation borné) restent la condition
> de la **parité de ressenti** de la pagination MP (slide-out, stale-while-switching, atterrissages
> instantanés) — ils gardent leur rôle de gate, portés par les lots 5 et 6 de
> [#1040](https://github.com/ForumHFR/redface2/issues/1040). Le partage de la surface de lecture
> elle-même (la carte d'un message, prolongement de la décision 1) est arbitré par #1040 et n'attend
> pas ces prérequis.

## Conséquences

- `:core:ui` porte le scrollbar générique (`list/LazyListScrollbar`) et les helpers purs du swipe (`pager/PageSwipe`) ; `:feature:messages` et `:feature:topic` les consomment sans nouvelle arête de dépendance (les deux dépendent déjà de `:core:ui`). Livré, PR #428.
- Prérequis UI côté MP : `selectPage()` / refresh ne doivent plus passer par `PrivateMessageThreadUiState.Mode.Loading` plein écran (qui efface le contenu affiché) — contenu conservé + indicateur de chargement, tranche a du [plan en trois tranches de #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989).
- La position de lecture locale introduit le premier stockage MP côté app : clé par conversation, format aligné MPStorage2 ([#6](https://github.com/ForumHFR/redface2/issues/6)), purge à la déconnexion comme le reste de l'état privé.
- Vie privée : rien de plus persistant par défaut qu'aujourd'hui. Le cache Room est OFF par défaut, purgé au logout ; les routes opaques de [#316](https://github.com/ForumHFR/redface2/issues/316) sont inchangées.
- Opportunité produit hors périmètre de cette ADR : exposer « Marquer comme non lu » dans l'app — le contrat `nonlu.php` est trivial (GET sans `hash_check`, [#361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).
- Pages canoniques mises à jour à l'acceptation (2026-06-12) : [architecture.md]({{ site.baseurl }}/specs/architecture) (stratégie de cache MP, exception prefetch), [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr) (exception MP à la règle prefetch, contrat `nonlu.php`). [navigation.md]({{ site.baseurl }}/specs/navigation) reste inchangé tant que la convergence route-driven (décision 4) n'est pas engagée.

## Alternatives considérées

- **Option A — porter `topicPageSwipe` tel quel sur les MP** : rejetée. Le latch n'est réarmé que par destruction de composition (modèle route-driven) ; en pagination in-place l'écran serait gelé après le premier swipe — bug structurel garanti, pas un détail d'implémentation ([#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)).
- **Option B — module `:core:postlist` / composant de liste paginée unifié** : rejetée dans sa forme module dédié. Sur-ingénierie à deux consommateurs, et les modèles de pagination divergent précisément là où le composant devrait être commun ([#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)). Réévaluable au troisième consommateur.
- **Option C — statu quo (MP sans gestes)** : rejetée. Le coût de la version minimale est faible et le retour testeur resterait sans réponse ([#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)).
- **Généraliser la machinerie gestuelle nav-driven pour couvrir les deux modèles** : rejetée — complexité spéculative pour deux consommateurs ; la frontière retenue (fonctions pures partagées, machinerie par modèle de pagination) est plus simple et suffisante.
- **Pas de cache MP du tout** (décision d'origine, époque [#316](https://github.com/ForumHFR/redface2/issues/316)) : remplacée — rouverte explicitement par XaaT ([addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671)). Les garanties de #316 qui restent pertinentes (routes opaques, pas de métadonnée privée dans le back stack) sont conservées.
- **Cache Room par défaut (opt-out)** : rejeté — du contenu privé persisté sur disque sans consentement explicite irait contre l'esprit de [#316](https://github.com/ForumHFR/redface2/issues/316).
- **Prefetch depuis la liste avec compensation `nonlu.php`** : rejeté en v1 — deux mutations non atomiques sur un état serveur visible des autres clients (et des autres participants en MultiMP) ; une interruption entre les deux corromprait l'état ([#361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).
- **Position de lecture serveur** : impossible, pas un choix — HFR n'offre aucun mécanisme de position pour `cat=prive`, l'état serveur est un dot binaire par conversation ([#361 Q3](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).
